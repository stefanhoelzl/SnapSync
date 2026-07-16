#!/usr/bin/env bash
# Composite the committed raw captures (`screenshots/`) into App Store listing images.
#
# The raws are the SINGLE SOURCE OF TRUTH (capability `ios-appstore-metadata`); this script is the App
# Store's rendering of them, and the backend's `deno task shots` is the landing page's. Compositing here —
# rather than baking frames in the capture workflow — is what lets a headline change re-render on ubuntu in
# seconds instead of costing a 10-20 minute macOS run.
#
# NO DEVICE FRAME, deliberately: Apple's Guidelines for Third Parties permit a depiction of Apple hardware
# only when it is "an actual photograph of the genuine Apple product and not an artist's rendering" — which
# bars a fetched frame AND a self-drawn bezel alike. Rounded corners imply a device without depicting one.
#
# Usage: compose_screenshots.sh [<locale>]     (default: en-US)
# Env:   RAW_DIR (default: screenshots), OUT_DIR (default: out)
# Out:   $OUT_DIR/<locale>/NN-<state>.png at exactly 1320x2868 (APP_IPHONE_69).
#        The layout is what `asc screenshots upload --path` fan-out expects: the immediate children of
#        --path are locale directories.
set -euo pipefail

LOCALE="${1:-en-US}"
RAW_DIR="${RAW_DIR:-screenshots}"
OUT_DIR="${OUT_DIR:-out}"
HEADLINES="metadata/screenshots/${LOCALE}.json"

# APP_IPHONE_69 accepts exactly these dimensions; App Store Connect scales this class down to the smaller
# iPhone listings, so it is the only iPhone set we produce.
CANVAS_W=1320
CANVAS_H=2868
SHOT_W=1120       # the screen within the canvas, leaving a brand margin
CORNER=44         # proportional to the shot, not the device's real radius — we are not drawing a device
BRAND="#0E9D6B"   # AppTheme's GreenLight; the light captures sit on the light brand colour

[ -d "$RAW_DIR" ] || { echo "::error::$RAW_DIR not found — commit the raw captures first"; exit 1; }
[ -f "$HEADLINES" ] || { echo "::error::$HEADLINES not found"; exit 1; }

# ImageMagick cannot resolve font ALIASES; it needs a font FILE path. This runs on UBUNTU (the composite
# moved off the macOS capture runner), so the macOS system fonts the capture workflow used are gone.
# Liberation Sans is metric-compatible with Arial; DejaVu ships on every ubuntu image as the backstop.
# ImageMagick 6 vs 7. Ubuntu's `imagemagick` package is **6** (6.9.x), whose binaries are `convert` and
# `identify`; the unified `magick` is ImageMagick **7** and does NOT exist there — apt offers no IM7 at all.
# macOS/brew and most container images ship 7. Support both rather than pinning the environment: the syntax
# below is common to both.
if command -v magick >/dev/null 2>&1; then
  IM="magick"; IDENTIFY="magick identify"
elif command -v convert >/dev/null 2>&1; then
  IM="convert"; IDENTIFY="identify"
else
  echo "::error::no ImageMagick found (need `magick` from IM7 or `convert` from IM6)"; exit 1
fi
echo "using imagemagick: $IM ($($IM --version | head -1))"

FONT=""
for f in "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf" \
         "/usr/share/fonts/liberation-sans/LiberationSans-Bold.ttf" \
         "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf" \
         "/usr/share/fonts/dejavu-sans-fonts/DejaVuSans-Bold.ttf"; do
  [ -f "$f" ] && { FONT="$f"; break; }
done
[ -n "$FONT" ] || { echo "::error::no bold sans font found — install fonts-liberation"; exit 1; }
echo "using font: $FONT"

mkdir -p "$OUT_DIR/$LOCALE"

# `NN-` orders the set on the listing; App Store Connect honours upload order per set.
i=0
for STATE in create joining in_sync; do
  i=$((i + 1))
  RAW="$RAW_DIR/${STATE}-light.png"   # the listing takes the light set; dark is for the landing page
  [ -f "$RAW" ] || { echo "::error::missing $RAW"; exit 1; }

  HEADLINE="$(jq -er --arg s "$STATE" '.headlines[$s]' "$HEADLINES")"
  OUT="$OUT_DIR/$LOCALE/$(printf '%02d' "$i")-${STATE}.png"

  TMP="$(mktemp -d)"

  # Resize first and read the real height back, so the mask below can be drawn with LITERAL numbers.
  # ImageMagick 6 does NOT expand `%[fx:…]` inside `-draw` (7 does), so an fx-sized roundrectangle fails
  # there with "non-conforming drawing primitive definition". Computing the geometry in the shell is the
  # portable form — and it is clearer anyway.
  $IM "$RAW" -resize "${SHOT_W}x" "$TMP/shot.png"
  SHOT_H="$($IDENTIFY -format '%h' "$TMP/shot.png")"

  # Rounded corners, never a device frame (see the header). White where the shot shows through.
  $IM -size "${SHOT_W}x${SHOT_H}" xc:none -fill white \
    -draw "roundrectangle 0,0,$((SHOT_W - 1)),$((SHOT_H - 1)),$CORNER,$CORNER" "$TMP/mask.png"
  $IM "$TMP/shot.png" "$TMP/mask.png" -alpha off -compose CopyOpacity -composite "$TMP/rounded.png"

  # Brand canvas + the rounded shot + the headline.
  #
  # `caption:` word-WRAPS inside a fixed box at a fixed pointsize, so a long headline becomes two lines
  # rather than bleeding off the canvas (`-annotate` neither wraps nor clips safely). Any copy length fits
  # by construction — which is why the headline needs no length gate.
  $IM -size "${CANVAS_W}x${CANVAS_H}" "xc:$BRAND" \
    "$TMP/rounded.png" -gravity south -geometry +0+100 -compose over -composite \
    \( -background none -fill white -font "$FONT" -pointsize 72 \
       -size 1140x -gravity center caption:"$HEADLINE" \) \
    -gravity north -geometry +0+170 -compose over -composite \
    "$OUT"
  rm -rf "$TMP"

  # A wrong size is rejected by App Store Connect, so fail here where the cause is obvious.
  GOT="$($IDENTIFY -format '%wx%h' "$OUT")"
  [ "$GOT" = "${CANVAS_W}x${CANVAS_H}" ] || { echo "::error::$OUT is $GOT, expected ${CANVAS_W}x${CANVAS_H}"; exit 1; }
  echo "composed $OUT  ($HEADLINE)"
done
