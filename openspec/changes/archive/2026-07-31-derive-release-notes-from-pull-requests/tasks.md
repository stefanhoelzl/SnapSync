## 1. Rewrite the derivation

- [x] 1.1 Replace `generate_notes()` in `.github/scripts/release_notes.py` with range enumeration:
      `git log --format=%H <previous>..<target>` (or `git log <target>` when no previous tag), via
      `subprocess`, failing loudly if the range cannot be listed.
- [x] 1.2 Add the GraphQL resolver: batch the shas (~25 per query) into an aliased
      `repository { object(oid:) { ... on Commit { oid associatedPullRequests(first:10) { nodes {
      number title merged baseRefName labels(first:20) { nodes { name } } } } } } }` query against
      `GH_TOKEN`, failing loudly on an HTTP or GraphQL-level error.
- [x] 1.3 Reduce commits to pull requests: keep associations with `merged == true` and
      `baseRefName == "main"`, union across the range deduped by number, and record every commit that
      yields none as an unassociated-commit anomaly.
- [x] 1.4 Inline the mapping as `CATEGORIES` (ordered `(heading, {labels})`) and `EXCLUDE`
      (`{"internal"}`), carrying the doctrine comment from `.github/release.yml` (why `New`/`Fixed`
      and not `Features`/`Bug Fixes`; why no catch-all). Delete `declared_titles()`, the `--config`
      argument, and the `yaml` import.
- [x] 1.5 Classify each pull request: excluded if it carries any `EXCLUDE` label (exclusion wins over
      a category label); otherwise the first `CATEGORIES` entry whose labels it carries; otherwise
      uncategorized.
- [x] 1.6 Delete `parse()`, the `CREDIT` regex, the `GENERATOR_SECTIONS` set, and the heading guard in
      `render()` — all four exist only to police GitHub-rendered markdown.
- [x] 1.7 Keep unchanged: `bullet()`'s `PREFIX`/`LEADING_FIX` stripping and capitalization, `FALLBACK`,
      and the `MAX_CHARS` refusal (applied to the changelog only, not the report). Order bullets by
      ascending pull-request number.

## 2. Split the outputs

- [x] 2.1 Replace `--tag` with `--version` (used only in the report's heading) and add
      `--changelog <path>`; write the plain-text changelog to that path.
- [x] 2.2 Emit the report on **stdout**: `### Release notes — <version>`, the changelog in a fenced
      block, the counts line (`N pull requests — P published, I internal, U uncategorized`), the
      `internal` roster, and the uncategorized block (pull requests and unassociated commit shas)
      omitted entirely when empty.
- [x] 2.3 Update the module docstring to describe the new mechanism, the two output channels, and the
      local preview invocation; drop every claim about `generate-notes` and `.github/release.yml`.

## 3. Wire the workflow

- [x] 3.1 In `.github/workflows/ios-appstore-promote.yml`, collapse the *Derive the release notes* step
      to the `git describe` lookup plus one `release_notes.py … --changelog "$NOTES_FILE" >>
      "$GITHUB_STEP_SUMMARY"` invocation; remove the hand-built summary block and the `cat`.
- [x] 3.2 Add `pull-requests: read` to the job's `permissions` block, with a comment saying why
      (`generate-notes` was a `contents` endpoint; GraphQL pull-request reads are not).
- [x] 3.3 Drop `pyyaml` from the *Install the App Store Connect client* step and update its comment.
- [x] 3.4 Update the step's rationale comment: the guard is no longer "an unconfigured generation lists
      every PR" but "the derivation is independent of the promoted commit, and reports what it could
      not categorize".

## 4. Retire the configuration file

- [x] 4.1 Delete `.github/release.yml`.
- [x] 4.2 Update `.github/workflows/check-label.yml`'s header comment, which cites the deleted file for
      the no-catch-all rationale.
- [x] 4.3 `grep -rn "release\.yml"` outside `openspec/changes/archive/` and fix every surviving
      reference.

## 5. Documentation

- [x] 5.1 Rewrite CLAUDE.md's release-notes paragraph: remove the warning that a build predating
      `.github/release.yml` cannot be promoted, state that any uploaded build is promotable, and name
      the script's table as the one place a label maps to a heading.
- [x] 5.2 Add the local preview command to the release runbook:
      `GH_TOKEN=$(gh auth token) python3 .github/scripts/release_notes.py --repo … --target <sha>
      --previous vX.Y --version X.Y --changelog /dev/stdout`.

## 6. Acceptance

- [x] 6.1 Differential run over `v0.1..f936b9fc` (build 542) — must render exactly the six known-good
      bullets and report `29 pull requests — 6 published, 23 internal, 0 uncategorized`.
- [x] 6.2 Differential run over `v0.1..6acb714c` (build 545) — must render the identical six bullets,
      proving the derivation no longer varies with the target commit.
- [x] 6.3 Confirm the report renders as intended when appended to a summary (fenced block intact,
      empty uncategorized section absent), and that nothing reaches stderr on success.
- [x] 6.4 Byte-check the changelog file against the previously published shape (headings, `- ` bullets,
      no trailing whitespace) before it is handed to `asc_release_notes_apply.sh`.

## 7. Ship

- [x] 7.1 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict`.
- [ ] 7.2 Branch → PR → `/ship` with the `internal` label (no customer-visible behavior changes).
- [ ] 7.3 After merge, promote build 542 (`gh workflow run ios-appstore-promote.yml -f
      build_number=542`) and confirm the summary's notes match 6.1 before deciding on `submit`.
