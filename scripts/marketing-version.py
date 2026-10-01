#!/usr/bin/env python3
"""Print the marketing version a delivered build carries, in BOTH stores (`docs/deployment.md`).

    MARKETING_VERSION = max(floor, latest vX.Y tag with its minor + 1)

The floor is `iosApp/Configuration/Config.xcconfig`'s; the minor bump is INTEGER (v0.9 → 0.10), and max() compares
(major, minor) TUPLES so a floor of 1.0 beats a derived 0.10. A promoted build's store version is DERIVED from this
value. `ios-build` and `android-build` each run it on a delivering run, so the one rule yields one version for the
same commit on both platforms without a job hand-off between the gates. Needs the release tags fetched.
"""

import re
import subprocess
import sys
from pathlib import Path

XCCONFIG = Path(__file__).resolve().parent.parent / "iosApp/Configuration/Config.xcconfig"


def parse(v):
    m = re.fullmatch(r"(\d+)\.(\d+)", v.strip())
    return (int(m.group(1)), int(m.group(2))) if m else None


def main():
    floor = None
    for line in XCCONFIG.read_text().splitlines():
        if line.startswith("MARKETING_VERSION"):
            floor = parse(line.split("=", 1)[1])
            break
    if floor is None:
        sys.exit("MARKETING_VERSION floor not found or not two-part in Config.xcconfig")

    tags = subprocess.run(
        ["git", "tag", "-l", "v[0-9]*.[0-9]*"], capture_output=True, text=True, check=True
    ).stdout.split()
    versions = [p for p in (parse(t[1:]) for t in tags) if p is not None]
    derived = [(max(versions)[0], max(versions)[1] + 1)] if versions else []

    major, minor = max([floor] + derived)
    print(f"{major}.{minor}")


if __name__ == "__main__":
    main()
