# /// script
# requires-python = ">=3.11"
# dependencies = ["epaper-dithering==6.1.1", "pillow>=10"]
# ///
"""Print the golden indices of DitherGoldenTest from the reference dithering library.

    uv run tools/dither-golden.py

The app's dithering (`FrameFormat.dither`, docs/protocol.md#dithering) is a port of the OpenDisplay
`epaper-dithering` library (MIT): Burkes kernel, serpentine rows, inks matched in OKLab. This script draws two
small synthetic pictures, dithers them with the library (BURKES, serpentine, no preprocessing: exposure,
saturation, shadows, highlights, tone and gamut all at their "off" defaults, no DBS) onto the app's palettes,
and prints the indices as Kotlin string literals, one hex digit per pixel and one string per row, ready to
paste into `app/src/test/java/io/github/hsw/inkeasel/DitherGoldenTest.kt`:

- `colour`: a hue sweep (left to right) × a lightness ramp (top: dark, middle: full colour, bottom: pale),
  on the measured NowMean E6 inks (`FrameFormats.E6`);
- `grey`: a diagonal grey ramp with a vertical ripple, on the OpenDisplay four greys (`FrameFormats.OD_G4`).
  It is grey and already spans luminance 0..255 (more than 0.5 % of it at 255), so the app's auto levels for
  grey panels leave it as it is.

The palettes are passed as a custom `ColorPalette` with no `scheme`, so the library takes its plain path,
which pins no pixel to an exact ink; the patterns contain non-ink colours, so its "every pixel is already an
ink" shortcut does not apply either. The inks are listed in the app's order, so the indices are the app's.

The pattern formulas are integer-only and mirrored in DitherGoldenTest; change both together. The script
runs by hand (it needs the package from PyPI), not in CI.
"""

from __future__ import annotations

import argparse

from epaper_dithering import ColorPalette, DitherMode, dither_image
from PIL import Image

W = 48
H = 32

# FrameFormats.E6 and FrameFormats.OD_G4, in the app's order (the index is the position)
E6 = {
    "black": (0, 0, 0),
    "white": (255, 255, 255),
    "yellow": (240, 224, 80),
    "red": (160, 32, 32),
    "blue": (80, 128, 184),
    "green": (95, 130, 78),
}
G4 = {
    "black": (0, 0, 0),
    "dark": (85, 85, 85),
    "light": (170, 170, 170),
    "white": (255, 255, 255),
}


def colour_pixel(x: int, y: int) -> tuple[int, int, int]:
    """Hue sweep along x (six segments of 256 steps), lightness ramp along y."""
    hp = x * 1536 // W
    seg, f = divmod(hp, 256)
    r, g, b = [
        (255, f, 0),
        (255 - f, 255, 0),
        (0, 255, f),
        (0, 255 - f, 255),
        (f, 0, 255),
        (255, 0, 255 - f),
    ][seg]

    def light(c: int) -> int:
        if y < 16:
            return c * (y + 1) // 16
        return c + (255 - c) * (y - 15) // 17

    return light(r), light(g), light(b)


def grey_pixel(x: int, y: int) -> tuple[int, int, int]:
    """Diagonal ramp 0..255 along x with a ripple along y, clamped."""
    v = (x * 255 + 23) // 47 + (y * 23) % 41 - 20
    v = max(0, min(255, v))
    return v, v, v


def dither(pixel, colors: dict[str, tuple[int, int, int]]) -> list[int]:
    img = Image.new("RGB", (W, H))
    img.putdata([pixel(x, y) for y in range(H) for x in range(W)])
    palette = ColorPalette(colors=colors, accent=next(iter(colors)))
    out = dither_image(
        img,
        palette,
        mode=DitherMode.BURKES,
        serpentine=True,
        exposure=1.0,
        saturation=1.0,
        shadows=0.0,
        highlights=0.0,
        tone=0.0,
        gamut=0.0,
        dbs=None,
    )
    return list(out.tobytes())  # a "P" image: one palette index per byte


def kotlin(name: str, indices: list[int]) -> str:
    rows = ["".join(f"{i:x}" for i in indices[y * W : (y + 1) * W]) for y in range(H)]
    body = "\n".join(f'        "{row}",' for row in rows)
    return f"    private val {name} = listOf(\n{body}\n    )"


def main() -> None:
    argparse.ArgumentParser(description=__doc__.splitlines()[0]).parse_args()
    print(kotlin("COLOUR_E6", dither(colour_pixel, E6)))
    print()
    print(kotlin("GREY_G4", dither(grey_pixel, G4)))


if __name__ == "__main__":
    main()
