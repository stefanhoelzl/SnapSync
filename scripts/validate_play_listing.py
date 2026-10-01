#!/usr/bin/env python3
"""Validate the RENDERED Google Play listing offline (`docs/deployment.md`, "Listing metadata").

`asc metadata validate` checks the App Store's half of the one listing source; Play has no offline validator, so
this is the Play half: each field present, inside Play's character limits, and with no placeholder left unfilled.
It reads what will be APPLIED — build/metadata/play/<locale>.json, written by scripts/resolve-deployment.py — never
the committed source.

    python3 scripts/validate_play_listing.py [<dir>]      (default: build/metadata/play)

Stdlib only, like the resolver, so it runs on a bare runner.
"""

import json
import pathlib
import sys

# Play Console's limits for a store listing, in characters.
LIMITS = {"title": 30, "shortDescription": 80, "fullDescription": 4000}
REQUIRED = [*LIMITS, "contactWebsite"]


def problems(listing: dict) -> list[str]:
    found = []
    for field in REQUIRED:
        value = listing.get(field)
        if not isinstance(value, str) or not value.strip():
            found.append(f"{field}: missing or empty")
            continue
        if field in LIMITS and len(value) > LIMITS[field]:
            found.append(f"{field}: {len(value)} characters, Play allows {LIMITS[field]}")
        if "{{" in value or "}}" in value:
            found.append(f"{field}: an unfilled placeholder")
    if not listing.get("contactWebsite", "").startswith("https://"):
        found.append("contactWebsite: not an https URL")
    return found


def main(argv: list[str]) -> int:
    directory = pathlib.Path(argv[0] if argv else "build/metadata/play")
    files = sorted(directory.glob("*.json"))
    if not files:
        print(f"error: no rendered Play listing in {directory} — run scripts/resolve-deployment.py first", file=sys.stderr)
        return 1
    failed = False
    for path in files:
        found = problems(json.loads(path.read_text()))
        for problem in found:
            print(f"::error::{path.name}: {problem}")
        print(f"{path.name}: {'INVALID' if found else 'ok'}")
        failed |= bool(found)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
