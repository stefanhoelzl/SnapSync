"""Map an api (Edge Script) Bugsink event's frames back to `api/src` through its bundle's source map.

Usage:
    python3 sourcemap.py <event.json> <bundle>

`<event.json>` is a Bugsink event detail (`data` holding the stored Sentry envelope). `<bundle>` is an
extracted `bundle-<sha>` artifact directory (holding `main.js.map`), or the map file itself.

The api ships as ONE bundled file, so every frame Sentry stores names that file (`/mod.ts`, or the
bundle's own path) at a line and column of the bundle. `deno bundle --sourcemap=external` writes the map
beside it, and deploy.yml archives both as `bundle-<sha>` — the sha is the event's `release`. This decodes
the map (source map v3, base64 VLQ) and prints each frame as `api/src/<file>:<line>:<col>`. Stdlib only.
"""

import json
import sys
from bisect import bisect_right
from pathlib import Path

B64 = {c: i for i, c in enumerate("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/")}


def vlq(segment):
    """The signed integers one base64-VLQ segment encodes."""
    values, shift, value = [], 0, 0
    for char in segment:
        digit = B64[char]
        value += (digit & 31) << shift
        if digit & 32:
            shift += 5
            continue
        values.append(-(value >> 1) if value & 1 else value >> 1)
        shift, value = 0, 0
    return values


def decode(mappings):
    """Per generated line: sorted (generated column, source index, source line, source column)."""
    lines, src, src_line, src_col = [], 0, 0, 0
    for text in mappings.split(";"):
        col, row = 0, []
        for segment in filter(None, text.split(",")):
            fields = vlq(segment)
            col += fields[0]
            if len(fields) >= 4:
                src += fields[1]
                src_line += fields[2]
                src_col += fields[3]
                row.append((col, src, src_line, src_col))
        lines.append(row)
    return lines


def map_file(arg):
    p = Path(arg)
    if p.is_dir():
        found = sorted(p.rglob("main.js.map"))
        if not found:
            sys.exit(f"no main.js.map under {p} — the bundle was archived before its map was (pre api-request-log)")
        return found[0]
    return p


def short(source):
    """The map names sources relative to `api/dist/`: `../src/routes/v2.ts` → `api/src/routes/v2.ts`, and a
    dependency (`…/registry.npmjs.org/hono/4.13.10/dist/hono-base.js`) → `npm:hono@4.13.10/dist/hono-base.js`."""
    if source.startswith("../src/"):
        return "api/" + source[len("../"):]
    marker = "registry.npmjs.org/"
    i = source.find(marker)
    if i >= 0:
        parts = source[i + len(marker):].split("/")
        scoped = parts[0].startswith("@")  # `@sentry/deno/10.76.0/…`
        name = "/".join(parts[:2]) if scoped else parts[0]
        version, rest = parts[2 if scoped else 1], "/".join(parts[3 if scoped else 2:])
        return f"npm:{name}@{version}/{rest}"
    return source


def lookup(lines, sources, lineno, colno):
    """The source position of 1-based bundle `lineno`:`colno`, or None."""
    if not lineno or lineno > len(lines):
        return None
    row = lines[lineno - 1]
    i = bisect_right([seg[0] for seg in row], (colno or 1) - 1) - 1
    if i < 0:
        return None
    _, src, src_line, src_col = row[i]
    return f"{short(sources[src])}:{src_line + 1}:{src_col + 1}"


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    event = json.load(open(sys.argv[1]))
    data = event.get("data", event)
    if isinstance(data, str):
        data = json.loads(data)
    sm = json.load(open(map_file(sys.argv[2])))
    lines, sources = decode(sm["mappings"]), sm["sources"]
    print(f"release={data.get('release')}  (bundle-<release> is the artifact this map must come from)")
    for exc in (data.get("exception") or {}).get("values", []):
        print(f"\n{exc.get('type')}: {exc.get('value')}")
        # Sentry orders frames oldest first; print innermost first, as a stack reads.
        for frame in reversed((exc.get("stacktrace") or {}).get("frames", [])):
            where = lookup(lines, sources, frame.get("lineno"), frame.get("colno"))
            fn = frame.get("function") or "?"
            raw = f"{frame.get('filename') or frame.get('abs_path')}:{frame.get('lineno')}:{frame.get('colno')}"
            print(f"  at {fn:<28} {where or '<not in the map>'}   ({raw})")


if __name__ == "__main__":
    main()
