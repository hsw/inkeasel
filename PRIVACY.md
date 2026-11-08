# InkEasel privacy policy

Last updated: 2026-11-08

InkEasel is an open-source Android app that sends pictures to your own e-paper frame over Bluetooth Low
Energy. It has no account, no analytics, no ads, no crash reporting and no server of its own. **Nothing is
sent to the developer.**

## What the app reads

- **What plays, only while the now-playing poster is on.** With notification access granted and the poster
  switched on, the app reads the media session of the app that is playing: its title, its playback state and
  its artwork. It uses them only to pick the poster and to time when it goes to the frame and when the frame
  returns to your picture. The app ignores the notifications themselves, although Android grants this access
  to all of them. The titles are written to the device's own system log (logcat) for troubleshooting; they are
  not stored by the app or sent anywhere.
- **Pictures you share to InkEasel** from a gallery or a file manager: the app copies the picture into its own
  storage so it can send it to the frame and show it under "My pictures".
- **Bluetooth Low Energy advertisements** of frames nearby, while the settings search for them: the search
  asks Android only for devices that advertise the frame's Bluetooth service, and the app stores only the
  frame you choose.

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

The chosen frame (its name, Bluetooth address and advertised properties), the default picture, the switches
in the settings, the last good catalog lists and the pictures you shared. They stay in the app's private
storage; Android backup is off for the app. Uninstalling the app, or clearing its storage in the system
settings, deletes all of them.

## Permissions and why

- **Nearby devices** (Bluetooth scan and connect, Android 12 and newer): to find your frame and send pictures
  to it. The app declares that it does not use the scan to find your location.
- **Location** (Android 11 and older only, together with the old Bluetooth permissions): those versions of
  Android require it for any Bluetooth Low Energy scan. The app does not read your location.
- **Notification access** (optional): to read what plays for the now-playing poster, as described above. The
  app shows what the access covers before it opens the system screen, and the poster can be switched off
  without revoking the access.
- **Internet**: the catalog downloads and poster artwork above.
- **Foreground service** (connected device): keeps a picture upload to the frame running for the few seconds
  it takes when you leave the app.

## Children

The app is not directed at children and collects no personal information from anyone.

## Changes and contact

Changes to this policy are made in this file, and its history is kept in the repository. Questions and
reports: open an issue in the [repository](https://github.com/hsw/inkeasel/issues).
