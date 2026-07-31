#!/usr/bin/env python3
"""Render an App Store version's release notes from the labelled pull requests in a commit range.

Capability `changelog-labels`. The unit is the PULL REQUEST, never the commit: `/ship` labels every
PR `enhancement` / `bug` / `internal`, so the label already answers the only question the store text
cares about — does a user of the app experience this? — which a `feat:`/`fix:` prefix does not
encode. (Measured on `v0.1..`build 542: 66 commits, 38 of them prefixed `feat:`/`fix:`, of which the
overwhelming majority were CI, website, backend and spec work; the 29 PRs behind them split cleanly
into 6 user-facing and 23 `internal`.)

Merges here are rebase-only, so there is no merge commit to read a PR number out of and 66 rebased
commits would have to be mapped back to their PRs by hand. GitHub's release-notes generator does that
association (verified: 29/29 for the range above) and applies `.github/release.yml`, which is the one
place a label maps to a heading.

THE ONE DANGEROUS FAILURE MODE, and the only reason this script reads that config: with no
configuration in effect the generator falls back to a single ungrouped "What's Changed" section
listing EVERY PR in the range — `internal` ones included — i.e. it would publish CI chores to App
Store customers. So any heading carrying items must be one the config declares. Note the guard is on
headings that CARRY ITEMS: an *empty* unrecognized heading is what a range of purely `internal` work
looks like, which is the fallback case below, not an error.

Output is plain text for Apple's `whatsNew`: no markdown, no links, no PR numbers, no authors.

Usage:
  release_notes.py --repo owner/name --tag v0.2 --target <sha> [--previous v0.1]
Env: GH_TOKEN (or GITHUB_TOKEN).
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request

import yaml

# Apple's limit on the `whatsNew` field. Exceeding it is refused rather than truncated: a release
# note cut off mid-sentence is worse than a red run that costs nothing (nothing has been mutated
# yet).
MAX_CHARS = 4000

# What an all-`internal` release says. Apple rejects an empty `whatsNew`, and a build promoted for
# infrastructure work alone is a legitimate release — so this is a committed constant rather than a
# failure, and it says what actually happened instead of the industry's "bug fixes and performance
# improvements" fiction.
FALLBACK = "Under-the-hood improvements and fixes."

# Sections GitHub appends ITSELF, which are not categories and carry items of their own, so the
# heading guard would fail on them. Keep this minimal — it is an exception to the guard, and the
# guard is the point.
GENERATOR_SECTIONS = {"New Contributors"}

# `* <title> by @<user> in <url>` — the generator's item shape. Stripped rather than parsed, so a
# shape change surfaces as leftover `@`/`http` in the title (checked below) instead of silently
# shipping "by @stefanhoelzl in https://..." to the App Store.
CREDIT = re.compile(r"\s+by\s+@\S+\s+in\s+\S+\s*$")

# Conventional-commit prefix on a PR title. Dropped: `type(scope):` is addressed to the repository,
# not to a customer.
PREFIX = re.compile(
    r"^(feat|fix|chore|docs|refactor|perf|test|style|build|ci|revert)(\([^)]*\))?!?:\s*",
    re.IGNORECASE,
)

# A leading "Fix"/"Fixes"/"Fixed" on the remaining title. Applied under EVERY heading, not only the
# bug one, which is what keeps this renderer heading-agnostic (the heading names live in
# `.github/release.yml` alone). A PR titled "Fix ..." and labelled `enhancement` is a mislabelled PR,
# not a case to preserve.
LEADING_FIX = re.compile(r"^(fix|fixes|fixed)\s+", re.IGNORECASE)


def declared_titles(config_path: str) -> list[str]:
    """The category headings `.github/release.yml` declares, in file order.

    Read from the working tree for the guard's allowlist and the output order, while the generator
    applies its own copy of the same file; a run whose two copies disagree fails the heading guard
    rather than emitting a section the repo does not declare.
    """
    with open(config_path, encoding="utf-8") as handle:
        config = yaml.safe_load(handle) or {}
    categories = (config.get("changelog") or {}).get("categories") or []
    titles = [str(category["title"]).strip() for category in categories if category.get("title")]
    if not titles:
        sys.exit(f"{config_path} declares no changelog categories")
    return titles


def generate_notes(repo: str, tag: str, target: str, previous: str | None) -> str:
    """Ask GitHub for the range's release notes (PR titles, grouped and filtered per release.yml)."""
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if not token:
        sys.exit("GH_TOKEN (or GITHUB_TOKEN) is required")

    payload: dict[str, str] = {"tag_name": tag, "target_commitish": target}
    if previous:
        payload["previous_tag_name"] = previous

    request = urllib.request.Request(
        f"https://api.github.com/repos/{repo}/releases/generate-notes",
        data=json.dumps(payload).encode(),
        method="POST",
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "X-GitHub-Api-Version": "2022-11-28",
            "Content-Type": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(request) as response:
            return json.load(response)["body"]
    except urllib.error.HTTPError as error:
        sys.exit(f"generate-notes failed: {error.code} {error.read().decode(errors='replace')}")


def parse(body: str) -> dict[str, list[str]]:
    """Group the generated body into {heading: [item line, ...]} — headings validated by the caller."""
    sections: dict[str, list[str]] = {}
    current: str | None = None
    for raw in body.splitlines():
        line = raw.rstrip()
        if line.startswith("## "):
            current = line[3:].strip()
            sections.setdefault(current, [])
            continue
        if not line.startswith("* "):
            continue  # blank lines, the trailing **Full Changelog** link, prose
        if current is None:
            sys.exit(f"generated notes list an item before any heading: {line!r}")
        sections[current].append(line[2:].strip())
    return sections


def bullet(item: str) -> str:
    """One PR's item line → the sentence a customer reads."""
    title = CREDIT.sub("", item).strip()
    if "@" in title or "http" in title:
        sys.exit(
            f"cannot render the pull-request title from {item!r} — the generator's item shape "
            "changed; refusing to publish author/URL noise to the App Store."
        )
    title = LEADING_FIX.sub("", PREFIX.sub("", title)).strip()
    if not title:
        sys.exit(f"pull-request title rendered empty from {item!r}")
    return title[0].upper() + title[1:] if title[:1].islower() else title


def render(body: str, titles: list[str]) -> str:
    sections = parse(body)

    unknown = {
        title: items
        for title, items in sections.items()
        if items and title not in titles and title not in GENERATOR_SECTIONS
    }
    if unknown:
        sys.exit(
            f"generated notes carry item(s) under the heading(s) {sorted(unknown)}, which "
            f".github/release.yml does not declare (declared: {titles}).\n"
            "An unconfigured generation lists EVERY pull request in the range, `internal` ones "
            "included — refusing rather than publishing that to the App Store."
        )

    blocks = [
        "\n".join([title, *(f"- {bullet(item)}" for item in sections.get(title) or [])])
        for title in titles
        if sections.get(title)  # a release with only features has no "Fixed" heading
    ]
    return "\n\n".join(blocks) if blocks else FALLBACK


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True, help="owner/name")
    parser.add_argument("--tag", required=True, help="the tag being released (need not exist yet)")
    parser.add_argument("--target", required=True, help="the release's commit (the build's origin)")
    parser.add_argument("--previous", help="the previous release's tag; omit for the first release")
    parser.add_argument("--config", default=".github/release.yml")
    args = parser.parse_args()

    body = generate_notes(args.repo, args.tag, args.target, args.previous)
    print(body, file=sys.stderr)  # the raw generation, for the run's log
    notes = render(body, declared_titles(args.config))

    if len(notes) > MAX_CHARS:
        sys.exit(
            f"the derived release notes are {len(notes)} characters, over Apple's {MAX_CHARS} "
            "limit. Shorten the pull-request titles in the range, or set the notes by hand in App "
            "Store Connect and re-run."
        )
    print(notes)


if __name__ == "__main__":
    main()
