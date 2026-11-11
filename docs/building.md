# Building, testing and releasing

How to build InkEasel from source, run its tests, work with emulators and devices, and publish a release.
Related: [architecture.md](architecture.md) (code map, upload engine, strings),
[catalog.md](catalog.md) (the catalog, its export tool and the starter set),
[now-playing.md](now-playing.md) (the poster and its emulator techniques), [frames.md](frames.md),
[protocol.md](protocol.md), [decisions.md](decisions.md) (design decisions with their reasons and the
options turned down).

## Requirements

- **JDK 21** (Gradle, AGP and Robolectric need it; the code compiles to Java 17 bytecode, `jvmTarget = "17"`).
- **Android SDK** with platform 36 and build-tools (`apksigner`, `zipalign`); for emulators also the
  emulator and system images. Its path goes into `local.properties` (`sdk.dir=…`), which is git-ignored and
  never committed.
- **uv** only to regenerate `DitherGoldenTest` with `tools/dither-golden.py` (it pins `epaper-dithering`
  6.1.1); not needed to build.
- Versions: compileSdk and targetSdk 36 (Android 16), minSdk 26 (Android 8.0); Android Gradle plugin 8.9.1
  (the oldest that supports API 36), Gradle 8.11.1 (wrapper), Kotlin 2.0.21, KSP `2.0.21-1.0.28`, Compose BOM
  2024.12.01.

### Pinned dependencies

| Dependency | Pinned at | Why |
|---|---|---|
| Room (runtime, ktx, paging; compiler through KSP) | 2.6.1 | 2.7+ pulls kotlin-stdlib 2.1 over the Kotlin 2.0.21 compiler |
| Paging (runtime, compose, testing) | 3.3.6 | paging-compose 3.4+ pulls Compose foundation over the BOM |
| MockWebServer | 4.12.0 | keeps the test classpath on the app's OkHttp 4.12.0 (5.x pulls OkHttp 5) |

After a dependency change, check `./gradlew :app:dependencies`: Compose must stay on the BOM and
kotlin-stdlib on 2.0.x. Then smoke-test the release APK (R8, below) and, if the change may bring native
code, check the 16 KB alignment (below).

Room exports each schema version to `app/schemas/` (committed): each version's JSON shows what changed.

### Third-party code and algorithms

Besides the AndroidX, Compose, Kotlin, OkHttp and Coil libraries, the app bundles and borrows:

