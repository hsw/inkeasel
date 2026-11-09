# InkEasel privacy policy

Last updated: 2026-11-09

InkEasel is an open-source Android app that sends pictures to your own e-paper frame over Bluetooth Low
Energy. It has no account, no analytics, no ads, no crash reporting and no server of its own. **Nothing is
sent to the developer.**

## What the app reads

- **What plays, while notification access is granted.** As long as you have granted the app notification
  access, it follows the media sessions of the apps that play: their title, playback state and artwork. It
  does so also while the now-playing poster is switched off, so that it knows what plays the moment you switch
  the poster on; with the poster off, nothing of it goes to the frame. To stop the reading, revoke the access
  in the system settings. The app uses what it reads only to pick the poster and to time when it goes to the
  frame and when the frame returns to your picture. It ignores the notifications themselves, although Android
  grants this access to all of them. The artwork of recent sessions is kept in the app's cache (see "What is
  stored on the device"). The released app writes no title and no artwork address to the device's system
  log (logcat); only a debug build, made for development, does.
- **Pictures you share to InkEasel** from a gallery or a file manager: the app copies the picture into its own
  storage so it can send it to the frame and show it under "My pictures".
- **Whether the device restricts what you see.** Each time the app's screen opens (when the app starts or
  comes back to the foreground) and when its settings open, the app asks Android whether the TV's parental
  controls are on and whether the user may not change accounts (as in a restricted profile). Either one fixes
  the Catalog's nudity filter at "Hide all nudity". The answer is used only for that: it is neither stored nor
  sent.
- **Bluetooth Low Energy advertisements** of frames nearby, while the settings search for them, and during
  an upload (also of a poster, in the background) when the chosen frame does not answer at its saved address
  and has to be found again by its name: the search asks Android only for devices that advertise the frame's
  Bluetooth service, and the app stores only the frame you choose.

## What leaves the device

- **To your frame, over Bluetooth:** the picture you choose, or the poster of what plays. The frame is a
  device you own; nothing goes through the internet on the way.
- **Downloads from public addresses:** the catalog's images come from
  [Wikimedia Commons](https://commons.wikimedia.org/) and the Cleveland Museum of Art. Each time the app's
  screen opens (when the app starts or comes back to the foreground, and when its language changes), it asks
  the [Cleveland Museum of Art Open Access API](https://openaccess-api.clevelandart.org/) and Wikimedia
  Commons for their lists of landscapes, each at most once a week (again the next time the screen opens when a
  request failed or its answer was empty or cut short). The catalog's files come from the app's public GitHub
  release (`github.com`, which redirects to `release-assets.githubusercontent.com`): at those same moments it
  asks for each file at most once a week (again the next time the screen opens when a request failed, and a
  day later when a downloaded file could not be stored), sending the label (ETag) of the copy it already has
  so that an unchanged file is not sent again; the file of a language the app has just started to need is
  asked for at once. The names of the files it asks for include the codes of the label languages it needs: the
  app's language (the device's, unless you chose another one for the app), for some languages also its region
  or script (for example `en-us`, `pt-br`, `es-419` or `zh-hant`), then English and `mul` (Wikidata's
  language-independent labels). So GitHub can tell that language, and for those languages its region or
  script, from them. A poster whose artwork the playing app gives as a web link is downloaded from that link.
  All of these go over https: the app refuses unencrypted connections, also when a server redirects to one.
  These are ordinary downloads: the server that answers sees your device's IP address and the request, as with
  any web page, under its own privacy policy. Besides the address it asks for, each request names the app and
  its version in its User-Agent header (for example `InkEasel/0.1 (+https://github.com/hsw/inkeasel)`, as
  Wikimedia asks of every client); it carries no identifier, account or personal data.

Nothing else is sent: no titles, no pictures, no device or usage data, to the developer or to anyone else.

## What is stored on the device

- the chosen frame (its name, Bluetooth address and advertised properties);
- the default picture, the switches in the settings, the Catalog's nudity filter as you chose it, and small
  app state such as when the frame last drew a picture, whether you denied the Bluetooth permission or
  answered the app's first question about it, and a shared picture that waits for it;
- which picture is on the frame: for a now-playing poster, its artwork address or a fingerprint of its
  artwork;
- the artwork of recent now-playing sessions (in the app's cache, also while the poster is switched off):
  each time it stores a new picture there, the app deletes the oldest ones down to the 20 most recent, but
  never one that a current session or a poster still waiting for the frame needs, so until the next new
  picture there can be more;
- the catalog database: the paintings of the catalog with their labels in the languages above and the
  pictures of the Cleveland and Commons lists; for each catalog file when it was last checked and the label
  (ETag) and fingerprint (SHA-256) of the copy that was imported, and for each list when it last came in
  full; a file being downloaded is kept in the app's cache only until it is imported (older versions kept
  the last lists in the app's files instead: the app deletes them);
- cached catalog images (at most 150 MB in the app's cache; the least recently used go first);
- the pictures you shared, and pictures you copied into "My pictures" yourself.

They stay on the device. The pictures under "My pictures" are in the app's own folder on the shared storage
(`Android/data/io.github.hsw.inkeasel/files/pictures`): on Android 9 and older, and on Android 10 for apps
that opt out of its storage limits, other apps that hold the storage permission can read that folder.
Everything else is in the app's private storage, which other apps cannot read. None of it goes into an
Android backup or a transfer to a new device. Uninstalling the app, or clearing its storage in the system
settings, deletes all of them.

## Permissions and why

- **Nearby devices** (Bluetooth scan and connect, Android 12 and newer): to find your frame and send pictures
  to it. The app declares that it does not use the scan to find your location.
- **Location** (Android 11 and older only, together with the old Bluetooth permissions): those versions of
  Android require it for any Bluetooth Low Energy scan. The app does not read your location.
- **Notification access** (optional): to read what plays for the now-playing poster, as described above. The
  app shows what the access covers before it opens the system screen or tells you where to grant it. The
  poster can be switched off without revoking the access; the reading then goes on (see above) until the
  access is revoked. Low-memory devices with Android 10 or older do not allow this access at all: there the
  app has no poster and asks for nothing.
- **Internet**: the catalog downloads (the catalog files, the Cleveland and Commons lists and the images) and
  poster artwork above.
- **Foreground service** (connected device): keeps a picture upload to the frame running for the few seconds
  it takes when you leave the app. Now-playing posters normally run without it; it keeps one running only
  while Android has not (yet) connected the app's notification listener, or has just disconnected it.

## Children

The app is not directed at children and collects no personal information from anyone. Its catalog of
paintings includes artistic nudity: by default it hides explicit works, and with the TV's parental controls
on, or in a restricted profile, it hides all nudity and the setting cannot be changed (see "What the app
reads"). The works are marked from Wikidata and a hand-checked list, so some may not be marked.

## Changes and contact

Changes to this policy are made in this file, and its history is kept in the repository. Questions and
reports: open an issue in the [repository](https://github.com/hsw/inkeasel/issues).
