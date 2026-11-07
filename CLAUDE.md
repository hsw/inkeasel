# InkEasel

Android app (Kotlin, Compose; `androidx.tv.material3`) that uploads pictures and now-playing posters to an
e-paper frame over BLE. Primary target: Android TV (D-pad); also runs on tablets and phones (touch via
`Modifier.onTap`, `Touch.kt`). Behaviour and code map: `README.md`. Deferred work: `docs/backlog/`.

## Build and test
- `JAVA_HOME=<JDK 21> ./gradlew testDebugUnitTest assembleDebug`
  In the sandboxed agent shell `packageDebugResources` may fail (`clonefile` → "Operation not permitted"):
  add `-x packageDebugResources` for a test-only run. A clean `app/build` can fail once in
  `parseDebugLocalResources` ("!directory.isDirectory()"); rerunning passes.
- Android SDK path is in `local.properties` (git-ignored).
- Emulator AVDs: `inkeasel-tablet` (Pixel Tablet, Android 15, Play) and `inkeasel-tv` (Android TV 14).
  Start: `$ANDROID_SDK/emulator/emulator -avd <name>` (the SDK that has the emulator and system images).
  Emulator Bluetooth is virtual: uploads to a real frame need a real device.
- The emulator's DNS is flaky right after boot; a catalog "Unable to resolve host" usually passes on retry.

## Conventions
- Code, comments, commit messages, README and backlog items: English. UI strings: currently Russian
  (English default + Russian translation is a backlog item).
- Pure logic stays free of Android types so it runs as JVM unit tests (see `nowplaying/`, `FrameFormat.kt`).
- tv-material components ignore touch: every new clickable component also gets `Modifier.onTap(...)`.

## Public-repo hygiene
This repository will become public. Committed text must contain no IP addresses, MAC addresses of real
devices, names of real frames (use `NM000001`), hostnames, local or home-directory paths, or names of other
private projects. Commits use the GitHub noreply address (set in `.git/config`) and never carry a
`Claude-Session` trailer or session links. Never commit `local.properties`, keystores or APKs.
