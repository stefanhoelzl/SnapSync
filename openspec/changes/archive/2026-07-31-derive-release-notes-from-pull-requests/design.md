## Context

`changelog-labels` shipped two days ago (`changes/archive/2026-07-31-derive-release-notes-from-labels`)
and has never completed a release. Its derivation calls GitHub's `POST /repos/{repo}/releases/generate-notes`,
which associates the range's commits with their pull requests, applies `.github/release.yml`, and
returns rendered markdown; `release_notes.py` then parses that markdown, guards it, and strips it to
plain text.

The measurement that forces this change: **GitHub reads the configuration from `target_commitish`**,
not from the default branch. Same range, two targets:

| `target_commitish` | headings returned |
| --- | --- |
| `f936b9fc` (build 542) | `## What's Changed` — 29 pull requests, ungrouped, `internal` included |
| `6acb714c` (build 545) | `## What's Changed` / `### New` / `### Fixed` — 6 pull requests |

`f936b9fc` is the commit immediately before the one that added `.github/release.yml`. The generation's
own body header states the rule: `Release notes generated using configuration in .github/release.yml
at 6acb714c…`. So the heading guard fired exactly as designed — an unconfigured generation would have
published 23 `internal` pull requests to the App Store listing — and build 542 became permanently
un-promotable, because nothing merged to `main` can change what an already-built commit contains.

The capability's premise is that the operator promotes **the exact bits they validated on TestFlight**,
chosen by build number. A derivation whose correctness depends on the age of those bits contradicts
that premise, and does so for every future changelog decision, not just this one.

The replacement rests on a second measurement: GitHub's GraphQL `associatedPullRequests` resolves
**rebased** commits to their pull requests. Over `v0.1..f936b9fc` — the range build 542 needs —
66/66 commits resolved, to 29 distinct pull requests, none ambiguous, all 29 carrying exactly one
changelog label, yielding the same six customer-facing pull requests (#149, #151, #156, #157, #162,
#166) that the *working* build's configured generation produced. Merges here are rebase-only, so this
was the open question; it is answered.

## Goals / Non-Goals

**Goals:**

- The changelog for a range is a function of **the range and the release process**, never of the
  released commit's contents. Any build `ios-deliver` uploaded is promotable.
- One place maps a changelog label to a heading, and one place excludes `internal`.
- A release is never blocked by history that predates the label gate, and never silently drops
  something customer-facing without saying so.
- The run summary reconciles the range: every pull request in it is accounted for as published,
  internal, or uncategorized.

**Non-Goals:**

- Changing which labels exist, what `/ship` applies, or the `check-label` gate. The vocabulary is
  settled; only the derivation changes.
- Publishing a GitHub Release or committing a `CHANGELOG.md`. The tags plus the labelled pull
  requests remain the history, and the store listing remains the only rendering.
- Making a build promotable more than once. The `vX.Y` tag guard and the single-shot property are
  untouched.
- Rescuing builds stranded by an out-of-order promote (below).

## Decisions

### D1 — Resolve pull requests ourselves; delete the `generate-notes` call

`git log <previous>..<target>` enumerates the range locally (the workflow already checks out with
`fetch-depth: 0` and `fetch-tags: true` for the tag guard and the tagging step), and a batched GraphQL
query resolves each commit to `{number, title, labels}`. Grouping happens against a table the script
owns.

This is the whole fix: the input becomes structured data about the *range*, and nothing in the
pipeline consults the released commit's tree.

*Alternatives considered.*
**(a) Keep `generate-notes`, force the unconfigured shape** by passing `configuration_file_path` at a
path documented to never exist, then parse pull-request numbers out of the item URLs and fetch labels
separately. Works, and keeps GitHub's association — but it rests on a magic absent path, still parses
markdown, and still leaves the heading/credit guards alive to protect against a rendering we no longer
want. Rejected as strictly more machinery for the same result.
**(b) Leave the mechanism alone** and require the operator to promote only builds young enough to
carry the configuration. Rejected: it declines the capability's premise, and the constraint compounds
with every future changelog decision.
**(c) Match pull requests by `merge_commit_sha`** over the closed-PR list instead of per-commit
association. Rejected: under rebase merges `merge_commit_sha` is the *last* rebased commit, so a
multi-commit pull request matches only via one of its commits and the mapping is fragile in exactly
the case that motivated using pull requests at all.

### D2 — A commit's pull requests are filtered to `merged && baseRefName == "main"`, then unioned

The origin commit is resolved from an `ios.yml` run with `branch=main`, so every commit in the range
is on `main` and only a pull request that reached `main` can describe it. Filtering to merged-into-main
pull requests and unioning across the range (deduped by number) removes the ambiguity case without a
tiebreak: a pull request reachable only from a side branch cannot contribute, and a pull request that
did reach `main` is in the set via its own commits regardless. Zero commits in the measured range had
more than one association.

*Alternative considered:* fail on ambiguity. Rejected as inconsistent with D3 and as a hard failure on
a situation that has never occurred.

### D3 — Uncategorized work is excluded and reported, not fatal

A pull request carrying none of the changelog labels, or a commit resolving to no merged-into-`main`
pull request, is left out of the changelog and listed in the report; the release proceeds.

`check-label` is a **required status check**, so every pull request merged since it landed carries a
label by construction. Anything uncategorized is therefore pre-gate history, and failing a release on
it would block shipping present work on account of past work — while the operator's remedy (labels are
editable after merge) is available either way.

