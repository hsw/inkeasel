# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Export the InkEasel painting catalog from Wikidata and Wikimedia Commons.

    uv run --script tools/catalog-export.py <outdir> [options]

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
directories), hence `labels-<lang>` rather than `labels/<lang>`. The `starter/` files go into the APK.

Rows are sorted by fame, most famous first (ties by numeric id). A labels file follows the same order:
each painting, then the entities it references that no earlier painting referenced, so any prefix of a
labels file covers the same prefix of the core file (stream import in batches).

Core row:   {"id", "fame", "img" (Commons file name), "w", "h" (image size in pixels), "lic" (licence
            short name), "attr" (attribution required), "credit" (author/credit text, plain, at most
            200 characters), "creators", "inception" (year), "collections", "movements", "genres"}
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
            "iiextmetadatafilter": "LicenseShortName|AttributionRequired|Artist",
            "format": "json", "maxlag": "5"}).encode()
        q = fetch(COMMONS_API, data=body).get("query", {})
        norm = {n["to"]: n["from"] for n in q.get("normalized", [])}
        for p in q.get("pages", {}).values():
            if "missing" in p or "invalid" in p:
                continue
            ii = (p.get("imageinfo") or [{}])[0]
            em = ii.get("extmetadata", {})
            title = norm.get(p.get("title"), p.get("title", ""))
            meta[title[5:]] = {"w": ii.get("width"), "h": ii.get("height"),
                               "lic": em.get("LicenseShortName", {}).get("value"),
                               "attr": em.get("AttributionRequired", {}).get("value") == "true",
                               "credit": plain(em.get("Artist", {}).get("value"))[:200]}
        if (i // BATCH) % 25 == 0:
            log(f"commons {i + len(chunk)}/{len(files)}")
        time.sleep(API_PAUSE)
    return meta


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
        for e in [it["id"], *(r for k in REF_KEYS for r in it[k] if r.startswith("Q"))]:
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


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("outdir", help="output directory (created if missing)")
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
    a = ap.parse_args(argv)

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
        c = e.get("claims", {})
        imgs = claim_values(c, "P18")
        if not imgs:
            continue
        take_texts(labels, eid, e)
        # fame from the same snapshot as the labels; WDQS counts sitelinks the same way
        items[eid] = {"id": eid, "fame": len(e.get("sitelinks", {})) or fame.get(eid, 0), "img": imgs[0],
                      "creators": claim_values(c, "P170"),
                      "inception": year((claim_values(c, "P571") or [None])[0]),
                      "collections": claim_values(c, "P195"), "movements": claim_values(c, "P135"),
                      "genres": claim_values(c, "P136")}

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
        core.append({"id": it["id"], "fame": it["fame"], "img": it["img"], **m,
                     **{k: it[k] for k in ("creators", "inception", "collections", "movements", "genres")}})
    core.sort(key=lambda it: (-it["fame"], qnum(it["id"])))
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

    order = entity_order(core)
    write_gz(os.path.join(a.outdir, "core.jsonl.gz"), core)
    for lang in langs:
        write_gz(os.path.join(a.outdir, f"labels-{lang}.jsonl.gz"), label_rows(labels, [lang], order, False))
    n_all = write_gz(os.path.join(a.outdir, "labels-all.jsonl.gz"), label_rows(labels, langs, order, True))

    starter = core[:a.starter_size]
    wanted = {s.strip() for s in a.starter_langs.split(",") if s.strip()}
    starter_langs = [lang for lang in langs if lang in wanted]
    write_gz(os.path.join(starter_dir, "core.jsonl.gz"), starter)
    n_starter = write_gz(os.path.join(starter_dir, "labels.jsonl.gz"),
                         label_rows(labels, starter_langs, entity_order(starter), True))

    log(f"done: {len(core)} paintings, {len(order) - len(core)} referenced entities, {len(langs)} label "
        f"languages ({n_all} label rows); starter: {len(starter)} paintings, {len(starter_langs)} languages "
        f"({n_starter} label rows)")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except FetchError as e:
        log(f"error: {e}")
        sys.exit(1)
