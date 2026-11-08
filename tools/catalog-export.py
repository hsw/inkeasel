# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Export the InkEasel painting catalog from Wikidata and Wikimedia Commons.

    uv run --script tools/catalog-export.py <outdir> [options]
    uv run --script tools/catalog-export.py app/src/main/assets/catalog --starter-from <dir>
    python3 tools/catalog-export.py --self-test

Set: paintings (P31 Q3305213) with an image (P18) and at least 3 sitelinks. Fame is the number of
sitelinks (Wikipedia and other Wikimedia articles about the painting).

Phases, all sequential, one request at a time:
1. Wikidata Query Service (WDQS): ids and sitelink counts only, one light query per fame bucket. Each
   finished bucket is stored in the cache; a later run reuses the fresh ones and queries only the buckets
   that are missing (see "Cache and retries"). `--fame-file` skips this phase.
2. Wikidata API `wbgetentities`, 50 ids per request: the paintings with claims, labels, aliases and
   sitelinks.
3. The same for the entities they reference (artists, collections, movements, genres), without claims.
4. Commons API `imageinfo`, 50 files per request: size, licence, attribution flag and credit of each image.
There is no copyright filter: a P18 value is a Commons file, and Commons hosts only free files; the licence
is recorded per image so that the app can show the credit where attribution is required.

Output (all gzip-compressed JSON Lines, one object per line, UTF-8, written deterministically so an
unchanged catalog gives byte-identical files):

    <outdir>/core.jsonl.gz              every painting, language-neutral
    <outdir>/labels-<lang>.jsonl.gz     one file per language present in Wikidata
    <outdir>/labels-all.jsonl.gz        every language in one file
    <outdir>/starter/core.jsonl.gz      the top --starter-size paintings (the starter set for the APK)
    <outdir>/starter/labels.jsonl.gz    their labels in the --starter-langs languages, in one file
    <outdir>/cache/                     WDQS bucket results and the merged fame list (not published)

The top-level `*.jsonl.gz` files are the release assets: they are published, replacing the previous
ones, under the GitHub release with the fixed tag `catalog`, e.g.
`gh release upload catalog <outdir>/*.jsonl.gz --clobber`. Release asset names are flat (no
directories), hence `labels-<lang>` rather than `labels/<lang>`. The `starter/` files go into the APK as
`app/src/main/assets/catalog/`.

