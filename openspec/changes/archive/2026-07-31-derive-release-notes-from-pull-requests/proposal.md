## Why

The App Store release derives its "What's New" text by asking GitHub's `releases/generate-notes` API
to render the range, then guarding the result. GitHub reads `.github/release.yml` **from the
`target_commitish`** — measured 2026-07-31, and stated by the generation's own body header
(`generated using configuration in .github/release.yml at <sha>`). The changelog's *shape* is
therefore a property of the commit being released, not of the release process, and a build whose
origin commit predates that file renders as one ungrouped `What's Changed` section listing every
pull request in the range, `internal` ones included. The heading guard correctly refuses to publish
that, so the promote fails (run 30638460360, build 542) and **the build is un-promotable forever** —
no edit to `main` can change what an already-built commit contains.

This is not a transitional wart. Every future changelog-affecting decision inherits it: the
generator applies whatever configuration the shipped commit happened to carry, so a renamed heading,
a new label, or a corrected exclusion reaches only builds produced after it merged. The release
process cannot be fixed forward, only waited out.

Making an arbitrary already-tested build promotable is the point of this capability — the build is
picked by number precisely so the operator ships the exact bits they validated — and today that
promise holds only for builds young enough to carry the configuration.

## What Changes

- **The derivation stops asking GitHub to render anything.** It enumerates the commit range locally
  (`git log <previous-tag>..<origin-commit>`), resolves each commit to its pull request through
  GitHub's GraphQL `associatedPullRequests` (which resolves rebased commits — measured 66/66 over
  `v0.1..f936b9fc`), and groups the resulting pull requests by their labels itself. The changelog
  becomes a function of the range and the release process, never of the released commit's contents.
- **BREAKING (operationally, in the right direction): any build is promotable.** The constraint "a
  build whose origin commit predates `.github/release.yml` cannot be promoted" is removed. Build 542
  renders the same six customer-facing bullets as build 545.
- **`.github/release.yml` is deleted.** With GitHub out of the loop it had exactly one consumer and
  one editor, at a path whose whole meaning is "GitHub reads this". The label→heading mapping and the
  `internal` exclusion move into `release_notes.py` as a table — the single place a label maps to a
  heading, unchanged as an invariant. The `pyyaml` install in the release path goes with it.
- **Uncategorized work is excluded and reported, not fatal.** A pull request carrying no changelog
  label, or a commit resolving to no pull request merged into `main`, no longer fails the
  derivation — `check-label` is a required check, so anything uncategorized is pre-gate history and
  must not block a release. It is reported instead.
- **The run summary reconciles the range.** The derivation writes the customer changelog to a file
  and emits a human report on stdout — the rendered notes, the counts
  (`N pull requests — P published, I internal, U uncategorized`), the `internal` roster, and the
  uncategorized anomalies — which the workflow appends to the job summary. The step log stays clean.
- **The heading guard and the markdown parsing are deleted.** Both existed only because the input was
  GitHub-rendered markdown whose configuration was invisible. Grouping structured pull-request data
  under a table the script owns cannot produce an undeclared heading.

## Capabilities

### New Capabilities

<!-- none -->

### Modified Capabilities

- `changelog-labels`: the derivation's mechanism and its failure modes. The changelog is derived from
  the range's pull requests resolved by commit association rather than from a rendered document;
  the committed mapping file is replaced by the derivation's own table; an uncategorized pull request
  is excluded-and-reported rather than fatal; and the derivation emits a reconciliation report
  alongside the changelog.
- `ios-appstore-release`: the release-notes requirement gains the guarantee that the derivation does
  not depend on the promoted commit's *contents* — so every build App Store Connect holds is
  promotable — and states that the run summary carries the reconciliation report beside the notes.
  (The "must carry the configuration" constraint was never written into this spec; it lived in
  `CLAUDE.md` and in the mechanism itself, and both are corrected here.)

## Impact

- `.github/scripts/release_notes.py` — rewritten: GraphQL association and local range enumeration
  replace the `generate-notes` call; `parse()`, the credit-shape guard, and the heading guard are
  deleted; the label→heading table is inlined; output becomes `--changelog <path>` plus a report on
  stdout.
- `.github/release.yml` — **deleted**.
- `.github/workflows/ios-appstore-promote.yml` — the derive step becomes one command plus
  `>> "$GITHUB_STEP_SUMMARY"`; `pyyaml` leaves the install step; `pull-requests: read` joins the
  permissions block (the default token needs it for GraphQL pull-request reads, where the
  `generate-notes` endpoint needed only `contents`).
- `.github/workflows/check-label.yml` — header comment, which names the deleted file.
- `CLAUDE.md` — the release-notes paragraph, including the warning that a build predating the
  configuration cannot be promoted, and the runbook gains the local preview command.
- No product code, no module, and no App Store Connect credential is touched. The change is confined
  to the release pipeline.