The measured range needs this zero times: 29/29 labelled. It is a policy for reaching further back.

*Alternatives considered.* **Fail loud**, consistent with the doctrine that an invisible change is
worse than a red run — rejected because the gate already provides that guarantee going forward, and
here the failure would be *retroactive*. **Silently drop**, which is what the old heading guard's
absence would have meant — rejected because a mislabelled customer-facing change would then reach no
surface at all.

### D4 — `.github/release.yml` is deleted; the mapping is a table in the script

With `generate-notes` gone the file has one consumer (`release_notes.py`), one editor, and a path
whose entire meaning is "GitHub reads this" — the belief that produced this change's bug. Keeping
GitHub's schema at GitHub's conventional path for a file GitHub no longer reads preserves precisely
the wrong mental model, and costs a `pyyaml` install inside the release path to parse four lines of
data.

Inlining it also makes D-nothing-else structural: the table travels *with* the script, which is
checked out at `main`'s HEAD, so the mapping cannot be read from the released commit even by mistake.

*Alternatives considered.* **Rename to `.github/changelog.yml`** — keeps the data/code split and fixes
the misleading path, at the cost of keeping the parser and the dependency. A reasonable end state;
declined because a two-entry mapping is not data worth a file, and the doctrine that belongs beside it
(why `New`/`Fixed` and not `Features`/`Bug Fixes`; why no catch-all) already lives in the script's
prose. **Leave it in place** with a corrected header comment — rejected for the mental-model reason
above.

Note the label *set* remains stated twice — in the script's table and in `check-label.yml`'s shell
loop. That is the status quo (`release.yml` and `check-label.yml` stated it twice already), not a
regression, and the two are different statements: which labels exist versus which heading each maps
to.

### D5 — The changelog goes to a file; the report goes to stdout

`--changelog <path>` writes the plain-text customer notes that `asc_release_notes_apply.sh` applies.
**stdout is the human report** — the rendered notes in a fenced block, the reconciliation counts, the
`internal` roster, the uncategorized anomalies — and the workflow redirects it into
`$GITHUB_STEP_SUMMARY`.

The redirect is what keeps the report out of the step log, as intended: stdout goes to the summary,
and nothing is written to stderr on a successful run. The script owns the whole summary section rather
than emitting a fragment the shell wraps, so the local preview shows byte-for-byte what the run will
show.

*Alternative considered:* stdout stays the changelog with the report in a second file. Rejected — the
customer-facing artifact is the one with a consumer that must not be guessed at, so it is the one that
gets an explicit path.

### D6 — Nothing else about the release changes

The range's endpoints (`git describe --tags --abbrev=0 --match 'v[0-9]*' <origin>` → the origin
commit), the two guards, the tag-created-last ordering, the 4000-character refusal, the all-`internal`
fallback sentence, and the title rendering (strip `type(scope):`, strip a leading `Fix`, capitalize)
are carried over unchanged. Bullets are ordered by ascending pull-request number, which is merge order
on a linear `main` and matches what the previous path produced.

## Risks / Trade-offs

- **The GraphQL association could regress or rate-limit** → It is one query per 25 commits against a
  public repo with the run's own token; the measured range costs three. A commit that resolves to
  nothing is reported, not fatal (D3), so a partial failure degrades to a visible under-report rather
  than a wrong changelog. The acceptance check below would catch a systematic regression.
- **We now own the grouping that GitHub used to do** → The deleted heading guard existed because the
  configuration in effect was invisible; with the table in the script it cannot be. The residual
  hazard — a bug in our grouping publishing an `internal` pull request — is covered by the report,
  which lists the internal roster next to the published notes in the same summary the operator reads
  before submitting.
- **`pull-requests: read` is a new token permission** → Additive and read-only, on a workflow that
  already holds `contents: write`. The `generate-notes` endpoint needed only `contents`, so this is
  required, not optional; omitting it fails the derivation before any App Store Connect mutation.
- **Promoting an older build strands newer ones** → Tagging `v0.2` at build 542's commit leaves builds
  543–545 carrying marketing version `0.2` with no version record to attach to; Apple's version trains
  make the marketing version a property of the bits, so it cannot be re-derived at promote time. This
  is inherent to promoting out of order and is accepted without a warning in the run: the remedy (the
  next release needs a build produced after the tag) follows from how `MARKETING_VERSION` is computed.
- **No test covers `.github/scripts/`** → Acceptance is a differential run over two ranges whose
  answer is already known independently: `v0.1..f936b9fc` (build 542, previously impossible) and
  `v0.1..6acb714c` (build 545, previously the only working case) must both render the same six
  bullets captured from the old path. A range with an externally-known answer is a stronger check than
  a unit test written against the new implementation.