| What | Licence | How it is used |
|---|---|---|
| JZlib (`com.jcraft:jzlib:1.1.3`), a pure-Java port of zlib | BSD-3-Clause | binary dependency: compresses OpenDisplay uploads with a 512-byte window ([protocol.md](protocol.md#opendisplay-upload)) |
| OpenDisplay `epaper-dithering` | MIT | algorithm ported to Kotlin (Burkes, serpentine, OKLab ink matching), constants taken over, no code copied ([protocol.md](protocol.md#dithering)) |

The app has no licences screen; this table is where the credit is kept.

## Build

    JAVA_HOME=<JDK 21> ./gradlew testDebugUnitTest assembleDebug lintDebug

To build and install only the debug APK:

    JAVA_HOME=<JDK 21> ./gradlew assembleDebug
    adb install -r app/build/outputs/apk/debug/app-debug.apk

Known quirks:

- A clean `app/build` can fail once in `parseDebugLocalResources` ("!directory.isDirectory()"); rerunning
  passes.
- After an entity change, KSP's incremental output can go stale (a `*_Impl.java` constructor mismatch in
  `compileDebugJavaWithJavac`): delete `app/build/generated/ksp` and build again.

Debug and release builds differ beyond signing and R8:

- `BuildConfig.DEBUG` gates `MediaLog`: a debug build logs now-playing titles, poster keys and artwork
  addresses, a release build logs `<title>` and `<key>` instead.
- Only the debug manifest overlay (`app/src/debug/AndroidManifest.xml`) exports `UploadService`, for a manual
  upload triggered from adb; `ManifestTest` guards both manifests. The adb command and its extras:
  [architecture.md](architecture.md).
- A debug build is several times slower: measure timings (such as the catalog import) only on a release
  build.

## Tests

    JAVA_HOME=<JDK 21> ./gradlew testDebugUnitTest

All tests are JVM unit tests (`app/src/test/`): JUnit 4 and `kotlinx-coroutines-test`; the Room DAOs, the
database, the catalog import and the paging source on an in-memory database under Robolectric; the settings
screen on its real composables ([Compose UI tests](#compose-ui-tests), also under Robolectric); HTTP against
MockWebServer; JSON with the real `org.json` library (`android.jar` has only stubs; it differs from
Android's copy in corner cases such as `optString` on JSON null, so the code keeps to plain values). Fixture
answers of the live sources are in `app/src/test/resources/catalog/`. Pure logic stays free of Android types
so that it runs here without Robolectric (see [architecture.md](architecture.md)).

**Robolectric** 4.16.1 runs at SDK 36 (`app/src/test/resources/robolectric.properties`) and needs JDK 21.
Its first run downloads `android-all-instrumented` (about 200 MB) from Maven Central into the local Maven
repository (`~/.m2/repository`), so that run needs network access and write access there. The tests read the
app's merged resources (`processDebugResources`), so a resource change needs one run that builds them; in a
sandboxed shell where `packageDebugResources` fails (`clonefile`), `-x packageDebugResources` still works for a
test-only run once they are built.

Its SQLite is newer than the one on minSdk 26 (3.18), so a passing test does not make newer SQL safe:
UPSERT needs 3.24, window functions 3.25, `RETURNING` 3.35; before 3.32 a statement binds at most 999
parameters, so a long `IN (:ids)` is split into chunks (`CatalogDao.markListed`, `LISTED_CHUNK`; its test
passes a small chunk, as Robolectric binds far more; see [catalog.md](catalog.md)).

Test pitfalls:

- Room 2.6: `fallbackToDestructiveMigration()` alone covers an upgrade and a downgrade; adding
  `fallbackToDestructiveMigrationOnDowngrade()` after it makes an upgrade ask for a migration again.
- OkHttp's own `RetryAndFollowUpInterceptor` repeats a 503 carrying `Retry-After: 0` once: a test that counts
  requests answers another code (`CatalogSyncTest` uses 500).

Tests that guard project rules:

- `StringResourcesTest`: both string tables (`res/values/strings.xml`, `res/values-ru/strings.xml`) have the
  same keys and placeholders.
- `ManifestTest`: `UploadService` is exported only by the debug overlay.
- `StarterAssetsTest`, `NudityTest`: the catalog's starter set and nudity list
  ([catalog.md](catalog.md)). `python3 tools/catalog-export.py --self-test` checks the export tool offline; it
  is not in CI.
- `DitherGoldenTest`: the dithering matches OpenDisplay's `epaper-dithering` index for index on two small
  synthetic pictures ([protocol.md](protocol.md#dithering)). Its expected indices come from
  `uv run tools/dither-golden.py` (needs network: it installs the pinned library from PyPI; not in CI), which
  prints them as Kotlin literals to paste into the test. The pattern formulas live in both files; change them
  together, and regenerate after a change to the palettes or the dithering.

### Compose UI tests

Screens are tested on the real composables under Robolectric with `createComposeRule()` (`ui-test-junit4` on the
Compose BOM; `testOptions.unitTests.isIncludeAndroidResources`, so the app's strings and themes load). The rule
hosts the content in a bare `ComponentActivity`; `ComposeHostRule` (in `ComposeTesting.kt`, with the shared test helpers) registers it
with Robolectric's package manager first, chained outside the compose rule. `ui-test-manifest` is not used: it
would merge an exported `ComponentActivity` into the debug APK installed on devices. Such a test drives a
stateless content composable (e.g. `FrameSettingsContent`) with fake state, at TV width
(`@Config(qualifiers = "w960dp-h540dp")`), and names which input it uses:

- **D-pad**: what a TV remote sends, the only input tv-material components react to. The focus is checked with
  `assertIsFocused()`, moved with `performKeyInput { pressKey(Key.DirectionDown) }` and pressed with
  `pressKey(Key.DirectionCenter)` on the focused node (`@OptIn(ExperimentalTestApi::class)`).
- **Touch**: `performScrollTo()` then `performClick()`; it reaches only `Modifier.onTap`, as tv-material
  ignores touch (so a tap that calls its action twice is caught too).
- **Back** on a dialog: a `KEYCODE_BACK` down and up dispatched to the dialog's window
  (`ShadowDialog.getLatestDialog()`); Espresso is not on the test classpath.
- **Touch without a tap**: a scroll is `performTouchInput { swipeUp() }`; neither it nor a tap moves the focus.

Time-driven behaviour runs on `compose.mainClock`. A node is found by its text from the resources
(`app.getString`), with `hasClickAction()` where a header line repeats the same text.

- **An older Android version** is not tested with `@Config(sdk = …)`: only the SDK 36 `android-all` jar is in
  the cache, and another one downloads about 200 MB, locally and in CI. The stateful wrapper decides what
  depends on the version (e.g. `locationOff`: location switched off where the scan needs it, before Android 12)
  and passes it to the content, so a test passes the flag.
- **Rows that depend on the device**: what the code asks the package manager for is made to resolve with
  `shadowOf(app.packageManager).addResolveInfoForIntent(intent, info)` (e.g. the notification-access detail
  screen, which makes the restricted-settings "App info" row show).
- **The stateful wrapper** (`FrameSettingsDialogTest`): the real `FrameSettingsDialog` under
  `createAndroidComposeRule<ComponentActivity>()` on Robolectric's Bluetooth shadows. The LE scanner's shadow
  keeps the callback of each running scan (`scanCallbacks`), so a test sees a scan start and stop and fails it
  (`onScanFailed`); `shadowOf(adapter).setEnabled(…)` plus an `ACTION_STATE_CHANGED` broadcast switches
  Bluetooth; `activityRule.scenario.moveToState(CREATED)` and back to `RESUMED` sends the app to the background
  and back. The location switch is read only before Android 12, so its re-read is checked on the emulator.

## Emulators and devices

### AVDs

| AVD name | Device | System image |
|---|---|---|
| `inkeasel-tablet` | Pixel Tablet | Android 15, Play |
| `inkeasel-tablet36` | Pixel Tablet | Android 16 / API 36, Play (the target SDK's behaviour changes) |
| `inkeasel-tv` | Android TV | Android 14 |

Start one with `$ANDROID_SDK/emulator/emulator -avd <name>` (the SDK that has the emulator and the system
images). Emulator Bluetooth is virtual: uploads to a real frame need a real device. The app is developed and
tested on an NVIDIA Shield TV Pro 2019 (Android 11) and these emulators.

### Techniques

- **Emulator serial**: the adb serial follows the first free port (`emulator-5554`, `-5556`, …), so a
  second or leftover emulator shifts it. Start with `-port 5554`, or take the serial from `adb devices`
  before waiting on `sys.boot_completed`.
- **DNS** is flaky right after an emulator boots; a catalog "Unable to resolve host" usually passes on
  retry.
- **Offline or slow network**: airplane mode does not cut the TV emulator's ethernet. Set a dead global
  proxy instead, and undo it afterwards:

      adb shell settings put global http_proxy localhost:9
      adb shell settings delete global http_proxy
      adb shell settings put global http_proxy :0

- **Screenshots on the TV emulator**: `adb exec-out screencap -p` returns black frames. Capture to the
  device, pull, then delete the file:

      adb shell screencap -p /sdcard/s.png
      adb pull /sdcard/s.png
      adb shell rm /sdcard/s.png

- **README screenshots** live in `docs/screenshots/`: English UI, nothing identifying (no real frame names,
  addresses or personal pictures), the tablet's status bar in demo mode, compressed with pngquant. Sizes:
  `tv-catalog.png` and `settings.png` 1280×720, `tablet-catalog.png` 800×1280 (portrait). Demo mode:

      adb shell settings put global sysui_demo_allowed 1
      adb shell am broadcast -a com.android.systemui.demo -e command enter
      adb shell am broadcast -a com.android.systemui.demo -e command exit

  The Pixel Tablet starts in landscape; turn it to portrait (it may then show "System UI isn't
  responding": press Wait):

      adb shell settings put system accelerometer_rotation 0
      adb shell settings put system user_rotation 1

  A first launch opens the settings and then the Bluetooth panel: close both. The settings shot needs a
  real device that sees frames: blur the frame names and addresses in the "Selected" line and in every
  frame row.
- **UI language**: switch the app's locale without changing the device's (Android 13 and later; on the
  Shield, Android 11, switch the system language for the shot and back afterwards):

      adb shell cmd locale set-app-locales io.github.hsw.inkeasel --locales ru-RU   # or en-US

- **Layout**: the TV emulator is checked at font scale 1.0 and 1.3 (one header row, 4 columns and the
  overscan margins stay; [architecture.md](architecture.md)).
- **Catalog**: watch it with `adb logcat -s CatalogStore CatalogSync LiveRefresh` ("starter set imported in
  N ms"). Measure import times only on a release build: a debug build takes 5–9 s against 1–2 s. Locale
  limits, the 429 hold after a fast scroll and other catalog checks: [catalog.md](catalog.md).
- **Now-playing poster** (media-session helper app, unbinding the listener, service and process state):
  [now-playing.md](now-playing.md).

## Lint and CI

`lintDebug` reports no errors; a new one is a regression. Warnings (mostly `UseKtx` since AGP 8.9) are
reported only. Two deliberate settings: `bluetooth_le` stays `required="true"` (BLE is the only transport),
with `UnsupportedTvHardware` suppressed in the manifest; and every UI string exists in both languages
(lint flags a gap as `MissingTranslation`).

**CI** (`.github/workflows/ci.yml`) runs on every branch push and pull request (a newer push to the same ref
cancels the run in progress), on JDK 21 (Temurin) with a read-only token:

    ./gradlew testDebugUnitTest assembleDebug assembleRelease lintDebug

The release build is signed with the debug key there (no signing secrets): it only has to build. A lint
error fails the run. On a failure the test and lint reports are kept as the artifact `test-reports` (7
days); the debug APK is always kept as `inkeasel-debug-apk` (14 days). A `v*` tag runs the release workflow
instead.

## Release build

- **R8** shrinks the code and the resources (`isMinifyEnabled`, `isShrinkResources`,
  `proguard-android-optimize.txt` plus `app/proguard-rules.pro`) but does not obfuscate (`-dontobfuscate`):
  the code is public, and kept names make crash traces readable without a mapping file and keep
  `Throwable.userText`'s class-name fallback meaningful. No keep rule was needed; add one to
  `app/proguard-rules.pro` only when an R8 warning or a release smoke test proves it necessary. Smoke-test the
  release APK after a dependency change.
- **Size**: the release APK is about 2.8 MB, 1.2 MB of it the catalog's starter set.
- **16 KB page size**: Play requires 16 KB page-size alignment. The only native library is Compose's
  `libandroidx.graphics.path.so`, with 16 KB-aligned LOAD segments. After a dependency change that may bring
  native code, check the release APK (`zipalign` from the SDK build-tools) and that every `.so` has 16
  KB-aligned LOAD segments:

      zipalign -c -P 16 -v 4 app-release.apk

## Signing

The release build is signed with the key given by four environment variables (`app/build.gradle.kts`):

| Variable | Value |
|---|---|
| `INKEASEL_KEYSTORE` | path to the keystore file |
| `INKEASEL_KEYSTORE_PASSWORD` | keystore password |
| `INKEASEL_KEY_ALIAS` | key alias |
| `INKEASEL_KEY_PASSWORD` | key password |

- All four set: the release key.
- None set: the debug key, and the build prints a warning.
- Only some set: a release build fails, naming the missing ones, rather than quietly falling back to the
  debug key.

Only the tasks that package or sign a release (`assembleRelease`, `bundleRelease`, `packageRelease`,
`packageReleaseBundle`, `signReleaseBundle`) check this, so `testReleaseUnitTest` and lint run without the
variables. For a hand-made release, pass the passwords to Gradle as environment variables from a secret
store, never on the command line. No keystore or secret is ever committed.

Check which key signed an APK (`apksigner` from the SDK build-tools):

    apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk

### The release key

A PKCS12 keystore holding one RSA 4096 key: alias `inkeasel`, certificate `CN=InkEasel`, valid 30 years.
PKCS12 keeps one password for the store and the key, so the key password equals the keystore password. The
keystore is kept only in the maintainer's password manager and the repository's four Actions secrets.

Every release must be signed with this key: Android installs an update only over the same signer, so a lost
key means users reinstall. Builds before 0.2.0 were debug-signed and cannot be updated in place to a release
build. Certificate SHA-256, to check a downloaded APK against:

    75:18:68:E6:F1:7B:3F:A6:38:AE:A2:35:E7:EB:A0:75:FB:CB:92:01:C5:48:8B:03:ED:2B:CC:5E:98:6C:6C:0A

## Releasing

1. Bump `versionCode` (by one) and `versionName` in `app/build.gradle.kts`; commit and push.
2. Tag the commit with an annotated tag `v<versionName>` and push the tag:

       git tag -a v<version> -m "InkEasel <version>"
       git push origin v<version>

3. The release workflow (`.github/workflows/release.yml`) runs:
   - **build** (read-only token, no stored git credentials, read-only Gradle cache): checks the four
     repository secrets `INKEASEL_KEYSTORE_BASE64` (the keystore file, base64-encoded),
     `INKEASEL_KEYSTORE_PASSWORD`, `INKEASEL_KEY_ALIAS`, `INKEASEL_KEY_PASSWORD` and stops before building,
     naming the missing ones, if any is unset (Settings → Secrets and variables → Actions); decodes the
     keystore into the runner's temp folder; runs `./gradlew testDebugUnitTest assembleRelease`; removes the
     keystore whatever the outcome; keeps the APK as a one-day artifact. A release is never signed with the
     debug key.
   - **release** (the only job with write access; it runs no build code): creates the GitHub release
     "InkEasel `<tag>`" for the existing tag (`--verify-tag`), with generated notes, marked latest, with two
     copies of the same APK: `inkeasel-<tag>.apk` and the fixed-name `inkeasel.apk`.
4. Check the published APK's signer with `apksigner verify --print-certs` against the certificate above.

Because every APK release is marked latest and carries the fixed name,
<https://github.com/hsw/inkeasel/releases/latest/download/inkeasel.apk> always serves the newest APK; the
README and a Downloader short code point at it.

The catalog files live in a separate release with the fixed tag `catalog` (assets replaced in place, never
marked latest), built by `tools/catalog-export.py`; no workflow touches it. See [catalog.md](catalog.md).
