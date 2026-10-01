"""Retrace an Android (R8-obfuscated) Bugsink event against its build's mapping.

Usage:
    python3 retrace.py <event.json> <mapping>

`<event.json>` is a Bugsink event detail (`data` holding the stored Sentry envelope). `<mapping>` is an
extracted `r8-mapping-<build>` artifact directory (holding `mapping.txt`), or the file itself.

The Java frames Sentry stores are the obfuscated names R8 wrote (`gg3.a(SourceFile:12)`). This rebuilds
each exception — and each thread's stack, for an event with no exception — as a Java stack trace and
hands it to R8's own `retrace`, from the R8 that WROTE the mapping: its version is the mapping's
`# compiler_version:` header, and the jar is fetched once from Google's Maven into ~/.cache. Needs
`java` on PATH. Stdlib only.
"""

import json
import os
import subprocess
import sys
import urllib.request
from pathlib import Path

MAVEN = "https://dl.google.com/dl/android/maven2/com/android/tools/r8/{v}/r8-{v}.jar"
CACHE = Path(os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache")) / "snapsync-r8"


def mapping_file(arg):
    p = Path(arg)
    if p.is_dir():
        found = sorted(p.rglob("mapping.txt"))
        if not found:
            sys.exit(f"no mapping.txt under {p}")
        return found[0]
    return p


def r8_jar(mapping):
    version = None
    with open(mapping) as f:
        for line in f:
            if not line.startswith("#"):
                break
            if line.startswith("# compiler_version:"):
                version = line.split(":", 1)[1].strip()
    if not version:
        sys.exit(f"{mapping} names no `# compiler_version:` — not an R8 mapping?")
    jar = CACHE / f"r8-{version}.jar"
    if not jar.exists():
        CACHE.mkdir(parents=True, exist_ok=True)
        print(f"fetching R8 {version} …", file=sys.stderr)
        urllib.request.urlretrieve(MAVEN.format(v=version), jar)
    return jar


def frame_line(fr):
    cls = fr.get("module") or "?"
    fn = fr.get("function") or "?"
    lineno = fr.get("lineno")
    where = f"{fr.get('filename') or 'SourceFile'}:{lineno}" if lineno else (fr.get("filename") or "Unknown Source")
    return f"\tat {cls}.{fn}({where})"


def java_frames(frames):
    # Sentry stores frames OLDEST first; a Java trace reads innermost first. Native frames have no class.
    return [frame_line(fr) for fr in reversed(frames or []) if fr.get("module")]


def blocks(data):
    exceptions = (data.get("exception") or {}).get("values") or []
    if exceptions:
        # Sentry orders a chain cause-first; the thrown exception is LAST, its causes before it.
        lines = []
        for i, ex in enumerate(reversed(exceptions)):
            name = ".".join(p for p in (ex.get("module"), ex.get("type")) if p) or "?"
            head = f"{name}: {ex.get('value')}" if ex.get("value") else name
            lines.append(head if i == 0 else f"Caused by: {head}")
            lines += java_frames((ex.get("stacktrace") or {}).get("frames"))
        yield "exception", lines
    for th in (data.get("threads") or {}).get("values") or []:
        frames = java_frames((th.get("stacktrace") or {}).get("frames"))
        if frames:
            label = f"thread {th.get('name') or th.get('id')}{' (crashed)' if th.get('crashed') else ''}"
            yield label, [f"{label}:"] + frames


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    data = json.load(open(sys.argv[1]))["data"]
    tags = data.get("tags") or {}
    platform = tags.get("platform") if isinstance(tags, dict) else None
    if platform not in (None, "android"):
        sys.exit(f"this event's platform tag is {platform!r}: symbolicate it with symbolicate.py, not retrace")
    mapping = mapping_file(sys.argv[2])
    jar = r8_jar(mapping)
    print(f"dist(build)={data.get('dist')}  mapping={mapping}  r8={jar.name}\n")

    found = False
    for label, lines in blocks(data):
        found = True
        stack = "\n".join(lines) + "\n"
        out = subprocess.run(
            ["java", "-cp", str(jar), "com.android.tools.r8.retrace.Retrace", str(mapping)],
            input=stack, capture_output=True, text=True,
        )
        if out.returncode != 0:
            sys.exit(f"retrace failed on {label}:\n{out.stderr}")
        print(f"== {label} ==")
        print("\n".join(l for l in out.stdout.splitlines() if not l.startswith("Waiting for stack-trace input")))
        print()
    if not found:
        print("this event carries no Java stacktrace (a diagnostic dump or a message event) — nothing to retrace")


if __name__ == "__main__":
    main()
