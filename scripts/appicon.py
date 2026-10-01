#!/usr/bin/env python3
"""Render the SnapSync app icon, and every icon and graphic derived from it.

The mark: two photo libraries as rounded cards, splayed (each turning on its own
centre), knocked through where they overlap — that shared region is the event. One
sun punched out of the upper card makes them read as photographs.

Fill rule is even-odd, so the shape is (A xor B) minus the sun, which is why the
overlap is a hole rather than a third card stacked on top.

The geometry below is the ONE master. Everything is drawn from it, never traced from
a bitmap:
  * the iPhone icon, Icon-1024.png (raster);
  * the Android adaptive launcher icon: a gradient background, the mark as the
    foreground inside the 66dp safe circle, and a monochrome layer for themed icons
    (VectorDrawables — the mark becomes ONE even-odd path, so it needs no density
    buckets);
  * the Play listing's 512×512 icon and 1024×500 feature graphic (raster, in
    metadata/play/images/, committed so the delivery uploads exactly the bytes it hashes).

    python3 scripts/appicon.py            # writes every output above
    python3 scripts/appicon.py --preview  # plus a contact sheet at OS sizes
    python3 scripts/appicon.py --check    # the vector path, rasterised, matches the raster mark
"""

import math
import pathlib
import sys
from PIL import Image, ImageChops, ImageDraw, ImageFont

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

ROOT = pathlib.Path(__file__).resolve().parent.parent
IOS_ICON = ROOT / "iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/Icon-1024.png"
ANDROID_RES = ROOT / "app/android/src/main/res"
PLAY_IMAGES = ROOT / "metadata/play/images"

# Android's adaptive icon: a 108dp canvas whose launcher mask may cut anything outside the
# centred 66dp circle, so the whole mark must fit inside it.
ADAPTIVE = 108.0
SAFE_RADIUS = 33.0

# The feature graphic's words. The name is the listing's; the tagline is the App Store subtitle.
NAME = "SnapSync Photos"
TAGLINE = "Group photos, shared instantly"
# The same face the store screenshots' headlines are set in (compose_screenshots.sh).
FONTS = [
    "/usr/share/fonts/truetype/liberation/LiberationSans-{}.ttf",
    "/usr/share/fonts/liberation-sans/LiberationSans-{}.ttf",
]


# ── Raster ─────────────────────────────────────────────────────────────────────────────


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


def _gradient(width, height):
    img = Image.new("RGB", (width, height))
    last = (width - 1) + (height - 1)
    img.putdata([
        tuple(
            round(a + (b - a) * (x + y) / last)
            for a, b in zip(TOP_LEFT, BOTTOM_RIGHT)
        )
        for y in range(height)
        for x in range(width)
    ])
    return img


def mark_mask(size):
    """The mark at size², supersampled: 255 where it is white."""
    a = _card_mask(size, A_ORIGIN, A_TILT)
    b = _card_mask(size, B_ORIGIN, B_TILT)
    # even-odd: |A - B| is the union minus the overlap; then the sun is cut away
    mark = ImageChops.subtract(ImageChops.difference(a, b), _sun_mask(size))
    return mark.resize((size, size), Image.LANCZOS)


def render(size):
    return Image.composite(Image.new("RGB", (size, size), GLYPH), _gradient(size, size), mark_mask(size))


# ── Vector ─────────────────────────────────────────────────────────────────────────────
#
# The same three shapes as outlines. PIL's rotate(θ) turns COUNTER-clockwise as seen on a
# y-down canvas, and [_turn] does the same, so a rotated outline lands where the raster's
# rotated mask does. A circular arc stays circular under rotation, so only end points turn.


def _turn(p, centre, degrees):
    a = math.radians(degrees)
    dx, dy = p[0] - centre[0], p[1] - centre[1]
    return (centre[0] + dx * math.cos(a) + dy * math.sin(a), centre[1] - dx * math.sin(a) + dy * math.cos(a))


def _card_outline(origin, tilt):
    """[(start, [(kind, end)…])] for one card: straight sides and clockwise quarter arcs."""
    x, y = origin
    r, w = RADIUS, CARD
    centre = (x + w / 2, y + w / 2)
    t = lambda p: _turn(p, centre, tilt)  # noqa: E731
    return t((x + r, y)), [
        ("L", t((x + w - r, y))), ("A", t((x + w, y + r))),
        ("L", t((x + w, y + w - r))), ("A", t((x + w - r, y + w))),
        ("L", t((x + r, y + w))), ("A", t((x, y + w - r))),
        ("L", t((x, y + r))), ("A", t((x + r, y))),
    ], r


