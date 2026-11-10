# Frames

Which e-paper frames InkEasel draws on, how a picture is rendered for a panel, how the frame is chosen and
stored, and how an upload reaches it. The BLE protocol and the image formats themselves are in
[protocol.md](protocol.md); this file does not repeat them. The rest of the app: [architecture.md](architecture.md).

## Supported frames

NowMean e-paper frames (BLE service `0xFFBB`, manufacturer data company ID `0xABC8`) and frames running the
OpenDisplay firmware (manufacturer data and service `0x2446`). A NowMean frame's colour type, panel and
resolution come from its advertisement; an OpenDisplay frame reports its panel only once connected (see
"Choosing the frame"). Each picture is rendered for that panel (see "Rendering"). `FrameKind` is the frame's
protocol (`nowmean` or `opendisplay`), saved with the chosen frame and carried by every scan result.

In short:

- **E6 (Spectra 6)**: verified on a real frame (1024×600); most other E6 sizes use the same layout at
  another size and count as verified.
- **E6** 1360×480, 1440×600 and 1200×1600, **E4** and **BWR**: implemented from the vendor app's formats,
  not yet seen on a real frame; the settings say "not verified on hardware", and they can still be chosen.
- **BW, BP, E7, BWY**, a rotated panel or panel type A: listed with the reason and not selectable.

### Per colour type and resolution

Supported: rotation 0° and a panel type other than A; formats from the vendor app, every type dithered with
its own inks, sent raw with content marker MK 0.

| Colour type | Resolutions | Payload | Verified on hardware |
|---|---|---|---|
| E6 (Spectra 6) | every size in the advertised table except 1360×480, 1440×600, 1200×1600 | 4 bpp, W·H/2 | yes (1024×600 seen; the rest is the same layout at another size) |
| E6 | 1360×480, 1440×600 | 4 bpp, W·H/2 | no |
| E6 | 1200×1600 | 4 bpp split: all left half rows, then all right half rows; 960,000 B | no |
| BWR | every size except 1360×480, 1440×600 | black plane, then red plane, 1 bpp each, each padded to a multiple of 512 | no |
| BWR | 1360×480, 1440×600 | split: left black, left red, right black, right red, each part padded to 512 (163,840 / 217,088 B) | no |
| E4 | every size | 2 bpp, W·H/4 | no |

