#!/usr/bin/env python3
"""
Renders the WealthBoard app icon from the same geometry as the Android
launcher icon, so the two platforms carry the same mark.

The Android version is an adaptive icon: a 108dp canvas of which only the
centre ~72dp is ever visible once the launcher applies its mask. iOS icons are
full-bleed squares with the squircle applied by the system, so the mark is
re-fitted here rather than copied at its Android coordinates — otherwise it
would sit in the middle of the square at half the size it has on Android.
"""
import io
from pathlib import Path

import cairosvg
from PIL import Image, ImageDraw

OUT = Path(__file__).parent

NAVY = "#0F2A43"
GOLD = "#E4C766"
IVORY = "#F4F1E8"

# Mark geometry, verbatim from ic_launcher_foreground.xml (108dp viewport).
BARS = [
    (33, 64, 47, 80, GOLD),    # shortest
    (50, 50, 64, 80, IVORY),
    (67, 34, 81, 80, GOLD),    # tallest
]
TREND = "M30,46 L52,32 L64,40 L84,22"
TREND_WIDTH = 3.4
ARROWHEAD = "M84,22 L74,23.5 L82.2,31 Z"

# The mark's bounding box in that 108dp space, widened by half the stroke so
# the round caps aren't clipped.
PAD = TREND_WIDTH / 2
BBOX = (30 - PAD, 22 - PAD, 84 + PAD, 80 + PAD)

SIZE = 1024
# How much of the square the mark fills. iOS icons want more breathing room
# than an Android adaptive icon's safe zone gives.
FILL = 0.70


def transform():
    """Scale and centre the Android geometry into the iOS square."""
    x0, y0, x1, y1 = BBOX
    scale = (SIZE * FILL) / max(x1 - x0, y1 - y0)
    tx = SIZE / 2 - ((x0 + x1) / 2) * scale
    ty = SIZE / 2 - ((y0 + y1) / 2) * scale
    return scale, tx, ty


def build_svg():
    scale, tx, ty = transform()
    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{SIZE}" height="{SIZE}" '
        f'viewBox="0 0 {SIZE} {SIZE}">',
        f'<rect width="{SIZE}" height="{SIZE}" fill="{NAVY}"/>',
        f'<g transform="translate({tx:.3f},{ty:.3f}) scale({scale:.5f})">',
    ]
    for bx0, by0, bx1, by1, colour in BARS:
        parts.append(
            f'<rect x="{bx0}" y="{by0}" width="{bx1 - bx0}" height="{by1 - by0}" '
            f'fill="{colour}"/>'
        )
    parts.append(
        f'<path d="{TREND}" fill="none" stroke="{IVORY}" stroke-width="{TREND_WIDTH}" '
        f'stroke-linecap="round" stroke-linejoin="round"/>'
    )
    parts.append(f'<path d="{ARROWHEAD}" fill="{IVORY}"/>')
    parts.append("</g></svg>")
    return "".join(parts)


def squircle_mask(size, radius_ratio=0.2237):
    """
    Approximates the iOS home-screen mask, for the preview only.

    Apple's real shape is a continuous-curvature squircle rather than a rounded
    rectangle; a rounded rect at ~22.37% of the width is close enough to show
    what the icon will look like without shipping a wrong shape in the bundle.
    """
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, size - 1, size - 1], radius=int(size * radius_ratio), fill=255
    )
    return mask


def main():
    png = cairosvg.svg2png(bytestring=build_svg().encode(), output_width=SIZE, output_height=SIZE)
    icon = Image.open(io.BytesIO(png)).convert("RGB")

    # App icons must be fully opaque — the App Store rejects an alpha channel,
    # and a transparent icon renders black on the home screen.
    appicon = OUT / "AppIcon.png"
    icon.save(appicon, "PNG")
    print(f"wrote {appicon} ({icon.size[0]}x{icon.size[1]}, mode={icon.mode})")

    # Preview: the masked icon at a few real home-screen sizes, on a neutral
    # backdrop so the navy doesn't blend into the page.
    sizes = [180, 120, 87, 60]
    gap = 28
    width = sum(sizes) + gap * (len(sizes) + 1)
    height = max(sizes) + gap * 2
    sheet = Image.new("RGB", (width, height), (238, 238, 240))

    x = gap
    for s in sizes:
        small = icon.resize((s, s), Image.LANCZOS)
        rounded = Image.new("RGBA", (s, s), (0, 0, 0, 0))
        rounded.paste(small, (0, 0), squircle_mask(s))
        sheet.paste(rounded, (x, (height - s) // 2), rounded)
        x += s + gap

    preview = OUT / "AppIcon-preview.png"
    sheet.save(preview, "PNG")
    print(f"wrote {preview} ({sheet.size[0]}x{sheet.size[1]})")

    # A single large masked render, which is what reads best in a chat.
    big = 512
    large = icon.resize((big, big), Image.LANCZOS)
    card = Image.new("RGB", (big + 96, big + 96), (238, 238, 240))
    rounded = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    rounded.paste(large, (0, 0), squircle_mask(big))
    card.paste(rounded, (48, 48), rounded)
    home = OUT / "AppIcon-home-screen.png"
    card.save(home, "PNG")
    print(f"wrote {home} ({card.size[0]}x{card.size[1]})")


if __name__ == "__main__":
    main()