def _sun_outline():
    cx, cy, r = SUN
    centre = (A_ORIGIN[0] + CARD / 2, A_ORIGIN[1] + CARD / 2)
    t = lambda p: _turn(p, centre, A_TILT)  # noqa: E731
    return t((cx - r, cy)), [("A", t((cx + r, cy))), ("A", t((cx - r, cy)))], r


def _outlines():
    return [_card_outline(A_ORIGIN, A_TILT), _card_outline(B_ORIGIN, B_TILT), _sun_outline()]


def mark_path(scale=1.0, offset=(0.0, 0.0)):
    """The mark as ONE path (fill rule even-odd), mapped p → offset + p·scale."""
    f = lambda v: f"{v:.3f}".rstrip("0").rstrip(".")  # noqa: E731
    pt = lambda p: f"{f(offset[0] + p[0] * scale)},{f(offset[1] + p[1] * scale)}"  # noqa: E731
    parts = []
    for start, segments, radius in _outlines():
        parts.append(f"M{pt(start)}")
        for kind, end in segments:
            # large-arc 0 for the card's quarter arcs; a semicircle is drawn as two, so 0 holds there too
            parts.append(f"L{pt(end)}" if kind == "L" else f"A{f(radius * scale)},{f(radius * scale)} 0 0 1 {pt(end)}")
        parts.append("Z")
    return "".join(parts)


def _flatten(start, segments, radius, steps=48):
    """The outline as polygon points: what a renderer of [mark_path] fills, for --check and the safe zone."""
    points, here = [start], start
    for kind, end in segments:
        if kind == "L":
            points.append(end)
        else:
            # the clockwise (on screen) arc of [radius] from here to end: centre on the right of the chord
            mx, my = (here[0] + end[0]) / 2, (here[1] + end[1]) / 2
            dx, dy = end[0] - here[0], end[1] - here[1]
            chord = math.hypot(dx, dy)
            h = math.sqrt(max(radius * radius - chord * chord / 4, 0.0))
            cx, cy = mx - dy / chord * h, my + dx / chord * h
            a0 = math.atan2(here[1] - cy, here[0] - cx)
            a1 = math.atan2(end[1] - cy, end[0] - cx)
            while a1 <= a0:
                a1 += 2 * math.pi
            points += [(cx + radius * math.cos(a0 + (a1 - a0) * i / steps),
                        cy + radius * math.sin(a0 + (a1 - a0) * i / steps)) for i in range(1, steps + 1)]
        here = end
    return points


def _vector_mask(size):
    px = size * SS / 100.0
    masks = []
    for outline in _outlines():
        img = Image.new("L", (size * SS, size * SS), 0)
        ImageDraw.Draw(img).polygon([(x * px, y * px) for x, y in _flatten(*outline)], fill=255)
        masks.append(img)
    a, b, sun = masks
    return ImageChops.subtract(ImageChops.difference(a, b), sun).resize((size, size), Image.LANCZOS)


def safe_scale():
    """Mark units → dp so the mark's farthest point from the canvas centre sits on the 66dp circle."""
    reach = max(math.hypot(x - 50, y - 50) for outline in _outlines() for x, y in _flatten(*outline))
    return SAFE_RADIUS / reach


# ── Android ────────────────────────────────────────────────────────────────────────────

GENERATED = "<!-- GENERATED by scripts/appicon.py from the icon's geometry. Do not edit; re-run the script. -->\n"


def _hex(rgb):
    return "#FF" + "".join(f"{c:02X}" for c in rgb)


def _vector(body):
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n' + GENERATED
        + '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        + '    xmlns:aapt="http://schemas.android.com/aapt"\n'
        + f'    android:width="{ADAPTIVE:g}dp"\n    android:height="{ADAPTIVE:g}dp"\n'
        + f'    android:viewportWidth="{ADAPTIVE:g}"\n    android:viewportHeight="{ADAPTIVE:g}">\n'
        + body + "</vector>\n"
    )


def _foreground(colour):
    s = safe_scale()
    path = mark_path(scale=s, offset=(ADAPTIVE / 2 - 50 * s, ADAPTIVE / 2 - 50 * s))
    return _vector(
        f'    <path\n        android:fillColor="{colour}"\n        android:fillType="evenOdd"\n'
        f'        android:pathData="{path}" />\n'
    )


