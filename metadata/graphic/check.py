"""Fail when a committed render of the use-case graphic no longer carries the tagline in
`metadata/screenshots/en-US.json` — i.e. the words changed and nobody re-ran `render.py`.

    python3 metadata/graphic/check.py

Standard library only (it runs in ci.yml's `metadata` gate, which installs nothing): reads each PNG's
`snapsync-tagline` text chunk, which `render.py` writes.
"""
import json
import struct
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
sys.path.insert(0, str(HERE))
from graphic import LAYOUTS  # noqa: E402

KEY = b"snapsync-tagline"


def text_chunks(path: Path) -> dict:
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError(f"{path} is not a PNG")
    out, at = {}, 8
    while at < len(data):
        (length,) = struct.unpack(">I", data[at:at + 4])
        kind = data[at + 4:at + 8]
        body = data[at + 8:at + 8 + length]
        if kind == b"tEXt" and b"\0" in body:
            k, v = body.split(b"\0", 1)
            out[k] = v.decode("latin-1")
        elif kind == b"iTXt" and b"\0" in body:
            k, rest = body.split(b"\0", 1)
            # compression flag, method, language\0, translated keyword\0, text
            _, _, rest = rest[0], rest[1], rest[2:]
            _, rest = rest.split(b"\0", 1)
            _, text = rest.split(b"\0", 1)
            out[k] = text.decode("utf-8")
        at += 12 + length
    return out


def main() -> int:
    want = json.loads((ROOT / "metadata/screenshots/en-US.json").read_text())["tagline"]
    stale = []
    for name, layout in LAYOUTS.items():
        png = ROOT / layout["out"]
        if not png.exists():
            stale.append(f"{layout['out']}: missing")
            continue
        got = text_chunks(png).get(KEY)
        if got is None or json.loads(got) != want:
            stale.append(f"{layout['out']}: rendered with {got or 'no tagline stamp'}")
    if stale:
        print("The use-case graphic is stale — re-render the graphic: uv run metadata/graphic/render.py", file=sys.stderr)
        for line in stale:
            print("  " + line, file=sys.stderr)
        return 1
    print(f"graphic: {len(LAYOUTS)} renders carry the current tagline")
    return 0


if __name__ == "__main__":
    sys.exit(main())
