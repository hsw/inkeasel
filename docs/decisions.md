# Decisions

Design decisions with their reasons and the options that were turned down, newest first. An entry marked
*planned* is decided but not implemented yet; the change that implements it drops the mark.

## 2026-11-08 — Release build: R8 shrinking without obfuscation (*planned*)
Release uses R8 (`isMinifyEnabled`, `isShrinkResources`) with `-dontobfuscate`. The code is public, so
renaming protects nothing, while it would make crash traces from GitHub APKs unreadable without a published
`mapping.txt` and turn `Throwable.userText`'s class-name fallback into meaningless names.
Rejected: obfuscation with `mapping.txt` uploaded by `release.yml` (more moving parts, no benefit for an open
project).

## 2026-11-08 — Privacy policy link as plain text (*planned*)
`PRIVACY.md` in the repository; the settings show its URL as text. Phones and tablets open it on a tap
(`startActivity` in a try/catch, no `resolveActivity`, which needs `<queries>` on Android 11+); TV only shows
it, since a TV app must not launch a browser (TV-WB).
Rejected: a QR code (needs a new dependency such as zxing), a WebView.

## 2026-11-08 — Prominent disclosure before notification access (*planned*)
Every way into the notification-access grant (the poster switch and the "Grant access" button) first shows an
in-app panel that says what the system grant covers (all notifications) and what the app actually reads (the
playing app's media-session title and artwork; notifications are ignored), and that only the poster leaves
the device, to the user's frame over Bluetooth. "Continue" is the affirmative action Play's User Data policy
requires; "Cancel" and Back change nothing.

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
engine still uploads and reports the request once.
Rejected:
- expedited WorkManager — on Android 11 and older (the Shield) it runs as a foreground service with a
  notification anyway, has quotas and would split the queue rules;
- no foreground service on TV only — two upload paths for no gain;
- `CompanionDeviceManager` — unclear support on Android TV (`companion_device_setup`), changes frame pairing;
  kept as a backlog idea;
- `shortService` for manual uploads — Android 14+ only; may be revisited if the Play foreground-service
  declaration becomes a problem.

## 2026-11-08 — `UploadService` is not exported in release builds (*planned*)
Any installed app could otherwise make InkEasel upload a file or URL of its choosing to the user's frame. The
service is exported only by a debug manifest overlay, for the documented adb trigger, and it accepts only
manual uploads (no automatic-upload extras), so even the debug export cannot touch now-playing work.
A JVM test guards the manifests.

## 2026-11-08 — `bluetooth_le` stays required
BLE is the app's only transport, so the feature stays `required="true"`; lint's `UnsupportedTvHardware` is
suppressed in the manifest because Bluetooth is not on Google's list of features TVs may lack.
`ACCESS_COARSE_LOCATION` is declared up to Android 11 next to the fine one.