def android_files():
    s = f"{ADAPTIVE:g}"
    background = _vector(
        f'    <path android:pathData="M0,0h{s}v{s}h-{s}z">\n'
        '        <aapt:attr name="android:fillColor">\n'
        f'            <gradient\n                android:type="linear"\n'
        f'                android:startX="0"\n                android:startY="0"\n'
        f'                android:endX="{s}"\n                android:endY="{s}"\n'
        f'                android:startColor="{_hex(TOP_LEFT)}"\n'
        f'                android:endColor="{_hex(BOTTOM_RIGHT)}" />\n'
        '        </aapt:attr>\n    </path>\n'
    )
    adaptive = (
        '<?xml version="1.0" encoding="utf-8"?>\n' + GENERATED
        + '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        + '    <background android:drawable="@drawable/ic_launcher_background" />\n'
        + '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
        + '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n'
        + '</adaptive-icon>\n'
    )
    return {
        "drawable/ic_launcher_background.xml": background,
        "drawable/ic_launcher_foreground.xml": _foreground(_hex(GLYPH)),
        # the system tints a themed icon's monochrome layer; only its alpha is read
        "drawable/ic_launcher_monochrome.xml": _foreground("#FF000000"),
        "mipmap-anydpi/ic_launcher.xml": adaptive,
        "mipmap-anydpi/ic_launcher_round.xml": adaptive,
    }


# ── Play ───────────────────────────────────────────────────────────────────────────────


def _font(style, size):
    for pattern in FONTS:
        path = pathlib.Path(pattern.format(style))
        if path.is_file():
            return ImageFont.truetype(str(path), size)
    sys.exit(f"error: Liberation Sans {style} not found (install fonts-liberation)")


def feature_graphic():
    """Play's 1024×500 feature graphic: the mark, the name and the tagline on the icon's gradient."""
    width, height = 1024, 500
    img = _gradient(width, height)
    mark, gap = 280, 40
    name, tagline = _font("Bold", 64), _font("Regular", 34)
    draw = ImageDraw.Draw(img)
    words = max(draw.textlength(NAME, font=name), draw.textlength(TAGLINE, font=tagline))
    # the group (mark, gap, words) centred, so neither edge crowds it
    start = round((width - (mark + gap + words)) / 2)
    img.paste(Image.new("RGB", (mark, mark), GLYPH), (start, (height - mark) // 2), mark_mask(mark))
    left = start + mark + gap
    draw.text((left, height // 2 - 8), NAME, font=name, fill=GLYPH, anchor="ls")
    draw.text((left, height // 2 + 48), TAGLINE, font=tagline, fill=GLYPH, anchor="ls")
    return img


def check():
    size = 512
    raster, vector = mark_mask(size), _vector_mask(size)
    off = sum(1 for d in ImageChops.difference(raster, vector).getdata() if d > 96)
    share = off / (size * size)
    print(f"vector vs raster mark at {size}²: {off} pixels differ by >96/255 ({share:.4%})")
    # anti-aliased edges differ by a hair (bicubic rotation vs exact outline); a wrong turn or arc moves whole areas
    if share > 0.002:
        sys.exit("error: the vector mark does not match the raster mark")


if __name__ == "__main__":
    if "--check" in sys.argv:
        check()
        sys.exit(0)

    render(1024).save(IOS_ICON)
    print("wrote Icon-1024.png (1024x1024, opaque)")

    for name, text in android_files().items():
        target = ANDROID_RES / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)
        print(f"wrote {target.relative_to(ROOT)}")

    PLAY_IMAGES.mkdir(parents=True, exist_ok=True)
    render(512).save(PLAY_IMAGES / "icon.png")
    feature_graphic().save(PLAY_IMAGES / "featureGraphic.png")
    print("wrote metadata/play/images/icon.png (512x512) and featureGraphic.png (1024x500)")

    if "--preview" in sys.argv:
        sizes = [180, 120, 87, 80, 60, 58, 40, 29]
        sheet = Image.new("RGB", (sum(sizes) + 20 * len(sizes), 200), (12, 14, 18))
        x = 10
        for s in sizes:
            sheet.paste(render(s), (x, (200 - s) // 2))
            x += s + 20
        sheet.save("/tmp/icon-sizes.png")
        print("wrote /tmp/icon-sizes.png at", sizes)
