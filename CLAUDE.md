# InkEasel

Android app (Kotlin, Compose; `androidx.tv.material3`) that uploads pictures and now-playing posters to an
e-paper frame over BLE. Primary target: Android TV (D-pad); also runs on tablets and phones (touch via
`Modifier.onTap`, `Touch.kt`). Behaviour and code map: `README.md`. Deferred work: `docs/backlog/`;
implementation plans: `docs/plans/`. Both are local and git-ignored: never stage, commit or force-add them.
Design decisions, with reasons and rejected options: `docs/decisions.md` (committed, newest first); a decision
or deviation made in a change gets its entry in the same commit.

## Build and test
- `JAVA_HOME=<JDK 21> ./gradlew testDebugUnitTest assembleDebug lintDebug`
  In the sandboxed agent shell `packageDebugResources` may fail (`clonefile` → "Operation not permitted"):
  add `-x packageDebugResources` for a test-only run. A clean `app/build` can fail once in
  `parseDebugLocalResources` ("!directory.isDirectory()"); rerunning passes.
- Android SDK path is in `local.properties` (git-ignored). compileSdk and targetSdk 36 on AGP 8.9.1 (the oldest
  that supports 36), Gradle 8.11.1, Kotlin 2.0.21, minSdk 26.
- Emulator AVDs: `inkeasel-tablet` (Pixel Tablet, Android 15, Play), `inkeasel-tablet36` (Pixel Tablet,
  Android 16 / API 36, Play image: the target SDK's behaviour changes) and `inkeasel-tv` (Android TV 14).
  Start: `$ANDROID_SDK/emulator/emulator -avd <name>` (the SDK that has the emulator and system images).
  Emulator Bluetooth is virtual: uploads to a real frame need a real device.
- The emulator's DNS is flaky right after boot; a catalog "Unable to resolve host" usually passes on retry.
- `lintDebug` has no errors; a new one is a regression. `bluetooth_le` stays `required="true"` (BLE is the
  only transport) with `UnsupportedTvHardware` suppressed in the manifest. `StringResourcesTest` checks that
  both string tables have the same keys and placeholders.
- CI (`.github/workflows/`): `ci.yml` runs the build above (a lint error fails it) plus `assembleRelease`;
  `release.yml` builds a signed APK for a `v*` tag with a read-only token and publishes it from a separate job
  that runs no build, marked latest (`releases/latest` is the app download). The catalog files live in a
  separate release with the fixed tag `catalog` (built by `tools/catalog-export.py`, assets replaced in place,
  never latest); no workflow touches it.
  Release signing comes only from `INKEASEL_KEYSTORE`, `_KEYSTORE_PASSWORD`, `_KEY_ALIAS`, `_KEY_PASSWORD`;
  none set falls back to the debug key, a partial set fails a release packaging task (not release unit tests
  or lint). No keystore or secret in the repo.
- Release uses R8 (code and resource shrinking) with `-dontobfuscate` (public code; readable traces and
  `Throwable.userText` class names). Add a keep rule to `app/proguard-rules.pro` only when an R8 warning or a
  release smoke test proves it necessary; smoke-test the release APK after a dependency change.
- Play requires 16 KB page-size alignment. After a dependency change that may bring native code, check the
  release APK with `zipalign -c -P 16 -v 4 app-release.apk` and that each `.so` has 16 KB-aligned LOAD
  segments (today the only one is Compose's `libandroidx.graphics.path.so`).
- Emulator techniques: to force an offline or slow catalog, airplane mode does not cut the TV emulator's
  ethernet; set a dead global proxy (`adb shell settings put global http_proxy localhost:9`; undo with
  `settings delete global http_proxy` and `settings put global http_proxy :0`). `adb exec-out screencap -p`
  returns black frames on the TV emulator: use `adb shell screencap -p /sdcard/s.png`, `adb pull`, then
  delete the file. README screenshots live in `docs/screenshots/` (English UI, nothing identifying, the
  tablet's status bar in demo mode, compressed with pngquant).
- Poster path on an emulator: a poster needs a media session with artwork, and YouTube does not run there,
  so use a throwaway media-session helper app (never committed, uninstalled afterwards). Unbind the listener
  to test the hold fallback with
  `adb shell cmd notification disallow_listener io.github.hsw.inkeasel/.nowplaying.NowPlayingListener`
  (`allow_listener` undoes it). `adb shell dumpsys activity services io.github.hsw.inkeasel` shows whether
  `UploadService` runs (a poster with the listener bound shows only `NowPlayingListener`), `dumpsys activity
  processes` the `curProcState`. With the app in the background, Android 12+ refuses the hold start
  (`ForegroundServiceStartNotAllowedException`), which reproduces the refused-hold path.

## Conventions
- Code, comments, commit messages, README and backlog items: English. UI strings live in resources:
  English in `res/values/strings.xml`, Russian in `res/values-ru/strings.xml`; every new string goes into
  both (`lintDebug` flags a gap as `MissingTranslation`). No hard-coded user-facing text in Kotlin, nor
  capitalisation, punctuation or separators added to a resolved text (`list_separator`, `*_sentence` forms).
  Keys are `<area>_<what>` (`catalog_`, `details_`, `viewer_`, `settings_`, `frame_`, `perm_`, `access_`,
  `feature_`, `advert_`, `reason_`, `bt_`, `scan_`, `upload_`, `share_`), in that area's block of the file; a lowercase fragment
  nested in another text (an error's reason) is `<area>_err_<what>`, `<area>_toast_<what>` is only a toast.
- Pure logic stays free of Android types so it runs as JVM unit tests (see `nowplaying/`, `FrameFormat.kt`).
  `org.json` counts as pure, since the JVM tests use the real library (`testImplementation org.json:json`;
  it differs from Android's in corner cases such as `optString` on JSON null, so keep to plain values); any
  other `android.*` class does not.
  It returns a `Txt` (`R.string` id + args; `Txt.kt`) instead of a string, and tests compare `Txt` values,
  not translations. Upload- and share-path failures meant for the user throw `TxtException` (details such as
  a URI or a file name go into its cause, for the log); other exceptions keep their raw message inside a
  generic text (`Throwable.userText` in `Txt.kt`). `Txt` is put into words only at the edge
  (`TxtAndroid.kt`: `resolve(ctx)`, `@Composable text()`). Stored data (`FrameStore`, `DefaultStore`) never
  holds translated text.
- tv-material components ignore touch: every new clickable component also gets `Modifier.onTap(...)`.
- Orientation is free and rotation does not recreate `MainActivity` (`configChanges`): keep state that must
  survive in Compose, not in a recreate. Widths come from `CatalogLayout.forWidth` (columns, padding, split
  header); a TV (960 dp) must keep 4 columns, the 48 × 27 dp overscan margins and one header row.
- `BlePermissions` is the one BLE permission set (scan + connect on 12+, fine location before); every screen
  asks for and checks it through it. "Permissions and features" in the settings is where the app shows what
  it needs; a new permission or feature switch gets a row there.
- Now-playing poster: `FeatureStore.posterEnabled` (default on) gates `FrameController` before any frame
  rule; whoever changes it calls `FrameController.onPosterSwitched()`. Notification access screens differ per
  device (`ListenerAccess`: detail screen, list screen, or the adb hint on older Android TV).
- Uploads: one process-wide `UploadEngine` (pure core `UploadEngineCore`) holds the queue and the worker.
  Automatic uploads go straight to it from `FrameController` (`AutoUploader`) with no foreground service
  while `NowPlayingListener` is bound; only while it is not bound they are held work and `UploadService`
  is started with `ACTION_HOLD` (a refused start is only logged, the engine still reports the request once);
  when it unbinds, unheld work still queued or in flight is made held (`AutoUploader.listenerLost`). A
  replaced automatic request is reported from inside `submit`, so `FrameController.onUploadResult` re-enters
  under the controller's lock: nothing may follow `uploader.upload` there. The report rules are pure
  (`UploadReports`) and the controller tests use them; never copy them into a test rig.
  Manual uploads and shares run in `UploadService` (`connectedDevice`), which stops with
  `stopSelfResult` once the engine reports its held work done; it reports nothing itself. Lock order is
  controller → engine: the engine resolves the controller lazily and never calls it (or `onIdle`) under its
  own lock. `UploadService` takes only manual extras and is not exported; only the debug manifest overlay
  (`app/src/debug/AndroidManifest.xml`) exports it for the adb trigger, and `ManifestTest` guards that.
- What plays reaches the log only through `MediaLog` (`title`, `key`, `error`): a new log line with a
  now-playing title, a poster or `onFrame` key, or an artwork address uses it, so a release build logs none.
- Notification access is asked for only through the disclosure panel in "Permissions and features"
  (`PosterSwitch.press` → `DISCLOSE`, then "Continue"); a new way into the grant, including a hint text such
  as the TV menu path or the adb command, goes through it too (`ListenerAccessUi.rowMenuHint`). `PRIVACY.md`
  must say exactly what the code does (what is read, when, and what is stored); a change to either updates
  the other. The privacy policy is `PRIVACY.md`, shown as `settings_privacy_url`; a TV never launches a
  browser.
- Share → InkEasel (`ShareActivity`, rules in `ShareRules`): only `content://` URIs of other apps; the copy is
  capped and goes through a `.part` file; without the permissions it waits in the app (pending share, 1 h),
  with no frame chosen the share itself says so. Toasts after the share has finished may be dropped on
  Android 13+ (no notification permission), so the share reports its own outcome while in the foreground.

## Public-repo hygiene
This repository will become public. Committed text must contain no IP addresses, MAC addresses of real
devices, names of real frames (use `NM000001`), hostnames, local or home-directory paths, or names of other
private projects. Commits use the GitHub noreply address (set in `.git/config`) and never carry a
`Claude-Session` trailer or session links. Never commit `local.properties`, keystores or APKs.