Starter set from a published release: `--starter-from <dir>` reads `<dir>/core.jsonl.gz` and
`<dir>/labels-all.jsonl.gz` (the release assets, downloaded from
https://github.com/hsw/inkeasel/releases/download/catalog/<file>) and writes only `<outdir>/core.jsonl.gz`
and `<outdir>/labels.jsonl.gz`, with the same writer and options as a full run and no Wikimedia request, so
the APK's starter set always matches the published release. Run it after each release upload.

`--self-test` checks the row building and the starter cut on a small built-in fixture (no network, nothing
written outside a temporary directory) and exits 0 when every check passes.

Rows are sorted by fame, most famous first (ties by numeric id). A labels file follows the same order:
each painting, then the entities it references that no earlier painting referenced, so any prefix of a
labels file covers the same prefix of the core file (stream import in batches).

Core row:   {"id", "fame", "img" (Commons file name), "w", "h" (image size in pixels), "lic" (licence
            short name), "licUrl" (the licence's deed URL, Commons `LicenseUrl`, always https; left out
            when Commons gives none, as for public-domain files), "attr" (attribution required), "credit"
            (author/credit text, plain, at most 200 characters), "creators", "inception" (year),
            "collections", "movements", "genres", "cma" (Cleveland Museum of Art ID, Wikidata P11110: the
            museum's accession number, e.g. "1945.24"; left out when the painting has none)}
            (lists hold Wikidata ids).
Label row:  {"id", "l" (label), "a" (aliases), "w" (Wikipedia article title in that language)}; keys
            without a value are left out. In `labels-all` and `starter/labels` each row also has
            "lang". The language `mul` holds Wikidata's language-independent labels.
Paintings whose file is missing on Commons are left out.

Wikimedia etiquette: a descriptive User-Agent naming the project, sequential requests with pauses
between them, `maxlag=5` on the APIs, `Retry-After` honoured, retries with exponential backoff.
Never run this on devices.

Cache and retries: a bucket query is tried a few times with backoff; buckets that still fail are
retried in further rounds after a longer pause (--wdqs-rounds). Each finished bucket is written to
`<cache>/wdqs-<lo>-<hi>-<hash>.json` (the hash covers the query text, so a changed query is never served
from an old file). A rerun reuses buckets younger than --cache-max-age and queries only the rest. The
merged list is written to `<cache>/fame.json`, which --fame-file accepts.

Exit status: 0 on success; 1 when a request still fails after its retries; 3 when WDQS buckets are still
missing after every round (nothing is written then, unless --allow-partial: a partial catalog must never
be published by accident). Rerun to retry only the missing buckets.
"""
import argparse
import gzip
import hashlib
import html
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import defaultdict

UA = ("InkEasel-catalog-export/1.0 (https://github.com/hsw/inkeasel; painting catalog export, "
      "sequential requests)")
WDQS = "https://query.wikidata.org/sparql"
WD_API = "https://www.wikidata.org/w/api.php"
COMMONS_API = "https://commons.wikimedia.org/w/api.php"

# Fame buckets [lo, hi) of sitelinks: small enough that each query stays well under the WDQS time limit.
BUCKETS = [(20, 100000), (10, 20), (7, 10), (5, 7), (4, 5), (3, 4)]
WDQS_PAUSE = 15          # seconds between WDQS queries
WDQS_ROUND_PAUSE = 120   # seconds before another round over the failed buckets
API_PAUSE = 1.0          # seconds between Wikidata/Commons API requests
BATCH = 50               # ids or titles per API request (the API maximum for anonymous clients)

# Languages the app's UI is to be offered in (Android locales that Wikipedia/Wikidata also cover), plus
# `mul`, Wikidata's language-independent labels. Default for the starter set.
UI_LANGS = """af am ar as az be bg bn bs ca cs da de el en es et eu fa fi fr gl gu he hi hr hu hy id is it
ja ka kk km kn ko ky lo lt lv mk ml mn mr ms my nb ne nl or pa pl pt ro ru si sk sl sq sr sv sw ta te th tl tr
uk ur uz vi zh zu mul""".split()

SKIP_SITES = {"commonswiki", "specieswiki", "metawiki", "wikidatawiki", "mediawikiwiki", "sourceswiki",
              "outreachwiki", "incubatorwiki", "foundationwiki", "wikimaniawiki", "simplewiki",
              "abstractwiki", "wikifunctionswiki"}
WIKI_LANG = {"no": "nb", "be_x_old": "be-tarask", "zh_yue": "yue", "zh_min_nan": "nan", "zh_classical": "lzh"}
LANG_RE = re.compile(r"^[a-z]{2,3}(-[a-z0-9]+)*$")
REF_KEYS = ("creators", "collections", "movements", "genres")


def log(msg):
    print(time.strftime("%H:%M:%S ") + msg, file=sys.stderr, flush=True)


class FetchError(RuntimeError):
    pass


def fetch(url, data=None, accept="application/json", timeout=90, attempts=6, delay=5):
    """GET (or POST `data`) and return the decoded JSON, retrying transient failures with backoff."""
    maxlag_waits = 0
    attempt = 0
    while attempt < attempts:
        req = urllib.request.Request(url, data=data, headers={"User-Agent": UA, "Accept": accept,
                                                              "Accept-Encoding": "gzip"})
        t0 = time.time()
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                raw = r.read()
                body = gzip.decompress(raw) if r.headers.get("Content-Encoding") == "gzip" else raw
                j = json.loads(body)
                err = j.get("error") if isinstance(j, dict) else None
                if err and err.get("code") == "maxlag" and maxlag_waits < 30:
                    # replication lag: the API asks clients to wait; this does not count as an attempt
                    maxlag_waits += 1
                    wait = retry_after(r.headers, 5)
                    log(f"maxlag, waiting {wait}s")
                    time.sleep(wait)
                    continue
                if err:
                    raise FetchError(f"API error {err.get('code')}: {err.get('info', '')[:200]}")
                return j
        except urllib.error.HTTPError as e:
            if 400 <= e.code < 500 and e.code not in (408, 429):
                raise FetchError(f"HTTP {e.code} for {url[:160]}") from e
            wait = retry_after(e.headers, delay)
            log(f"HTTP {e.code} after {time.time() - t0:.1f}s, retry in {wait}s")
        except FetchError:
            raise
        except Exception as e:  # timeouts, cut connections, truncated or invalid JSON
            wait = delay
            log(f"{type(e).__name__}: {e} after {time.time() - t0:.1f}s, retry in {wait}s")
        attempt += 1
        if attempt < attempts:
            time.sleep(wait)
            delay = min(delay * 2, 120)
    raise FetchError(f"gave up after {attempts} attempts: {url[:160]}")


def retry_after(headers, default):
    v = (headers or {}).get("Retry-After", "")
    return min(int(v), 600) if v.isdigit() else default


# ---- 1. WDQS: ids and fame, one cached query per bucket

def bucket_query(lo, hi):
    # hint:optimizer "None" keeps the written join order; the optimizer's own plan times out on the
    # top bucket.
    return (f'SELECT ?p ?links WHERE {{ hint:Query hint:optimizer "None". ?p wdt:P31 wd:Q3305213. '
            f"?p wikibase:sitelinks ?links. FILTER(?links >= {lo} && ?links < {hi}) ?p wdt:P18 []. }}")


def bucket_path(cache, lo, hi):
    h = hashlib.sha256(bucket_query(lo, hi).encode()).hexdigest()[:10]
    return os.path.join(cache, f"wdqs-{lo}-{hi}-{h}.json")


def load_bucket(cache, lo, hi, max_age_h):
    path = bucket_path(cache, lo, hi)
    try:
        with open(path, encoding="utf-8") as f:
            d = json.load(f)
        if max_age_h > 0 and time.time() - d["fetched"] <= max_age_h * 3600:
            return {k: int(v) for k, v in d["fame"].items()}
    except (OSError, ValueError, KeyError, TypeError):
        pass
    return None


def write_json(path, obj):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, separators=(",", ":"))
    os.replace(tmp, path)


def query_bucket(lo, hi):
    j = fetch(WDQS, data=urllib.parse.urlencode({"query": bucket_query(lo, hi)}).encode(),
              accept="application/sparql-results+json", timeout=90, attempts=3, delay=30)
    try:
        rows = j["results"]["bindings"]
        return {b["p"]["value"].rsplit("/", 1)[-1]: int(b["links"]["value"]) for b in rows}
    except (KeyError, TypeError, ValueError) as e:
        raise FetchError(f"unexpected WDQS answer: {e}") from e


def wdqs_fame(cache, rounds, max_age_h):
    """Return ({id: sitelinks}, [missing buckets])."""
    fame, pending = {}, list(BUCKETS)
    for rnd in range(1, rounds + 1):
        failed, queried = [], False
        for lo, hi in pending:
            cached = load_bucket(cache, lo, hi, max_age_h)
            if cached is not None:
                fame.update(cached)
                log(f"WDQS bucket {lo}-{hi}: {len(cached)} paintings from cache")
                continue
            if queried:
                time.sleep(WDQS_PAUSE)
            queried = True
            t0 = time.time()
            try:
                got = query_bucket(lo, hi)
            except FetchError as e:
                log(f"WDQS bucket {lo}-{hi} failed (round {rnd}): {e}")
                failed.append((lo, hi))
                continue
            write_json(bucket_path(cache, lo, hi), {"bucket": [lo, hi], "query": bucket_query(lo, hi),
                                                    "fetched": time.time(), "fame": got})
            fame.update(got)
            log(f"WDQS bucket {lo}-{hi}: {len(got)} paintings in {time.time() - t0:.1f}s")
        pending = failed
        if not pending:
            break
        if rnd < rounds:
            log(f"{len(pending)} bucket(s) failed, next round in {WDQS_ROUND_PAUSE}s")
            time.sleep(WDQS_ROUND_PAUSE)
    return fame, pending


# ---- 2.-4. Wikidata and Commons APIs

def batches(seq, n=BATCH):
    for i in range(0, len(seq), n):
        yield i, seq[i:i + n]


def claim_values(claims, prop):
    out = []
    for c in claims.get(prop, []):
        if c.get("rank") == "deprecated":
            continue
        v = c.get("mainsnak", {}).get("datavalue", {}).get("value")
        if isinstance(v, dict) and "id" in v:
            out.append(v["id"])
        elif isinstance(v, str):
            out.append(v)
        elif isinstance(v, dict) and "time" in v:
            out.append(v["time"])
    return out


def year(ts):
    m = re.match(r"([+-]\d+)-", ts or "")
    return int(m.group(1)) if m else None


def take_texts(labels, eid, e):
    for lang, lv in e.get("labels", {}).items():
        labels[lang].setdefault(eid, {})["l"] = lv["value"]
    for lang, al in e.get("aliases", {}).items():
        labels[lang].setdefault(eid, {})["a"] = [a["value"] for a in al]
    for site, sl in e.get("sitelinks", {}).items():
        if not site.endswith("wiki") or site in SKIP_SITES:
            continue
        lang = WIKI_LANG.get(site[:-4], site[:-4].replace("_", "-"))
        labels[lang].setdefault(eid, {})["w"] = sl["title"]


def get_entities(ids, props, what):
    for i, chunk in batches(ids):
        url = WD_API + "?" + urllib.parse.urlencode({"action": "wbgetentities", "ids": "|".join(chunk),
                                                     "props": props, "format": "json", "maxlag": "5"})
        j = fetch(url)
        yield from j.get("entities", {}).items()
        if (i // BATCH) % 25 == 0:
            log(f"{what} {i + len(chunk)}/{len(ids)}")
        time.sleep(API_PAUSE)


def plain(s):
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", "", s or ""))).strip()


def commons_meta(files):
    meta = {}
    for i, chunk in batches(files):
        # POST: 50 long file names do not fit a GET URL
        body = urllib.parse.urlencode({
            "action": "query", "titles": "|".join("File:" + f for f in chunk), "prop": "imageinfo",
            "iiprop": "size|extmetadata",
            "iiextmetadatafilter": "LicenseShortName|LicenseUrl|AttributionRequired|Artist",
            "format": "json", "maxlag": "5"}).encode()
        q = fetch(COMMONS_API, data=body).get("query", {})
        norm = {n["to"]: n["from"] for n in q.get("normalized", [])}
        for p in q.get("pages", {}).values():
            if "missing" in p or "invalid" in p:
                continue
            title = norm.get(p.get("title"), p.get("title", ""))
            meta[title[5:]] = image_meta((p.get("imageinfo") or [{}])[0])
        if (i // BATCH) % 25 == 0:
            log(f"commons {i + len(chunk)}/{len(files)}")
        time.sleep(API_PAUSE)
    return meta


def image_meta(ii):
    """Size, licence and credit from one Commons `imageinfo` entry; `licUrl` only when there is one."""
    em = ii.get("extmetadata", {})
    m = {"w": ii.get("width"), "h": ii.get("height"), "lic": em.get("LicenseShortName", {}).get("value")}
    url = licence_url(em.get("LicenseUrl", {}).get("value"))
    if url:
        m["licUrl"] = url
    m["attr"] = em.get("AttributionRequired", {}).get("value") == "true"
    m["credit"] = plain(em.get("Artist", {}).get("value"))[:200]
    return m


def licence_url(value):
    """A licence URL as https, or None when the value is not a plain web URL (HTML, text, empty)."""
    s = plain(value)
    if s.startswith("//"):
        s = "https:" + s
    if not re.fullmatch(r"https?://[^\s\"'<>]+", s):
        return None
    return "https://" + s[len("http://"):] if s.startswith("http://") else s


def painting(eid, e, fame):
    """The core fields of one painting from its `wbgetentities` entity, or None when it has no image."""
    c = e.get("claims", {})
    imgs = claim_values(c, "P18")
    if not imgs:
        return None
    # fame from the same snapshot as the labels; WDQS counts sitelinks the same way
    it = {"id": eid, "fame": len(e.get("sitelinks", {})) or fame, "img": imgs[0],
          "creators": claim_values(c, "P170"), "inception": year((claim_values(c, "P571") or [None])[0]),
          "collections": claim_values(c, "P195"), "movements": claim_values(c, "P135"),
          "genres": claim_values(c, "P136")}
    cma = [v.strip() for v in claim_values(c, "P11110") if isinstance(v, str) and v.strip()]
    if cma:
        it["cma"] = cma[0]
    return it


def core_row(it, m):
    """A core row: the painting's fields around its Commons metadata, in the documented key order."""
    row = {"id": it["id"], "fame": it["fame"], "img": it["img"], **m,
           **{k: it[k] for k in ("creators", "inception", "collections", "movements", "genres")}}
    if "cma" in it:
        row["cma"] = it["cma"]
    return row


# ---- output

def qnum(eid):
    return int(eid[1:]) if eid[1:].isdigit() else sys.maxsize


def write_gz(path, rows):
    data = "".join(json.dumps(r, ensure_ascii=False, separators=(",", ":")) + "\n" for r in rows).encode()
    tmp = path + ".tmp"
    with open(tmp, "wb") as f:
        f.write(gzip.compress(data, 9, mtime=0))   # mtime=0: same content, same bytes
    os.replace(tmp, path)
    return len(rows)


def entity_order(core):
    """Each painting, then its references not yet listed: a labels prefix covers the same core prefix."""
    order, seen = [], set()
    for it in core:
        for e in [it["id"], *(r for k in REF_KEYS for r in it.get(k) or () if r.startswith("Q"))]:
            if e not in seen:
                seen.add(e)
                order.append(e)
    return order


def label_rows(labels, langs, order, with_lang):
    if with_lang:   # entity-major: all languages of one entity together, entities in fame order
        return [{"lang": lang, "id": e, **labels[lang][e]}
                for e in order for lang in langs if e in labels[lang]]
    (lang,) = langs
    return [{"id": e, **labels[lang][e]} for e in order if e in labels[lang]]


def clear_old(d):
    if os.path.isdir(d):
        for name in os.listdir(d):
            if name.endswith(".jsonl.gz"):
                os.remove(os.path.join(d, name))


def write_release(d, core, labels, langs):
    """The release files; returns (entities in label order, rows in labels-all)."""
    order = entity_order(core)
    write_gz(os.path.join(d, "core.jsonl.gz"), core)
    for lang in langs:
        write_gz(os.path.join(d, f"labels-{lang}.jsonl.gz"), label_rows(labels, [lang], order, False))
    return order, write_gz(os.path.join(d, "labels-all.jsonl.gz"), label_rows(labels, langs, order, True))


def write_starter(d, core, labels, langs, size, wanted):
    """The starter set: the first `size` core rows and their labels in the `wanted` languages, in one file.
    Returns (paintings, languages, label rows)."""
    starter = core[:size]
    starter_langs = [lang for lang in langs if lang in wanted]
    write_gz(os.path.join(d, "core.jsonl.gz"), starter)
    n = write_gz(os.path.join(d, "labels.jsonl.gz"),
                 label_rows(labels, starter_langs, entity_order(starter), True))
    return len(starter), len(starter_langs), n


def read_gz(path):
    """The rows of a gzipped JSON Lines file, one at a time."""
    with gzip.open(path, "rt", encoding="utf-8") as f:
        for n, line in enumerate(f, 1):
            if not line.strip():
                continue
            try:
                yield json.loads(line)
            except ValueError as e:
                raise ValueError(f"{path}:{n}: {e}") from e


def fame_key(row):
    return -row["fame"], qnum(row["id"])


def starter_from(src, outdir, size, wanted):
    """The starter set cut from release files in `src` (`core.jsonl.gz`, `labels-all.jsonl.gz`) into
    `outdir`, through the same writer as a full export; reads only the labels it keeps into memory."""
    if size < 1:
        raise ValueError(f"starter size {size}")
    if os.path.realpath(src) == os.path.realpath(outdir):
        raise ValueError("the output directory must not be the release directory")
    core = []
    for row in read_gz(os.path.join(src, "core.jsonl.gz")):
        if len(core) == size:
            break
        if core and fame_key(row) <= fame_key(core[-1]):
            raise ValueError(f"core.jsonl.gz is not in fame order at {row.get('id')}")
        core.append(row)
    if not core:
        raise ValueError("core.jsonl.gz has no row")
    entities = set(entity_order(core))
    labels = defaultdict(dict)
    for row in read_gz(os.path.join(src, "labels-all.jsonl.gz")):
        lang, eid = row.pop("lang", None), row.pop("id", None)
        if lang in wanted and eid in entities and LANG_RE.match(lang):
            labels[lang][eid] = row
    os.makedirs(outdir, exist_ok=True)
    return write_starter(outdir, core, labels, sorted(labels), size, wanted)


# ---- self-test (no network)

def self_test():
    import tempfile
    failed = []
    checks = 0

    def check(ok, what):
        nonlocal checks
        checks += 1
        if not ok:
            failed.append(what)

    def claim(v, rank="normal"):
        return {"rank": rank, "mainsnak": {"datavalue": {"value": v}}}

    # painting(): P11110 as `cma` (first non-deprecated, trimmed), absent without one, no image -> None
    e = {"sitelinks": {"enwiki": {}, "frwiki": {}, "dewiki": {}},
         "claims": {"P18": [claim("A b.jpg")], "P170": [claim({"id": "Q100"})], "P195": [claim({"id": "Q200"})],
                    "P571": [claim({"time": "+1503-00-00T00:00:00Z"})],
                    "P11110": [claim("1900.1", "deprecated"), claim(" 1945.24 "), claim("1960.81")]}}
    it = painting("Q5", e, 0)
    check(it["cma"] == "1945.24", "painting: cma is the first non-deprecated P11110, trimmed")
    check((it["fame"], it["inception"], it["creators"]) == (3, 1503, ["Q100"]), "painting: fame, year, creators")
    del e["claims"]["P11110"]
    check("cma" not in painting("Q5", e, 0), "painting: no P11110, no cma key")
    check(painting("Q6", {"claims": {}}, 4) is None, "painting: no image -> None")
    check(painting("Q7", {"claims": {"P18": [claim("x.jpg")]}}, 4)["fame"] == 4, "painting: fame fallback")

    # image_meta(): licUrl as https, left out when absent or not a URL; documented key order
    def ii(**em):
        return {"width": 10, "height": 20, "extmetadata": {k: {"value": v} for k, v in em.items()}}
    m = image_meta(ii(LicenseShortName="CC BY-SA 4.0", AttributionRequired="true", Artist="<b>Ann</b> &amp; Bo",
                      LicenseUrl="http://creativecommons.org/licenses/by-sa/4.0"))
    check(list(m) == ["w", "h", "lic", "licUrl", "attr", "credit"], "image_meta: key order")
    check(m["licUrl"] == "https://creativecommons.org/licenses/by-sa/4.0", "image_meta: http -> https")
    check((m["attr"], m["credit"]) == (True, "Ann & Bo"), "image_meta: attr and plain credit")
    check("licUrl" not in image_meta(ii(LicenseShortName="Public domain")), "image_meta: no LicenseUrl")
    check(licence_url("//creativecommons.org/publicdomain/zero/1.0/")
          == "https://creativecommons.org/publicdomain/zero/1.0/", "licence_url: protocol-relative")
    check(licence_url("https://creativecommons.org/licenses/by/2.0") == "https://creativecommons.org/licenses/by/2.0",
          "licence_url: https kept")
    for bad in (None, "", "see the file page", "ftp://example.org/x", "https://a b", "<a href=\"x\">x</a>"):
        check(licence_url(bad) is None, f"licence_url: {bad!r} -> None")
    row = core_row({**painting("Q5", {"claims": {"P18": [claim("a.jpg")], "P11110": [claim("1.2")]}}, 3)}, m)
    check(list(row) == ["id", "fame", "img", "w", "h", "lic", "licUrl", "attr", "credit", "creators", "inception",
                        "collections", "movements", "genres", "cma"], "core_row: key order, cma last")

    # the starter cut: from release files == from memory, byte for byte
    def pic(qid, fame, refs=(), cma=None):
        p = {"id": qid, "fame": fame, "img": f"{qid}.jpg"}
        return core_row({**p, "creators": list(refs), "inception": None, "collections": ["Q900"],
                         "movements": [], "genres": [], **({"cma": cma} if cma else {})},
                        {"w": 1, "h": 2, "lic": "Public domain", "attr": False, "credit": ""})
    core = sorted([pic("Q10", 5, ["Q100"]), pic("Q9", 5, ["Q101"], "1945.24"), pic("Q3", 9, ["Q100"]),
                   pic("Q4", 2), pic("Q8", 1, ["Q102"])], key=fame_key)
    check([r["id"] for r in core] == ["Q3", "Q9", "Q10", "Q4", "Q8"], "fixture: fame order, ties by number")
    labels = defaultdict(dict)
    for lang in ("de", "en", "mul", "ru", "xx"):
        for eid in ("Q3", "Q9", "Q10", "Q4", "Q8", "Q100", "Q101", "Q102", "Q900"):
            if (lang, eid) not in (("de", "Q9"), ("mul", "Q3")):
                labels[lang][eid] = {"l": f"{eid} {'Мона' if lang == 'ru' else lang}", "a": ["x"], "w": eid}
    langs = sorted(labels)
    wanted = {"en", "ru", "mul", "de", "zz"}
    with tempfile.TemporaryDirectory() as tmp:
        rel, mem, cut = (os.path.join(tmp, n) for n in ("release", "memory", "cut"))
        for d in (rel, mem):
            os.makedirs(d)
        write_release(rel, core, labels, langs)
        write_starter(mem, core, labels, langs, 3, wanted)
        got = starter_from(rel, cut, 3, wanted)
        for name in ("core.jsonl.gz", "labels.jsonl.gz"):
            with open(os.path.join(mem, name), "rb") as f1, open(os.path.join(cut, name), "rb") as f2:
                check(f1.read() == f2.read(), f"starter_from: {name} byte-identical to a full run's starter")
        check(got[0] == 3 and got[1] == 4, "starter_from: 3 paintings, 4 languages")
        with gzip.open(os.path.join(rel, "core.jsonl.gz"), "rt", encoding="utf-8") as f:
            rel_lines = f.read().splitlines()
        with gzip.open(os.path.join(cut, "core.jsonl.gz"), "rt", encoding="utf-8") as f:
            cut_lines = f.read().splitlines()
        check(cut_lines == rel_lines[:3], "starter_from: core lines are a prefix of the release core")
        rows = list(read_gz(os.path.join(cut, "labels.jsonl.gz")))
        check({r["lang"] for r in rows} == {"de", "en", "mul", "ru"}, "starter_from: only wanted languages")
        order = ["Q3", "Q100", "Q900", "Q9", "Q101", "Q10"]
        check([r["id"] for r in rows if r["lang"] == "en"] == order, "starter_from: entity order")
        check([r["lang"] for r in rows[:3]] == ["de", "en", "ru"], "starter_from: entity-major, sorted languages")
        check(rows[2]["l"] == "Q3 Мона", "starter_from: non-ASCII label kept")
        starter_from(rel, cut, 99, wanted)
        check(len(list(read_gz(os.path.join(cut, "core.jsonl.gz")))) == 5, "starter_from: size over the core")
        main_out = os.path.join(tmp, "main")
        code = main([main_out, "--starter-from", rel, "--starter-size", "3", "--starter-langs", "en,ru,mul,de,zz"])
        check(code == 0 and sorted(os.listdir(main_out)) == ["core.jsonl.gz", "labels.jsonl.gz"],
              "main --starter-from: exit 0, only the two starter files (no cache directory)")
        with open(os.path.join(main_out, "labels.jsonl.gz"), "rb") as f1, \
                open(os.path.join(mem, "labels.jsonl.gz"), "rb") as f2:
            check(f1.read() == f2.read(), "main --starter-from: same labels as the writer")
        for args, what in (((rel, rel, 3, wanted), "output = release directory"),
                           ((rel, cut, 0, wanted), "size 0"),
                           ((os.path.join(tmp, "none"), cut, 3, wanted), "missing release files")):
            try:
                starter_from(*args)
                check(False, f"starter_from fails: {what}")
            except (OSError, ValueError):
                check(True, what)
        bad = os.path.join(tmp, "unordered")
        os.makedirs(bad)
        write_release(bad, [core[1], core[0]], labels, langs)
        try:
            starter_from(bad, cut, 3, wanted)
            check(False, "starter_from fails: core not in fame order")
        except ValueError:
            check(True, "core not in fame order")
        check(main([os.path.join(tmp, "x"), "--starter-from", bad]) == 1, "main --starter-from: error -> exit 1")

    for what in failed:
        print(f"FAIL {what}", file=sys.stderr)
    print(f"self-test: {checks - len(failed)}/{checks} checks passed", file=sys.stderr)
    return 1 if failed else 0


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("outdir", nargs="?", help="output directory (created if missing)")
    ap.add_argument("--cache", help="cache directory for WDQS bucket results (default: <outdir>/cache)")
    ap.add_argument("--cache-max-age", type=float, default=48, metavar="HOURS",
                    help="reuse cached buckets younger than this; 0 ignores the cache (default: 48)")
    ap.add_argument("--wdqs-rounds", type=int, default=3, metavar="N",
                    help="rounds over the failed WDQS buckets within one run (default: 3)")
    ap.add_argument("--fame-file", default=os.environ.get("FAME_FILE"), metavar="FILE",
                    help="JSON {id: sitelinks} to use instead of WDQS, e.g. <cache>/fame.json "
                         "(default: $FAME_FILE)")
    ap.add_argument("--allow-partial", action="store_true",
                    help="write the catalog even when WDQS buckets are still missing (exit 0)")
    ap.add_argument("--limit", type=int, metavar="N",
                    help="export only the N most famous paintings (for test runs; not a full catalog)")
    ap.add_argument("--starter-size", type=int, default=1000, metavar="N",
                    help="paintings in the starter set (default: 1000)")
    ap.add_argument("--starter-langs", default=",".join(UI_LANGS), metavar="LANGS",
                    help="comma-separated label languages of the starter set (default: the UI languages)")
    ap.add_argument("--starter-from", metavar="DIR",
                    help="only write the starter set into <outdir>, cut from the release files in DIR "
                         "(core.jsonl.gz, labels-all.jsonl.gz); no Wikimedia request")
    ap.add_argument("--self-test", action="store_true", help="run the built-in checks and exit")
    a = ap.parse_args(argv)
    if a.self_test:
        return self_test()
    if not a.outdir:
        ap.error("the output directory is required")
    wanted = {s.strip() for s in a.starter_langs.split(",") if s.strip()}

    if a.starter_from:
        try:
            n, n_langs, n_labels = starter_from(a.starter_from, a.outdir, a.starter_size, wanted)
        except (OSError, EOFError, ValueError, KeyError, TypeError) as e:
            log(f"error: {type(e).__name__}: {e}")
            return 1
        log(f"starter: {n} paintings, {n_langs} languages ({n_labels} label rows) in {a.outdir}")
        return 0

    cache = a.cache or os.path.join(a.outdir, "cache")
    os.makedirs(cache, exist_ok=True)

    # 1. ids and fame
    if a.fame_file:
        with open(a.fame_file, encoding="utf-8") as f:
            fame = {k: int(v) for k, v in json.load(f).items()}
        log(f"fame from file: {len(fame)} paintings")
    else:
        fame, missing = wdqs_fame(cache, max(1, a.wdqs_rounds), a.cache_max_age)
        if missing:
            names = ", ".join(f"{lo}-{hi}" for lo, hi in missing)
            if not a.allow_partial:
                log(f"error: WDQS bucket(s) {names} still missing; finished buckets are cached, "
                    f"rerun to retry only the missing ones (or pass --allow-partial)")
                return 3
            log(f"warning: WDQS bucket(s) {names} missing, writing a partial catalog (--allow-partial)")
        write_json(os.path.join(cache, "fame.json"), fame)
    ids = sorted(fame, key=lambda i: (-fame[i], qnum(i)))
    if a.limit is not None:
        ids = ids[:a.limit]
        log(f"limited to the {len(ids)} most famous paintings")

    # 2. paintings
    labels = defaultdict(dict)   # lang -> id -> {"l", "a", "w"}
    items = {}
    for eid, e in get_entities(ids, "labels|aliases|sitelinks|claims", "paintings"):
        it = painting(eid, e, fame.get(eid, 0))
        if it is None:
            continue
        take_texts(labels, eid, e)
        items[eid] = it

    # 3. referenced entities
    refs = sorted({r for it in items.values() for k in REF_KEYS for r in it[k] if r.startswith("Q")}
                  - set(items), key=qnum)
    for eid, e in get_entities(refs, "labels|aliases|sitelinks", "referenced"):
        take_texts(labels, eid, e)

    # 4. Commons: image size and licence
    meta = commons_meta(sorted({it["img"] for it in items.values()}))
    core, missing_files = [], 0
    for it in items.values():
        m = meta.get(it["img"]) or meta.get(it["img"].replace("_", " "))
        if not m:
            missing_files += 1
            continue
        core.append(core_row(it, m))
    core.sort(key=fame_key)
    if missing_files:
        log(f"{missing_files} painting(s) left out: file not found on Commons")

    # write
    langs = sorted(lang for lang in labels if LANG_RE.match(lang))
    skipped = sorted(set(labels) - set(langs))
    if skipped:
        log(f"label languages with unusable codes left out: {', '.join(skipped)}")
    starter_dir = os.path.join(a.outdir, "starter")
    os.makedirs(starter_dir, exist_ok=True)
    clear_old(a.outdir)
    clear_old(starter_dir)

    order, n_all = write_release(a.outdir, core, labels, langs)
    n_starter, n_starter_langs, n_starter_labels = write_starter(starter_dir, core, labels, langs,
                                                                 a.starter_size, wanted)

    log(f"done: {len(core)} paintings, {len(order) - len(core)} referenced entities, {len(langs)} label "
        f"languages ({n_all} label rows); starter: {n_starter} paintings, {n_starter_langs} languages "
        f"({n_starter_labels} label rows)")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except FetchError as e:
        log(f"error: {e}")
        sys.exit(1)
