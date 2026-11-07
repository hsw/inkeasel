# InkEasel

Android TV app that uploads pictures to an e-paper frame over BLE: free paintings and photos from a
built-in catalog, your own pictures, and the poster of whatever is playing. When a film plays, its poster
goes to the frame; when playback ends, the frame returns to the last manually uploaded picture.
Supported frames: NowMean (BLE service `0xFFBB`). Developed and tested on an NVIDIA Shield TV Pro 2019
(Android 11).

License: [Apache-2.0](LICENSE).

Build and install (JDK 21 from Homebrew, Android SDK in `local.properties`):

    JAVA_HOME=<JDK 21> ./gradlew assembleDebug
    adb connect <tv-ip>:5555
    adb install -r app/build/outputs/apk/debug/app-debug.apk
    adb shell pm grant io.github.hsw.inkeasel android.permission.ACCESS_FINE_LOCATION   # BLE scan on Android 11

Unit tests (JVM, JUnit 4 + `kotlinx-coroutines-test`; same `JAVA_HOME` as above):

    ./gradlew testDebugUnitTest

## Language

The UI is in English, and in Russian when the device language (or the app's language in the system
settings, Android 13+) is Russian. All texts are string resources: English in `res/values/strings.xml`,
Russian in `res/values-ru/strings.xml`. Code that runs as JVM unit tests has no `Context`, so it returns a
`Txt` (a string resource id and its format arguments, which may be `Txt` themselves) that the UI puts into
words where it is shown; an upload failure meant for the user is a `TxtException` carrying one. Other
exceptions (e.g. from the network library) are shown with their raw message. To check a language on a
device:

    adb shell cmd locale set-app-locales io.github.hsw.inkeasel --locales ru-RU   # or en-US

## Choosing the frame

Uploads go only to the frame chosen in **Settings → Frame**. Open it with the header button ("Frame: <name>"
or "No frame selected"); a fresh start with no frame chosen opens it by itself.

