# /// script
# requires-python = ">=3.11"
# dependencies = ["playwright==1.56.0", "pillow"]
# ///
"""Render the use-case graphic into its committed PNGs (`graphic.py` builds the HTML).

    uv run metadata/graphic/render.py            # every layout
    uv run metadata/graphic/render.py site-hero  # one layout

Run it after changing `graphic.py` or the `tagline` in `metadata/screenshots/en-US.json`, LOOK at the
results, and commit them. Each PNG carries the tagline it was rendered with (a `snapsync-tagline` text
chunk), which `check.py` compares with the JSON so a stale render fails the build. The words are set in the
machine's sans-serif (Adwaita Sans / Inter first), so render on the same kind of machine to avoid a
font-only diff.
"""
import json
import subprocess
import sys
from io import BytesIO
from pathlib import Path

from PIL import Image, PngImagePlugin
from playwright.sync_api import sync_playwright

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
sys.path.insert(0, str(HERE))
import graphic  # noqa: E402

TAGLINE_KEY = "snapsync-tagline"


def tagline():
    return json.loads((ROOT / "metadata/screenshots/en-US.json").read_text())["tagline"]


def launch(p):
    try:
        return p.chromium.launch()
    except Exception:  # the browser build this Playwright expects is not installed yet
        subprocess.run([sys.executable, "-m", "playwright", "install", "chromium"], check=True)
        return p.chromium.launch()


def main(names):
    words = tagline()
    stamp = PngImagePlugin.PngInfo()
    stamp.add_text(TAGLINE_KEY, json.dumps(words, sort_keys=True, ensure_ascii=False))
    with sync_playwright() as p:
        browser = launch(p)
        for name in names:
            L = graphic.LAYOUTS[name]
            page = browser.new_page(viewport={"width": L["w"], "height": L["h"]}, device_scale_factor=L["scale"])
            page.set_content(graphic.page(name, words))
            page.wait_for_timeout(200)  # let filters and 3D layers settle
            clear = L["bg"] is None  # a transparent layout keeps its alpha channel
            shot = Image.open(BytesIO(page.screenshot(omit_background=clear))).convert("RGBA" if clear else "RGB")
            page.close()
            want = (L["w"] * L["scale"], L["h"] * L["scale"])
            if shot.size != want:
                sys.exit(f"{name}: rendered {shot.size}, expected {want}")
            out = ROOT / L["out"]
            out.parent.mkdir(parents=True, exist_ok=True)
            shot.save(out, pnginfo=stamp, optimize=True)
            print(f"wrote {L['out']} ({want[0]}x{want[1]})")
        browser.close()


if __name__ == "__main__":
    main(sys.argv[1:] or list(graphic.LAYOUTS))
