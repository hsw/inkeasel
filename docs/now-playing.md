# Now playing → poster

When a film plays on the device, InkEasel sends its poster to the frame; when playback ends, the frame goes
back to the last picture you sent by hand. The standard `MediaSession` metadata (title + artwork) of **any**
app is read, so no player needs to know about InkEasel.

Related: the upload engine, `UploadService` and the lock order in general are in
[architecture.md](architecture.md); connecting to the chosen frame and the no-frame rules in
[frames.md](frames.md); the privacy policy in [PRIVACY.md](../PRIVACY.md); the reasons behind the design in
[decisions.md](decisions.md).

## Requirements and granting access

The poster needs **notification access**, granted once, so the system binds the MediaSession reader
(`NowPlayingListener`). It is optional: pictures work without it. It lets the app read the media sessions of
other apps (title and artwork of what plays); the app does not read notifications. There is no permission
dialog for it: pressing the poster switch (or **Grant access**) in **Settings → Permissions and features**
first shows what the access covers and what the app really reads; **Continue** opens the system settings, and
the poster turns on once you come back with the access granted:

- phones and tablets open the system screen for InkEasel; on Android 13+ an APK installed outside a store may
  first need App info → ⋮ → "Allow restricted settings", and the section says so;
- Android TV 12 and newer open the list of apps with notification access: select InkEasel there. The
  disclosure also names the menu path below, in case the remote cannot reach the list (seen on Google TV 14);
- Android TV 9–11 (e.g. the Shield) have the switch only in the menu: the app opens Settings → Apps; the
  disclosure names the path **Settings → Apps → Special app access → Notification access → InkEasel** (one
  press on InkEasel there grants it);
- Android TV 8 has no screen for it at all, so the disclosure shows the adb command (pictures work without
  it);
- low-RAM devices with Android 10 or older (e.g. 1–1.5 GB TV boxes) cannot have the poster: Android grants no
  notification access there at all, not even with adb, so the section says the poster is not available on
  this device and offers no way to grant it.

