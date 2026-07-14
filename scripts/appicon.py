#!/usr/bin/env python3
"""Render the SnapSync app icon to Icon-1024.png.

The mark: two photo libraries as rounded cards, splayed (each turning on its own
centre), knocked through where they overlap — that shared region is the event. One
sun punched out of the upper card makes them read as photographs.

Fill rule is even-odd, so the shape is (A xor B) minus the sun, which is why the
overlap is a hole rather than a third card stacked on top.

    python3 scripts/appicon.py            # writes the app icon
    python3 scripts/appicon.py --preview  # plus a contact sheet at OS sizes
"""

import sys
from PIL import Image, ImageChops, ImageDraw

SS = 4  # supersampling factor; the mark is downsampled from 4096² for clean edges

# Geometry, in a 0–100 canvas (see openspec: the icon is drawn, not traced)
CARD = 48.0  # card edge
RADIUS = 12.0  # card corner radius
A_ORIGIN = (14.0, 14.0)  # upper-left card
B_ORIGIN = (38.0, 38.0)  # lower-right card; 24pt of overlap is the shared middle
A_TILT = 11.0  # counter-clockwise, degrees
B_TILT = -6.0  # the splay: the two cards do not move as one rigid object
SUN = (30.0, 30.0, 6.5)  # centre + radius, in the upper card's top-left corner

# Emerald colorway. The brand greens are AppTheme.kt's; the gradient runs corner to corner.
TOP_LEFT = (0x34, 0xDD, 0xA2)
BOTTOM_RIGHT = (0x0B, 0x8A, 0x5E)
GLYPH = (0xFF, 0xFF, 0xFF)


def _card_mask(size, origin, tilt):
    px = size * SS / 100.0
    img = Image.new("L", (size * SS, size * SS), 0)
    x, y = origin
    ImageDraw.Draw(img).rounded_rectangle(
        [x * px, y * px, (x + CARD) * px, (y + CARD) * px], radius=RADIUS * px, fill=255
    )
    centre = ((x + CARD / 2) * px, (y + CARD / 2) * px)
    return img.rotate(tilt, resample=Image.BICUBIC, center=centre)


def _sun_mask(size):
    px = size * SS / 100.0
    img = Image.new("L", (size * SS, size * SS), 0)
    cx, cy, r = SUN
    ImageDraw.Draw(img).ellipse(
        [(cx - r) * px, (cy - r) * px, (cx + r) * px, (cy + r) * px], fill=255
    )
    # the sun is punched out of the upper card, so it turns with it
    centre = ((A_ORIGIN[0] + CARD / 2) * px, (A_ORIGIN[1] + CARD / 2) * px)
    return img.rotate(A_TILT, resample=Image.BICUBIC, center=centre)


def _gradient(size):
    img = Image.new("RGB", (size, size))
    last = 2 * (size - 1)
    img.putdata([
        tuple(
            round(a + (b - a) * (x + y) / last)
            for a, b in zip(TOP_LEFT, BOTTOM_RIGHT)
        )
        for y in range(size)
        for x in range(size)
    ])
    return img


def render(size):
    a = _card_mask(size, A_ORIGIN, A_TILT)
    b = _card_mask(size, B_ORIGIN, B_TILT)
    # even-odd: |A - B| is the union minus the overlap; then the sun is cut away
    mark = ImageChops.subtract(ImageChops.difference(a, b), _sun_mask(size))
    mark = mark.resize((size, size), Image.LANCZOS)
    return Image.composite(Image.new("RGB", (size, size), GLYPH), _gradient(size), mark)


if __name__ == "__main__":
    icon = render(1024)
    icon.save("iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/Icon-1024.png")
    print("wrote Icon-1024.png (1024x1024, opaque)")

    if "--preview" in sys.argv:
        sizes = [180, 120, 87, 80, 60, 58, 40, 29]
        sheet = Image.new("RGB", (sum(sizes) + 20 * len(sizes), 200), (12, 14, 18))
        x = 10
        for s in sizes:
            sheet.paste(render(s), (x, (200 - s) // 2))
            x += s + 20
        sheet.save("/tmp/icon-sizes.png")
        print("wrote /tmp/icon-sizes.png at", sizes)
