# Decisions

Design decisions with their reasons and the options that were turned down, newest first. An entry marked
*planned* is decided but not implemented yet; the change that implements it drops the mark.

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
