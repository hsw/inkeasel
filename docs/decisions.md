# Decisions

Design decisions with their reasons and the options that were turned down, newest first. An entry marked
*planned* is decided but not implemented yet; the change that implements it drops the mark.

## 2026-11-09 — The Catalog tab pages from the database; the viewer steps by rank
The Catalog is the first tab and the one the app starts on; My pictures and, until the live sources move
into the database, Paintings and Landscapes follow. It shows `CatalogStore`'s Paging 3 list
(`collectAsLazyPagingItems`, pages of 60 rows, placeholders on): a row not read yet is an empty card of the
same size that takes the D-pad focus and does nothing when pressed, so the grid has its full length at once
and the focus never jumps when a page arrives. A row becomes a picture at the edge (`CatalogPicture.of`)
with a translatable `list_names_separator` for the artists and a 330 or 500 px thumbnail, the smallest
standard width that covers the card's column in pixels. The viewer opened from a Catalog picture steps
through the database, not through what the grid has loaded: the shown neighbour by rank, with its place
and the list's size (`COUNT` queries) for "n / N" (`CatalogSteps`); it shows the picture alone for the
moment its place is read, and a step pressed while one is read is dropped. My pictures and the old tabs keep
stepping through their loaded list (`ListSteps`). The list's pager starts only once the starter step has
run: until then the tab says "Loading the catalog…", and its first query neither waits for the starter's
transaction nor holds it up; should the starter set fail to import and leave the database empty, the tab
says so instead of loading for ever. Back counts the placeholders as cards (`CatalogBack`), so it scrolls a
far-scrolled Catalog back to the top. The Catalog keeps its own grid state, so its place survives a visit
to another tab.
Rejected: the whole list in memory (about 9,600 rows on 2 GB TV boxes, and the paging query exists for
this), stepping the viewer through the loaded pages (it would stop where the grid stopped loading, and "N"
would be the loaded count), placeholders off (the grid grows under the focus while pages arrive), the cards
in a fixed 500 px thumbnail (twice the pixels a phone or a three-column window needs).

## 2026-11-09 — The starter set is one labels file per language
The first start imports the starter set before the Catalog can show anything, and measured on the TV
emulator (release build, fresh install) it took about 7 s warm and over a minute right after a cold boot:
it read the one starter labels file of every UI language (41,643 rows, 4.6 MB of text), parsed each row
with `org.json` to learn its language and kept about 2,000. The starter's labels are now cut as in the
release, one `labels-<lang>.jsonl.gz` per language (`--starter-from` writes them, 1.12 MB together, 68 KB
more than one file), and a start opens only the files of its chain, English and `mul`. The import also lost
its regular expressions (a matcher per id and language code; plain loops now), writes the starter's rows at
their final ranks when the table is empty (no rank pass afterwards), and the rank pass moves rows through
free ranks only when a new rank is taken. On the same emulator (five alternating fresh installs of each
release build), from the process start to the starter set in the database: 6.8–10.6 s before, 1.7–2.1 s
after; the import itself, the database's creation included, 1.0–1.3 s, about 0.5 s when nothing else
runs (the app's first frame and the TV launcher compete for the CPU). A debug build is several times
slower (5–9 s), as debuggable code runs unoptimised.
Rejected: a prebuilt SQLite file in the APK (Room's schema and identity hash in the export, as rejected
before), skipping other languages' rows of the one file by their first bytes without parsing (tried: still
inflates, hashes and decodes 4.6 MB, and depends on the key order), showing the pictures before their
labels (titles would flip from file names to labels), a background start before the activity (no
Application class for it; the import already runs off the main thread from `onCreate`).

## 2026-11-09 — Catalog pictures build their Commons URLs from the file name
A catalog row becomes a `Picture` by a pure mapping (`CatalogPicture`): id `wd:Q…`, title, artists and first
collection from the labels (the file name without its extension when there is no title label), the inception
year as the date, the Wikidata page (`https://www.wikidata.org/wiki/Q…`) as the web page, source "Wikidata".
Its image URLs are built from the Commons file name with no API request (`CommonsUrls`, on
`upload.wikimedia.org`, as MediaWiki names them): the MD5 hash path of the name with underscores, MediaWiki's
percent-encoding (so the URLs match the API's and share its caches), only the standard thumbnail widths;
a name over 160 bytes is `<W>px-thumbnail.<ext>`, a TIFF `lossy-page1-<W>px-<File>.jpg`, an SVG
`<W>px-<File>.png`. Commons does not scale a picture up, so a JPEG, PNG, WebP or GIF no wider than the
asked step is loaded as its original, a TIFF that narrow at the largest standard width it has (never the TIFF
itself), an SVG at any width. The card takes a 500 px thumbnail by default, the frame the 1920 px step, the
viewer the step that covers the window (`ViewerRules`, for any picture with a Commons file name). The
licence link is the release's `licUrl`, else the Creative Commons deed for `CC0` and `CC BY`/`CC BY-SA`
short names (with a jurisdiction port before 4.0); any other licence gets no link rather than a guess. Where
the licence asks for attribution, the image's credit (the Commons author of the photo or scan, not the
painter) is shown in the details panel ("Image credit") and in the viewer's credit line ("image: …"). The
artists are joined with a separator the caller passes from a string resource, since they are labels in the
UI language.
Rejected: asking the Commons API for each picture's URLs (a request per card, and the export already has
the file name), any width that is not a standard one (refused by Wikimedia), the original for every narrow
file (a TIFF original is tens of megabytes and not decodable by Android), a deed URL for public-domain marks
and other licences (not a Creative Commons deed; a wrong link is worse than none).

