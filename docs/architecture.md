# Architecture

How the app is put together across its subsystems: the code map, the rules every part follows, the screens,
permissions, sharing, uploads, the network client and what is stored where. Subsystem details:
[catalog.md](catalog.md) (the painting catalog, its database, sync, live sources, ranking and nudity filter),
[now-playing.md](now-playing.md) (the poster pipeline), [frames.md](frames.md) (supported panels, choosing and
uploading to the frame), [building.md](building.md) (build, tests, emulators, CI, releases),
[protocol.md](protocol.md) (the BLE protocol and image formats), [decisions.md](decisions.md) (design decisions,
with their reasons and the options turned down). The privacy policy is [PRIVACY.md](../PRIVACY.md).

## Code map

Sources live in `app/src/main/java/io/github/hsw/inkeasel/`. "Pure" means free of Android types, so it runs as
a JVM unit test (see [Pure logic and texts](#pure-logic-and-texts)).

App and screens:
- `MainActivity.kt`: Compose for TV catalog (`androidx.tv.material3`), the frame button in the header, opens the
  frame settings; `MainViewModel` holds the screen state that must survive a rotation (open tab, settings,
  details panel, viewer, Bluetooth panel), the nudity filter in force (`applyNudity`) and the gate every open
  and send of a Catalog picture goes through (`whenAllowed`); sends a waiting shared picture once the
  permission is granted and a frame chosen
- `CatalogLayout.kt`: columns, side and top/bottom padding, card gap and header shape for the window width (pure)
- `CatalogBack.kt`: what Back does on the catalog screen before it leaves the app (pure)
- `CatalogView.kt`: what the Catalog tab shows (the grid, "none yet" or loading) (pure)
- `CatalogError.kt`: a failed download in words (no connection, timeout, HTTP code, unreadable answer), for the
  viewer
- `LocalLoad.kt`: what "My pictures" shows (`LocalState`), one load of it (`readLocal`) and when it reloads (pure)
- `DetailsPanel.kt`: the details panel of a long-pressed card, the picture large beside its lines;
  `PictureDetails.kt`: its lines and source names (pure)
- `FullscreenViewer.kt`: the full-screen view opened by a press on a card or from the details panel, with its
  card (details and "Send to frame") on Down, and
  `FittedPicture`, a picture whole in an area (thumbnail, then a rendition for the area), which the details
  panel shares
- `ViewerRules.kt`: which rendition the viewer loads for the window, its list position, when its card shows
  (`ViewerOverlay`) and swipe rules, and its moves through the list (`ViewerNav`: a step pressed during a read is dropped, a late
  result is ignored; `ListSteps` for "My pictures") (pure)
- `HeldCenter.kt`: the rest of a CENTER press already handled (the long press that opened the details panel,
  the viewer's own CENTER) kept from the dialog's buttons (pure)
- `Touch.kt`: `Modifier.onTap` (tap, and tap plus long press), the touch twin of tv-material's D-pad clicks;
  `CENTER_KEYS`; `Modifier.dropHeldCenter` (applies `HeldCenter`)
- `FrameSettings.kt`: the settings dialog: "Frame" (live frame list, select), "Catalog" (the Nudity radio group) and
  "Permissions and features" (Bluetooth, the poster switch with its disclosure panel, notification access, the
  privacy policy row). A thin stateful wrapper (`FrameSettings`: the scanner, the Bluetooth state receiver, the
  scan error, whether location is switched off where the scan needs it (before Android 12; read on resume and
  on `MODE_CHANGED_ACTION`), and `scanRun`, bumped at every scan start) feeds the stateless
  `FrameSettingsContent`, which draws everything and keeps only the focus, the disclosure and the "No frames
  seen yet" timer (keyed on `scanRun`, so the tests' clock drives it; texts from `ScanStatus`); the
  notification-access screens and the device kind are read by its `FeaturesSection` itself. The Compose UI
  tests drive the content with fake frames and states (`FrameSettingsUiTest`) and the wrapper on Robolectric's
  Bluetooth shadows (`FrameSettingsDialogTest`) ([building.md](building.md#compose-ui-tests))
- `ScanStatus.kt`: what the frame list says while a running scan has heard no frame: "Searching for frames…",
  then after `NONE_SEEN_MS` "No frames seen yet", each with its hint, the location variant while location is
  off (pure)
- `Txt.kt`: `Txt` (a user-facing text as a string resource id and arguments), `TxtException` and
  `Throwable.userText`, free of Android types; `TxtAndroid.kt` resolves them with a `Context` or in Compose

Permissions and access:
- `BlePermissions.kt`: the Bluetooth permission set, whether it is granted or blocked (only once a denial was
  seen: a dismissed dialog does not count), whether the first-run panel was answered, and App info
- `BleAsk.kt`: when and how the app asks for Bluetooth: the first-run panel, a pressed or shared picture, its
  text per state and Android version, and what happens after the system dialog (pure); `BlePanel.kt`: the panel
- `ListenerAccess.kt`: notification access for the poster (granted?, the system screens, the adb command);
  `ListenerAccessUi.kt` decides what the section shows and which screen opens (pure); `PosterSwitch.kt`: what
  the poster switch shows and does, and the wish kept until the access is granted (pure)

Sharing and uploads:
- `ShareActivity.kt`: "Share → InkEasel" (copy into "My pictures", then `UploadService`, or wait for the
  permission as `PendingShare`); `ShareRules.kt`: which shares are taken, the file name, saving the copy
  (`save`), the size cap and the pending-share rule (pure)
- `UploadEngine.kt`: the process-wide upload engine (core in `nowplaying/UploadEngineCore.kt`, pure), one upload
  at a time; see [Uploads](#uploads)
- `UploadService.kt`: transient foreground service for manual uploads (and the hold of automatic ones while the
  listener is not bound)
- `DrawGate.kt`: spacing between uploads (at least 35 s after a NowMean upload, kept in prefs, so also across
  app restarts; an OpenDisplay upload ends when the frame reports its refresh done and does not arm it; see
  [now-playing.md](now-playing.md))

Frames ([frames.md](frames.md)):
- `FrameClient.kt`: BLE connect/upload to the chosen NowMean frame, rescan by its name
  ([protocol.md](protocol.md)); an OpenDisplay frame is handed to `OdClient`
- `FrameScanner.kt`: BLE scan for service `0xFFBB` (NowMean) and manufacturer data `0x2446` (OpenDisplay),
  shared by the settings list, `FrameClient` and `OdClient`
- `FrameAdvert.kt`: parser of a NowMean frame's manufacturer data
- `OdProtocol.kt`: the OpenDisplay requests and answers, config and display record (`OdDisplay`), advertisement
  (`OdAdvert`); pure
- `OdExchange.kt`: the OpenDisplay request and answer sequences over any link (pure)
- `OdClient.kt`: BLE client of an OpenDisplay frame (the GATT link, `OdSession`): finds it by name, reads its
  display when it is chosen, uploads
- `FrameCaps.kt`: protocol-neutral `FrameCaps` and `FrameKind`; `Hex.kt`: the hex form of frame records in prefs
  and the log
- `FrameFormat.kt`: render formats and `FrameFormats`, the one place that maps an advertisement to a format or
  to the reason it is not supported (pure)
- `Oklab.kt`: the dithering's colour math and `InkMatcher`, the nearest ink (OKLab, or RGB distance on a panel with
  one or two colour inks; pure); `Levels.kt`: auto levels for a panel of greys (pure)
- `EpdImage.kt`: decode, fit, dither and pack a picture for the frame
- `FrameStore.kt`: the chosen frame (`SelectedFrame`) in prefs `frame`, and `SeenFrame` (one scan result)

Catalog and network ([catalog.md](catalog.md)):
- `Catalog.kt`: `Picture`, "My pictures" (`LocalImages`), the live sources (`ClevelandPaintings`,
  `CommonsLandscapes`) and `Http`, the one OkHttp client
- `RetryPolicy.kt`: when a 429 or 503 answer is asked again, how long its host is held and when a card asks for
  its thumbnail again (pure), and the interceptor that retries and holds (only requests tagged `Holdable`: the
  cards' thumbnails)
- `Images.kt`: Coil's image loader, once per process, with its 150 MB disk cache
- `SourceMapping.kt`: the live sources' JSON mapped to catalog rows (`LiveRow`) (pure)
- `CatalogFilterStore.kt`, `NudityLockReader.kt`, `NuditySetting.kt`: the nudity filter as chosen, the device's
  lock on it, and the radio rows' rules (pure)
- `catalog/`: the painting catalog database (`CatalogDb`, `CatalogDao`), its owner `CatalogStore`, the release
  sync and import (`CatalogSync`, `CatalogSyncPlan`, `CatalogImport`, `CatalogRows`, `CatalogWriter`), the
  stored order (`CatalogRank`), the live sources' refresh (`LiveSources.kt`: `LiveRefresh`, `LiveSourceRows`),
  nudity (`Nudity`), label languages (`LanguageChain`), a row as a `Picture` (`CatalogPicture`, `CommonsUrls`)
  and the viewer's steps (`CatalogSteps`)

Now playing ([now-playing.md](now-playing.md)), `nowplaying/`:
- `NowPlayingListener.kt` (the `NotificationListenerService`), `Playing.kt`, `SessionView.kt`, `ArtCache.kt`
- `FrameController.kt` (the state machine), `FrameControllers.kt` (the process-wide instance),
  `FeatureStore.kt` (the poster switch, prefs `features`), `DefaultStore.kt` (the default picture, prefs
  `default`, and the key on the frame, prefs `frame`)
- the upload engine's pure parts: `UploadEngineCore.kt`, `UploadQueue.kt`, `UploadRequest.kt`,
  `UploadReports.kt`, `UploadOutcome.kt`, and `AutoUploader.kt` (the controller's uploader); see
  [Uploads](#uploads)
- `MediaLog.kt`: what plays as the log names it; see [Privacy in the code](#privacy-in-the-code)

## Pure logic and texts

- Pure logic stays free of Android types so it runs as JVM unit tests (see `nowplaying/`, `FrameFormat.kt`).
  `org.json` counts as pure, since the JVM tests use the real library (`testImplementation org.json:json`; it
  differs from Android's in corner cases such as `optString` on JSON null, so keep to plain values); any other
  `android.*` class does not.
- Code that runs as JVM unit tests has no `Context`, so it returns a `Txt` (an `R.string` id and its format
  arguments: `String`, `Int` or a nested `Txt`, resolved first) instead of a string; the UI puts it into words
  where it is shown, and tests compare `Txt` values, not translations. `Txt` is put into words only at the edge
  (`TxtAndroid.kt`: `resolve(ctx)`, `@Composable text()`).
- Upload- and share-path failures meant for the user throw `TxtException` (an `IOException` carrying a `Txt`;
  details such as a URI or a file name go into its cause, for the log). Any other exception (e.g. from the
  network library) keeps its raw message, or its class name when it has none, inside a generic text
  (`Throwable.userText(generic)` in `Txt.kt`, a string resource with one `%1$s`).
- Stored data (`FrameStore`, `DefaultStore`) never holds translated text.

## Language and strings

The UI is in English, and in Russian when the device language (or the app's language in the system settings,
Android 13+: Settings → Apps → InkEasel → Language, listed from the locale config the build generates from
`res/`) is Russian. The catalog's titles, artists and museums follow that language whether or not the UI has a
translation for it (on a German device the UI is English and the titles German): the language, its regional or
script variant where Wikidata has one, then English, `mul`, then any stored label (`LanguageChain`; see
[catalog.md](catalog.md)). To check a language on a device:

    adb shell cmd locale set-app-locales io.github.hsw.inkeasel --locales ru-RU   # or en-US

String rules:
- All texts are string resources: English in `res/values/strings.xml`, Russian in `res/values-ru/strings.xml`.
  Every new string goes into both (`lintDebug` flags a gap as `MissingTranslation`; `StringResourcesTest` checks
  that both tables have the same keys and placeholders).
- No hard-coded user-facing text in Kotlin, nor capitalisation, punctuation or separators added to a resolved
  text (`list_separator`, `*_sentence` forms). Name lists (a painting's artists) come from the database as lists
  and are joined at the edge with the translatable `list_names_separator`, never in SQL or in a mapper.
- Keys are `<area>_<what>` (`catalog_`, `details_`, `viewer_`, `settings_`, `frame_`, `perm_`, `access_`,
  `feature_`, `advert_`, `reason_`, `bt_`, `scan_`, `upload_`, `share_`), in that area's block of the file. A
  lowercase fragment nested in another text (an error's reason) is `<area>_err_<what>`; `<area>_toast_<what>`
  is only a toast.

## Screens

### Layout and orientation

Made for a TV and the D-pad; phones and tablets work by touch in any orientation. tv-material components ignore
touch, so every clickable component also gets `Modifier.onTap(...)` (`Touch.kt`; the two-argument form also
takes a long press, the touch twin of a tv-material `Card`'s `onLongClick`).

Orientation is free and rotating or resizing does not restart the screen: `MainActivity` (and `ShareActivity`)
declare `configChanges` (`keyboard|keyboardHidden|navigation|orientation|screenSize|screenLayout|smallestScreenSize`),
so the open tab, the settings and their frame list stay, and no permission is asked twice. State that must
survive lives in Compose (and `MainViewModel`), not in a recreate. The layout follows the window width
(`CatalogLayout.forWidth`; it also gives the settings dialog's side padding):

| Width         | Columns | Side padding | Top and bottom | Gap   | Header                                     |
|---------------|---------|--------------|----------------|-------|--------------------------------------------|
| under 600 dp  | 2       | 16 dp        | 24 dp          | 12 dp | name and frame button, tabs on a row below |
| 600–719 dp    | 3       | 32 dp        | 24 dp          | 16 dp | as above                                   |
| 720–839 dp    | 3       | 32 dp        | 24 dp          | 16 dp | one row                                    |
| 840 dp and up | 4       | 48 dp        | 27 dp          | 20 dp | one row (a TV is 960 dp wide)              |

The widest bucket keeps the Android TV overscan margins (48 × 27 dp); a landscape tablet falls into it too. A TV
(960 dp) must keep 4 columns, the 48 × 27 dp overscan margins and one header row. One header row needs about
700 dp with the frame button.

The app draws behind the status and navigation bars (`enableEdgeToEdge`; Android 15 requires it, and Android 16
has no opt-out) and keeps its content clear of them, with gesture or button navigation; the settings dialog does
the same. A TV has no bars, so nothing changes there.

### Tabs and Back

Tabs: "Catalog" (the start tab) and "My pictures" (see [catalog.md](catalog.md)). The tabs scroll sideways when
they do not fit. Back from the content (a card, a **Retry** button) first moves the focus to the selected tab and
scrolls the grid back to its first row; the next Back leaves the app. A grid scrolled down by touch goes back to
its first row the same way. The settings and the disclosure panel close with Back as before (`CatalogBack`). An
open or send still waiting at the gate is dropped by Back before anything else takes it (`gatePending`).

### My pictures

"My pictures" is the app's own folder on the device (`getExternalFilesDir()/pictures`; see
[Privacy in the code](#privacy-in-the-code)) and reads it whole (`LocalLoad.kt`: `LocalState`, `readLocal`). It
speaks of your pictures, not of a catalog, while it reads the folder ("Looking for your pictures…") and if that
fails (with **Retry**). Empty, it leads with sharing a picture to InkEasel from a gallery or a file manager on the
device (see [Share → InkEasel](#share--inkeasel)); the folder path and an `adb push` line follow as secondary text
for advanced users. A picture there carries only its name and pixel size.

### Text sizes

Text to read from the sofa is at least 14 sp (`bodyMedium`): the settings' descriptions, the poster's access
state, the disclosure panel with its adb line, why a frame cannot be chosen, the privacy address. Only secondary
metadata is 12 sp: a card's artist and date, a frame's address and properties. Checked on the TV emulator at font
scale 1.0 and 1.3: one header row, 4 columns and the overscan margins stay.

### Press and long press

A press on a card (CENTER, or a tap) opens the picture full screen (below) and sends nothing
(`MainViewModel.openViewer`); sending is **Send to frame** in the viewer's card or in the details panel, which
take the send path (`BleAsk.onSend`): sent; without the Bluetooth permission the Bluetooth panel (it replaces
the viewer); with no frame chosen a toast "No frame selected"; once the system no longer shows the permission
dialog, a toast that Bluetooth is denied.

A long press (CENTER held, or a long touch) opens the card's details panel instead. A tv-material `Card` fires
`onLongClick` on the first key repeat, while CENTER is still down; `HeldCenter` swallows that press's further
repeats and its release, so they do not reach the panel's focused button: releasing the held CENTER leaves the
panel open and sends nothing. The viewer's own CENTER is kept from the button its card brings the same way, and
so is a press still held when the viewer opens.
A dialog starts armed; a fresh press goes through as usual, and with no key held (a touch) nothing is swallowed.

### Details panel

Google's "content details" layout: the title, every field the source gave (artist, date, collection, source,
licence and its link, the image credit where the licence asks for one, original size, web page; links as text,
since a TV opens no browser), **Send to frame** (focused; it closes the panel and takes the send path, without
the full-screen view; with no frame chosen it toasts "No frame selected"), **View full screen** and, on the right,
the picture whole and as large as that side allows (a portrait takes its full height; the card's thumbnail first,
then an image sized to that area, the same one the full-screen view loads, via `FittedPicture`). Back closes it. A
narrow or portrait window stacks it into one scrolling column, the buttons under the title and the picture under
them. The panel never opens together with the settings, the Bluetooth panel or the viewer.

### Full-screen viewer

Opened by a press on a card or by **View full screen**. It shows the picture on black, fitted to the screen and
never cropped, with the system bars hidden; no frame is needed. Left and Right (or a swipe) go to the previous and
next picture of the same tab and stop at its ends (in the Catalog the next one is read from the database, so it
does not matter how far the grid has loaded; "n / N" is the place in the whole list as the nudity filter leaves
it, see [catalog.md](catalog.md)). It opens clean, "as in a museum": the picture alone, no caption, counter or
hint. Down or CENTER (a tap on a touch screen) shows the picture's card over its lower part (`ViewerOverlay`):
the title and "n / N", the details panel's lines (`PictureRows`: artist, date, collection, source, licence, the
image credit where the licence asks for one, …; they scroll when long), why the full image did not load, and
**Send to frame**, focused (with no frame chosen, a note that none is, instead). Up, Back or a tap hides the
card (a swipe down does what Back does: hides the card, or closes the viewer; `ViewerRules.swipe` reads a swipe
by its longer axis, 64 dp or more; a swipe up does nothing); Left and Right go on stepping with it shown, and it shows the new picture. Back without the card returns
to the grid when a press opened the view (`viewerByPress`), to the details panel of the picture on screen when
the panel did.

The image is loaded in a size that fits the screen: for a Commons file (the release paintings and the Commons
landscapes) a standard width (960, 1280, 1920 or 3840 px), or the original when a JPEG, PNG or WebP is narrower
(TIFF and SVG always as a thumbnail), for Cleveland the `web` image up to a Full HD screen and the `print` image on
larger ones, your own pictures as they are; it is decoded to at most the screen's size, and the card's thumbnail
shows while it loads. The screen is not kept on, so the screen saver starts as usual.

### The gate

Every open and send of a Catalog picture goes through one gate, `MainViewModel.whenAllowed`: a press (the
viewer), a long press (the details panel), **View full screen**, each Left/Right of the
viewer (`ViewerNav`), **Send to frame**, and the Bluetooth panel's later send once the permission is granted. It
runs the action only once `allowed` has read the nudity level stored for the picture at that moment and checked it
against the filter in force; a hidden picture opens and sends nothing. One action waits at a time; it is dropped by
a newer press or send, another tab, the details panel dismissed, the settings, the Bluetooth panel, a new filter
and Back. A picture of "My pictures" passes at once. The filter and what a change of it closes:
[catalog.md](catalog.md).

## Permissions

- **Bluetooth** ("Nearby devices" on Android 12+, location on Android 11 and older, where the system also wants
  location turned on for a BLE scan): needed to find the frame and send pictures. One permission set everywhere
  (`BlePermissions`): Nearby devices (`BLUETOOTH_SCAN`, declared `neverForLocation`, + `BLUETOOTH_CONNECT`) on
  Android 12+, fine location before (`BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION`,
  `ACCESS_COARSE_LOCATION` with `maxSdkVersion="30"`). Every screen asks for and checks it through
  `BlePermissions`. The app asks only in context (below); if the system no longer shows its dialog, the settings
  name the path in App info.
- **Notification access** (optional, only for the now-playing poster): it lets the app read the media sessions of
  other apps (title and artwork of what plays). The app does not read notifications. There is no permission
  dialog for it; see [Notification access](#notification-access).
- **Internet**: the catalog and the posters (see [Privacy in the code](#privacy-in-the-code)).
- **Foreground service** (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`; no dialog): keeps a
  manual upload or a share running for the seconds it takes when you leave the app. Now-playing posters run
  without it, except while the notification listener is not bound, or unbinds with a poster upload under way
  (see [Uploads](#uploads) and [now-playing.md](now-playing.md)).

### Asking for Bluetooth

`BleAsk` (pure; `MainActivity` applies it, `BlePanel` shows it):
- On the first start a panel says why the app needs Bluetooth. A start without the permission shows it over the
  catalog, not the system dialog: "InkEasel sends pictures to your e-paper frame over Bluetooth. To find the
  frame, allow Nearby devices." (on Android 11 and older it names Location, adds that Android uses location for
  Bluetooth searches and that it must be on). **Find my frame** has the focus and opens the system dialog; once
  allowed, the frame settings open, where the frame appears in the list (with a frame already chosen, the button
  says **Allow** and a pressed picture is sent). **Not now**, Back or a tap outside closes it and leaves the
  catalog. It appears by itself only until it has been answered once (**Not now** or an answer in the system
  dialog, prefs `permissions`, key `bleIntroDone`); with the permission and no frame chosen, a start opens the
  frame settings instead, and the two never open together.
- After that the app asks only when the user wants the feature: pressing a picture, or sharing one, without the
  permission opens the same panel; **Settings** offers "Allow" next to the Bluetooth state and in the frame
  section. After one denial the panel says what stops working (the frame search and sending; the catalog still
  works) before the dialog again. A system dialog denied once, or closed without an answer, leaves the panel open
  with the text for the new stage.
- Once the system no longer shows its dialog, the panel is not shown: the app keeps working without the frame, a
  pressed picture toasts that Bluetooth is denied and where the settings say how to allow it, and only the
  settings (and a share, which opens them) offer App info. A dismissed dialog is not a denial: only after a real
  denial has been seen once (prefs `permissions`) does a silent "no" count as blocked. The frame settings' own
  Bluetooth texts: [frames.md](frames.md).

### Notification access

Notification access (for the now-playing poster only) is asked for only through the disclosure panel in
**Settings → Permissions and features** (`PosterSwitch.press` → `DISCLOSE`, then "Continue"); a new way into the
grant, including a hint text such as the TV menu path or the adb command, goes through it too
(`ListenerAccessUi.rowMenuHint`). The screens differ per device (`ListenerAccess`: detail screen, list screen, or
the adb hint on older Android TV); the steps per Android version, the low-RAM case and the switch's states are in
[now-playing.md](now-playing.md#requirements-and-granting-access).

## Settings → Permissions and features

Below the Catalog section, **Settings → Permissions and features** collects what the app needs. It is where the
app shows what it needs: a new permission or feature switch gets a row there (a Catalog content setting goes
into the settings' "Catalog" group instead, see [catalog.md](catalog.md); the "Frame" section:
[frames.md](frames.md)).

Every row there is a tv-material `ListItem` (one row style, one left edge) with no button inside it: the row's
press does what a button would, and a trailing label names it where the press acts. One focus stop per row is
what the D-pad expects of a TV list. A button stands on its own line only outside a row ("Grant access", the
disclosure's buttons).

- **Bluetooth**: whether the permission is granted as the row's text. While it is not, a trailing label says
  "Allow" (or "App info", see [frames.md](frames.md)) and a press asks (`BleAccess.request`); once granted the
  row only shows it, and stays focusable like any row. One permission set everywhere (`BlePermissions`): Nearby
  devices (`BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT`) on Android 12+, fine location before.
- **Now-playing poster**: an on/off switch and the state of the notification access it needs (on only while the
  poster is wanted, prefs `features`, key `poster`, **and** the access is granted; `PosterSwitch`). Without the
  access a press opens the disclosure panel first; "Continue" remembers the wish (key `posterPending`) and the
  poster is switched on when the app comes back with the access granted (`PosterSwitch.enableOnResume`). Whoever
  changes `FeatureStore.posterEnabled` calls `FrameController.onPosterSwitched()`. The panel's text, the per-device
  paths (`ListenerAccessUi`) and the low-RAM case: [now-playing.md](now-playing.md#the-poster-switch-in-the-settings).
- **App info** (phones and tablets, Android 13+, an app not installed by a store, the access missing;
  `ListenerAccessUi.restrictedHint`): how to allow the restricted setting, and a press opens App info. It opens
  only that screen and is not a way into the grant, which stays behind the disclosure.
- The states are read again whenever the app comes back to the front, e.g. from a system screen.
- **Privacy policy**: the address of [PRIVACY.md](../PRIVACY.md) as text (`settings_privacy_url`). A tap opens it
  in the browser on a phone or tablet; on TV the row only shows it (a TV app must not launch a browser), and stays
  focusable so the D-pad scrolls it into view.

Switching the poster off stops new posters at once and puts the default picture back if a poster is on the frame;
see [now-playing.md](now-playing.md).

## Share → InkEasel

On a phone or a tablet, "Share" on a picture in a gallery or a file manager lists InkEasel (`ShareActivity`, rules
in `ShareRules`). The picture is copied into "My pictures" (the sender's file name, made safe, with the extension
of its type; `-2`, `-3`, … when the name is taken; `shared-<date>-<time>` when the sender gives none; at most
50 MB (`ShareRules.MAX_BYTES`), written to a `.part` file, created first so two shares at once never pick the
same name, and renamed when complete; an existing file is never overwritten) and sent to the frame as a manual
upload through `UploadService`, so it becomes the default picture. The gallery stays on screen; a toast says the
picture is saved and on its way to the frame. On Android 13 and newer the app does not ask for the notification
permission, so the result toast of an upload started from a share (the gallery is in front) may not appear; open
InkEasel or look at the frame to see how it went. Toasts after the share has finished may be dropped, so the
share reports its own outcome while in the foreground. With no frame chosen the picture is only saved, and the
share itself says so ("No frame selected: choose one in the settings, then send it from there").

Only a `content://` picture of another app is taken (no `file://` paths, nothing of the app's own, also with a
`<user>@` prefix on the authority). Types: JPEG, PNG, WebP, GIF, BMP and HEIC/HEIF (decoded by the system,
Android 9+).

Without the Bluetooth permission the picture is still copied and waits (`PendingShare`): the app opens with the
Bluetooth panel (or, once the system no longer asks, with its settings, which name the path in App info;
`BleAsk.onShare`); once it is granted and a frame is chosen, `MainActivity` sends the picture (after the
permission request, on resume and when a frame is selected), only while it is started: Android 12+ refuses to
start the upload service from the background, and an OpenDisplay frame's read can end with the app away, so a
frame chosen then sends the share at the next start (`MainActivity` watches the chosen frame through its own
lifecycle; the view model holds no callback into the activity). The share is cleared only once the service has
started (`PendingShare.send`); a refused start keeps it for the next resume. One share waits at a time (the
newest), for at most an hour (`ShareRules.PENDING_MAX_AGE_MS`, prefs `share`; a clock set back counts as stale);
after that, or if the file is gone, it is dropped, and the picture stays in "My pictures" either way.

`ShareActivity` stays exported as the share target, so a share uploads the shared picture without asking again
(see [decisions.md](decisions.md)); `UploadService` itself is not exported (see [Uploads](#uploads)).

## Uploads

One process-wide `UploadEngine` (pure core `UploadEngineCore`, queue rules in `UploadQueue`) holds the queue and
the worker: one upload at a time, wired to the frame (`FrameClient`), each picture rendered in the selected frame's
format (`FrameStore`, `FrameFormats`). A newer request replaces a queued one of the same kind, and a manual one
goes first; the next upload waits for a NowMean frame to finish drawing (`DrawGate`; an OpenDisplay upload reports
its refresh done itself and does not arm it). Every request is reported exactly once, a replaced one from inside
the `submit` that replaced it. What the engine does with each report (toasts, `FrameController` callbacks) is pure
(`UploadReports`; `UploadOutcome`: toast, report to the controller, remember as the default), and the controller
tests use the production rules: never copy them into a test rig. A request (`UploadRequest`) is manual, or
automatic with the controller's key and stamp.

- **Manual uploads and shares** run in `UploadService` (`foregroundServiceType="connectedDevice"`), a short
  foreground service that holds the process in the foreground, so they finish after the user leaves the app.
  Manual uploads toast and become the default picture. The service stops with `stopSelfResult` once the engine
  reports its held work done, so a start arriving as it finishes is not lost; it reports nothing itself. Started
  without the Bluetooth permissions (only the debug adb trigger can) it toasts and stops.
- **Automatic uploads** (a poster, the revert to the default) go straight to the engine from `FrameController`
  through `AutoUploader`, with no foreground service while `NowPlayingListener` is bound (the binding keeps the
  process alive; unheld work). Only while it is not bound (access granted but not bound yet, the binding lost, a
  controller timer firing after a disconnect) they are held work, and `UploadService` is started with
  `ACTION_HOLD`, which keeps the process in the foreground until the engine has reported them. A refused start (a
  background start on Android 12+, `ForegroundServiceStartNotAllowedException`) is only logged: the request is
  already in the engine, which uploads it and reports it once. When the listener unbinds, unheld work still
  queued or in flight is made held (`AutoUploader.listenerLost`, `holdAll`) and the hold starts for it; both run
  under one lock. Without the Bluetooth permissions an automatic upload is reported as failed at once. Automatic
  uploads are dropped if a manual upload succeeded after they were decided (`stamp` =
  `FrameController.manualUploads`), and per the poster rules in [now-playing.md](now-playing.md).
- **Locks**: lock order is controller → engine. The controller submits under its lock; the engine resolves the
  controller lazily (on each callback, never while the engine is built, so building it from inside
  `FrameControllers.get` cannot recurse) and never calls it (or `onIdle`, which runs on the main thread) under its
  own lock. A replaced automatic request is reported from inside `submit`, so `FrameController.onUploadResult`
  re-enters under the controller's lock: nothing may follow `uploader.upload` there.
- **Manifest**: `UploadService` takes only manual extras and is not exported, so no other app can start an upload
  through it; only the debug manifest overlay (`app/src/debug/AndroidManifest.xml`) exports it for the adb
  trigger, and `ManifestTest` guards both manifests. The adb trigger: [now-playing.md](now-playing.md).
- The pure classes' `log(message, throwable)` callbacks (`UploadReports`, `UploadEngineCore`, `AutoUploader`)
  share one Android-side rule: a message with a throwable is a fault and logged as a warning, one without is
  information.

## Network

- Every request goes through one OkHttp client (`Http.client` in `Catalog.kt`, with the app's User-Agent, which
  Wikimedia asks of every client): at most 4 at a time to one host (`maxRequestsPerHost = 4`) and
  `RetryInterceptor`. A `newBuilder()` of it is fine (a call timeout); a new `OkHttpClient` is not.
- Retries (`RetryPolicy.kt`): an HTTP 429 or 503 answer to a GET is asked again at most twice, after its
  `Retry-After` (or 1 s, then 2 s), unless it asks for more than 10 s; a cancelled request stops waiting at once,
  and the main thread never waits.
- Host hold: a host that answered 429 or 503 gets no card thumbnail request until the pause it asked for is over
  (at most a minute): a thumbnail asked for in the meantime is answered 429 by the app itself, so a fast scroll
  past hundreds of cards does not send hundreds of thumbnail requests to `upload.wikimedia.org` when it asks for a
  pause (it answers `Retry-After: 11`), and a card whose thumbnail was refused so asks again once the pause is
  over, up to three times while it is on screen. A thumbnail that waits out a pause or a retry reads the hold
  again before it is sent, so a longer pause asked for meanwhile counts too. Only requests tagged `Holdable` are
  held: a card's thumbnail, tagged on Coil's `ImageRequest`, which passes its tags to OkHttp. A request a user
  asked for (the picture sent to the frame, the viewer's image) and the lists and release files are never tagged,
  so they are never refused locally and get the server's own answer.
- Images: Coil's loader is installed only by `Images.install` (once per process, over `Http.client`). Coil allows
  one `DiskCache` per folder; the one cache keeps up to 150 MB of images in `cacheDir/image_cache` (Coil's own
  default folder, so an upgrade keeps what is cached; the least recently used entries go first).
- Cleartext traffic is refused on every Android version (`usesCleartextTraffic="false"`).
- Catalog requests, their timeouts and the release files: [catalog.md](catalog.md).

## Privacy in the code

InkEasel has no account, no analytics, no ads and no server of its own. The policy, with each permission and why,
is [PRIVACY.md](../PRIVACY.md); the settings show its address (a tap opens it on a phone or tablet). `PRIVACY.md`
must say exactly what the code does (what is read, when, and what is stored); a change to either updates the
other.

- **What plays** is read from the media sessions **on the device** whenever notification access is granted (also
  with the poster switched off, which only stops the uploads) and used only to pick and time the poster; it is not
  sent anywhere. It reaches the log only through `MediaLog` (`title`, `key`, `error`): a new log line with a
  now-playing title, a poster or `onFrame` key, or an artwork address uses it, so a release build writes no title
  and no artwork address to the system log (logcat; `<title>` / `<key>` instead); a debug build does, for
  development.
- **Network traffic** is limited to the picture sources: the catalog's lists (when the app starts or comes back to
  the foreground, or its language changes, each at most once a week) and images from the
  [Cleveland Museum of Art Open Access API](https://openaccess-api.clevelandart.org/) and
  [Wikimedia Commons](https://commons.wikimedia.org/), the painting catalog's files from the project's GitHub
  release (`github.com`, redirected to `release-assets.githubusercontent.com`; at the same moments, each file at
  most once a week, and the names of the labels files asked for show the app's language and, for some languages,
  its region or script, such as `en-us` or `pt-br`), a picture URL given to the upload service from adb (debug
  builds only), and the poster artwork, downloaded over https from the address the playing app publishes.
- **Stored on the device**: pictures are sent to the frame over BLE only. The chosen frame, the catalog database
  (the painting catalog, the live sources' lists and when each release file and list was last checked), the cached
  images (at most 150 MB), your pictures, the artwork of recent now-playing sessions (in the app's cache, pruned to
  the 20 most recent each time a new one is stored, sparing any a tracked session or a pending poster still needs)
  and the key of the picture on the frame (for a poster, its artwork address or a hash of its artwork) stay in the
  app's storage on the device. "My pictures" is the app's folder on the shared storage (`getExternalFilesDir`),
  which other apps with the storage permission can read on Android 9 and older (and on Android 10 if they opt out
  of scoped storage); the rest is private. Prefs files: `frame`, `default`, `features`, `catalog`, `permissions`,
  `share`.
- **Backup**: backup and device-to-device transfer are off (`allowBackup="false"`, which covers Android 11 and older
  and on 12+ only the cloud backup; data extraction rules that exclude every domain on Android 12+,
  `res/xml/data_extraction_rules.xml`, for both `cloud-backup` and `device-transfer`: an exclusion of `root` does
  not reach the `file`, `database` and `sharedpref` trees, which the backup walks on their own, nor `device_root`
  the device-protected ones).
- **The lock read**: at each start of the app's screen (`MainActivity.onStart`) and when the settings open, the
  app asks Android whether the TV's parental controls are on and whether the user may change accounts
  (`NudityLockReader`), only to lock the Catalog's nudity filter; the answer is not stored or sent (the system log
  gets the filter in force and the lock's kind when it changes). The filter as chosen is kept in prefs `catalog`.
  Details: [catalog.md](catalog.md).
- **BLE scan**: a scan runs while the settings search for frames (also while an OpenDisplay frame being chosen
  is awaited), and during an upload when the chosen frame has to be found again by its name (a NowMean frame
  that does not answer at its saved address; an OpenDisplay frame on every upload), filtered to the NowMean
  service or the OpenDisplay manufacturer data (`FrameScanner`; see [frames.md](frames.md)).
- **Location switch** (Android 11 and older only): while the settings are open, they ask Android whether location
  is switched on (`LocationManagerCompat.isLocationEnabled`, when they open, at every resume and on
  `MODE_CHANGED_ACTION`), only to say in the search hint that a Bluetooth search needs it on. The app never reads
  where the device is; the answer is not stored, logged or sent.
- **OpenDisplay config**: when such a frame is chosen and at each upload, `OdExchange.display` reads the frame's
  whole config. Only the display record (`Od.display` → `OdDisplay`) is kept and logged (an answer reaches the
  log as its status, opcode and length, `Od.Reply.toString`, never its data); the rest, including the Wi-Fi
  network and password and the encryption key, is dropped on arrival: never stored (prefs `frame` holds only
  `selected.display`), logged or sent.
