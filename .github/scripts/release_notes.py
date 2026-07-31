#!/usr/bin/env python3
"""Render an App Store version's release notes from the labelled pull requests in a commit range.

Capability `changelog-labels`. The unit is the PULL REQUEST, never the commit: `/ship` labels every
PR `enhancement` / `bug` / `internal`, so the label already answers the only question the store text
cares about — does a user of the app experience this? — which a `feat:`/`fix:` prefix does not
encode. (Measured on `v0.1..`build 542: 66 commits, 38 of them prefixed `feat:`/`fix:`, of which the
overwhelming majority were CI, website, backend and spec work; the 29 PRs behind them split cleanly
into 6 user-facing and 23 `internal`.)

WHY THIS DOES NOT ASK GITHUB TO RENDER THE NOTES. The obvious implementation — `POST
/repos/{repo}/releases/generate-notes` — reads its `.github/release.yml` configuration from the
**`target_commitish`**, not from the default branch (measured 2026-07-31; the generation says so
itself in a leading HTML comment). That makes the changelog's shape a property of the commit being
released: a build whose commit predates the configuration renders as one ungrouped section listing
EVERY pull request, `internal` ones included, and no edit to `main` can ever change what those
already-built bits contain. Build 542 became permanently un-promotable that way. So the association
is done here instead, against the RANGE: nothing this script reads comes out of the commits it
describes, and every build App Store Connect holds stays promotable.

Merges here are rebase-only, so there is no merge commit to read a PR number out of and the range's
commits carry identities the PR's own branch never held. GitHub's GraphQL `associatedPullRequests`
resolves those anyway (measured: 66/66 commits over `v0.1..f936b9fc`, to 29 distinct PRs, none
ambiguous) — it is the one part of the old approach worth keeping, and it is available without
letting GitHub decide the grouping.

Output travels on two channels, deliberately: the customer-facing changelog goes to `--changelog`
(plain text for Apple's `whatsNew`: no markdown, no links, no PR numbers, no authors), while the
operator-facing report goes to STDOUT as markdown, for `>> $GITHUB_STEP_SUMMARY`. The report carries
the rendered changelog too, so one place shows both what will be published and what was withheld
from it. Nothing is written to stderr on success.

Usage:
  release_notes.py --repo owner/name --target <sha> [--previous v0.1] [--version X.Y]
                   [--changelog <path>] >> "$GITHUB_STEP_SUMMARY"
Omit --changelog to preview: the report alone is printed and no file is written.
Env: GH_TOKEN (or GITHUB_TOKEN).
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request

# Apple's limit on the `whatsNew` field. Exceeding it is refused rather than truncated: a release
# note cut off mid-sentence is worse than a red run that costs nothing (nothing has been mutated
# yet, and this runs before the first App Store Connect call).
MAX_CHARS = 4000

# What an all-`internal` release says. Apple rejects an empty `whatsNew`, and a build promoted for
# infrastructure work alone is a legitimate release — so this is a committed constant rather than a
# failure, and it says what actually happened instead of the industry's "bug fixes and performance
# improvements" fiction.
FALLBACK = "Under-the-hood improvements and fixes."

# THE SINGLE PLACE a changelog label maps to a heading, and the single place `internal` is excluded.
# `/ship` puts exactly one of the three labels on every PR (capability `ship-command`) and
# `check-label.yml` fails a PR carrying none, so the set below is total.
#
# The headings are read by App Store CUSTOMERS — hence "New"/"Fixed" rather than "Features"/"Bug
# Fixes". Order is output order.
#
# ⚠️ NO CATCH-ALL CATEGORY, deliberately. A catch-all would publish an unlabelled PR's raw
# engineering title into the customer-facing listing; without one such a PR appears under no heading
# and is named in the report instead, and the PR gate — not a residual bucket — is the answer to a
# missing label.
#
# This lives beside the derivation rather than in a configuration file so that it is loaded from the
# same place the derivation is. A file at `.github/release.yml` is exactly what made the changelog
# depend on the released commit; a file anywhere else would still be a second artifact for a
# two-entry mapping with one reader.
EXCLUDE = frozenset({"internal"})
CATEGORIES: tuple[tuple[str, frozenset[str]], ...] = (
    ("New", frozenset({"enhancement"})),
    ("Fixed", frozenset({"bug"})),
)

# Conventional-commit prefix on a PR title. Dropped: `type(scope):` is addressed to the repository,
# not to a customer.
PREFIX = re.compile(
    r"^(feat|fix|chore|docs|refactor|perf|test|style|build|ci|revert)(\([^)]*\))?!?:\s*",
    re.IGNORECASE,
)

# A leading "Fix"/"Fixes"/"Fixed" on the remaining title. Applied under EVERY heading, not only the
# bug one, which is what keeps this renderer heading-agnostic. A PR titled "Fix ..." and labelled
# `enhancement` is a mislabelled PR, not a case to preserve.
LEADING_FIX = re.compile(r"^(fix|fixes|fixed)\s+", re.IGNORECASE)

# Commits per GraphQL request. The measured 66-commit range costs three round-trips; a chunk large
# enough to matter would risk the query-complexity limit for no useful gain.
CHUNK = 25

GRAPHQL = "https://api.github.com/graphql"


def token() -> str:
    value = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if not value:
        sys.exit("GH_TOKEN (or GITHUB_TOKEN) is required")
    return value


def range_commits(previous: str | None, target: str) -> list[str]:
    """The range's commits, oldest first. `previous` is None for the first release (open-ended)."""
    spec = f"{previous}..{target}" if previous else target
    result = subprocess.run(
        ["git", "log", "--format=%H", spec],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        sys.exit(f"cannot list the commit range '{spec}': {result.stderr.strip()}")
    commits = result.stdout.split()
    if not commits:
        sys.exit(f"the commit range '{spec}' is empty — nothing to derive a changelog from")
    return list(reversed(commits))


def query(payload: dict, auth: str) -> dict:
    request = urllib.request.Request(
        GRAPHQL,
        data=json.dumps(payload).encode(),
        method="POST",
        headers={
            "Authorization": f"Bearer {auth}",
            "Content-Type": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(request) as response:
            body = json.load(response)
    except urllib.error.HTTPError as error:
        sys.exit(f"GraphQL request failed: {error.code} {error.read().decode(errors='replace')}")
    if body.get("errors"):
        sys.exit(f"GraphQL request returned errors: {json.dumps(body['errors'])}")
    return body["data"]


def associate(repo: str, commits: list[str], auth: str) -> tuple[list[dict], list[str]]:
    """Resolve the range's commits to the pull requests merged into the repo's default branch.

    Returns (pull requests ordered by number, commits that resolved to none of them). A pull request
    contributes several commits, so it is deduplicated by number; a commit reachable only from
    another branch's pull request contributes nothing, because the promoted build's origin commit is
    on the default branch by construction and only a PR that reached it can describe the range.
    """
    owner, _, name = repo.partition("/")
    pulls: dict[int, dict] = {}
    unassociated: list[str] = []
    for start in range(0, len(commits), CHUNK):
        chunk = commits[start : start + CHUNK]
        aliases = " ".join(
            f'c{i}: object(oid:"{sha}"){{... on Commit{{oid associatedPullRequests(first:20)'
            "{nodes{number title merged baseRefName labels(first:50){nodes{name}}}}}}"
            for i, sha in enumerate(chunk)
        )
        data = query(
            {
                "query": (
                    f'{{repository(owner:"{owner}",name:"{name}")'
                    f"{{defaultBranchRef{{name}} {aliases}}}}}"
                )
            },
            auth,
        )
        repository = data.get("repository")
        if not repository:
            sys.exit(f"repository '{repo}' not found, or the token cannot read it")
        base = (repository.get("defaultBranchRef") or {}).get("name")
        if not base:
            sys.exit(f"cannot read the default branch of '{repo}'")
        for i, sha in enumerate(chunk):
            commit = repository.get(f"c{i}")
            nodes = (commit or {}).get("associatedPullRequests", {}).get("nodes", [])
            merged = [n for n in nodes if n.get("merged") and n.get("baseRefName") == base]
            if not merged:
                unassociated.append(sha)
                continue
            for node in merged:
                pulls[node["number"]] = node
    return [pulls[number] for number in sorted(pulls)], unassociated


def classify(pulls: list[dict]) -> tuple[dict[str, list[dict]], list[dict], list[dict]]:
    """Split the range's pull requests into (published by heading, excluded, uncategorized).

    An excluded label wins over a category label: a PR carrying both is a mislabelling, and the
    conservative reading of "this is internal" is to withhold it from customers rather than publish
    it. Otherwise the first category in declaration order whose labels the PR carries wins.
    """
    published: dict[str, list[dict]] = {title: [] for title, _ in CATEGORIES}
    excluded: list[dict] = []
    uncategorized: list[dict] = []
    for pull in pulls:
        labels = {node["name"] for node in pull.get("labels", {}).get("nodes", [])}
        if labels & EXCLUDE:
            excluded.append(pull)
            continue
        for title, wanted in CATEGORIES:
            if labels & wanted:
                published[title].append(pull)
                break
        else:
            uncategorized.append(pull)
    return published, excluded, uncategorized


def bullet(title: str) -> str:
    """One pull request's title → the sentence a customer reads."""
    rendered = LEADING_FIX.sub("", PREFIX.sub("", title.strip())).strip()
    if not rendered:
        sys.exit(f"pull-request title rendered empty from {title!r}")
    return rendered[0].upper() + rendered[1:] if rendered[:1].islower() else rendered


def changelog(published: dict[str, list[dict]]) -> str:
    blocks = [
        "\n".join([title, *(f"- {bullet(pull['title'])}" for pull in published[title])])
        for title, _ in CATEGORIES
        if published[title]  # a release with only features has no "Fixed" heading
    ]
    return "\n\n".join(blocks) if blocks else FALLBACK


def report(
    version: str | None,
    notes: str,
    published: dict[str, list[dict]],
    excluded: list[dict],
    uncategorized: list[dict],
    unassociated: list[str],
) -> str:
    """The operator-facing markdown: what will be published, and everything that will not."""
    count = sum(len(pulls) for pulls in published.values())
    total = count + len(excluded) + len(uncategorized)
    lines = [
        f"### Release notes — {version}" if version else "### Release notes",
        "",
        "```",
        notes,
        "```",
        "",
        f"{total} pull request(s) in range — {count} published, {len(excluded)} internal, "
        f"{len(uncategorized)} uncategorized",
    ]
    # Anomalies first, and absent entirely when there are none: the top of the report is empty
    # exactly when nothing is wrong.
    if uncategorized or unassociated:
        lines += ["", "#### ⚠️ Uncategorized — excluded from the changelog", ""]
        lines += [f"- #{pull['number']} {pull['title']} (no changelog label)" for pull in uncategorized]
        lines += [f"- commit `{sha[:8]}` (no pull request merged into the default branch)" for sha in unassociated]
    if excluded:
        lines += ["", f"#### Internal — not published ({len(excluded)})", ""]
        lines += [f"- #{pull['number']} {pull['title']}" for pull in excluded]
    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True, help="owner/name")
    parser.add_argument("--target", required=True, help="the release's commit (the build's origin)")
    parser.add_argument("--previous", help="the previous release's tag; omit for the first release")
    parser.add_argument("--version", help="the store version, for the report's heading")
    parser.add_argument("--changelog", help="write the plain-text changelog here; omit to preview")
    args = parser.parse_args()

    auth = token()
    commits = range_commits(args.previous, args.target)
    pulls, unassociated = associate(args.repo, commits, auth)
    published, excluded, uncategorized = classify(pulls)
    notes = changelog(published)

    if len(notes) > MAX_CHARS:
        sys.exit(
            f"the derived release notes are {len(notes)} characters, over Apple's {MAX_CHARS} "
            "limit. Shorten the pull-request titles in the range, or set the notes by hand in App "
            "Store Connect and re-run."
        )

    if args.changelog:
        with open(args.changelog, "w", encoding="utf-8") as handle:
            handle.write(notes + "\n")
    sys.stdout.write(report(args.version, notes, published, excluded, uncategorized, unassociated))


if __name__ == "__main__":
    main()