The advertised size table and the byte layouts: [protocol.md](protocol.md#resolution-codes),
[Packing](protocol.md#packing), [Split layouts](protocol.md#split-layouts),
[Payload size](protocol.md#payload-size).

### OpenDisplay

Any size the frame reports, rotation 0° only; formats from the reference sender, every type dithered with its
own inks, sent zlib-compressed where the frame takes it ([protocol.md](protocol.md#opendisplay-image-formats),
[protocol.md](protocol.md#opendisplay-upload)).

| Colour scheme | Payload | Verified on hardware |
|---|---|---|
| four greys | two 1-bit planes, rows padded to a byte (96,000 B at 800×480) | yes (reTerminal E1001, 800×480) |
| mono | one 1-bit plane | no |
| BWR, BWY | two 1-bit planes | no |
| Spectra 6 (even widths) | 4 bpp, the NowMean E6 codes | no |

Refused with the reason: BWRY and the other schemes ("colour scheme 6 is not supported yet"), four greys on
panels `0x28` and `0x48`, which keep the greys the other way round, and a frame with encryption on (it answers
"auth required"; the toast says encryption is not supported yet).

A reTerminal E1001 runs either as a 1-bit panel (59, with partial refresh) or as a four-grey one (60, full
refresh only); both are supported, and the app always sends the whole picture with a full refresh.

### Not supported

Listed in the frame list with the reason shown, and not selectable:

- BW, BP and E7: "format not verified (the vendor app does not draw it correctly)";
- BWY: "the vendor app cannot send to such frames";
- panel type A: "panel A (not a photo frame)" (the vendor's photo app ignores it as well);
- a rotation other than 0°: "rotation 90° is not supported yet" (whether such a frame rotates the image
  itself is unknown, [protocol.md](protocol.md#rotation));
- an unknown resolution code: "unknown resolution (code 0x1f)";
- no manufacturer data: "no frame data" under its name, and "the frame does not report its parameters".

An unknown colour or panel code decodes the way the vendor app does (BWR, panel AB). The palettes and
packings of BW, BP, E7 and BWY are already described in `FrameFormats` and plug in by dropping the reason.

### One place decides

The check and the render format (size, inks, packing, marker, verified flag) come from one place,
`FrameFormats` in `FrameFormat.kt`, which works on protocol-neutral `FrameCaps` (size, colour scheme,
rotation; from a NowMean frame's advertisement, `FrameAdvert.caps`, or an OpenDisplay frame's display record,
`OdDisplay.caps`) and the frame's kind (the same inks are packed differently by the two protocols); a new
panel type is one more entry there. A protocol's own rejections come first: a NowMean frame's panel A or
unknown size code (`FrameAdvert.lookup`), an OpenDisplay frame's unknown colour scheme or swapped greys
(`OdDisplay.lookup`). The frame
list does not let a refused frame be chosen, `FrameClient` refuses a selected frame whose caps no longer qualify, and
`UploadEngine` renders with the format found there.

## Rendering

Each picture is rendered for the chosen frame's panel: the whole picture, scaled to fit and centred, never
cropped, on white (bars at the sides of a picture narrower than the panel, at the top and bottom of a wider
one), dithered onto the panel's inks.

- `EpdImage.kt`: decode and scale the whole picture to fit inside the frame (`FrameFormat.fit`), then dither
  it onto the frame's inks with white bars around it and pack via its `FrameFormat`. The decoder subsamples
  by the largest power of two that still leaves the picture at least as large as its place on the panel
  (`FrameFormat.sampleSize`); each intermediate bitmap is recycled as soon as the next step has its pixels.
- Fit: scaled up or down, whole, centred; each side at least 1 px. The bars are the panel's own white (the
  ink nearest to white, `FrameFormat.paper`) and plain: no dithering error reaches them.
- Dithering: Floyd–Steinberg onto the panel's own inks, never the E6 inks for every type as the vendor app
  does ([protocol.md](protocol.md#dithering)).
- Packings: `Nibbles4` (4 bpp, optionally split), `Crumbs2` (2 bpp), `BitPlanes` (1-bpp planes, padded,
  optionally split) and, for OpenDisplay, `RowPlanes` (1-bpp planes with each row padded to a byte). `FrameFormat.kt` is free of Android types and runs as JVM unit tests.
- The size and inks come from the frame's advertisement (OpenDisplay: its display record); there is no rotation step (only rotation 0° is
  supported).

The catalog cards keep the frame's shape: their aspect is the chosen frame's format, or 1024×600
(`FrameFormats.DEFAULT`, the E6 frame this app was built and verified with) while no frame is chosen.

## Choosing the frame

Uploads go only to the frame chosen in **Settings → Frame** (`FrameSettings.kt`, a dialog over the catalog).
Open it with the header button ("Frame: <name>" or "No frame selected"); a fresh start with no frame chosen
(and the Bluetooth permission granted) opens it by itself. The settings scroll as one column (a phone in
landscape is short); **Close** sits next to the title and has the first focus. Once frames show up, the
focus moves once to the chosen frame (or, if it has not shown up within 1.5 s, the first one that can be
chosen), unless the user has already moved onto the list.

- Under the "Frame" title: the chosen frame ("Selected: NM000001 · <address> · seen <time>", plus "not
  verified on hardware" for an unverified format), or "No frame selected: choose one to send pictures."
- While it is open, the app scans for BLE service `0xFFBB` (`FrameScanner`, low-latency mode) and lists
  every frame heard, one entry per name (the name is the frame's identity; its MAC may change; a nameless
  advertisement is skipped), sorted by name and updated live: name, MAC, colour and panel type, resolution
  and rotation, battery, firmware, signal (RSSI) and safe mode if set (e.g. "E6, panel TA · 1024×600, 0° ·
  battery 100 % · firmware 21 · signal -60 dBm"). The chosen frame is marked ("NM000001 — selected"). The
  scan stops when the screen closes or the app goes to the background. With no frame heard yet it shows
  "Searching for frames…" and the hint "The frame must be nearby and not connected to a phone." (before
  Android 12 also that the search needs location on); a scan error shows "Frame search failed: BLE scan
  error (<code>)" and "Retry".
- Selecting a frame saves it (with its advertisement and the time it was seen) and closes the screen.
  **Close** or Back closes it with the frame unchanged.
- An OpenDisplay frame is listed with "OpenDisplay · battery 4.20 V" (from its advertisement) and is always
  selectable: what it can show is known only once connected. Selecting it connects (Android waits for the
  frame's next wake-up, up to about a minute; the screen says "Reading the panel of frame NM000001… It wakes
  about once a minute.") and reads the display from its config (`OdClient.interrogate`). A supported display
  saves the frame with it and closes the screen (with the "not verified on hardware" toast where it applies);
  an unsupported one, or a failure (not reachable, encryption on), toasts why ("Frame NM000001 is not
  supported: …", "Frame NM000001: …") and leaves the selection as it was. **Close** cancels the reading.
- Frames the app cannot draw on are listed with the reason ("not supported: …", in the error colour) and
  "unavailable", and cannot be chosen; they stay focusable so the D-pad reaches the reason, and a press only
  toasts it ("Frame NM000001 is not supported: …"). A supported frame whose format was never seen working on
  a real panel says "not verified on hardware" under its properties (and in a toast when chosen); it can be
  chosen.
- Without Bluetooth permission the screen asks for it ("Allow"; on Android 11 and older its text names the
  location permission, which the system dialog asks for, and why a Bluetooth search needs it); with Bluetooth
  off it says so and offers the system Bluetooth settings. Before Android 12 the scan also needs location on,
  and only there does the hint under the search say so (above). Once the system no longer shows its dialog ("Don't
  allow" twice, or "don't ask again"), "Allow" would silently do nothing, so the button becomes "App info"
  and the text names the path: Settings → Apps → InkEasel → Permissions → Nearby devices (Location on
  Android 11 and older). The grant is picked up when the app comes back. A dialog closed without an answer
  (Back, a tap outside) is not a denial: "Allow" asks again. Only after a real denial has been seen once
  (remembered in prefs `permissions`) does a silent "no" count as blocked. The permission set
  (`BlePermissions`) and when the app asks: [architecture.md](architecture.md).

A BLE scan runs only while the settings search for frames, and during an upload when the chosen frame has to
be found again by its name (filtered to the frame's service, see "Uploading to the chosen frame").

## What is stored

Stored in prefs `frame` (`FrameStore`, `SelectedFrame`; next to the upload spacing `endedAt` and the
`onFrame` key): `selected.kind` (the frame's protocol, `nowmean`), `selected.name`, `selected.address`,
`selected.advert` (the raw manufacturer data as hex, the source of truth), its parsed properties
(`selected.colour`, `.panel`, `.width`, `.height`, `.rotation`, `.battery`, `.firmware`, `.safeMode`, for
reading the file only) and `selected.seenAt` (wall-clock ms when it was last seen). An OpenDisplay frame keeps
`selected.display` instead of the advertisement: the raw display record of its config, the source of truth,
with `.panel`, `.colour`, `.width`, `.height` and `.rotation` parsed next to it; nothing else of its config. A selection replaces
every entry of the previous frame. A frame of a kind this build does not know, or with a blank name or
address, reads back as none. The `address`/`name` keys of older builds (the first frame a scan found) are
ignored and removed on the first selection, so an existing install shows the picker once.

Stored data never holds translated text. The chosen frame stays in the app's private storage on the device
(see [PRIVACY.md](../PRIVACY.md)).

## Uploading to the chosen frame

`FrameClient` connects to the saved address; if that fails, it scans **for that name only** (15 s), saves
its new address and advertisement, and tries again. It never switches to another frame. In detail:

- At most two attempts: the fast path (write without response) first, then acked writes. Each attempt first
  waits until the frame has finished drawing the previous picture (`DrawGate`, below).
- A failed connect drops the saved address for the next attempt, which scans for the name; a saved address
  that is not a valid MAC goes straight to the scan. A frame not heard within 15 s fails the upload ("frame
  NM000001 not found", with the scan hint). A successful connect or a rescan updates `selected.seenAt` (a
  rescan also the address and advertisement).
- The picture is rendered in the frame's format (size and inks from its advertisement); if a rescan shows
  the frame now advertises other properties, the upload fails ("the parameters of frame NM000001 have
  changed, send again", or "not supported" if it no longer qualifies) and the next one uses the new format.
  A picture is never sent in a layout the frame does not expect.

An OpenDisplay frame (`OdClient.upload`) is found by its name on every upload (it is connectable only just
after it advertises; the scan waits up to 75 s), then the app connects, reads its config again and fails
the upload ("the parameters of frame NM000001 have changed, send again", or "not supported") if its display
no longer gives the format the picture was rendered for, saving the new display so the next upload uses it.
The picture goes in one attempt, compressed if the frame takes zlib (uncompressed if it refuses the
compressed start), and the upload ends when the frame reports its refresh done
([protocol.md](protocol.md#opendisplay-upload)); `DrawGate` does not apply.

NowMean uploads are spaced by `DrawGate`: the frame acks an upload at once but draws it for ~27 s after the link
drops and gives no sign when it is done, and a picture sent meanwhile is not drawn properly, so the next
upload waits until 35 s after the previous one was handed over (`endedAt` in prefs `frame`, so also across
app restarts; a stored time later than now means a reboot and counts as no upload). The queue, the services
and how a waiting request is replaced: [architecture.md](architecture.md); timing on the link:
[protocol.md](protocol.md#drawing-and-timing).

## No frame chosen

The catalog browses normally, but nothing is uploaded:

- the full-screen view's card says "No frame selected: choose one in the settings to send this picture." in
  place of its button; **Send to frame** in the details panel toasts "No frame selected: choose one in the
  settings" and starts nothing. Without the Bluetooth permission either
  opens the Bluetooth panel instead, since a frame can only be found with it;
- a shared picture is only saved, and the share says so (see [architecture.md](architecture.md));
- `UploadService` refuses a manual request (adb, debug build) before touching Bluetooth and toasts the same;
  the upload engine fails an automatic one before any download or Bluetooth and reports it to
  `FrameController` (so its key does not stay pending);
- `FrameController` schedules no poster and no revert, and logs `no frame selected: …`
  ([now-playing.md](now-playing.md)).

## Code

- `FrameFormat.kt`: render formats (size, inks with their device codes, packing, start-frame marker,
  verified flag; Floyd–Steinberg onto the panel's own inks and the packings `Nibbles4`, `Crumbs2` and
  `BitPlanes`, free of Android types) and `FrameFormats`, the one place that maps an advertisement to a
  format or to the reason it is not supported
- `FrameCaps.kt`: protocol-neutral `FrameCaps` (size, colour scheme, rotation), the input of `FrameFormats`,
  and `FrameKind` (the frame's protocol, saved with the chosen frame)
- `FrameAdvert.kt`: parser of the frame's manufacturer data (colour/panel type, resolution, rotation, safe
  mode, battery, firmware; keeps the raw bytes)
- `FrameStore.kt`: the chosen frame (`SelectedFrame`) in prefs `frame`, and `SeenFrame` (one scan result)
- `FrameScanner.kt`: BLE scan for service `0xFFBB` and manufacturer data `0x2446`, shared by the settings
  list, `FrameClient` and `OdClient`
- `OdProtocol.kt`: OpenDisplay requests and answers, the config reader, `OdDisplay` (the display record, its
  caps and rejections) and `OdAdvert`; free of Android types
- `OdClient.kt`: BLE client of an OpenDisplay frame: reads its display, uploads
- `FrameSettings.kt`: the settings dialog; its "Frame" section is the live frame list
- `EpdImage.kt`: decode, fit, dither and pack for one `FrameFormat`
- `FrameClient.kt`: BLE connect/upload to the chosen frame, rescan by its name (protocol:
  [protocol.md](protocol.md))
- `DrawGate.kt`: spacing between uploads (pure, its time kept in prefs)

## Testing with a frame

Emulator Bluetooth is virtual: uploads to a real frame need a real device. The frame path logs under
`adb logcat -s FrameScanner FrameClient OdClient UploadEngine UploadService`. In examples and committed text a frame
is named `NM000001`, never after a real one.