The poster can be switched off in the same section without revoking the access; what plays is still followed
while the access is granted (see [Privacy and logging](#privacy-and-logging)).

Granting and checking from a computer:

    adb shell cmd notification allow_listener io.github.hsw.inkeasel/io.github.hsw.inkeasel.nowplaying.NowPlayingListener
    adb shell dumpsys notification | grep -A3 'Live notification listeners'   # check: NowPlayingListener listed

The system rebinds a granted listener after a force-stop (verified on the Shield) and is expected to do the
same after a reboot (platform behaviour, not yet tested here); the binding keeps the process alive, also
through HDMI-CEC standby, so no `BOOT_COMPLETED` receiver is needed.

### The poster switch in the settings

**Settings → Permissions and features** has the **Now-playing poster** row: an on/off switch and the state of
the notification access it needs.

- The switch shows the poster as it works: on only while the poster is wanted (on by default, prefs
  `features`, key `poster`; `FeatureStore.posterEnabled`) **and** the access is granted (`PosterSwitch`).
  Pressing it while it is on switches the poster off; while it is off with the access, on.
- Without the access the switch stays off, and the press (like "Grant access") opens a disclosure panel under
  it first (`PosterSwitch.press` → `DISCLOSE`): the system grant covers all notifications; the app ignores
  them and reads only the playing app's media-session title and artwork (artwork given as a web link is
  downloaded from there); it reads them whenever the access is granted, also with the poster off, and keeps
  recent artwork in its cache; only the poster leaves the device, to the frame over Bluetooth (with the poster
  off, nothing of what plays does).
- "Cancel" and Back close it and change nothing (the focus goes back to the switch or the button);
  "Continue" remembers the wish (key `posterPending`, `FeatureStore.posterPending`) and starts the grant flow,
  and when the app comes back with the access granted the poster is switched on. Where no screen leads to the
  access, the panel shows the adb command instead of "Continue".
- Every way to the grant (the system screen, the TV menu path, the adb command) appears only in the panel, or
  after its "Continue": the menu path is repeated under the switch while the wish is pending
  (`ListenerAccessUi.rowMenuHint`). A new way into the grant, including a hint text, goes through the panel
  too.
- If the access is revoked from outside, the switch shows off but the stored wish stays, so granting the
  access again brings the poster back.
- The states are read again whenever the app comes back to the front, e.g. from a system screen.
- Switching the poster off stops new posters at once and puts the default picture back if a poster is on the
  frame (see [Switching the poster off and on](#switching-the-poster-off-and-on)).

With the access missing, what the section shows and which screen opens (`ListenerAccessUi`, pure; the screens
themselves in `ListenerAccess`: the detail screen, the list screen, or the adb hint on older Android TV):

- phones and tablets: "Continue" opens the system screen: the app's own page (Android 11+), else the list of
  apps. On Android 13+ an app installed from an APK file (not from a store) may find the switch there greyed
  out ("Restricted setting"): the section says so and offers App info, where ⋮ → "Allow restricted settings"
  unlocks it (not shown when a store installed the app);
- Android TV 12+: the list of apps (InkEasel is in it, one D-pad press grants it), plus the menu path as a
  fallback, in the panel and then under the switch: "If the remote cannot reach the switch there, go back and
  use Settings → Apps → Special app access → Notification access → InkEasel" (on Google TV 14 the D-pad cannot
  reach the rows of that list);
- Android TV 9–11 (the Shield): no such screen; "Continue" opens Settings → Apps (the Settings home where that
  is missing), and the panel (then the section) names the same menu path;
- Android TV 8, a low-RAM TV with Android 11+ without the list screen (they hide the menu item), or any device
  where no settings screen resolves: the adb command (see above), in small print in the disclosure panel only;
- a low-RAM device with Android 10 or older (`ActivityManager.isLowRamDevice()`), where the system neither
  grants nor binds a notification listener: "The now-playing poster is not available on this device", the
  switch disabled and shown off, no "Grant access", no menu path, no adb command and so no disclosure. The
  stored wish is left as it is; with no listener bound nothing reports what plays, so no poster is scheduled.

## Event model and key

- The standard `MediaSession` metadata (title + artwork) of **any** app, read by `NowPlayingListener`, is
  turned into one `Playing(pkg, key, title, art, state, session)` event.
- **Key = the picture, not the title**: the art URL, or the SHA-1 of the artwork bitmap's pixels. Episodes of a
  series share one poster, so autoplay to the next episode does not redraw the frame.
- Art priority: `ART_URI` → `ALBUM_ART_URI` → `DISPLAY_ICON_URI` → bitmap; only https URIs are used. Another
  app's `content://` (or `file://`, `android.resource://`) URI is not readable here, and a plain `http://` one
  is not used (the app refuses cleartext traffic on every Android version, `usesCleartextTraffic="false"`), so
  they fall through to the artwork bitmap, if the session has one (the framework has already downsized it to
  about 640 px).
- Sessions without art are ignored. Spotify publishes an https cover and works; SmartTube publishes its
  artwork once yuliskov/SmartTube#6423 is merged.
- A session's effective event (effective state + current art) is reported to the controller only when it
  changes, from every path (snapshot, metadata, playback state, reconnect) (`SessionView`). The listener
  tracks active media sessions, emits a snapshot on connect, and reports sessions that go away.
- Artwork bitmaps are cached keyed by the SHA-1 of their pixels, as `cacheDir/nowplaying/<sha1>.png`
  (`ArtCache`); storing a new one prunes to the 20 most recent, a target rather than a bound: pruning never
  deletes the artwork of a tracked session or a key the controller may still upload (what plays, a pending
  start, an upload without a result yet).

## Timing rules

`FrameController` is the state machine (debounce, dedup by key, revert, poster switch):

- a poster goes up after **60 s** of continuous playback (skips trailers, short clips, channel surfing);
- **60 s** after pause, stop or the session going away, the default picture goes back, but only if an
  automatic poster is on the frame; a session that keeps playing but moves on to an item without art counts as
  stopped; ends are matched by session, not by picture: only the playing session's pause, stop or departure
  ends playback (also if its item changed meanwhile), not that of another session (an earlier player, a helper
  session, another app), nor the playing session's own end while another session of the same app plays (or
  seeks or buffers while playing);
- seeking and buffering are ignored (a session first seen buffering, e.g. after a restart, counts as playing),
  a session that clears its playback state counts as stopped; a failed poster upload is retried on the next
  transition into PLAYING, a failed revert every 60 s (at most 5 attempts in a row);
- an upload takes tens of seconds over BLE, so its result is checked against what plays by then: a poster
  that lands after playback ended is reverted 60 s later, and a default that lands after the film resumed is
  followed by that film's poster 60 s later; a picture whose earlier request has no result yet is not
  requested again (e.g. pause/resume while the poster still waits for the frame to finish drawing), but if
  that earlier request then fails while the film still plays, the poster is requested again at once;
- a manual upload during a film stays until the next transition into PLAYING (pause/resume, another film);
- a revert or a poster decided while a manual upload was still queued or uploading is dropped once the manual
  upload succeeds (it would cover the user's pick); if the manual upload fails, it runs as usual
  (`stamp` = `FrameController.manualUploads`, checked by `FrameController.isStale`);
- after a process restart or a reboot, a poster left on the frame is reverted once the listener finds nothing
  playing; likewise when the listener reconnects and the app that was playing no longer plays.

With no frame chosen in the settings, `FrameController` schedules no poster and no revert, and logs
`no frame selected: …`; the upload engine fails an automatic request before any download or Bluetooth and
reports it to `FrameController`, so its key does not stay pending (see [frames.md](frames.md)).

## Switching the poster off and on

`FeatureStore.posterEnabled` (default on) gates `FrameController` before any frame rule; whoever changes it
calls `FrameController.onPosterSwitched()`.

- **Switched off** in the settings: what plays is still followed, but no poster is scheduled or uploaded
  (`poster off: not scheduled` in the log). Switching off cancels a pending start and reverts a poster on the
  frame at once; a poster whose upload was already under way is reverted at once when it lands, and one still
  queued in the upload engine is dropped. Reverts work as usual; if the process restarts before the switch's
  revert ran, the poster is reverted once the listener has reported the sessions again, even while the film
  plays.
- **Switched on**: acts on what plays now: a film already playing gets its poster 60 s after the switch; a
  revert still queued in the upload engine while its poster is on the frame is dropped (as when the film
  resumes before a queued revert), and one already uploading is followed by the poster 60 s later.

## Default picture: the last manual upload

The default picture is **the last manual upload** (catalog tap, share or `UploadService` from adb in a debug
build). There is no separate setting. Until the first manual upload there is no default, so a poster stays on
the frame after playback ends: do one manual upload (a catalog tap, or the adb command below in a debug build)
after installing.

The default is kept in prefs `default`, the key of the picture on the frame in prefs `frame`, key `onFrame`
(`DefaultStore`): a poster key, `default:…`, `manual:…`, or none (unknown).

## Uploads and the hold

- Automatic uploads (a poster, the revert to the default) run in the app process, in the same upload engine
  as manual ones, without a foreground service: the bound listener keeps the process alive. They go straight
  to the engine from `FrameController` (`AutoUploader`). Only while the listener is not bound (access granted
  but not bound yet, the binding lost) a request is held work and also starts `UploadService` with an internal
  hold action (`ACTION_HOLD`) that keeps the process in the foreground until the upload is done; if Android
  refuses that start (the app in the background; `ForegroundServiceStartNotAllowedException` on Android 12+),
  the refusal is only logged: the upload still runs and is reported once. When the listener unbinds while an
  automatic upload is still queued or under way, that upload becomes held work and the hold starts for it the
  same way (`AutoUploader.listenerLost`). No toast over the film, only a log line.
- Without the Bluetooth permissions an automatic upload is reported as failed at once (`AutoUploader`).
- Manual uploads (a catalog tap, a share) go through the same engine inside `UploadService`, a short
  `connectedDevice` foreground service, so they finish after the user leaves the app
  ([architecture.md](architecture.md)).
- Uploads are at least 35 s after a NowMean upload, also across app restarts: the frame draws for ~27 s after an
  upload and a picture sent meanwhile is not drawn properly (`DrawGate`). An OpenDisplay upload ends when the frame
  reports its refresh done, so it does not hold back the next one. A later request waits (a manual one toasts how
  long); while it waits, a newer request of the same kind replaces it and a manual one goes first.
- The engine drops an automatic upload, reported as failed, if a manual upload succeeded after it was decided,
  if a poster's turn comes while the poster is switched off, or if a revert's turn comes while the playing
  film's poster is on the frame.
- A replaced automatic request is reported from inside `submit`, so `FrameController.onUploadResult`
  re-enters under the controller's lock: nothing may follow `uploader.upload` there. Lock order is
  controller → `AutoUploader` → engine; the engine resolves the controller lazily and never calls it (or
  `onIdle`) under its own lock. The report rules are pure (`UploadReports`) and the controller tests use
  them; never copy them into a test rig.

Manual upload from adb, **debug builds only** (release builds do not export `UploadService`, so no other app
can start an upload through the service directly; a share to `ShareActivity`, which stays exported as the
share target, still uploads the shared picture without asking again, see [decisions.md](decisions.md). Only
the debug manifest overlay `app/src/debug/AndroidManifest.xml` exports the service, and `ManifestTest` guards
both manifests; extras, first usable wins: `path` = an existing file, `photo` = file name in the pictures
folder, `url`; plus an optional `title` for any of them):

    adb shell am start-foreground-service -n io.github.hsw.inkeasel/.UploadService -e photo <name>

## Privacy and logging

- What plays is read from the media sessions **on the device** whenever notification access is granted (also
  with the poster switched off, which only stops the uploads) and used only to pick and time the poster; it is
  not sent anywhere. Only the poster leaves the device, to the frame over Bluetooth.
- The poster artwork is downloaded over https from the address the playing app publishes.
- The artwork of recent now-playing sessions stays in the app's cache (pruned to the 20 most recent each time
  a new one is stored, sparing any a tracked session or a pending poster still needs), and the key of the
  picture on the frame (for a poster, its artwork address or a hash of its artwork) in the app's storage.
- What plays reaches the log only through `MediaLog` (`title`, `key`, `error`): a release build writes no
  title, no poster key (also as `onFrame`) and no artwork address to the system log (logcat), it logs
  `<title>` and `<key>` instead, and a poster upload's error with those words replaced by `<key>`; a debug
  build logs them as they are, for development. A new log line with a now-playing title, a poster or `onFrame`
  key, or an artwork address uses it, so a release build logs none.
- `PRIVACY.md` must say exactly what the code does (what is read, when, and what is stored); a change to
  either updates the other.

Logs (`NowPlayingListener` is also used by the system launcher, so filter by the app's pid; titles and poster
keys appear only in a debug build, a release build logs `<title>` and `<key>` instead):

    adb logcat --pid=$(adb shell pidof io.github.hsw.inkeasel) \
      -s NowPlayingListener FrameController UploadService UploadEngine FrameClient

## Testing on an emulator

- A poster needs a media session with artwork, and YouTube does not run there, so use a throwaway
  media-session helper app (never committed, uninstalled afterwards).
- Unbind the listener to test the hold fallback with
  `adb shell cmd notification disallow_listener io.github.hsw.inkeasel/.nowplaying.NowPlayingListener`
  (`allow_listener` undoes it).
- `adb shell dumpsys activity services io.github.hsw.inkeasel` shows whether `UploadService` runs (a poster
  with the listener bound shows only `NowPlayingListener`), `dumpsys activity processes` the `curProcState`.
- With the app in the background, Android 12+ refuses the hold start
  (`ForegroundServiceStartNotAllowedException`), which reproduces the refused-hold path.

## Code

`nowplaying/` (free of Android types except `NowPlayingListener`, `FrameControllers` and the two stores, so the rules run as JVM unit tests):

- `Playing.kt`: the `Playing`/`Art`/`State` model and pure mappers from metadata (art priority `ART_URI` →
  `ALBUM_ART_URI` → `DISPLAY_ICON_URI` → bitmap; only https URIs are used)
- `NowPlayingListener.kt`: `NotificationListenerService` that tracks active media sessions, emits a snapshot
  on connect, and reports sessions that go away
- `SessionView.kt`: one session's effective event (effective state + current art), reported to the controller
  only when it changes, from every path (snapshot, metadata, playback state, reconnect)
- `ArtCache.kt`: artwork bitmaps keyed by the SHA-1 of their pixels, stored as `cacheDir/nowplaying/<sha1>.png`
  (storing a new one prunes to the 20 most recent, a target rather than a bound: pruning never deletes the
  artwork of a tracked session or a key the controller may still upload: what plays, a pending start, an
  upload without a result yet)
- `FrameController.kt`: the state machine (debounce, dedup by key, revert, poster switch);
  `FrameControllers.kt` wires the process-wide instance
- `FeatureStore.kt`: the poster switch and its pending wish (prefs `features`)
- `DefaultStore.kt`: the default picture (prefs `default`) and the key on the frame (prefs `frame`)
- `UploadEngineCore.kt`: the engine's queue and worker, pure: every request reported exactly once, a replaced
  one from inside the `submit` that replaced it; held work and `onIdle`; `holdAll` makes unheld work held;
  `UploadQueue.kt`: the queue rules
- `UploadRequest.kt`: one request (manual, or automatic with the controller's key and stamp);
  `UploadReports.kt`: what the engine does with each report (toasts, `FrameController` callbacks), pure, so the
  controller tests run the production rules
- `AutoUploader.kt`: the controller's uploader: straight to the engine, held only while the listener is not
  bound (and made held when it unbinds during an upload); without the Bluetooth permissions an automatic
  upload is reported as failed at once
- `UploadOutcome.kt`: what the upload engine does after an upload (toast, report to the controller, remember
  as the default)
- `MediaLog.kt`: what plays as the log names it: titles, poster keys and a poster upload's error as they are
  in a debug build, `<title>` / `<key>` in a release build

Outside the package:

- `ListenerAccess.kt`: notification access for the poster (granted?, the system screens, the adb command);
  `ListenerAccessUi.kt` decides what the section shows and which screen opens (pure); `PosterSwitch.kt`: what
  the poster switch shows and does, and the wish kept until the access is granted (pure)
- `UploadEngine.kt`, `UploadService.kt` (the hold), `DrawGate.kt` (the 35 s spacing): see
  [architecture.md](architecture.md)

Tests: `app/src/test/java/io/github/hsw/inkeasel/nowplaying/` (`FrameControllerTest`, `PlayingTest`,
`SessionViewTest`, `ArtCacheTest`, `AutoUploaderTest`, `MediaLogTest`, `UploadEngineCoreTest`, …), plus
`PosterSwitchTest` and `ListenerAccessUiTest`.
