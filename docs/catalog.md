# Catalog

The painting catalog behind the Catalog tab: where its rows come from, how they are stored, ordered,
filtered and shown, and the tool that exports them. Screens and navigation in general:
[architecture.md](architecture.md); sending a picture to the frame: [frames.md](frames.md); building and
testing: [building.md](building.md). The reasons behind each choice: [decisions.md](decisions.md).

## Overview

Tabs: "Catalog" (the start tab: famous paintings from the catalog database, below, with the Cleveland
Museum of Art's CC0 landscapes and Wikimedia Commons' featured landscapes among them) and "My pictures" (the
app's own folder on the device).

The catalog database holds about 9,600 paintings. Its rows come from three places:

- the **release**: gzipped JSON Lines files exported from Wikidata and Commons by `tools/catalog-export.py`
  and published under the GitHub release with the fixed tag `catalog` ([Release sync](#release-sync));
- the **starter set** in the APK, cut from that release, so the first start shows a catalog offline
  ([Starter set](#starter-set));
- the **live sources**, the Cleveland Museum of Art and Wikimedia Commons, whose lists are asked for at
  runtime and kept as rows of the same database ([Live sources](#live-sources)).

Every id keeps its `<source>:` prefix: `wd:` (`wd:Q12418`), `cma:<api id>`, `commons:<file name>`.

In short: starter set at the first start → staged release import in the background → a check of each file a
week later; the live sources a week after their last whole list.

## Code

- `catalog/`: the painting catalog database (`CatalogDb`, the stored order in `CatalogRank`), the release
  rows (`CatalogRows`) and their import (`CatalogImport`), the nudity levels and filter (`Nudity`,
  `NudityLevel`, `NudityFilter`, with the built-in list; pure), the label languages (`LanguageChain`), and its
  process-wide owner `CatalogStore`, which runs the starter import, the release sync (`CatalogSync`; which
  files are due and what is recorded: `CatalogSyncPlan`, pure) and the live sources' refresh (`LiveRefresh`;
  its rules in `LiveSourceRows`, pure) and gives the Catalog tab its pages; a row as a `Picture`
  (`CatalogPicture`, its Commons URLs from `CommonsUrls`, both pure) and the viewer's steps through the list
  (`CatalogSteps`); `CatalogWriter`, the write lock's interface.
- `Catalog.kt`: `Picture`, "My pictures" (`LocalImages`) and the live sources (Cleveland Museum of Art CC0
  paintings, Wikimedia Commons featured landscapes); list requests give up after 60 s, Commons reads at most
  3 pages within 120 s (`collectPages`, pure; a list cut short is marked incomplete); `Http`, the one OkHttp
  client.
- `SourceMapping.kt`: the live sources' JSON (Cleveland, Commons) mapped to catalog rows (`LiveRow`) with
  their metadata (pure, tested against fixture answers in `app/src/test/resources/catalog/`).
- `CatalogView.kt`: what the Catalog tab shows (the grid, "none yet", "filtered" or loading) (pure).
- `CatalogError.kt`: a failed download in words (no connection, timeout, HTTP code, unreadable answer), for
  the viewer.
- `RetryPolicy.kt`: when a 429 or 503 answer is asked again, how long its host is held and when a card asks
  for its thumbnail again (pure), and the interceptor that retries and holds (only requests tagged
  `Holdable`: the cards' thumbnails).
- `Images.kt`: Coil's image loader, once per process, with its 150 MB disk cache.
- `CatalogFilterStore.kt`: the Catalog's nudity filter as chosen (prefs `catalog`); `NudityLockReader.kt`: the
  device's lock on it (TV parental controls, a restricted profile); `NuditySetting.kt`: the lock decision,
  the press's next value and the row's texts (pure).
- `MainViewModel` (in `MainActivity.kt`): the filter in force (`applyNudity`) and the stored-level gate
  (`allowed`, `whenAllowed`).
- `tools/catalog-export.py`: the export; `tools/catalog-nsfw.txt`: the hand-kept nudity list.

To watch the catalog on a device:

    adb logcat -s CatalogStore CatalogSync LiveRefresh

## Database

The painting catalog lives in a Room database, `catalog.db` (`CatalogDb`), owned by one process-wide
`CatalogStore` (`CatalogStore.get`): the one owner of `CatalogDb` and of its coroutine scope (never a
screen's). Tables:

- `picture`: every picture, Wikidata and live rows alike. `id` (with its prefix), `source` (that prefix),
  `rank` (the list order, unique index), `hidden` (a live row that duplicates a Wikidata row), `nudity` (the
  `NudityLevel` ordinal, default 0), the Wikidata id, the Commons file, the museum's accession number
  (`museumRef`: Cleveland's from its API, Wikidata P11110 for a Wikidata row), `fame` (sitelinks),
  `sourceRank` (the line in `core`, or the place in a live source's list), size, licence and its URL, the
  attribution flag and credit, the inception year, and for live rows the texts and URLs as the source gives
  them (`title`, `artists` as a list, `date`, `collection`, `pageUrl`, `thumbUrl`, `fullUrl`). A second index
  `(hidden, rank, nudity)` serves the list's order and filter, so its counts and scans never read the table
  rows.
- `label` (`entityId`, `lang`: label, aliases, Wikipedia article title) and `picture_entity` (a Wikidata
  picture's links to its creators, collections, movements and genres, in the release's order): Wikidata rows
  get their title, artists and collection through them.
- `catalog_file`: each release file's state (the ETag and SHA-256 of the content last imported, its size,
  when it was checked and imported, `missing` on a 404, `importFailed`), and each live source's refresh as a
  `live-<source>` row (`LiveSourceRows.stateName`); no release file may take that prefix.
- `picture_stage`, `label_stage`, `picture_entity_stage`: an import's staging tables, read only by its swap.

Rules:

- Every catalog write (starter, release swap, live refresh, the built-in list's levels) takes the store's
  write `Mutex` (`CatalogStore.write`, through `CatalogWriter`); network requests run outside it (the release
  sync and `LiveRefresh` fetch first, then write through the lock they are given).
- `rank` (unique) and `hidden` are stored, recomputed by `CatalogDao.rerank` (rules in the pure
  `CatalogRank`, [Order and duplicates](#order-and-duplicates)) inside the write transaction, never in the
  paging query (`WHERE hidden = 0 AND nudity < :nudityBelow ORDER BY rank`, on the index
  `(hidden, rank, nudity)`). The moving rows go to free negative ranks first, then to their new ones, so the
  unique index holds at every step; only the rows that change are written.
- No DAO returns the whole list.
- The database holds only re-downloadable data: a schema change bumps the version (now 2) and the database
  is dropped and created empty, both ways (`fallbackToDestructiveMigration()`; in Room 2.6 it alone covers an
  upgrade and a downgrade, and adding `fallbackToDestructiveMigrationOnDowngrade()` after it makes an upgrade
  ask for a migration again). Room exports each schema version to `app/schemas/` (committed). After a schema
  change the starter set goes in again at the next start.
- Room 2.6.1 (compiler through KSP `2.0.21-1.0.28`) and Paging 3.3.6 are pinned: Room 2.7+ pulls
  kotlin-stdlib 2.1 over the 2.0.21 compiler, paging-compose 3.4+ pulls Compose foundation over the BOM.
  After an entity change KSP's incremental output can go stale (a `*_Impl.java` constructor mismatch in
  `compileDebugJavaWithJavac`): delete `app/build/generated/ksp` and build again.
- SQL must run on minSdk 26's SQLite (3.18): no UPSERT (3.24), window functions (3.25) or `RETURNING` (3.35),
  and before 3.32 a statement binds at most 999 parameters, so a long `IN (:ids)` goes in chunks
  (`CatalogDao.markListed`, `LISTED_CHUNK` = 900). Robolectric's SQLite is newer, so a passing test does not
  make newer SQL safe.

`CatalogStore` is woken each time the app's screen starts (`startOnEachStart`, `MainActivity`'s `onStart`:
the app's start, each return to the foreground, and a change of the app's language, which re-creates it), so
a process that a TV keeps for weeks (for the notification listener) still checks what fell due. Once per
process it imports the starter set into an empty database, raises the stored rows to the built-in nudity
list (`CatalogDao.markListed`, [Nudity](#nudity)) and deletes the list cache of older versions
(`files/catalog/`). Then each start runs a pass: the starter's labels of a chain language the database has
none of, the release sync, then the live sources that are due. Passes run one after another; a start during
a pass asks for one more. A failed step is logged and the next pass tries again. A pass with nothing due
makes no request.

## Starter set

The APK's starter set (`app/src/main/assets/catalog/`) is the top 1,000 paintings of the release by fame,
less the paintings listed as explicit, with their labels in the UI languages: `core.jsonl.gz` and one
`labels-<lang>.jsonl.gz` per language, as in the release, so the first start reads only its languages'
files. AGP's asset merge decompresses `.gz` assets, so the APK holds `catalog/core.jsonl` and
`catalog/labels-<lang>.jsonl` (deflated, about the same size): open those names
(`CatalogStore.CORE_ASSET`, `CatalogStore.labelsAsset`). The release APK is about 2.8 MB, 1.2 MB of it the
starter set.

It is cut from the published `catalog` release, never edited by hand ([Catalog export](#catalog-export)):
download the release's `core.jsonl.gz` and `labels-all.jsonl.gz`, then

    python3 tools/catalog-export.py app/src/main/assets/catalog --starter-from <dir>

(no Wikimedia request; `StarterAssetsTest` checks the result). Run it after each release upload.

The starter set never carries an explicit painting (listed in `tools/catalog-nsfw.txt`, or `"nsfw":
"explicit"` in the release): the cut leaves it out and reads on in fame order to 1,000 rows, so its core is
the release core minus those rows, cut at that size.

On the first start (or after a schema change: a database with no Wikidata row) the starter set goes into the
empty database in one transaction, straight into the live tables at its final ranks, with the labels of the
app language's chain, English and `mul`, read from those languages' own files only. While it imports (about
a second on a TV emulator) the tab says "Loading the catalog…" rather than showing an empty grid, and the
list starts only after it, so its first query does not wait for the import. Should the starter set fail to
import and the database stay empty, the tab says the catalog has no pictures yet and that it downloads when
the device is online.

A language that joins the chain (or comes back to it) gets the starter set's labels at once, also offline,
while its release file downloads. A language whose release file was imported (an empty one included) keeps
what that file holds: the starter's labels do not come back over it (`CatalogSyncPlan.importedLabels`).

## Release sync

At every pass the store checks the files of the GitHub release `catalog` that are due (`CatalogSync`, rules
in `CatalogSyncPlan`): `core.jsonl.gz` and `labels-<lang>.jsonl.gz` for each language of the chain, English
and `mul` (`labels-all` is never fetched), each one a week after its own last check (a file of a language
that just joined the chain at once; a 404 again a week later; a check "in the future", after the clock was
set back, is due too).

- Catalog files come only from `https://github.com/hsw/inkeasel/releases/download/catalog/<file>`
  (`CatalogSync.RELEASE`), never `releases/latest` (that is the APK). github.com redirects to a signed
  address (`release-assets.githubusercontent.com`).
- A check sends the ETag of the copy imported last (`If-None-Match`); the signed address answers 304 when the
  file is unchanged. A downloaded file is streamed to a temporary `.part` file in `cacheDir/catalog-sync`
  while its SHA-256 is computed; one whose SHA-256 is the one imported last is not imported again (it keeps
  its hash and takes the new ETag: GitHub gives an uploaded file a new one even when its bytes are the same).
- Changed files are first streamed into the `_stage` tables (one batch of 500 in memory at a time), then
  swapped in by one transaction (`CatalogImport.release`'s `commit`) that replaces the release rows (a row
  already there keeps its place), removes those the new `core` no longer has, drops labels outside the chain,
  computes the stored order and the hidden duplicates again and stores the files' ETag and SHA-256. A failure
  before it changes nothing, so a failed import (or no network) leaves the list as it was; the paging source
  is invalidated once, by the swap.
- In a `core` the first row of an id wins; a row's place is its position among the file's valid rows; each
  row's nudity level is computed as it is read ([Nudity](#nudity)). A `core` with no valid row, or a labels
  file whose every line is bad, is refused (an empty labels file empties its language).
- A failed request (no network, a timeout, another HTTP code, a body cut short) records nothing and is asked
  again at the next pass; a download that could not be imported (a broken upload, a full disk) is checked
  again a day later (`RETRY_FAILED_IMPORT_MS`), so it is not downloaded again at every pass. A 304, an
  unchanged hash or a 404 records only the check.
- The downloads run one at a time outside the store's write lock, each within 10 minutes
  (`CALL_TIMEOUT_MIN`); only the import and the files' state are written under it.
- A language that leaves the chain loses its labels, and its file's state, at the next import, so when it
  comes back its file is downloaded and imported again.

The labels files asked for show the app's language and, for some languages, its region or script (such as
`labels-en-us` or `labels-pt-br`); see [PRIVACY.md](../PRIVACY.md).

## Label languages

Titles, artists and museums are the labels of the app language's chain (`LanguageChain`), whether or not the
UI has a translation for that language (on a German device the UI is English and the titles German): the
language's regional or script variant where Wikidata has one, then the language (for English, plain `en`
first and its regional variant after it, as Wikidata's regional English labels are few and often worse), then
English, then `mul` (Wikidata's language-independent labels), then any stored label (that last fallback is
the query's part, not the chain's). Android's legacy codes are mapped (`in` → `id`, `iw` → `he`, `ji` → `yi`,
`fil` → `tl`); Chinese, Serbian, Norwegian and Latin-American Spanish (`es-419`) have their own rules. The
chain is read from the UI language: the app's own locale where one is set, else the system's
(`CatalogStore.chainOf`). Artists are joined with the translatable `list_names_separator` at the edge, never
in SQL or in a mapper.

## Live sources

The live sources' lists are rows of the same database (`LiveRefresh`, rules in the pure `LiveSourceRows`):

- **Cleveland Museum of Art** Open Access API (`ClevelandPaintings`, rows `cma:<api id>`): CC0 landscape
  paintings with an image (`q=landscape`, `type=Painting`, `has_image=1`, `cc0=1`, `limit=200`), no API key;
  the accession number is the row's `museumRef`.
- **Wikimedia Commons** "Featured pictures of landscapes" (`CommonsLandscapes`, rows `commons:<file name>`):
  photos under CC BY-SA and similar, newest first, pages of 100, at most 3 pages or 120 pictures within
  120 s.

A source is asked in the background at app start (and on each return to the foreground), after the release
sync, a week after its last whole list. Its request runs outside the store's write lock (a list request gives
up after 60 s; Commons reads at most 3 pages within 120 s) and its answer replaces the source's rows in one
transaction under the lock, which also computes rank and dedupe again (`CatalogDao.rerank`) and, after a
whole list, records the refresh in its `live-<source>` row. A failure (of the request or of the write), an
empty answer or a list cut short that is smaller than the stored one keeps the rows, and the next pass asks
again; a list cut short that is not smaller replaces the rows and is asked for again at the next pass too.
A failure of one source leaves the others to be refreshed. There is no note and no **Retry** in the Catalog,
which always has the starter set. No source gives a nudity level, so every live row is level 0. The list
cache of older versions (`files/catalog/`) is deleted at start.

Known risk: the Cleveland API sometimes answers slowly (27 s seen) or with HTTP 504. If it takes longer than
60 s every time, its rows never come (or stay as they were); asking for fewer results than `limit=200` would
be the next step then.

## Order and duplicates

`CatalogRank` (pure) computes the order in the write transaction after each import or live refresh:

- Wikidata rows in `core` order (fame), at `10 · i` for the i-th of them, whatever their nudity level (the
  queries filter on it, so a filter change writes nothing and a process start ranks nothing again).
- A live row that is a Wikidata row (the same Commons file, underscores read as spaces, or the Cleveland
  accession number the release has as P11110) is hidden as its duplicate whatever the filter, so a filtered
  Wikidata row never shows through a live copy, which carries no nudity data.
- The other live rows take turns by source (`cma`, then `commons`, each in its own order): every 10th card of
  the unfiltered order (`LIVE_EVERY`) is one of them (the j-th goes at `10 · k + 5` right after Wikidata row
  `k = 9 · j + 8`; a filter that leaves rows out before it moves it up), and they follow the last Wikidata row
  once those run out.
- Hidden rows come last, so every rank stays unique.

## The Catalog tab

The Catalog tab pages through the database (Paging 3, `CatalogStore.pages` over `CatalogDao.pages`): pages of
60 rows (`PAGE`, about 15 rows of 4 cards on a TV), so only the rows near the screen are read, and at most
360 rows stay loaded (`maxSize = MAX_ROWS`, six pages; pages far from the screen are dropped and read again
when scrolled back to). A change of the label chain or of the nudity filter starts a new pager.

- A card not read yet is an empty card of the same size (the same `PictureCard` node, empty), which the D-pad
  focus moves through like any other (a press on it does nothing), so a long scroll never jumps. The grid
  keys its items by index, not by row id (an id key replaces a focused placeholder when its page arrives,
  and the D-pad focus is lost), so a card keeps its place in the grid when its row arrives and a focused card
  keeps the focus.
- A card shows its picture whole, fitted on black inside the card (the cards keep the frame's shape, so the
  grid stays regular), with the title and credit over a dark band at the bottom. It takes a 330 or 500 px
  Commons thumbnail (`CatalogPicture.thumbWidth`), whichever covers the width the picture takes in the card:
  the whole column for a wide picture, less for a portrait one.
- What the tab shows (`CatalogView`): the grid when any row is there; "Loading the catalog…" while the
  starter set is on its way; that the catalog has no pictures yet (and downloads when the device is online)
  when the starter step left the database empty (`CatalogStore.empty`, counted without the filter:
  `checkEmpty`); and, when the filter leaves no picture of a catalog that has some, that the nudity filter
  leaves none and where to change it (Settings, Catalog → Nudity).
- A press on a card (CENTER, or a tap) opens the picture full screen and sends nothing (Down there brings
  **Send to frame**); a long press opens its details ([architecture.md](architecture.md)). Both go through the nudity gate first
  ([The gate](#the-gate)).
- The full-screen viewer steps through the Catalog by rank from the database (`CatalogSteps`:
  `CatalogDao.next`, `CatalogDao.previous`), so it does not matter how far the grid has loaded; "n / N" is the
  place in the whole list as the nudity filter in force when the viewer opened leaves it (a filter change
  closes the viewer). A picture the list no longer shows (gone, hidden as a duplicate or filtered out since
  the grid loaded it) is shown alone, with no neighbours.

"My pictures" reads its folder whole; it is described in [architecture.md](architecture.md).

## Picture metadata

Each picture carries what its source tells about it (`Picture`; a field the source does not give stays
empty):

- a release row: its labels (title, artists, museum; the file name when there is no title label), the
  inception year, its Wikidata page (`https://www.wikidata.org/wiki/<Q-id>`), the licence with its link (the
  release's, else the licence's deed) and the image credit where the licence asks for one;
- Cleveland: the artists, the date, the museum, the artwork's page, the licence (CC0) and the master's pixel
  size;
- Commons: the author, the date taken, the file's page (its short `curid` link), the licence with its link
  and the original's size;
- a picture in "My pictures": only its name and pixel size.

## Image URLs

Commons URLs are built from the file name alone, with no API request (`CommonsUrls`): the original at
`upload.wikimedia.org/wikipedia/commons/<h1>/<h2>/<File>` (the first one and two hex digits of the MD5 of
the name), a thumbnail at one of Wikimedia's standard widths (20 … 330, 500, 960, 1280, 1920, 3840 px; a name
longer than 160 bytes is `<W>px-thumbnail.<ext>`, a TIFF's `lossy-page1-<W>px-<File>.jpg`, an SVG's
`<W>px-<File>.png`), percent-encoded as MediaWiki does it, so the URLs are the ones the Commons API hands out
and share its caches.

- A card: the 330 or 500 px thumbnail (above).
- The viewer: a standard width that fits the screen (960, 1280, 1920 or 3840 px), or the original when a
  JPEG, PNG, WebP or GIF is no wider (Commons does not scale up); a TIFF narrower than the step at the largest
  standard width it has, an SVG always as a thumbnail. Cleveland: the `web` image up to a Full HD screen and
  the `print` image on larger ones.
- The frame: a release painting's 1920 px rendition (`CommonsUrls.FRAME_WIDTH`); see [frames.md](frames.md).

## Network

All requests go through one OkHttp client (`Http.client`, with the app's User-Agent; a `newBuilder()` of it
adds a call timeout, as for the lists and the release files): at most 4 at a time to one host, and an HTTP
429 or 503 answer to a GET is asked again at most twice, after its `Retry-After` (or 1 s, then 2 s), unless
it asks for more than 10 s; a cancelled request stops waiting at once, and the main thread never waits
(`RetryPolicy.kt`). General network rules: [architecture.md](architecture.md).

A host that answered 429 or 503 gets no card thumbnail request until the pause it asked for is over (at most
a minute): a thumbnail asked for in the meantime is answered 429 by the app itself, so a fast scroll past
hundreds of cards does not send hundreds of thumbnail requests to `upload.wikimedia.org` when it asks for a
pause (it answers `Retry-After: 11`), and a card whose thumbnail was refused so asks again once the pause is
over, up to three times while it is on screen. A thumbnail that waits out a pause or a retry reads the hold
again before it is sent, so a longer pause asked for meanwhile counts too. Only the cards' thumbnails are held
(tagged `Holdable` on Coil's `ImageRequest`, which passes its tags to OkHttp): what a user asked for (the
picture sent to the frame, the viewer's image) and the lists and release files are always sent, and get the
server's own answer. Coil's loader is installed only by `Images.install` (once per process; Coil allows one
`DiskCache` per folder) and keeps up to 150 MB of images in `cacheDir/image_cache`.

## Nudity

### Levels

Every catalog painting has a nudity level (`NudityLevel`, stored as its ordinal in `picture.nudity`):
explicit (2: genitals in focus, a sexual act or assault, such as Courbet's "L'Origine du monde"), nude (1:
other nudity, a classic nude), or none (0). A Wikidata row's level is `Nudity.levelOf`, the strictest of:

- the release's `nsfw` field (`"nude"` or `"explicit"`; absent or blank is none; a value this version does
  not know counts as nude, never less strict than a mark it cannot read);
- the list `tools/catalog-nsfw.txt`, built into the app too (`Nudity.EXPLICIT`, `Nudity.NUDE`), so it applies
  to a release already published;
- Wikidata's nude genre (P136 `Q40446`, `Nudity.NUDE_GENRE`) → nude.

A source never lowers it. Live rows (Cleveland, Commons) carry none: level 0. The import computes the level as
it reads each row; each process start raises stored rows to the built-in list (`CatalogDao.markListed`, in
chunks of `LISTED_CHUNK` ids), since a release already imported is not read again when a newer app version
brings a longer list (an unchanged file is a 304); it never lowers a level and writes no row whose level is
already there.

### Filter

`NudityFilter` has three values, each an exclusive bound on the level (`nudityBelow`): "Hide explicit works"
(`HIDE_EXPLICIT`, the default), "Hide all nudity" (`HIDE_NUDES`) and "Show all" (`SHOW_ALL`). The Catalog
shows the rows below the bound of the filter in force: every query the grid and the viewer read takes
`nudityBelow` (`pages`, `count`, `PLACED_ROW`, `FROM_RANK`), so the paging query, the count and the viewer's
"n / N" and Left/Right all leave the hidden ones out; only `checkEmpty` counts unfiltered. The order is
computed over all levels, so a filter change writes nothing and a process start ranks nothing again. A
Cleveland or Commons copy of a Wikidata painting is hidden as its duplicate whatever the filter.

### Setting and lock

Below the frame list, **Settings → Catalog** has one row, **Nudity**, with the filter in force. A press (OK
or a tap) moves to the next value in that order (hide explicit → hide all nudity → show all → hide explicit,
`NuditySetting.next`) and applies it at once (prefs `catalog`, key `nudity`, the `NudityFilter` name; the
default for a value it does not know, `CatalogFilterStore`); under the value the row says that works are
marked from Wikidata and a hand-checked list and that some may not be marked (Cleveland and Commons rows
never are).

The device can lock it at "Hide all nudity" (`NudityLockReader`, read at each `onStart` of the activity, so
also on a return to the foreground, and when the settings open, since in a split screen the system settings
change it while the app stays started; never stored or sent): Android TV's parental controls on
(`TvInputManager.isParentalControlsEnabled()`; phones and tablets without `android.software.live_tv` have no
such service), or a user that may not change accounts (`UserManager.DISALLOW_MODIFY_ACCOUNTS`, set for a
restricted profile, and on some managed devices). Parental controls win as the more specific reason; a read
that fails counts as off. The row then shows that value with the reason ("Locked by the TV's parental
controls" or "Locked for this profile"), is disabled (the D-pad still reaches it) and a press does nothing;
the stored choice comes back once the lock is off. Family Link and Google TV kids profiles have no public API
and are not seen. In force is `NudityFilter.effective(choice, lock)`; `PRIVACY.md` says what the lock reads.
The system log gets the filter in force and the lock's kind when it changes.

### Changing the filter

A change of the filter in force (`MainViewModel.applyNudity`, the one place) re-pages the grid and takes it
back to its top (the D-pad focus moves to the tab first), drops an open or send waiting at the gate, and
closes an open Catalog viewer (it steps under the filter it opened with, so its "n / N" would count another
list than the grid's) and the details panel of a Catalog picture, as the picture may be one the filter now
hides; the Bluetooth panel stays but no longer sends its Catalog picture. The old cards stay until the new
list's first page arrives (and after an import until the grid re-pages), so a picture carries its level: an
old card the new filter hides shows empty at once (`CatalogPicture.card`).

### The gate

A press, a long press (details), "View full screen", each Left/Right of the viewer, "Send to frame" and the
Bluetooth panel's later send go through one gate (`MainViewModel.allowed`, run by `whenAllowed`), which
reads the level stored at that moment (`CatalogDao.nudity`) and checks it against the filter in force: a
hidden picture opens and sends nothing (the refusal is only logged; its card is about to go). A write that
finishes during the read reads it again (`CatalogStore.writes`); a picture gone from the database, or a
failed read, keeps the level it was read with; a picture of "My pictures" is never read. What waits for that
read is dropped by a newer press or send, another tab, the details panel dismissed, the settings, the
Bluetooth panel, a new filter and Back.

An import that raises a shown picture's level (a new release, the built-in list) takes it away the same way:
after each write to the catalog, and when the viewer finds no place for its picture, the viewer, the details
panel and the Bluetooth panel's Catalog picture are checked again at the stored level (`recheckShown`).

## Catalog export

`uv run --script tools/catalog-export.py <outdir>` exports famous paintings from Wikidata and Commons as
gzipped JSON Lines for the GitHub release `catalog`, which the app downloads. The set: paintings (P31
Q3305213) with an image (P18) and at least 3 sitelinks; fame is the number of sitelinks. Phases, sequential,
one request at a time: WDQS ids and sitelink counts per fame bucket (cached; `--fame-file` skips it), Wikidata
`wbgetentities` for the paintings and then the entities they reference, Commons `imageinfo` for each image's
size, licence, attribution flag and credit. The tool's docstring is the full reference (row format, cache,
retries, exit status, Wikimedia etiquette).

- Output: `core.jsonl.gz` (every painting, language-neutral, sorted by fame), `labels-<lang>.jsonl.gz` (one
  per language present in Wikidata), `labels-all.jsonl.gz` (every language, the source of the starter cut),
  `starter/` (the APK's starter set) and `cache/` (not published). The top-level `*.jsonl.gz` files are the
  release assets, replaced in place:
  `gh release upload catalog <outdir>/*.jsonl.gz --clobber`. The `catalog` release is never marked latest
  (`releases/latest` is the app download), and no workflow touches it.
- `--starter-from <dir>` cuts the starter set from the downloaded release files ([Starter set](#starter-set)).
- Each `core` row carries a nudity level, `"nsfw": "nude"` or `"explicit"` (left out when none): the strictest
  of the list's level, Wikidata's "depicts" (P180: a vulva, a penis or sexual intercourse → explicit; nudity
  or pubic hair → nude; breast alone does not count) and the nude genre (P136) → nude. The release published
  before this field was added does not carry it yet.
- `python3 tools/catalog-export.py --self-test` checks the tool offline (row building, the nudity list, the
  starter cut); it is not in CI, so run it after changing the tool.
- A full export takes 20+ minutes of Wikidata load: test a change with `--fame-file` on a handful of ids
  instead. Never run it on devices.

## Nudity list maintenance

`tools/catalog-nsfw.txt` (`--nsfw-list`) is kept by hand, one painting per line:
`<Q-id> <explicit|nude>  # <title>` (blank lines and `#` lines are ignored; a malformed line or an id listed
twice is an error). The app builds the same ids in (`Nudity.EXPLICIT`, `Nudity.NUDE` in `catalog/Nudity.kt`)
and `NudityTest` checks they equal the file, so an edit changes both: it fails until both agree.

For an added explicit id, cut the starter set again with `--starter-from`. Installs that hold the release
already get the new level at their next process start (`CatalogDao.markListed`).

## Testing and debugging

- Room DAOs and the database run as JVM unit tests under Robolectric (SDK 36, JDK 21); see
  [building.md](building.md). Its SQLite is newer than minSdk 26's ([Database](#database)); the
  `markListed` test passes a small chunk, as Robolectric binds far more parameters.
- An app locale outside the generated localeConfig (e.g. `doi-IN`) is not applied by Android, so a label
  chain with no labels file is testable only in Robolectric (`CatalogStoreTest`).
- OkHttp's own `RetryAndFollowUpInterceptor` repeats a 503 carrying `Retry-After: 0` once: a test that
  counts requests answers another code (`CatalogSyncTest` uses 500).
- `StarterAssetsTest` checks the starter set; `NudityTest` the built-in list against `tools/catalog-nsfw.txt`.
- Measure import times only on a release build ("starter set imported in N ms" in `CatalogStore`'s log): a
  debug build is several times slower (5–9 s against 1–2 s).
- The emulator's DNS is flaky right after boot; a catalog "Unable to resolve host" usually passes on retry.
- To force an offline or slow catalog on the TV emulator (airplane mode does not cut its ethernet), set a
  dead global proxy: `adb shell settings put global http_proxy localhost:9`; undo with
  `adb shell settings delete global http_proxy` and `adb shell settings put global http_proxy :0`.
- A fast D-pad scroll makes `upload.wikimedia.org` answer 429 with `Retry-After: 11`: the case the host hold
  covers (`RetryInterceptor`), visible in logcat after a scroll.