## 2026-11-09 — The catalog syncs at app start, file by file, from the fixed release address
One process-wide `CatalogStore` owns the catalog database, a coroutine scope of its own (not a screen's) and
the one write lock every catalog write takes. The app's start (`MainActivity.onCreate`, also after a change
of the UI language, which re-creates it) wakes it: once per process the starter set goes into an empty
database, and every start runs a pass that checks only the release files that are due (`CatalogSyncPlan`,
pure): `core` and `labels-<lang>` for the chain's languages, English and `mul`, each a week after its own
last check, a language new to the chain at once, a 404 again a week later. A pass with nothing due makes no
request, so passes run at every start rather than once per process: on a TV the notification listener keeps
the process alive for weeks. A check always starts at
`https://github.com/hsw/inkeasel/releases/download/catalog/<file>` (the signed address it redirects to
expires and is never stored) with `If-None-Match`, streams a 200 to a temporary file while hashing it, and
skips the import when the SHA-256 is the one imported last (a re-upload gets a new ETag even for the same
bytes). The changed files go into one swap (`CatalogImport.release`), and their ETag and hash are written in
that transaction, so a failed import, a body cut short or no network leaves both the list and the state as
they were, and the files are asked for again at the next start; one file's failure does not hold back the
others. A language that leaves the chain loses its labels at the next swap, and its file's state goes in the
same transaction, so a return downloads it again instead of getting a 304 for labels no longer stored. A
language that joins the chain gets the starter set's labels at once (offline too), looked for once per
process. The UI language is the configuration's first locale: the per-app language where Android 13+ sets
one, else the system's. The tabs do not show the catalog yet, so the delivery entry below stays *planned*
for the list itself and the weekly export job.
Rejected: WorkManager (a scheduler and a dependency for a check that only matters while the app is used;
periodic work would wake a TV for a list nobody looks at), `releases/latest` (it is the newest APK release),
recording the ETag as soon as the answer arrives (a 304 would then hide a failed import), checking once per
process only (weeks without a check on a TV), a check with a HEAD request first (a second request per file;
the conditional GET already costs nothing when the file is unchanged).

## 2026-11-09 — The starter set is cut from the published release
The APK's starter set (`app/src/main/assets/catalog/`: the first 1,000 rows of the release `core` and their
labels in the UI languages plus `mul`, 1.1 MB gzipped; one labels file per language since the entry "The
starter set is one labels file per language") is cut from the published release files by
`tools/catalog-export.py --starter-from`, through the same writer as a full export and with no Wikimedia
request, so its rows are byte for byte the first lines of the release `core` and the release import replaces
them in place. It is committed and cut again after each release upload; the build stays offline. All 75
default languages fit well under the 1.5 MB budget, so the default (`--starter-langs`: the Android UI locales
that Wikidata covers, plus `mul`) stays. AGP's asset merge decompresses `.gz` assets: the APK holds
`catalog/core.jsonl` and the labels files without `.gz` (deflated, about the same size), and the app opens
those names. The export also writes `cma` (Wikidata P11110, the Cleveland Museum of Art ID, which is the museum's
accession number), to hide live Cleveland rows that duplicate a Wikidata row, and `licUrl` (Commons
`LicenseUrl`, always https, left out when Commons gives none or not a plain URL) for the licence link. The
published release gets both only when it is exported again; until then the reader treats them as absent.
`--self-test` checks the row building and the starter cut offline; it is not part of CI.
Rejected: building the starter set in Gradle from a download (a build that needs the network), a separate
export run for the starter (a second snapshot that may disagree with the release), fewer languages (not
needed under the budget), committing it uncompressed (4.9 MB in the tree for the same APK size).

## 2026-11-09 — A release import is staged and swapped in one transaction
The starter set goes straight into the live tables, in one transaction and only into a database with no
Wikidata row (so a second run reads nothing), keeping labels of the language chain, English and `mul`. A
release import (`CatalogImport`) first clears the `_stage` tables, then streams `core` and each labels file
into them in batch transactions (one batch in memory), and only then swaps in one transaction: the staged
pictures replace the Wikidata rows by id (a row already there keeps its rank, so the starter's top 1,000 do
not move), rows absent from the new `core` are swept, its entity links replace all links, each imported
labels file replaces its language (a file of every language replaces every chain language), labels outside
the chain are dropped, and rank and dedupe are computed again. A broken file (a truncated gzip, a failed
download) or a `core` with no valid row fails the whole import before the swap, so nothing visible changes
and the paging source, which reads only live tables, is invalidated once, by the swap; leftover staging is
cleared then and at the next start. In a `core` the first row of an id wins. Labels of entities no longer
referenced stay until their language's file is imported again: removing them by reference would lose labels
when a new `core` arrives after an unchanged labels file that is not imported again. No UPSERT (SQLite 3.18):
`INSERT OR REPLACE` keeps the rank by reading it from the row it replaces. The delivery entry below stays
*planned* (the starter import and the download came with the sync entry above; no list shows them yet).
Rejected: per-batch upserts into the live tables (a list mixing two releases while it imports, no sweep of
absent rows, a paging invalidation per batch), staging the starter set too (it goes into an empty database,
where one transaction already is all or nothing).

