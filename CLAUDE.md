# InkEasel

Android app (Kotlin, Compose; `androidx.tv.material3`) that uploads pictures and now-playing posters to an
e-paper frame over BLE. Primary target: Android TV (D-pad); also runs on tablets and phones (touch via
`Modifier.onTap`, `Touch.kt`).

## Where things are written

- `README.md`: the user guide only (what it does, install, getting started, using it). No class names, prefs
  keys or code paths there.
- `docs/` by subsystem — read the one you are about to change first:
  - `docs/architecture.md`: code map, screens and navigation, the gate, permissions UX, Share, uploads,
    network, privacy in the code;
  - `docs/catalog.md`: the catalog database, starter set, release sync, live sources, ranking, nudity,
    `tools/catalog-export.py` and `tools/catalog-nsfw.txt`;
  - `docs/now-playing.md`: the poster pipeline, granting notification access, its emulator tests;
  - `docs/frames.md`: supported panels, choosing the frame, uploading to it;
  - `docs/building.md`: build, tests, emulators and device techniques, lint, CI, R8, releasing and signing;
  - `docs/protocol.md`: the BLE protocols (NowMean, OpenDisplay), image formats and dithering;
  - `docs/decisions.md`: design decisions with reasons and rejected options (committed, newest first).
- `PRIVACY.md` is the privacy policy (shown in the app as `settings_privacy_url`); it must say exactly what the
  code does (what is read, when, what is stored): a change to either updates the other.
- A behaviour change updates its subsystem's `docs/*.md` in the same commit, and `README.md` too when a user
  would notice it. A decision or deviation made in a change gets its `docs/decisions.md` entry in the same
  commit.
- `docs/backlog/` (deferred work) and `docs/plans/` (implementation plans) are local and git-ignored: never
  stage, commit or force-add them.

## Build and test

- `JAVA_HOME=<JDK 21> ./gradlew testDebugUnitTest assembleDebug lintDebug` (CI adds `assembleRelease`).
  Everything else about building, testing and releasing: `docs/building.md`.
- In the sandboxed agent shell `packageDebugResources` may fail (`clonefile` → "Operation not permitted"): add `-x
  packageDebugResources` for a test-only run. Not after `gradle clean` (it then fails in
  `parseDebugLocalResources`) nor after adding a string (`R` keeps the old ids): run once without `-x`.
  Robolectric's first run downloads about 200 MB into `~/.m2/repository`; a sandboxed shell without network or
  write access there fails at that point, so run it once outside the sandbox.
- Rules that cost a lot when broken (reasons in the docs):
  - Room 2.6.1, Paging 3.3.6 and MockWebServer 4.12.0 are pinned; after a dependency change check
    `./gradlew :app:dependencies` (Compose on the BOM, kotlin-stdlib on 2.0.x) and smoke-test the release APK.
  - SQL must run on SQLite 3.18 (minSdk 26): no UPSERT, window functions or `RETURNING`; at most 999 bound
    parameters per statement. A passing Robolectric test does not prove it.
  - A catalog schema change bumps the version and commits its export in `app/schemas/`; keep
    `fallbackToDestructiveMigration()` alone (adding `…OnDowngrade` breaks upgrades).
  - The starter set in `app/src/main/assets/catalog/` is cut from the published `catalog` release with
    `tools/catalog-export.py --starter-from`, never edited by hand; `tools/catalog-nsfw.txt` and
    `Nudity.EXPLICIT`/`Nudity.NUDE` change together (`NudityTest`). Run `python3 tools/catalog-export.py
    --self-test` after changing the tool.
  - `lintDebug` has no errors; a new one is a regression.
  - `FrameFormat.dither` and `Oklab.kt` are a port of `epaper-dithering` 6.1.1 checked by `DitherGoldenTest`:
    never paste our own output into it; after changing the palettes, the patterns (mirrored in both files) or
    the dithering, regenerate it with `uv run tools/dither-golden.py`. BWR, BWY and E4 deliberately match by
    RGB distance (docs/decisions.md); their own tests pin that.
  - OpenDisplay uploads keep a DEFLATE window of at most 512 bytes (header `18 D3`): compress only through
    `Od.zlib` (JZlib, windowBits 9), never `java.util.zip.Deflater`; `OdProtocolTest` checks it with
    `TinyInflate`.
  - Add a keep rule to `app/proguard-rules.pro` only when an R8 warning or a release smoke test proves it
    necessary.
- Release signing comes only from the four `INKEASEL_*` secrets; never put a keystore or a secret in the repo.

## Conventions

- Code, comments, commit messages, README, docs and backlog items: English. UI strings live in resources
  (`res/values/strings.xml`, `res/values-ru/strings.xml`); every new string goes into both. No hard-coded
  user-facing text in Kotlin, nor capitalisation, punctuation or separators added to a resolved text. Key
  naming and the `Txt` rules: `docs/architecture.md`.
- Pure logic stays free of Android types (`org.json` counts as pure) so it runs as JVM unit tests; it returns a
  `Txt`, and tests compare `Txt` values, not translations.
- tv-material components ignore touch: every new clickable component also gets `Modifier.onTap(...)`.
- Rotation does not recreate `MainActivity`: keep state that must survive in Compose. Widths come from
  `CatalogLayout.forWidth`; a TV (960 dp) must keep 4 columns, the 48 × 27 dp overscan margins and one header
  row.
- `BlePermissions` is the one BLE permission set; a new permission or feature switch gets a row in "Permissions
  and features", a Catalog content setting goes into the settings' "Catalog" group.
- Notification access is asked for only through the disclosure panel (`PosterSwitch.press` → `DISCLOSE`); any
  new way into the grant goes through it too.
- Whoever changes `FeatureStore.posterEnabled` calls `FrameController.onPosterSwitched()`. Nothing may follow
  `uploader.upload` in `FrameController` (re-entry under its lock). Lock order is controller → engine. Never copy
  `UploadReports` into a test rig.
- What plays reaches the log only through `MediaLog`.
- An OpenDisplay frame's config carries its Wi-Fi password and encryption key: only the display record
  (`OdDisplay`) is kept, stored (`selected.display`) or logged; never log or store the raw config or a config
  reply. PRIVACY.md says so.
- Every request goes through `Http.client` (a `newBuilder()` of it is fine, a new `OkHttpClient` is not); only
  requests tagged `Holdable` may be held.
- Every catalog write takes `CatalogStore`'s write `Mutex`; network requests run outside it. No DAO returns the
  whole list. Every query the grid or the viewer reads takes `nudityBelow`; every open or send of a Catalog
  picture goes through `MainViewModel.whenAllowed`.
- `UploadService` is exported only in the debug manifest overlay (`ManifestTest`).

## Public-repo hygiene
This repository is public. Committed text must contain no IP addresses, MAC addresses of real devices, names
of real frames (use `NM000001`, or `OD000001` for an OpenDisplay frame), hostnames, local or home-directory
paths, or names of other private projects.
Commits use the GitHub noreply address (set in `.git/config`) and never carry a `Claude-Session` trailer or
session links. Never commit `local.properties`, keystores or APKs.