- While it is open, the app scans for BLE service `0xFFBB` and lists every frame heard, one entry per name
  (the name is the frame's identity; its MAC may change), updated live: name, MAC, colour and panel type,
  resolution and rotation, battery, firmware, signal (RSSI) and safe mode if set. The chosen frame is marked.
  The scan stops when the screen closes or the app goes to the background.
- Selecting a frame saves it and closes the screen. **Cancel** or Back closes it without changes.
- Frames the app cannot draw on are listed but greyed out with the reason ("not supported: …") and
  cannot be chosen. A supported frame whose format was never seen working on a real panel says
  "not verified on hardware" under its properties (and in a toast when chosen); it can be chosen.
- Supported (rotation 0° and a panel type other than A; formats from the vendor app, every
  type dithered with its own inks, sent raw with content marker MK 0):

  | Colour type | Resolutions | Payload | Verified on hardware |
  |---|---|---|---|
  | E6 (Spectra 6) | every size in the advertised table except 1360×480, 1440×600, 1200×1600 | 4 bpp, W·H/2 | yes (1024×600 seen; the rest is the same layout at another size) |
  | E6 | 1360×480, 1440×600 | 4 bpp, W·H/2 | no |
  | E6 | 1200×1600 | 4 bpp split: all left half rows, then all right half rows; 960,000 B | no |
  | BWR | every size except 1360×480, 1440×600 | black plane, then red plane, 1 bpp each, each padded to a multiple of 512 | no |
  | BWR | 1360×480, 1440×600 | split: left black, left red, right black, right red, each part padded to 512 (163,840 / 217,088 B) | no |
  | E4 | every size | 2 bpp, W·H/4 | no |

- Not supported, with the reason shown: BW and BP, E7 ("format not verified (the vendor app does not draw it
  correctly)"), BWY ("the vendor app cannot send to such frames"). Their
  palettes and packings are already described in `FrameFormats` and plug in by dropping the reason.
- The check and the render format (size, inks, packing, marker, verified flag) come from one place,
  `FrameFormats` in `FrameFormat.kt`, which works on protocol-neutral `FrameCaps` (taken from the
  advertisement here); a new panel type is one more entry there.
- Without Bluetooth permission the screen asks for it ("Allow"); with Bluetooth off it says so and
  offers the system Bluetooth settings. On Android 11 the scan also needs location on.

Stored in prefs `frame` (next to the upload spacing `endedAt` and the `onFrame` key):
`selected.kind` (the frame's protocol, `nowmean`), `selected.name`, `selected.address`, `selected.advert` (the raw manufacturer data as hex, the source of
truth), its parsed properties (`selected.colour`, `.panel`, `.width`, `.height`, `.rotation`, `.battery`,
`.firmware`, `.safeMode`, for reading the file only) and `selected.seenAt` (wall-clock ms when it was last
seen). The `address`/`name` keys of older builds (the first frame a scan found) are ignored and removed on
the first selection, so an existing install shows the picker once.

Uploading to the chosen frame: connect to the saved address; if that fails, scan **for that name only**
(15 s), save its new address and advertisement, and try again. It never switches to another frame. The
picture is rendered in the frame's format (size and inks from its advertisement); if a rescan shows the
frame now advertises other properties, the upload fails and the next one uses the new format.

**No frame chosen**: the catalog browses normally, but nothing is uploaded:
- a catalog tap toasts "No frame selected: choose one in the settings" and starts nothing;
- `UploadService` refuses every request before touching Bluetooth: a manual one (adb) toasts the same, an
  automatic one is reported to `FrameController` as failed (so its key does not stay pending);
- `FrameController` schedules no poster and no revert, and logs `no frame selected: …`.

## Now playing → poster

One-time grant, so the system binds the MediaSession reader. The system rebinds a granted listener after a
force-stop (verified on the Shield) and is expected to do the same after a reboot (platform behaviour, not
yet tested here); the binding keeps the process alive, also through HDMI-CEC standby, so no
`BOOT_COMPLETED` receiver is needed:

    adb shell cmd notification allow_listener io.github.hsw.inkeasel/io.github.hsw.inkeasel.nowplaying.NowPlayingListener
    adb shell dumpsys notification | grep -A3 'Live notification listeners'   # check: NowPlayingListener listed

How it works:
- The standard `MediaSession` metadata (title + artwork) of **any** app, read by `NowPlayingListener`, is
  turned into one `Playing(pkg, key, title, art, state, session)` event.
- **Key = the picture, not the title**: the art URL, or the SHA-1 of the artwork bitmap's pixels. Episodes
  of a series share one poster, so autoplay to the next episode does not redraw the frame.
- **Timing** (`FrameController`):
  - a poster goes up after **60 s** of continuous playback (skips trailers, short clips, channel surfing);
  - **60 s** after pause, stop or the session going away, the default picture goes back, but only if an
    automatic poster is on the frame; a session that keeps playing but moves on to an item without art
    counts as stopped; ends are matched by session, not by picture: only the playing session's pause,
    stop or departure ends playback (also if its item changed meanwhile), not that of another session (an
    earlier player, a helper session, another app), nor the playing session's own end while another
    session of the same app plays (or seeks or buffers while playing);
  - seeking and buffering are ignored (a session first seen buffering, e.g. after a restart, counts as
    playing), a session that clears its playback state counts as stopped; a failed poster upload is retried on the next transition into
    PLAYING, a failed revert every 60 s (at most 5 attempts in a row);
  - an upload takes tens of seconds over BLE, so its result is checked against what plays by then: a poster
    that lands after playback ended is reverted 60 s later, and a default that lands after the film resumed
    is followed by that film's poster 60 s later; a picture whose earlier request has no result yet is not
    requested again (e.g. pause/resume while the poster still waits for the frame to finish drawing), but
    if that earlier request then fails while the film still plays, the poster is requested again at once;
  - a manual upload during a film stays until the next transition into PLAYING (pause/resume, another film);
  - a revert or a poster decided while a manual upload was still queued or uploading is dropped once the
    manual upload succeeds (it would cover the user's pick); if the manual upload fails, it runs as usual;
  - after a process restart or a reboot, a poster left on the frame is reverted once the listener finds
    nothing playing; likewise when the listener reconnects and the app that was playing no longer plays.
- **Default picture = the last manual upload** (catalog tap or `UploadService` without `auto`). There is
  no separate setting. Until the first manual upload there is no default, so a poster stays on the frame
  after playback ends: do one manual upload (catalog tap or the adb command below) after installing.
- Automatic uploads run through `UploadService` with `auto=true`: no toast over the film, only a log line.
- Uploads are at least 35 s apart, also across app restarts: the frame draws for ~27 s after an upload and
  a picture sent meanwhile is not drawn properly. A later request waits (a manual one toasts how long);
  while it waits, a newer request of the same kind replaces it and a manual one goes first.
- Sessions without art are ignored. Spotify publishes an https cover and works; SmartTube publishes its
  artwork once yuliskov/SmartTube#6423 is merged. A plain `http://` art URI is not used (the
  app does not permit cleartext traffic); the artwork bitmap is used instead, if the session has one.

Manual upload from adb (extras, first usable wins: `path` = an existing file, `photo` = file name in the
pictures folder, `url`; plus an optional `title` for any of them; `auto`, `key` and `stamp` are internal):

    adb shell am start-foreground-service -n io.github.hsw.inkeasel/.UploadService -e photo <name>

Logs (`NowPlayingListener` is also used by the system launcher, so filter by the app's pid):

    adb logcat --pid=$(adb shell pidof io.github.hsw.inkeasel) \
      -s NowPlayingListener FrameController UploadService FrameClient

Code:
- `Catalog.kt`: picture sources (Cleveland Museum of Art CC0 paintings, Wikimedia Commons featured landscapes);
  list requests give up after 20 s, Commons reads at most 3 pages within 30 s (`PageBudget`)
- `CatalogError.kt`: a failed catalog load in words (no connection, timeout, HTTP code, unreadable answer)
- `EpdImage.kt`: decode and crop to the frame's size, then dither and pack via its `FrameFormat`
- `FrameFormat.kt`: render formats (size, inks with their device codes, packing, start-frame marker,
  verified flag; Floyd–Steinberg onto the panel's own inks and the packings `Nibbles4` (4 bpp, optionally
  split), `Crumbs2` (2 bpp) and `BitPlanes` (1-bpp planes, padded, optionally split), free of Android types)
  and `FrameFormats`, the one place that maps an advertisement to a format or to the reason it is not
  supported
- `FrameCaps.kt`: protocol-neutral `FrameCaps` (size, colour scheme, rotation), the input of `FrameFormats`,
  and `FrameKind` (the frame's protocol, saved with the chosen frame)
- `FrameAdvert.kt`: parser of the frame's manufacturer data (colour/panel type, resolution, rotation, safe
  mode, battery, firmware; keeps the raw bytes)
- `FrameStore.kt`: the chosen frame (`SelectedFrame`) in prefs `frame`, and `SeenFrame` (one scan result)
- `FrameScanner.kt`: BLE scan for service `0xFFBB`, shared by the settings list and `FrameClient`
- `Txt.kt`: `Txt` (a user-facing text as a string resource id and arguments) and `TxtException`, free of
  Android types; `TxtAndroid.kt` resolves them with a `Context` or in Compose
- `FrameSettings.kt`: the "Settings → Frame" dialog (live frame list, select, cancel)
- `FrameClient.kt`: BLE connect/upload to the chosen frame, rescan by its name
- `DrawGate.kt`: spacing between uploads: the frame acks an upload at once but draws it for ~27 s after the
  link drops and gives no sign when it is done, and a picture sent meanwhile is not drawn properly, so the
  next upload waits until 35 s after the previous one was handed over (kept in prefs, so also across
  service starts and app restarts)
- `MainActivity.kt`: Compose for TV catalog, the frame button in the header, opens the frame settings
- `UploadService.kt`: transient foreground service, one upload at a time (a newer request replaces a queued
  one of the same kind, a manual one goes first; `nowplaying/UploadQueue.kt`); manual uploads toast and
  become the default picture, `auto` uploads report the result to `FrameController` and are dropped if a
  manual upload succeeded after they were decided (`stamp` = `FrameController.manualUploads`); it stops
  with `stopSelfResult`, so a start arriving as it finishes is not lost, and a request it never finishes
  is reported as failed; it waits for the frame to finish drawing before the next upload (`DrawGate.kt`)
- `nowplaying/`:
  - `Playing.kt`: the `Playing`/`Art`/`State` model and pure mappers from metadata
    (art priority `ART_URI` → `ALBUM_ART_URI` → `DISPLAY_ICON_URI` → bitmap; only https URIs are used)
  - `NowPlayingListener.kt`: `NotificationListenerService` that tracks active media sessions, emits a
    snapshot on connect, and reports sessions that go away
  - `SessionView.kt`: one session's effective event (effective state + current art), reported to the
    controller only when it changes, from every path (snapshot, metadata, playback state, reconnect)
  - `ArtCache.kt`: artwork bitmaps keyed by the SHA-1 of their pixels, stored as `cacheDir/nowplaying/<sha1>.png`
    (the 20 most recent are kept; pruning never deletes the artwork of a tracked session or a key the
    controller may still upload: what plays, a pending start, an upload without a result yet)
  - `FrameController.kt`: the state machine (debounce, dedup by key, revert); `FrameControllers.kt` wires
    the process-wide instance
  - `DefaultStore.kt`: the default picture (prefs `default`) and the key on the frame (prefs `frame`)
  - `UploadOutcome.kt`: what `UploadService` does after an upload (toast, report to the controller,
    remember as the default)