## 2026-11-09 — The catalog database holds only data that can be downloaded again
The catalog database (`CatalogDb`, separate from the frame and feature preferences) stores the release's
rows, the live sources' rows, the labels of the stored languages and the state of the release files: nothing
the user made. So any other schema version, older or newer (a downgrade included), drops it and creates it
empty (Room's destructive fallback both ways), and the starter set imports and the files download again.
The order is stored, not computed when the list reads it: every row has a unique `rank` and a `hidden` flag,
set in the write transaction after each import or live refresh (`CatalogRank`, pure; only the rows that
change are written), so the paging query is `WHERE hidden = 0 ORDER BY rank` on one index and the viewer
steps by the neighbour ranks. Wikidata rows go in `core` order; a live row that duplicates one (the same
Commons file, or the same accession number) is hidden; the other live rows take turns by source as every
10th card. Titles, artists and collections are resolved by the query for the rows of a page only, over the
language chain, then any stored language; artists come back as a list and are joined only at the edge. The
SQL keeps to what SQLite 3.18 (Android 8.0) has: no window functions, no UPSERT.
Rejected: Room migrations for this database (code to keep for data that downloads again), ranking in the
paging query (`ROW_NUMBER` needs SQLite 3.25; a sort per page), joining the artists with a visible separator
in SQL.

## 2026-11-09 — Catalog labels follow a language chain; release rows are read leniently
The catalog's labels are looked up along a chain of Wikidata language codes built from the UI language's tag
(`LanguageChain`, pure): the regional or script variant first where Wikidata has one, then the language, then
`en`, then `mul` (Wikidata's language-independent labels); a label missing in all of them falls back to any
stored language. Android's legacy codes are mapped (`in`→`id`, `iw`→`he`, `ji`→`yi`, `fil`→`tl`). Variants:
`pt-br`, `en-gb`, `en-ca`, `en-us`, `de-at`, `de-ch`; `es-419` for Latin American regions (`es-US` included,
Android's Latin American Spanish); Chinese by script (`Hant`, or Taiwan, Hong Kong and Macau without one, is
Traditional: `zh-hant`, the regional codes with the user's own first, `zh`; else `zh-hans`, …, `zh`);
Serbian `sr-el` or `sr-ec`, then `sr`; Norwegian `nb`, `no` (Nynorsk `nn` first, then those two). The chain
names only codes the release can have a labels file for, since each one is a download and a weekly check.
The release's JSON Lines are read in batches (`JsonLinesReader`, gzip or plain, with the SHA-256 of the file
as downloaded): a line that is not a valid row (no Wikidata id, no image, no size) is skipped and counted,
unknown keys and optional values of the wrong type are ignored, so a newer export never stops an older app;
a broken stream (a truncated gzip) fails the whole file.
Rejected: `<language>-<region>` for every region (a 404 per user each week for `ru-ru`, `fr-fr`, …), English
before the user's other variants of the same language, rejecting a whole file for one bad line.

## 2026-11-09 — Room and Paging, tested under Robolectric
The catalog database is Room (2.6.1, compiler through KSP) shown through Paging 3 (3.3.6). Both are pinned
below their newest lines because those pull a newer Kotlin standard library (Room 2.7+: 2.1, over the 2.0.21
compiler) or Compose foundation over the BOM (paging-compose 3.4+). The DAOs, the import and the paging
source are tested as JVM unit tests on an in-memory database under Robolectric (4.16.1, SDK 36), with
paging-testing, so they run in the same `testDebugUnitTest` as the pure tests, in CI and without an emulator.
Robolectric's SQLite is newer than the one on Android 8.0 (3.18), so the SQL stays within what 3.18 has.
Rejected: instrumented tests only (an emulator in every CI run and in every local test loop), no DAO tests
(the paging query, the neighbours and the staging swap are SQL), a hand-written fake DAO (tests the fake, not
the queries).

## 2026-11-09 — A full-screen viewer, opened from the details panel
**View full screen** (second in the details panel's action row; **Send to frame** keeps the first focus)
opens the picture letterboxed on black, fitted, never cropped, in a dialog that hides the system bars. A
plain press on a card still sends at once. The viewer replaces the details panel (dialogs never stack), and
Back brings that panel back for the picture then shown, not the grid: Back returns one step, and after
Left/Right the details of what is on screen are the useful ones. Left/Right go through the list of the tab it
was opened from and stop at its ends (no wrap). Any other key shows the action row with the credit line; its
CENTER only shows it (its release is swallowed, so it never also presses **Send to frame**); volume, Home and
Back stay the system's. With no frame chosen the row says so instead of a button. Back closes the viewer even
while the row is up, rather than hiding the row first: the row hides by itself, and two presses to leave would
cost more. The credit line (title; artist, source, licence) shows for 5 s on open, on every picture change
and with the row: the Commons licences (CC BY, BY-SA) ask for attribution wherever the picture is shown.
Touch: a swipe of 64 dp or more goes to the next or previous picture, a tap toggles the row. **Send to frame**
takes the card's path and keeps the viewer open; only the Bluetooth panel, when it is needed, replaces it.
The rendition is sized to the window in pixels (on a 4K TV whose UI runs at 1920 × 1080 a larger image could
not be drawn sharper) taken landscape, so a rotation loads and decodes nothing again (`ViewerRules`, pure):
Commons the smallest standard thumbnail width (960, 1280, 1920, 3840; Wikimedia refuses any other, 2560
included, with HTTP 400 or 429) that covers the fitted width or the original's, or the original as listed
when it is narrower than 1920 px; Cleveland `web` (about 893 px high) up to a Full HD window and `print` (up
to 3400 px wide) beyond, so a 2560 × 1600 tablet gets `print`; a local file as is. Coil decodes it to at most
the window's size, so a 10 000 px original costs one screen of bitmap memory. The card's thumbnail (in the
cache from the grid) stands in while it loads and stays if it fails, with the reason in the credit area. The
screen is not kept on (no `FLAG_KEEP_SCREEN_ON`): a still picture is static imagery, and Google's TV
guidance lets the screen saver or Ambient Mode start over it.
Rejected: CENTER on a card opening the viewer (one more press per upload), keeping the details panel under the
viewer (two dialogs and their focus), Back to the grid (skips the step the user came from), Cleveland `print`
on a Full HD screen (about 5 MB per picture for a modest gain over a 1.2× enlargement), a 2560 px Commons
width (refused), the display mode's size instead of the window's (decodes pixels the window cannot show),
pinch-zoom and a timed slideshow (left for later).

## 2026-11-08 — A long press on a card opens its details; a press still sends
A plain press (CENTER or a tap) keeps the one-press send. A long press (tv-material `Card.onLongClick`, which
takes CENTER held; a long touch through `onTap(action, onLongPress)`) opens a full-screen details panel laid
out after Google's TV "content details" template: the title, the fields as label and value lines, **Send to
frame** first and focused, and the picture large on the right above a preview of it on the chosen frame.
The preview runs the upload's own decode, crop and Floyd–Steinberg onto the frame's inks
(`EpdImage.previewOf`) on the full image an upload would download, off the main thread, and is cancelled
with its download when the panel closes; without a frame, or with one that cannot be drawn on, the panel says
so instead. Send closes the panel and takes the card's path (`BleAsk.onSend`: a toast without a frame, the
Bluetooth panel without the permission), so dialogs never stack. Links are text: a TV launches no browser,
and a QR code would need an encoder (a dependency or several hundred lines) for little gain over a short
link (Commons gets its `curid` link).
No footer with the focused card's details under the grid for now (Google's "immersive list"): on a TV
(960 × 540 dp, 4 columns, one header row, 48 × 27 dp margins) two lines at 14 sp or more take about 56 dp,
which cuts the visible full rows from three to two; it would repeat the card's own two lines; and touch
screens have no focused card, so it would stand empty there. The details panel shows all of it on demand.
Rejected: CENTER opening the details and sending from there (one more press per upload), an overlay
inside the catalog instead of a dialog (focus would leak to the grid behind it), rendering the preview from
the card's thumbnail (Commons' is 500 px wide, smaller than the frame, so its dithering would not be the
frame's), a disabled placeholder button for the viewer (a control that does nothing).

## 2026-11-08 — A picture carries its metadata, named after the one-catalog schema
`Picture` keeps `title` and `credit` (the card's two lines) and gains what the sources tell: `artist` (all
creators as one text), `date` (as the source writes it), `collection`, `pageUrl`, `licence`, `licenceUrl`,
`width` and `height` of the original; `source` is the prefix of the id. A field a source does not give is
`null`, never a guess. The names follow the planned one-catalog schema, so the Room move maps fields instead
of redesigning them. Cleveland: the museum's name as the collection (data, not translated), the artwork page,
`share_license_status` (CC0 with its deed link), the `full` master's size. Commons: the short `curid` page
link (typable from a TV), `DateTimeOriginal` without the time of day, HTML in the metadata stripped and its
entities decoded, only `https:` links kept. Local files: the pixel size from the file header; no date (the file
time is when it was copied, not the picture's date). The mapping is pure (`SourceMapping.kt`) and tested
against trimmed real answers. `CatalogCache` writes format 2 with the new fields left out when `null`; a
format-1 file still reads with them `null` (the cache is only a fallback, and the next good load rewrites
it), a newer format reads as no cache.
Rejected: dropping old caches on the format change (a source that is down would show nothing until it
recovers), a list of artists (the sources give one display text; the Room schema can split it), the Commons
file page URL as the link (long and percent-encoded, hard to type).

## 2026-11-08 — No poster on low-RAM devices with Android 10 or older
Android neither grants nor binds a notification listener on a low-RAM device (`isLowRamDevice()`) up to
Android 10 (`NotificationListenerService` reference), not even through adb, so the menu path, the adb command
and "Grant access" cannot work there. `ListenerAccessUi` has an unavailable state for it, checked before the
granted one: "The now-playing poster is not available on this device", the switch disabled and shown off,
and no way to the grant, so no disclosure. The stored wish (`FeatureStore.posterEnabled`, on by default) is
left untouched: with no listener bound nothing reports what plays, so `FrameController` schedules no poster
anyway, and nothing else has to keep a second flag in step. Low-RAM devices with Android 11+
may bind the listener (only the TV menu item is missing), so they keep the adb fallback.
Rejected: hiding the poster row (the user would not learn why there is no poster), forcing the stored wish
off on such devices (it would change stored data for what the system already prevents), the adb command
there (the system refuses that grant too).

## 2026-11-08 — Bluetooth is asked for in context, through one panel
The app no longer opens the system permission dialog in `onCreate`. Google asks to request a permission when
the user starts the feature and not all at startup (developer.android.com/training/permissions/requesting,
…/usage-notes). A start without the permission shows a panel over the catalog that says why ("InkEasel sends
pictures to your e-paper frame over Bluetooth. To find the frame, allow Nearby devices."), with **Find my
frame** focused and **Not now**; after a grant without a frame chosen it opens the frame settings. The
settings no longer open by themselves without the permission (they could only ask again), so the panel and
the settings never stack. "Not now", Back and any answer in the system dialog count as an answer: the panel
then never appears by itself again (prefs `permissions`, key `bleIntroDone`), only when the user wants the
feature: a pressed or shared picture without the permission opens it, and the settings keep "Allow". After a
denial it says what stops working before the dialog. Once the system no longer asks, there is no panel: the
app degrades in place, a pressed picture toasts why it is not sent and where the settings explain it, and
App info is offered only in the settings (and to a share, which opens them), following Google's advice not to
push users to the system settings. Below Android 12 the panel and the frame section name Location, which
the dialog asks for, and the panel adds that a Bluetooth search uses location and needs it on. The rules are
pure (`BleAsk`). With a frame chosen (the permission taken back later) the button says "Allow" and the pressed
picture is sent once granted.
Rejected: the system dialog at start (it covered the app before the user saw anything), the panel inside the
settings (a pressed picture would have opened a full-screen dialog to explain one permission), showing the
first-run panel on every start until granted (nagging), a panel for the permanently denied state with an App
info button (pushing to the system settings), asking again at every start after a denial.

## 2026-11-08 — The frame protocol is described in the repository
Code comments cite `docs/protocol.md`: the BLE protocol (advertising, GATT, framing, chunking, acks, timing)
and the image formats, each statement checked against the code, with what was only seen on a real frame or
only in the vendor's app marked as such. It leaves out the vendor app's internals, firmware updates and real
device identifiers.
Rejected: comments that cite a document outside the repository (a reader cannot follow them), rewording every
comment to stand alone (the byte layouts would be scattered over many files).

## 2026-11-08 — Text to read at TV distance is at least 14 sp
Google asks for text readable at about 3 m and gives no minimum size. Everything the user must read
(settings descriptions, the poster's access state and menu path, the disclosure panel including its adb
command, why a frame cannot be chosen or is not verified, the privacy address) is `bodyMedium` (14 sp) or
larger; tv-material's `ListItem` puts its supporting text in `bodySmall` (12 sp), so those texts set
`bodyMedium` themselves. 12 sp stays for secondary metadata only: a card's credit (`labelMedium`, up from
11 sp `labelSmall`) and a frame's address and advertised properties. The search hint names location only
where a BLE scan needs it (before Android 12), which makes it one line on a TV.
Rejected: a custom typography scale (every component would need checking against it), 14 sp for the
metadata too (a frame's property line would wrap on phones), restructuring the settings for shorter text
(a separate item).

## 2026-11-08 — Back from the catalog content goes to the tabs first
Back with the focus on a card or a Retry button moves the focus to the selected tab and scrolls the grid to
its first row; only the next Back leaves the app. That is Google's TV navigation guidance for a top
navigation, and before it Back several rows down closed the app at once. Phones and tablets get the same:
a grid scrolled down by touch scrolls back up on Back, without moving the focus onto a tab (it was not in the
content). The rule is pure (`CatalogBack`); the settings dialog, its disclosure panel and the share flow keep
their own Back.
Rejected: Back leaving the app from anywhere (the old behaviour), scrolling to the top without moving the
focus (the focused card would scroll out of view and the tabs would still be rows away).

## 2026-11-08 — Search runs locally first, then on live Wikidata (*planned*)
Search matches titles, aliases and artist names in every language stored on the device at once, whatever the
UI language ("Джоконда", "La Gioconda" and "Mona Lisa" find the same picture). It uses an FTS4 table over our
own normalised text (case folding, no diacritics, token prefixes, one typo on long tokens) in the framework
SQLite, so no native SQLite library ships. When the local search finds little, or the query is in a language
whose labels the device did not download, it asks Wikidata's full-text search (paintings with an image), then
the sources' own search APIs.
Rejected: FTS5 or the `unicode61` tokenizer (not guaranteed in the SQLite of minSdk 26 devices), a bundled
SQLite (`.so` files and their 16 KB alignment checks), a search server of our own.

## 2026-11-08 — The catalog is exported from Wikidata and shipped as files, without a server (*planned*)
A script in the repository, run by hand or by a weekly CI job and never on devices, exports paintings with an
image and at least 3 Wikipedia articles (about 9,600). Their metadata is CC0 and their files are on Commons,
which hosts only free files, so there is no copyright or death-date filter. The script records each image's
licence, attribution flag and credit, and the app shows the credit where attribution is required. The
output is JSON Lines sorted by fame: a language-neutral core and one labels file per language (label,
aliases, Wikipedia article title), published gzipped as GitHub Release assets rather than committed weekly.
The device downloads the core, English and the UI language (about 1.2 MB), more languages on demand. The
release also carries one file with every language for offline search in all of them. The APK carries a
starter set, the top 1,000 with labels in every UI language (about 1.1 MB), which shows at once on first
launch while the full catalog downloads and imports in the background. The download replaces the starter rows
by Wikidata id in one transaction; the starter set stays if the download fails. A weekly update fetches only
changed files (ETag). The export is `tools/catalog-export.py`; it keeps each Query Service result on disk and
retries only the failed queries, and fails rather than write a partial catalog. Its files are the assets of
one GitHub release with the fixed tag `catalog`, replaced on each update, and the app reads
`https://github.com/hsw/inkeasel/releases/download/catalog/<file>`. Asset names are flat (`core.jsonl.gz`,
`labels-<lang>.jsonl.gz`, `labels-all.jsonl.gz`), since a release asset cannot sit in a directory.
Rejected: one labels file with all languages on the device (3.8 MB gzip, but about 16 MB of text and an
estimated 30–40 MB in SQLite with the search index, too much for 8 GB TV boxes), querying Wikidata from
devices for the catalog, a backend of our own (not needed; the format lets a server serve it later),
committing the export weekly (repository growth), `releases/latest` for the catalog (it is the newest APK
release).

## 2026-11-08 — A multilingual app with no preferred language (*planned*)
English is the base. The app is translated into the languages that both Wikipedia/Wikidata and Android
support, and none gets preference: Russian is one translation among the others. Picture titles and artists
come from the catalog in the UI language, falling back to English, then to the original label. Translation
feedback goes to GitHub Issues.
Rejected: keeping English and Russian as the two first-class languages.

## 2026-11-08 — One "Catalog" section, sorted by fame, with filters and search (*planned*)
Every source (the Wikidata catalog and the live museum and Commons sources) is mapped to one schema in a local
Room database and shown as one Catalog section instead of a tab per source. The default order is fame: the
number of Wikipedia articles (Wikidata sitelinks), found for museum pictures through the museum's
external-ID property on Wikidata; pictures without a match rank by the source's own signal, after the matched
ones. The same painting from two sources is one entry, joined by its Wikidata id. Filters (museum, artist,
period, movement, genre, orientation, fits the frame) and search narrow the same Paging 3 list; "Landscapes"
becomes a genre filter. Navigation shrinks to Catalog, My pictures and Settings.
Rejected: a tab per source (does not scale past a few sources and repeats the same painting), sorting by
source order or at random by default.

## 2026-11-08 — What plays is logged only in debug builds
Now-playing titles, poster keys (an artwork URL or the SHA-1 of the artwork pixels, also as `onFrame`) and the
artwork addresses in a poster upload's error reach logcat only through `MediaLog`, which passes them as they
are in a debug build (`BuildConfig.DEBUG`, so `buildConfig` is on) and writes `<title>` / `<key>` in a release
build. Package names, session ids and the user's own picks (`default:` and `manual:` keys, catalog titles,
shared file names) are not what plays and stay. A redacted error keeps its class, its stack trace and the rest
of its message (e.g. `HTTP 404: <key>`). Logcat is readable over adb and lands in bug reports, and the
disclosure before notification access should not need a clause about the log, so it and `PRIVACY.md` drop it.
The cost: diagnosing a poster problem by film needs a debug build; a release log still shows every step.
Rejected: keeping the titles in release logs and disclosing them (the earlier choice; it widens what the user
has to accept for diagnosis only), dropping the log lines entirely (they are what a poster problem is debugged
with), a short hash of the key instead of a placeholder (it would still link log lines to one film).

## 2026-11-08 — No cleartext traffic on any Android version
The main manifest sets `android:usesCleartextTraffic="false"`. targetSdk 28+ makes that the default only from
Android 9; on Android 8.x (minSdk 26) cleartext was still allowed, and OkHttp follows a redirect from https to
http. With the attribute, the platform's policy (which OkHttp honours; Coil uses the same client) refuses every
`http://` connection, a redirect included, so `PRIVACY.md`'s "over https" holds on every supported version.
No catalog or artwork address is `http://` (the catalogs publish https, poster art uses only https URIs).
`ManifestTest` guards the attribute and that no overlay or network security config relaxes it.
Rejected: a network security config (one attribute says the same), an OkHttp interceptor that rejects
downgrade redirects (covers OkHttp only, not every client the platform policy covers).

## 2026-11-08 — "My pictures" stays in the app's external files folder
Shared and copied pictures live in `getExternalFilesDir()/pictures`: Android deletes it with the app, it needs
no storage permission, and `adb push` can put pictures there (the empty tab shows that path). The cost
is that on Android 9 and older, and on Android 10 for apps that opt out of scoped storage, any app with the
storage permission can read the folder. `PRIVACY.md` and the README say so instead of calling all stored data
private; everything else stays in internal storage.
Rejected: moving the folder into `filesDir` (private on every version, but no way left to copy pictures in
from a computer).

## 2026-11-08 — The listener follows media sessions whenever the access is granted
`NowPlayingListener` reads every media session (title, state, artwork; artwork bitmaps cached as PNGs) as
long as notification access is granted, also while the poster is switched off. `FrameController` needs that:
switching the poster on acts on what plays at once (`onPosterSwitched` arms the start of a film that is
already playing, whose PLAYING is not reported again), and switching it off reverts a poster even after a
process restart. `PRIVACY.md` and the README say exactly this (reading while the access is granted, the
artwork cache, the `onFrame` key that may be an artwork URL, the BLE rescan during an upload, the hold
service), and that revoking the access is what stops the reading; the disclosure panel before the grant says
it too. Titles and artwork keys reach the logcat lines only in debug builds (see "What plays is logged only
in debug builds" above).
Rejected: gating the listener on the switch (the poster would come up only on the next transition into
PLAYING after switching it on, and a lost revert would wait for one).

## 2026-11-08 — No backup and no device transfer of app data
`android:allowBackup="false"` stops backup only up to Android 11; from Android 12 a device-to-device transfer
ignores it. `android:dataExtractionRules` (`res/xml/data_extraction_rules.xml`) therefore excludes every
domain for both cloud backup and device transfer, so "nothing goes into a backup or a transfer" in
`PRIVACY.md` holds on every version. Each domain is listed on its own (`root`, `file`, `database`,
`sharedpref`, `external` and the four `device_*` ones): the backup agent walks the files, databases and
shared preferences as trees of their own and matches an exclusion by the exact path of what it visits, so an
exclusion of `root` (the app's data folder itself) never matches inside them and left the preferences and the
catalog files in a transfer. `ManifestTest` guards the manifest and every domain in both sections.
Rejected: allowing the transfer and saying so (it would carry the chosen frame and cached artwork to a new
device to save one frame choice, at the cost of an exception in the policy).

## 2026-11-08 — TV overscan margins by window width, lint in CI
The catalog keeps the Android TV overscan margins, 48 dp at the sides and 27 dp at the top and bottom, in the
widest `CatalogLayout` bucket (840 dp and up); narrower windows keep 24 dp top and bottom. The bucket is chosen
by width only, so a landscape tablet gets the TV margins too, which costs it 3 dp. The settings dialog already
pads 48 dp at the sides and 32 dp at the top and bottom, so it keeps its own vertical padding. A focused row
there grows by tv-material's default focus scale and its highlight reaches into the margin while it has focus;
that is the library's focus indication, not content, and stays. CI runs `lintDebug` with the build, so a new
lint error fails it; warnings are only reported.
Rejected: detecting a TV (`UiModeManager`, the leanback feature) for the margins (a second layout input next
to the width, for 3 dp on tablets), the 27 dp in the settings dialog as well (it would shrink a padding that
already clears the margin), a lint baseline (there are no errors to freeze).

## 2026-11-08 — TV banner with the app name, vector adaptive launcher icon
The TV banner keeps the original motif (a light frame line around the five e-paper colour stripes on dark
grey) and its black label now reads "InkEasel" instead of "Frame". It is a 320×180 PNG in `drawable-xhdpi`,
the size and density Android TV asks for; in the densityless `drawable` folder it was taken as mdpi and
scaled up twice on a TV. The text uses Pillow's bundled Aileron Regular (CC0), so no font licence comes
along. The launcher icon is an adaptive icon in `mipmap-anydpi` (minSdk 26, so it serves every API level)
with vector layers: a dark grey background colour, the same frame and stripes as the foreground, kept inside
the 66 dp safe zone so no launcher mask cuts the frame's corners, and a monochrome layer for themed icons in
which the stripes alternate full and half alpha. The old 192 px `mipmap-xhdpi/ic_launcher.png` is deleted:
nothing uses it once the adaptive icon exists. Lint now warns `IconMissingDensityFolder` for the single
banner density instead of `IconLocation` for a bitmap in `drawable`; it stays a warning, since Android TV
specifies the banner at xhdpi only.
Rejected: a 432 px bitmap foreground (the motif is plain rectangles, a vector is sharp at every size), banner
copies for other densities (not asked for by Android TV, more files to keep in step), a `roundIcon` (the
adaptive icon covers round masks).

## 2026-11-08 — Target API 36 with AGP 8.9.1
compileSdk and targetSdk are 36 (Play's target requirement). AGP goes from 8.7.3 to 8.9.1, the oldest
release that supports API 36; it still runs on Gradle 8.11.1 and Kotlin 2.0.21, so neither moves, and no
library changes. The release APK already meets the 16 KB page-size rule: Compose's only native library
(`libandroidx.graphics.path.so`) has 16 KB-aligned LOAD segments in all four ABIs and is stored 16 KB-aligned
(`zipalign -c -P 16`), so no Compose bump is needed. The newer lint brings warnings only (mostly `UseKtx`);
they are left for a separate cleanup. Android 16's changes were checked on an API 36 tablet emulator: the
app already draws edge to edge, Back (key and gesture) closes the disclosure panel, then the settings, then
leaves the app, and rotation or resizing does not recreate the activity (it declares no orientation or
resizability limits, which Android 16 would ignore on large screens anyway).
Rejected: AGP 8.10–8.13 or 9.x (newer than needed; 8.11+ also needs Gradle 8.13), keeping compileSdk 35
with `android.suppressUnsupportedCompileSdk` (an untested combination).

## 2026-11-08 — Release build: R8 shrinking without obfuscation
Release uses R8 (`isMinifyEnabled`, `isShrinkResources`, `proguard-android-optimize.txt`) with
`-dontobfuscate`. The code is public, so renaming protects nothing, while it would make crash traces from
GitHub APKs unreadable without a published `mapping.txt` and turn `Throwable.userText`'s class-name fallback
into meaningless names. R8 reported no warnings and no missing rules, and the release smoke tests (catalog,
image decoding, settings, disclosure, share, manual upload and poster on a real frame) needed none, so
`app/proguard-rules.pro` holds no keep rule: the libraries' consumer rules cover them. The release APK went
from 6.3 MB to 1.4 MB.
Rejected: obfuscation with `mapping.txt` uploaded by `release.yml` (more moving parts, no benefit for an open
project).

## 2026-11-08 — Privacy policy link as plain text
`PRIVACY.md` in the repository; the settings show its URL as text, in the last row of "Permissions and
features". Phones and tablets open it on a tap (`startActivity` in a try/catch, no `resolveActivity`, which
needs `<queries>` on Android 11+); TV only shows it, since a TV app must not launch a browser (TV-WB). The
row stays focusable on TV, with a click that does nothing: it is the last thing in the settings, and the
D-pad scrolls only to focusable rows, so a plain text there could not be brought into view.
Rejected: a QR code (needs a new dependency such as zxing), a WebView.

## 2026-11-08 — Prominent disclosure before notification access
Every way into the notification-access grant (the poster switch and the "Grant access" button) first shows an
in-app panel that says what the system grant covers (all notifications) and what the app actually reads (the
playing app's media-session title and artwork; notifications are ignored), and that only the poster leaves
the device, to the user's frame over Bluetooth. "Continue" is the affirmative action Play's User Data policy
requires; "Cancel" and Back change nothing. The text also says that artwork given as a web link is downloaded
from there, since that is a network request the user should not be surprised by, and that the reading goes on
as long as the access is granted, also with the poster switched off, with recent artwork kept in the cache;
with the poster off nothing of what plays leaves the device. It says nothing about the log: a release build
logs no title or artwork address (debug builds do, for development). Play asks the
disclosure to cover access the user would not expect, and reading with the poster off is that.
"Continue" from either entry point remembers the poster wish as pending (the access serves only the poster),
so "Grant access" now switches the poster on after the grant as the switch does; a press of the switch alone
no longer stores anything. The panel is inline in the settings, under the poster row, rather than a second
dialog: the settings are already a dialog, and an inline panel keeps one Back handler and one focus path
(focus moves to "Continue" when it opens and back to the element that opened it when it closes). Where no
system screen leads to the access (adb-only TVs) the panel shows the adb command instead of "Continue", and
nothing is stored. The other ways to the grant are hints, and they too appear only in the panel: the TV menu
path (as the way to go, or as the fallback when the list screen's rows are out of the D-pad's reach) and the
adb command. The menu path is repeated under the poster switch once "Continue" has made the wish pending, so
the user finds it again after the system screen. Rejected: a separate `AlertDialog` (nested dialogs on TV,
focus return harder), showing the disclosure only once (the grant may be asked for again after a revoke, and
the disclosure belongs right before each request).

## 2026-11-08 — Uploads: poster in-process, manual uploads in a foreground service
Automatic uploads (now-playing poster, revert to the default picture) run in the app process without a
foreground service: while notification access is granted the system keeps `NowPlayingListener` bound, which
keeps the process alive for a 10–30 s BLE transfer. Android TV forbids foreground services once the user
has left the app (the poster always runs while a player is in front), and the service's notification is
hidden on Android 13+ anyway. One in-process engine keeps the single queue and its rules for both kinds.
When the listener is not connected (access granted but not yet bound, or the binding lost while a controller
timer fires) the engine still takes the request, and a payload-free "hold" start of `UploadService` keeps the
process in the foreground until that work is done. Manual uploads and shares stay on the short
`connectedDevice` foreground service, which may outlive the app for a few seconds. The listener's binding is
read from a flag it sets first on connect and clears first on disconnect; the controller's uploader
(`AutoUploader`, pure, so the hold fallback and a refused hold start are unit-tested) submits every request
before it starts the hold, so a refused start (a background start on Android 12+) is only logged and the
engine still uploads and reports the request once. When the listener unbinds (`onListenerDisconnected`, or
`onDestroy` without it) after clearing its flag, `AutoUploader.listenerLost` makes every unheld request still
queued or in flight held work and starts the hold for it; `AutoUploader` decides and submits under one lock,
so a request is either promoted or decided after the flag was cleared. The engine is the only reporter:
`UploadService.onDestroy` reports nothing, and a process death takes the controller with it. A request
replaced in the queue is reported at once, from inside the `submit` that replaced it, so the controller's
`onUploadResult` re-enters under its own lock (a revert that replaces a queued poster may arm a second revert
timer, which then finds the default on the frame and does nothing). The report rules are a pure
`UploadReports` that the controller tests use as they are; why a request was dropped (`REPLACED` or `STALE`)
only chooses the log line, but stays explicit for the tests. A failed draw wait (`DrawGate`) lets the upload go
ahead without it rather than leave the queue stuck. `UploadService`'s stop logic (stay while held work is
left, `stopSelfResult(lastStartId)`) is Android glue checked on an emulator, not by unit tests.
Rejected:
- expedited WorkManager — on Android 11 and older (the Shield) it runs as a foreground service with a
  notification anyway, has quotas and would split the queue rules;
- no foreground service on TV only — two upload paths for no gain;
- `CompanionDeviceManager` — unclear support on Android TV (`companion_device_setup`), changes frame pairing;
  kept as a backlog idea;
- `shortService` for manual uploads — Android 14+ only; may be revisited if the Play foreground-service
  declaration becomes a problem.

## 2026-11-08 — `UploadService` is not exported in release builds
Any installed app could otherwise make InkEasel upload a file or URL of its choosing to the user's frame. The
service is exported only by a debug manifest overlay, for the documented adb trigger, and it accepts only
manual uploads (no automatic-upload extras), so even the debug export cannot touch now-playing work.
A JVM test guards the manifests. The overlay repeats `foregroundServiceType`, because lint's
`ForegroundServiceType` check reads the overlay's `<service>` element on its own.
Started without the Bluetooth permissions, which only the debug adb trigger can do, the service toasts and
stops.
Accepted residual: `ShareActivity` stays exported (it is the share target) and uploads a shared picture
without asking again, so an app that may start activities (one in the foreground, or exempt from Android's
background-launch limits) can still send an image of its choosing to the frame, through the visible share
path and with a `content://` grant of its own. A share is a user action by design, the sender has to be in
front to start it, and the picture also lands in "My pictures", where the user sees it. Rejected: a
confirmation step before every share (one more press for every real share), checking the referrer (it is
set by the sender and proves nothing).
Rejected: keeping it exported behind a custom signature permission (adb's shell cannot hold it), or a
runtime check of the caller (a `Service` start carries no caller identity).

## 2026-11-08 — `bluetooth_le` stays required
BLE is the app's only transport, so the feature stays `required="true"`; lint's `UnsupportedTvHardware` is
suppressed in the manifest because Bluetooth is not on Google's list of features TVs may lack.
`ACCESS_COARSE_LOCATION` is declared up to Android 11 next to the fine one.
