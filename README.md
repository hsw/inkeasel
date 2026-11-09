# InkEasel

[![CI](https://github.com/hsw/inkeasel/actions/workflows/ci.yml/badge.svg)](https://github.com/hsw/inkeasel/actions/workflows/ci.yml)

InkEasel puts pictures on an e-paper frame over Bluetooth: famous paintings and photos from a built-in
catalog, your own pictures, and the poster of whatever is playing on your TV. When a film starts, its poster
goes to the frame; when it ends, the frame returns to the last picture you chose.

Made for Android TV and its remote; it also runs on tablets and phones, where you can share a picture to the
frame straight from the gallery. License: [Apache-2.0](LICENSE).

| Android TV | Tablet |
|---|---|
| ![The catalog on Android TV: a tab of picture cards in four columns](docs/screenshots/tv-catalog.png) | ![The catalog on a tablet in portrait: three columns](docs/screenshots/tablet-catalog.png) |

![Settings: the frame search and "Permissions and features"](docs/screenshots/settings.png)

## Supported frames

NowMean e-paper frames.

- **E6 (Spectra 6)**: tested on a real 1024×600 frame; most other E6 sizes work the same way.
- **E6** 1360×480, 1440×600 and 1200×1600, **E4** and **BWR**: supported but not yet tried on a real frame;
  the settings mark them "not verified on hardware".
- **BW, BP, E7, BWY** and a few other panel types: listed in the settings with the reason, but cannot be
  chosen yet.

Details per model: [docs/frames.md](docs/frames.md).

## Install

You need Android 8.0 or newer with Bluetooth: an Android TV, a tablet or a phone.

**Download the APK** (the newest release, always at the same address):

- **Android TV**: install [Downloader](https://play.google.com/store/apps/details?id=com.esaba.downloader)
  from Google Play, open it and enter the code **`1236481`**. When the download finishes, choose **Install**;
  the first time, Android asks you to allow Downloader to install apps.
- **Phone or tablet**: open
  [github.com/hsw/inkeasel/releases/latest/download/inkeasel.apk](https://github.com/hsw/inkeasel/releases/latest/download/inkeasel.apk)
  and open the downloaded file; allow your browser to install apps when asked.

All versions are on the [Releases](https://github.com/hsw/inkeasel/releases) page. A new version installs
over the old one the same way.

**With adb** (if the TV cannot run Downloader): turn on Developer options → network debugging on the TV,
then from a computer:

    adb connect <tv-address>:5555
    adb install -r inkeasel.apk

**Updating from a build before 0.2.0**: those builds were signed with a development key, so 0.2.0 does not
install over them. Uninstall InkEasel first (its settings and chosen frame are lost), then install.

To build it yourself, see [docs/building.md](docs/building.md).

## Getting started

1. On the first start the app explains why it needs Bluetooth. Press **Find my frame** and allow it.
2. The frame settings open and list every frame nearby. Select yours.
3. Press any picture: it goes to the frame and opens full screen.

You can change the frame later with the button at the top of the screen ("Frame: …").

## Using InkEasel

**Catalog.** About 9,600 famous paintings, plus landscapes from the Cleveland Museum of Art and Wikimedia
Commons. A press on a picture sends it to the frame and shows it full screen; Left and Right go to the
previous and next picture. A long press shows its details (artist, date, museum, licence) with **Send to
frame** and **View full screen**. The catalog comes with the app and updates itself at most once a week when
the device is online. Titles and artists follow the device language.

**Nudity filter.** By default the catalog hides explicit works. **Settings → Catalog → Nudity** switches
between "Hide explicit works", "Hide all nudity" and "Show all". With the TV's parental controls on, or in a
restricted profile, it is locked at "Hide all nudity". Works are marked from Wikidata and a hand-checked
list, so some may not be marked.

**My pictures.** Your own pictures on the device. On a phone or tablet, share a picture to InkEasel from the
gallery or a file manager: it is saved here and sent to the frame. On a TV, copy pictures into the folder the
empty tab shows.

**Now-playing poster.** When something plays in any app that publishes what it plays (a film, a series, music
with artwork), its poster goes to the frame; when playback ends, the frame returns to your last picture. It
needs notification access, which you grant once:

- Open **Settings → Permissions and features** and press the poster switch. The app explains what the access
  covers; **Continue** opens the right system screen, where you allow InkEasel.
- On Android TV 9–11 (such as the NVIDIA Shield) the app opens the system settings; go to **Apps → Special
  app access → Notification access → InkEasel**.
- Only if your TV has no such screen (Android TV 8), grant it from a computer:

      adb shell cmd notification allow_listener io.github.hsw.inkeasel/io.github.hsw.inkeasel.nowplaying.NowPlayingListener

Low-memory devices with Android 10 or older cannot grant this access at all, so they have no poster. The
switch turns the poster off without taking the access away.

**Language.** English and Russian, following the device language (or the app's language in the system
settings on Android 13+).

## Permissions

- **Bluetooth** ("Nearby devices"; location on Android 11 and older): to find the frame and send pictures.
- **Notification access** (optional): only for the now-playing poster. The app reads the title and artwork of
  what plays; it does not read your notifications.
- **Internet**: the catalog and the posters.

## Privacy

No account, no analytics, no ads, no server of its own. What plays is read on the device only to pick the
poster and is not sent anywhere. The full policy: [PRIVACY.md](PRIVACY.md).

## For developers

- [docs/building.md](docs/building.md): build, tests, emulators, CI and releases.
- [docs/architecture.md](docs/architecture.md): how the app is put together.
- [docs/catalog.md](docs/catalog.md): the painting catalog, its database and its export tool.
- [docs/now-playing.md](docs/now-playing.md): the poster.
- [docs/frames.md](docs/frames.md): supported frames, choosing one and uploading.
- [docs/protocol.md](docs/protocol.md): the frame's Bluetooth protocol and image formats.
- [docs/decisions.md](docs/decisions.md): design decisions, with their reasons.
