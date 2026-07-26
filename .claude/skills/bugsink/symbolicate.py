#!/usr/bin/env python3
"""Offline symbolication of a Bugsink/Sentry cocoa event against extracted dSYMs.

Run it in an env that has the `symbolic` library (Sentry's own symbolication core):

    uvx --from symbolic python symbolicate.py <event.json> <dsym_dir>

`<event.json>` is the body of GET /api/canonical/0/events/<id>/ (it has a top-level
`data` holding the stored Sentry envelope). `<dsym_dir>` is an extracted
`dsyms-<build>` artifact directory (contains `*.dSYM/Contents/Resources/DWARF/*`).

Why this and not atos: `symbolic` reads the dSYM DWARF and does debug-id matching +
address resolution natively on Linux — no Mac, no atos, no hand-rolled load-address
math. It is the same core Sentry runs server-side.

Feasibility note: this repo's only stored event at authoring time was a
WatchdogTermination, which carries NO stacktrace/addresses by design, so the exact
field shape below (debug_meta.images + per-frame instruction_addr) is written against
the standard Sentry-cocoa payload and must be sanity-checked on the FIRST real native
crash. If a field name differs, fix it here — the shape is the only assumption.
"""
import json
import os
import sys

from symbolic import Archive, find_best_instruction, normalize_debug_id


def _addr(v):
    if v is None:
        return None
    if isinstance(v, int):
        return v
    return int(v, 16) if str(v).startswith("0x") else int(v)


def load_objects(dsym_dir):
    """Map normalized debug_id -> symbolic Object for every DWARF found under dsym_dir."""
    objs = {}
    for root, _dirs, files in os.walk(dsym_dir):
        # dSYM DWARF lives at *.dSYM/Contents/Resources/DWARF/<binary>; also accept
        # bare object files handed in directly.
        for name in files:
            path = os.path.join(root, name)
            try:
                archive = Archive.open(path)
            except Exception:
                continue
            for obj in archive.iter_objects():
                try:
                    did = str(normalize_debug_id(str(obj.debug_id)))
                except Exception:
                    did = str(obj.debug_id)
                objs[did.lower()] = obj
    return objs


def image_for(addr, images):
    for img in images:
        base = _addr(img.get("image_addr"))
        size = img.get("image_size") or 0
        if base is not None and base <= addr < base + size:
            return img
    return None


def fmt_location(loc):
    fn = getattr(loc, "function_name", None) or getattr(loc, "symbol", None)
    filename = getattr(loc, "full_path", None) or getattr(loc, "filename", None)
    line = getattr(loc, "line", None)
    where = f"  ({filename}:{line})" if filename else ""
    return f"{fn or '<unknown>'}{where}"


def symbolicate_frames(frames, images, objs):
    """frames are Sentry order: oldest first, crashing instruction LAST."""
    out = []
    n = len(frames)
    symcache_cache = {}
    for i, frame in enumerate(frames):
        iaddr = _addr(frame.get("instruction_addr"))
        pkg = frame.get("package") or ""
        base_label = frame.get("function") or (f"{os.path.basename(pkg)} {hex(iaddr)}" if iaddr is not None else "<no addr>")
        if iaddr is None:
            out.append(f"#{i:<3} {base_label}")
            continue
        img = image_for(iaddr, images)
        if img is None:
            out.append(f"#{i:<3} {hex(iaddr)}  <no image>  {base_label}")
            continue
        did = str(img.get("debug_id", "")).lower()
        try:
            did = str(normalize_debug_id(did))
        except Exception:
            pass
        obj = objs.get(did)
        if obj is None:
            out.append(f"#{i:<3} {hex(iaddr)}  {os.path.basename(img.get('code_file', '?'))}  (dSYM {did} missing)  {base_label}")
            continue
        if did not in symcache_cache:
            symcache_cache[did] = obj.make_symcache()
        symcache = symcache_cache[did]
        rel = iaddr - _addr(img.get("image_addr"))
        crashing = i == n - 1  # last frame is the crashing instruction in Sentry order
        try:
            rel = find_best_instruction(rel, arch=obj.arch, crashing_frame=crashing)
        except Exception:
            pass
        locs = symcache.lookup(rel)
        if not locs:
            out.append(f"#{i:<3} {hex(iaddr)}  {os.path.basename(img.get('code_file', '?'))}  <symbol not found>")
            continue
        # innermost first; inlined frames come back as multiple locations
        for j, loc in enumerate(locs):
            prefix = f"#{i:<3}" if j == 0 else "    (inlined)"
            out.append(f"{prefix} {fmt_location(loc)}")
    return out


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(2)
    event = json.load(open(sys.argv[1]))
    dsym_dir = sys.argv[2]
    data = event.get("data", event)
    dist = data.get("dist")
    images = (data.get("debug_meta") or {}).get("images") or []

    threads_and_excs = []
    for v in (data.get("exception") or {}).get("values", []):
        threads_and_excs.append((f"exception: {v.get('type')}: {v.get('value')}", v.get("stacktrace") or {}))
    for t in (data.get("threads") or {}).get("values", []):
        if t.get("stacktrace"):
            crashed = " (crashed)" if t.get("crashed") else ""
            threads_and_excs.append((f"thread {t.get('id')}{crashed}", t["stacktrace"]))

    if not images:
        print(f"NO debug_meta.images in this event (dist={dist}).")
        print("This event has no symbolicatable native frames — e.g. a WatchdogTermination")
        print("or a pure message/handled event. Nothing to symbolicate.")
        return

    objs = load_objects(dsym_dir)
    print(f"dist(build)={dist}  images={len(images)}  dSYM objects loaded={len(objs)}\n")

    any_frames = False
    for label, st in threads_and_excs:
        frames = st.get("frames") or []
        if not frames:
            continue
        any_frames = True
        print(f"=== {label} ===")
        for line in symbolicate_frames(frames, images, objs):
            print(line)
        print()
    if not any_frames:
        print("Event has images but no frames in any exception/thread stacktrace.")


if __name__ == "__main__":
    main()
