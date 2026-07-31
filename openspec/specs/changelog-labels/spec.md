# changelog-labels Specification

## Purpose

Makes the **labelled pull request the unit of the changelog**, so the list of changes a *user* is told
about is curated at the moment the author knows the answer and never written again. Every PR carries
one of three labels — `enhancement`, `bug`, `internal` — applied by `/ship` (capability
`ship-command`); a committed `.github/release.yml` is the single place that maps a label to a heading
and excludes `internal`; and the changelog for any commit range is **derived** from the pull requests
that range contains, rendered as plain text.

The unit is the pull request, not the commit, because the label encodes the only question that
matters — does a user of the app experience this? — and a commit prefix does not. Measured on the
range between the `v0.1` release and build 542: 66 commits, 38 of them prefixed `feat:`/`fix:`, of
which the overwhelming majority were CI, website, backend, and spec work; the 29 pull requests behind
them split cleanly into 6 user-facing and 23 `internal`.

The changelog exists to be **read by App Store customers** (its one automated consumer is the App
Store release, capability `ios-appstore-release`), so it is plain text with no links, no issue
numbers, and no markdown. Its correctness rests on two loud failures rather than on care: a pull
request carrying **no** label fails its own gate, because a changelog cannot report what it was never
told about; and a heading the committed configuration does not declare — carrying items — fails the
derivation, because the generator's un-configured fallback is a single ungrouped section containing
*every* pull request, `internal` ones included.

Decision record: `changes/archive/2026-07-31-derive-release-notes-from-labels`

## Requirements
### Requirement: Every pull request carries a changelog label

Every pull request SHALL carry exactly one of the labels `enhancement`, `bug`, or `internal`. A
workflow triggered on pull requests SHALL fail the pull request when it carries **none** of them, and
SHALL report the required set. The check SHALL read the pull request's **live** labels rather than the
triggering event's payload, because a label applied moments after the PR is created races the
`opened` webhook snapshot.

The gate SHALL be a **required status check** (capability `branch-protection`): an unlabelled pull
request is otherwise absent from the changelog with no other signal anywhere, since the derivation
drops what it cannot categorize.

#### Scenario: An unlabelled pull request fails

- **WHEN** a pull request carries none of `enhancement`, `bug`, `internal`
- **THEN** the check fails, naming the three labels, and the pull request cannot merge

#### Scenario: A labelled pull request passes

- **WHEN** a pull request carries any one of the three labels
- **THEN** the check succeeds

#### Scenario: A label applied just after creation still passes

- **WHEN** the label is applied by a separate API call about a second after the pull request is opened
- **THEN** the check reads the live labels and succeeds, rather than failing on the empty label set
  the `opened` event carried

### Requirement: One committed file maps labels to changelog headings

`.github/release.yml` SHALL be the **single** source of the mapping from label to changelog heading and
of the exclusion of non-user-facing work: it SHALL exclude the `internal` label and SHALL declare one
category per user-facing label (`enhancement`, `bug`). No script, workflow, or spec other than that
file SHALL restate which label belongs under which heading.

The file SHALL NOT declare a catch-all category matching every label: a pull request that escapes the
label gate must not be silently published to customers under a residual heading.

#### Scenario: Internal work is excluded

- **WHEN** a changelog is derived for a range containing pull requests labelled `internal`
- **THEN** none of them appears in the result

#### Scenario: The headings come from the committed file

- **WHEN** a changelog heading is renamed
- **THEN** `.github/release.yml` is the only file that changes

#### Scenario: No residual category exists

- **WHEN** a pull request carrying no changelog label is somehow merged
- **THEN** it appears under no heading, rather than under a catch-all

### Requirement: A changelog is derived from a commit range's pull requests

The changelog for a range SHALL be derived from the **pull requests** the range contains and their
labels, never from commit subjects — merges are rebase-only, so a pull request contributes several
commits and its title is the one statement of what it changed.

The derivation SHALL render **plain text**: each heading the configuration declares, followed by one
`- ` bullet per pull request carrying that heading's label. Each bullet SHALL be the pull request's
title with the conventional-commit `type(scope):` prefix removed and the first letter capitalized,
and SHALL NOT carry an author, a pull request number, a URL, or markdown.

The derivation SHALL **fail** when the rendered result contains a heading that `.github/release.yml`
does not declare, rather than emit it. A derivation with no configuration in effect produces one
ungrouped section holding every pull request in the range, so this refusal is what stops `internal`
work reaching a customer-facing surface.

#### Scenario: A range renders as grouped plain text

- **WHEN** a changelog is derived for a range whose pull requests carry `enhancement` and `bug` labels
- **THEN** the result is plain text listing each pull request's title as a `- ` bullet under its
  label's heading, with no author, number, URL, or markdown

#### Scenario: A conventional-commit prefix is stripped

- **WHEN** a contributing pull request is titled `feat(ui): split the join range into two lists`
- **THEN** its bullet reads `Split the join range into two lists`

#### Scenario: An unconfigured heading fails the derivation

- **WHEN** the derivation produces a heading that `.github/release.yml` does not declare — for
  example because the configuration was not in effect for the range
- **THEN** the derivation fails and produces no changelog, rather than emitting a section that would
  include `internal` pull requests

### Requirement: A range with no user-facing change has a committed fallback

The derivation SHALL produce a **committed fallback sentence**, rather than empty text, when a range
contains **no** pull request under any declared heading — every one of them `internal`. A release
consisting only of internal work is a legitimate outcome, and the surfaces that consume a changelog
reject an empty value.

#### Scenario: An all-internal range still yields text

- **WHEN** every pull request in the range is labelled `internal`
- **THEN** the derivation produces the committed fallback sentence

### Requirement: The changelog has exactly one automated consumer

The derived changelog SHALL be consumed by the App Store release (capability
`ios-appstore-release`) and by nothing else. No workflow SHALL publish a GitHub Release, and no
changelog file SHALL be committed to the repository: the `vX.Y` tags plus the labelled pull requests
already carry the history, and a second rendering would be a second thing to keep true.

#### Scenario: No GitHub Release is published

- **WHEN** a release succeeds and pushes its `vX.Y` tag
- **THEN** no GitHub Release object is created, and the derived changelog exists only on the App
  Store listing and in the run's log

