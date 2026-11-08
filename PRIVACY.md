# InkEasel privacy policy

Last updated: 2026-11-08

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
  stored on the device"). Titles and artwork addresses are written to the device's own system log (logcat)
  for troubleshooting, which other apps cannot read; the app sends them nowhere.
- **Pictures you share to InkEasel** from a gallery or a file manager: the app copies the picture into its own
  storage so it can send it to the frame and show it under "My pictures".
- **Bluetooth Low Energy advertisements** of frames nearby, while the settings search for them, and during
  an upload (also of a poster, in the background) when the chosen frame does not answer at its saved address
  and has to be found again by its name: the search asks Android only for devices that advertise the frame's
  Bluetooth service, and the app stores only the frame you choose.

## What leaves the device

- **To your frame, over Bluetooth:** the picture you choose, or the poster of what plays. The frame is a
  device you own; nothing goes through the internet on the way.
- **Downloads from public addresses:** the built-in catalogs load their lists and images from the
  [Cleveland Museum of Art Open Access API](https://openaccess-api.clevelandart.org/) and
  [Wikimedia Commons](https://commons.wikimedia.org/). A poster whose artwork the playing app gives as a web
  link is downloaded from that link (https only). These are ordinary downloads: the server that answers sees
  your device's IP address and the request, as with any web page, under its own privacy policy. The app sends
  nothing of its own with them.

Nothing else is sent: no titles, no pictures, no device or usage data, to the developer or to anyone else.

## What is stored on the device

- the chosen frame (its name, Bluetooth address and advertised properties);
- the default picture, the switches in the settings, and small app state such as when the frame last drew a
  picture, whether you denied the Bluetooth permission, and a shared picture that waits for it;
- which picture is on the frame: for a now-playing poster, its artwork address or a fingerprint of its
  artwork;
- the artwork of recent now-playing sessions (at most 20 pictures, in the app's cache), also while the poster
  is switched off;
- the last good catalog lists and cached catalog images;
- the pictures you shared.

They stay in the app's private storage. None of it goes into an Android backup or a transfer to a new
device. Uninstalling the app, or clearing its storage in the system settings, deletes all of them.

## Permissions and why

- **Nearby devices** (Bluetooth scan and connect, Android 12 and newer): to find your frame and send pictures
  to it. The app declares that it does not use the scan to find your location.
- **Location** (Android 11 and older only, together with the old Bluetooth permissions): those versions of
  Android require it for any Bluetooth Low Energy scan. The app does not read your location.
- **Notification access** (optional): to read what plays for the now-playing poster, as described above. The
  app shows what the access covers before it opens the system screen or tells you where to grant it. The
  poster can be switched off without revoking the access; the reading then goes on (see above) until the
  access is revoked.
- **Internet**: the catalog downloads and poster artwork above.
- **Foreground service** (connected device): keeps a picture upload to the frame running for the few seconds
  it takes when you leave the app. Now-playing posters normally run without it; it keeps one running only
  while Android has not (yet) connected the app's notification listener, or has just disconnected it.

## Children

The app is not directed at children and collects no personal information from anyone.

## Changes and contact

Changes to this policy are made in this file, and its history is kept in the repository. Questions and
reports: open an issue in the [repository](https://github.com/hsw/inkeasel/issues).
