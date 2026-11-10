# Frame BLE protocols

What InkEasel knows about the BLE protocol of NowMean e-paper frames and the image formats they take. It
comes from studying the vendor's Android app and from tests against a real frame (E6, 1024×600, firmware 21).
The second protocol, OpenDisplay, is an open one with its own specification; the part this app uses is in
[OpenDisplay](#opendisplay) at the end.

How to read the marks:
- No mark: what this app does, in `FrameClient.kt`, `FrameScanner.kt`, `FrameAdvert.kt` and `FrameFormat.kt`.
- *(observed)*: seen on the real frame, but nothing in the code checks it.
- *(vendor app)*: how the vendor's app does it; this app does not use it, and it was not tried on hardware.

Multi-byte integers are big-endian unless stated otherwise. Bytes are written in hex.

## Overview

- Bluetooth LE is the only transport. There is no pairing, no authentication and no link encryption on the
  image path.
- No frame carries a checksum or CRC. Image data frames carry a 16-bit packet index.
- The frame identifies itself by its advertised name, not by its MAC address.

## Advertising

A frame advertises:
- the 16-bit service UUID `0xFFBB` (`0000ffbb-0000-1000-8000-00805f9b34fb`). This app's scan filters on it;
- a complete local name, which is the frame's identity. This app skips nameless advertisements, lists one
  entry per name and finds a frame again by its name when its saved address no longer connects;
- manufacturer-specific data with company ID `0xABC8` (on air the AD payload starts with `C8 AB`). Its bytes
  after the company ID are described below. An advertisement without them is listed, but the frame cannot
  be chosen.

Example *(observed)*: manufacturer data `43 13 64 15` = E6, panel type TA, 1024×600 at 0°, battery 100 %,
firmware 21. The frame advertises every few seconds (gaps of 0.3 to 6 s, rarely about 12 s) *(observed)*.

### Manufacturer data

Bit 7 is the most significant bit. `b0` to `b2` are mandatory; this app ignores shorter data. `b3` is optional.

| Byte | Bits | Meaning |
|---|---|---|
| b0 | 7..4 | Colour type: `0` BW, `1` BWR, `2` BWY, `3` E4, `4` E6, `5` E7, `6` BP; any other value is read as BWR (as the vendor app does) |
| b0 | 3..0 | Panel type: `1` A, `2` AB, `3` TA; any other value is read as AB |
| b1 | 4..0 | Resolution code, see [Resolution codes](#resolution-codes) |
| b1 | 7..5 | Rotation: `0` 0°, `1` 90°, `2` 180°, `3` 270°; `4` to `7` are unknown |
| b2 | 7 | Safe mode flag |
| b2 | 6..0 | Battery level in percent (100 on a full frame *(observed)*) |
| b3 | 6..0 | Firmware/protocol version; absent on some firmware |

Colour types: BW black/white, BWR black/white/red, BWY black/white/yellow, E4 four colours, E6 six colours
(Spectra 6), E7 seven colours, BP black/pink.

The vendor's photo app ignores frames of panel type A, and frames whose size and rotation it does not know
(the five largest sizes only at 0°). This app refuses panel type A, any rotation other than 0° (see
[Rotation](#rotation)) and unknown resolution codes.

### Resolution codes

| Code | W×H | Code | W×H |
|---|---|---|---|
| `01` | 200×200 | `09` | 960×640 |
| `02` | 122×250 | `10` | 960×672 |
| `03` | 128×296 | `11` | 1440×600 |
| `04` | 152×296 | `12` | 1360×480 |
| `05` | 184×384 | `13` | 1024×600 |
| `06` | 400×300 | `14` | 1200×1600 |
| `07` | 648×480 | | |
| `08` | 800×480 | | |

Every other code is unknown. W and H are the image size the frame takes, whatever the rotation.

## GATT

| UUID | Use | Properties |
|---|---|---|
| `0000ffe0-0000-1000-8000-00805f9b34fb` | Primary service | |
| `0000ffe1-0000-1000-8000-00805f9b34fb` | App → frame frames (FFE1); mandatory | write, write without response, read |
| `0000ffe2-0000-1000-8000-00805f9b34fb` | Frame → app status notifications (FFE2); optional | notify, with a CCCD (`0x2902`) |
| `0000ffe3-0000-1000-8000-00805f9b34fb` | Unused (FFE3; reads `00 00 00` *(observed)*) | read |

*(observed)*: FFE0 is the only service in the database; there is no GAP, GATT or Device Information
service. FFE1 has a user description `"write image"`. FFE1 reads `01 00` on a fresh connection and
`64 02` from the image end frame on, until the link drops.

### Connection setup

1. Connect over LE without auto-connect; give up after 15 s.
2. Request an MTU of 512. Any MTU above 23 works: the data chunk size follows from it (512 was granted
   *(observed)*).
3. Discover services and take FFE1 (required) and FFE2 (if present).
4. Enable FFE2 notifications: `setCharacteristicNotification` and `01 00` to its CCCD.
5. Ask for a short connection interval (high connection priority) and for the LE 2M PHY on both sides. The
   frame supports LE 2M *(observed)*; without it an upload is much slower.

### Status notification (FFE2)

```
01 <battery %> <lzo flag>
```

Valid when byte 0 is `01` and the length is at least 3. The frame sends it right after the notifications
are enabled *(observed: `01 64 00`)*. LZO flag `1` means the frame takes LZO-compressed image data (see
[LZO compression](#lzo-compression)); the vendor app then compresses, and sends raw data when the flag is
`0`. This app only logs the notification and always sends raw data (`CF = 00` in the start frame).

## Frame format

Every app → frame message is one frame written to FFE1:

```
+-----+-----+----+-------------------+----+
| SOF | LEN | OP | payload (n bytes) | CA |
+-----+-----+----+-------------------+----+
 total = n + 4
 SOF = AE, or AF when total >= 256 (AF carries bit 8 of the length)
 LEN = total & FF
```

A frame never exceeds MTU − 3, so it always fits in one ATT write.

### Opcodes

| OP | Meaning | Write type |
|---|---|---|
| `01` | Image start | without response |
| `02` | Image data | without response, or with response (see [Write types and pacing](#write-types-and-pacing)) |
| `04` | Image end; the frame draws the image | with response |
| `DA` | Time sync *(vendor app; see [Time sync](#time-sync))* | without response |

The vendor app uses more opcodes (for a memo strip on a secondary screen and for firmware updates); they are
not described here and this app does not send them.

## Image upload

One upload is a start frame, `N` data frames and an end frame. The payload is the packed image (see
[Image formats](#image-formats)), raw or LZO-compressed.

### Start frame

```
AE 0A 01 | 00 MK ST 00 CF 01 | CA
```

- `MK`: content marker, see [Content marker](#content-marker).
- `ST`: `00` (the vendor app uses other values only for other products).
- `CF`: `01` if the payload is LZO-compressed, `00` if raw. This app always sends `00`.
- The other bytes are constant; their meaning is unknown.

### Data frames

```
SOF LEN 02 | <chunk> IDX_HI IDX_LO | CA
```

- `CHUNK = MTU − 9` (3 bytes of ATT header, 6 of framing), so `N = ceil(payload / CHUNK)`.
- Frame `k` (from 0) carries `payload[k·CHUNK ..< (k+1)·CHUNK]` and the index `k + 2` (u16, big-endian).
  The last chunk may be shorter.
- Example *(observed)*: at MTU 512, 1024×600 E6 raw is 307,200 bytes in 611 frames of 503 bytes
  (`AF FD 02 …`), the last one 370 bytes, indices 2 to 612.

### End frame

```
AE 06 04 | IDX_HI IDX_LO | CA      IDX = N + 2
```

Written with response; this app waits up to 40 s for it. The frame acknowledges it 23 to 25 ms after it
arrives, as soon as the data is in and before it draws *(observed)*. After the acknowledgement this app
waits 1 s and closes the link; a missing or failed acknowledgement counts as a failed attempt (see below).

### Write types and pacing

The frame has no flow control and no per-packet acknowledgement. When it sees a gap in the indices it drops
the link about a second later, and the image is not drawn *(observed)*.

- The vendor app writes the start and data frames without response, at about 12 ms per frame.
- This app writes them without response too, no faster than one frame per 12 ms, but makes every 8th data
  frame a write with response. Android reports a write without response as done once it is queued, so the
  acknowledged write bounds the queue on a slow link.
- The frame also accepts data frames written with response *(observed)*. If the fast path fails (the link
  sometimes drops about 5 s into the data with a supervision timeout, even on an idle frame *(observed)*),
  this app sends the whole image again with every frame acknowledged, after 2 s, or after the drawing gap
  (see [Drawing and timing](#drawing-and-timing)) if the end frame was already sent.

## Drawing and timing

All *(observed)*:
- The frame draws the image once the link drops. Nothing on the link marks the end of the drawing: kept
  connected for 45 s after the end frame, the frame sent no notification and FFE1 and FFE3 did not change.
- After the link drops it does not advertise for about 27 s, then the usual cadence resumes. It is busy
  drawing for about that long.
- A second upload is accepted as usual at any time, so a client cannot tell from the link whether the frame
  will draw it, but a picture sent while the previous one is still drawing is not drawn properly.
- When two uploads follow within about a second, Android keeps the link up between them, so the frame sees
  no disconnect in between.

So this app starts the next upload no earlier than 35 s after the previous image was handed over (end frame
sent and link closed), across app restarts (`DrawGate`).

## Time sync

*(vendor app; observed on the real frame)*. Sets the frame's clock. This app does not send it.

```
AE 0D DA | YY_HI YY_LO MM DD hh mm ss LANG MEMO | CA
```

- Local time, binary (not BCD); the year is a u16, the other fields one byte each. No time zone.
- `LANG`: `00` if the vendor app runs in Chinese, `01` otherwise (the language of the frame's clock). `MEMO`: `01` if the vendor app's memo strip is on,
  else `00`.
- Example: 2026-10-29 17:48:05, `LANG 01`, `MEMO 00`: `AE 0D DA 07 EA 0A 1D 11 30 05 01 00 CA`.
- The frame does not reply.

## Image formats

The image is W×H exactly as advertised, row-major from the top-left (left to right, then top to bottom),
not rotated. Each pixel is one of the panel's inks; the inks become device codes or plane bits, packed per
colour type.

What this app sends:

| Colour type | Sizes | Packing | Verified on hardware |
|---|---|---|---|
| E6 | all except 1360×480, 1440×600, 1200×1600 | 4 bpp | yes (1024×600; other sizes differ only in W and H) |
| E6 | 1360×480, 1440×600 | 4 bpp | no |
| E6 | 1200×1600 | 4 bpp, split | no |
| BWR | all except 1360×480, 1440×600 | two 1-bit planes | no |
| BWR | 1360×480, 1440×600 | two 1-bit planes, split | no |
| E4 | all | 2 bpp | no |

BW, BP, E7 and BWY are described from the vendor app, but this app does not send them: the vendor app
cannot send to BWY frames at all, and for the others (as for E4 and BWR) it dithers with the E6 inks and so
does not draw them correctly (see [Dithering](#dithering)).

### Content marker

`MK` in the start frame says what the payload holds.

| Colour type | MK |
|---|---|
| E6, E7, E4 | `00` image data follows; `03` all white, no image data |
| BWR | `00` black and red planes; `01` red plane only; `02` black plane only; `03` neither, no image data |
| BW, BP | `01`, always |

With no image data the vendor app sends the payload `00 00`. This app always sends the full image: `MK 00`
for E6, E4 and BWR (both planes, even an empty one).

### Palettes and device codes

E6 (Spectra 6), the measured inks the vendor app dithers with, and their 4-bit device codes:

| Code | Colour | RGB |
|---|---|---|
| `0` | black | 0, 0, 0 |
| `1` | white | 255, 255, 255 |
| `2` | yellow | 240, 224, 80 |
| `3` | red | 160, 32, 32 |
| `5` | blue | 80, 128, 184 |
| `6` | green | 95, 130, 78 |

Only these codes were seen in a real upload *(observed)*.

The other types, with the vendor app's pure RGB inks:

| Type | Inks → code or plane bits |
|---|---|
| E7 | black `0`, white `1`, yellow (255,255,0) `2`, red (255,0,0) `3`, orange (255,127,0) `4`, blue (0,0,255) `5`, green (0,255,0) `6` |
| E4 | black `0`, white `1`, yellow (255,255,0) `2`, red (255,0,0) `3` |
| BWR | white: no bit; black: black-plane bit; red (255,0,0): red-plane bit |
| BWY | white: no bit; black: black-plane bit; yellow (255,255,0): red-plane bit (a guess: the vendor packing puts this yellow into the red plane, but has no BWY palette) |
| BW | white: 0; black: 1 |
| BP | black: 1; pink (255,192,203): 0. There is no white |

The vendor app's orange for E7 does not match the colour its packing expects for code `4`, so code `4` as
orange is inferred.

### Packing

The bit and nibble order is the same for every size; "padded to 512" means `00` bytes appended until the
length is a multiple of 512.

| Type | Packing | Padding |
|---|---|---|
| E6, E7 | 4 bits per pixel, two pixels per byte, left pixel in the high nibble: `byte = c(x) << 4 \| c(x+1)` | none |
| E4 | 2 bits per pixel, first pixel in the top bits: `byte = c0 << 6 \| c1 << 4 \| c2 << 2 \| c3`; one stream over the whole image (rows may share a byte); W·H must be a multiple of 4 | none |
| BW, BP | one 1-bit plane, first pixel in the most significant bit; one continuous bit stream over the whole image (rows are not byte-aligned) | to a whole byte, then to 512 |
| BWR, BWY | black plane, then red plane, each packed like BW | each plane padded to 512 |

A 1024×600 E6 image is 512 bytes per row and 307,200 bytes in all *(observed)*.

### Split layouts

Wide panels take each row cut at its horizontal middle: all left halves (x < W/2, rows 0 to H − 1) first,
then all right halves.

| Type | When | Parts in order |
|---|---|---|
| E6, E7 | image width 1200 or 1600 (of the sizes, only 1200×1600) | left half, right half; no padding |
| BWR, BWY | width 1360 or 1440 | left black, left red, right black, right red; each part padded by `512 − (len mod 512)` bytes |

The BWR padding adds a full 512 bytes if a part is already a multiple of 512, which no listed size hits.
BW, BP and E4 have no split layout. E6 at 1360×480 and 1440×600 is sent unsplit, as the vendor app does.

### Rotation

The image is always W×H as advertised and never rotated by the vendor app; the advertised rotation only
changes its preview. Whether a frame that advertises 90° or 270° rotates the image itself is unknown, so
this app refuses such frames.

### Payload size

Raw payload in bytes, with `P(n)` = n rounded up to a multiple of 512:

| Type | Bytes |
|---|---|
| E6, E7 | W·H/2 |
| E4 | W·H/4 |
| BW, BP | P(ceil(W·H/8)) |
| BWR, unsplit (both planes) | 2 · P(ceil(W·H/8)) |
| BWR, split (both planes) | 4 · P'(W·H/16), with P'(n) = n + 512 − (n mod 512) |

| W×H | BW, BP | BWR | E4 | E6, E7 |
|---|---|---|---|---|
| 200×200 | 5,120 | 10,240 | 10,000 | 20,000 |
| 122×250 | 4,096 | 8,192 | 7,625 | 15,250 |
| 128×296 | 5,120 | 10,240 | 9,472 | 18,944 |
| 152×296 | 5,632 | 11,264 | 11,248 | 22,496 |
| 184×384 | 9,216 | 18,432 | 17,664 | 35,328 |
| 400×300 | 15,360 | 30,720 | 30,000 | 60,000 |
| 648×480 | 38,912 | 77,824 | 77,760 | 155,520 |
| 800×480 | 48,128 | 96,256 | 96,000 | 192,000 |
| 960×640 | 76,800 | 153,600 | 153,600 | 307,200 |
| 960×672 | 80,896 | 161,792 | 161,280 | 322,560 |
| 1440×600 | 108,032 | 217,088 (split) | 216,000 | 432,000 |
| 1360×480 | 81,920 | 163,840 (split) | 163,200 | 326,400 |
| 1024×600 | 76,800 | 153,600 | 153,600 | 307,200 |
| 1200×1600 | 240,128 | 480,256 | 480,000 | 960,000 (split) |

### Dithering

The frame takes device codes, so dithering is up to the client. This app dithers each type onto its own
inks: Floyd–Steinberg (7/16 right, 3/16 down-left, 5/16 down, 1/16 down-right) in plain raster order, not
serpentine; the nearest ink by squared RGB distance, the first one winning a tie; the pixel plus its
accumulated error clamped to 0..255. There is no contrast, saturation or gamma step.

The vendor app dithers the same way, but always onto the measured E6 inks, whatever the colour type, and
then maps pixels to codes by exact RGB match. On other types most E6 inks match nothing and become black or
white, so it draws correctly only on E6 frames.

### LZO compression

*(vendor app)*. Used when the frame reports LZO flag `1` and the start frame has `CF = 01`:
- the packed payload is cut into 512-byte blocks (the last may be shorter);
- each block is compressed on its own with LZO1X-1, and the 3-byte end marker `11 00 00` is removed;
- each block is sent as `[u16 big-endian: compressed length][compressed bytes]`, the blocks one after
  another with no overall header.

A frame decompresses each block (after putting the end marker back) into at most 512 bytes. Block
boundaries are independent of data frame boundaries. The real frame reported LZO flag `0` and took raw data
*(observed)*.

## Open questions

- The meaning of the constant bytes in the start frame, and of FFE1's read value.
- How frames of types other than E6 read the formats above; whether BW and BP use `MK 01` as the vendor app
  sends it; whether a BWY frame takes yellow in the red plane.
- Whether a frame that advertises 90° or 270° rotates the image.
- Whether frames other than E6 decompress LZO.

## OpenDisplay

[OpenDisplay](https://opendisplay.org) is an open firmware for e-paper boards (GPL-3) with a published BLE
protocol: the wire contract is one header, `opendisplay_protocol.h` in
[opendisplay-protocol](https://github.com/OpenDisplay/opendisplay-protocol), and the reference sender is
[py-opendisplay](https://github.com/OpenDisplay/py-opendisplay). This section is the part InkEasel uses, in
`OdProtocol.kt` and `OdClient.kt`; tried on a Seeed reTerminal E1001 (800×480, panel 60 in four greys,
firmware 2.27).

### OpenDisplay advertising

Manufacturer data company ID `0x2446`. The v1 layout has 14 bytes: 0..10 buttons and sensors (laid out by
the frame's config), 11 chip temperature as `(t + 40) × 2`, 12 and bit 0 of 13 battery voltage in steps of
10 mV, the rest of 13 flags and a counter. The app reads only the battery and the temperature. The name
(for example `OD` and three bytes of the MAC) is the identity, as with NowMean frames.

The advertisement says nothing about the panel: size, colour scheme and rotation come only from the config,
after connecting.

A frame on battery sleeps between wake-ups and is connectable only for a short time after it advertises
(about once a minute without deep sleep, `sleep_timeout_ms` 40 s *(observed)*); a connection is started right
after an advertisement is heard.

### OpenDisplay GATT and framing

One service, `0x2446`, with one characteristic for both directions: requests are written to it (data chunks
without response where the characteristic allows it), answers arrive as notifications on it. MTU 247 is
asked for; a data request needs 232 bytes.

- Request: `[opcode:2 BE][payload]`. Payload fields are little-endian unless stated otherwise.
- Answer: `[status][opcode low byte][data]`; status `00` ACK, `FF` NACK, `FE` authentication required
  (current firmware also answers `00 <op> FE`). A frame with encryption on (AES-128-CCM sessions) answers
  every request but the version and authentication with it; the app does not implement sessions and refuses
  such a frame.

### OpenDisplay config

`00 40` reads it. The answer is one or more notifications `00 40 [chunk:2 LE][data]`, chunk 0 with
`[total:2 LE]` before its data. The whole config is `[length:2][version:1][packets][crc:2]`; each packet is
`[number][type][fixed-size data]`, the sizes by type: `01` 22, `02` 22, `04` 30, `20` 46, `21` 22, `23` 30,
`24` 30, `25` 30, `26` 160, `27` 64, `28`..`2B` 32, `2C` 288. The app walks them to the first display packet
(`20`) and stops at a type it does not know.

The display packet: panel model (`panel_ic_type`, 16 bits LE at 2), width (4), height (6), rotation at 14
(an index 0..3, in some configs degrees), colour scheme at 21 (0 mono, 1 BWR, 2 BWY, 3 BWRY, 4 Spectra 6,
5 four greys, 6 sixteen greys, 7 seven colours, 8 Spectra 6 split, 9 eight greys), transmission modes at 22.
The rest of the config (pins, power, Wi-Fi with its password, the encryption key) is not kept.

Switching the panel mode (on the E1001: 1 bit with partial refresh, or four greys with full refresh only) is
a config write and a reboot of the frame, done with the OpenDisplay tools, not by this app.

### OpenDisplay upload

The uncompressed full-frame direct write:

1. `00 70`: start; answered `00 70`.
2. `00 71 [≤230 bytes]` per chunk, each answered `00 71`. Once the frame has every byte of the frame it
   answers the last chunk with `00 72` and refreshes by itself.
3. `00 72 00` (end, full refresh) only if the frame did not end it itself; answered `00 72`.
4. Then, unsolicited, `00 73` when the panel is refreshed or `00 74` if the refresh timed out.

The frame reports the end of its refresh, so no fixed spacing between uploads is needed. On the E1001 in four
greys: 96,000 bytes take about 19 s from a phone (one answer per 230 bytes), the refresh about 4 s
*(observed)*.

Not used: zlib compression (`00 70 [size:4 LE][zlib…]`; the firmware's inflater takes only a 512-byte
window, which `java.util.zip.Deflater` cannot be asked for), the sliding-window pipe (`00 80`), partial
writes (`00 76`, 1-bit panels only) and fast refresh.

### OpenDisplay image formats

The frame takes the panel's native layout and does no conversion; the sender dithers. Each row is padded to a
whole byte, first pixel in the most significant bit; there is no other padding.

| Scheme | Payload | Ink codes |
|---|---|---|
| mono | one 1-bit plane | bit 1 = white |
| BWR | two 1-bit planes | first plane white or red, second red |
| BWY | two 1-bit planes | first plane white, second yellow |
| four greys | two 1-bit planes: bit 0, then bit 1 of a 2-bit code | black 3, dark grey 1, light grey 2, white 0 (panels `0x28` and `0x48` swap the greys) |
| Spectra 6 | 4 bits per pixel, high nibble first | as NowMean E6: 0 black, 1 white, 2 yellow, 3 red, 5 blue, 6 green |

Four greys at 800×480: 2 × 100 × 480 = 96,000 bytes. The greys are dithered at the even levels 0, 85, 170,
255, as the reference sender does where it has no measured palette. Verified on hardware: four greys (a scale
of the four levels came out in order). The others follow the reference sender and are marked "not verified on
hardware"; BWRY, the sixteen, eight and seven colour schemes and the split Spectra 6 are refused.
