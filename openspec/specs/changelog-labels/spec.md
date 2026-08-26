# changelog-labels Specification

## Purpose

Makes the **labelled pull request the unit of the changelog**, so the list of changes a *user* is told
about is curated at the moment the author knows the answer and never written again. Every PR carries
one of three labels — `enhancement`, `bug`, `internal` — applied by the `/ship` command as it
opens the pull request; a single table, carried by the derivation itself, maps a label to a heading and
excludes `internal`; and the changelog for any commit range is **derived** from the pull requests that
range contains, rendered as plain text.

The unit is the pull request, not the commit, because the label encodes the only question that
matters — does a user of the app experience this? — and a commit prefix does not. Measured on the
range between the `v0.1` release and build 542: 66 commits, 38 of them prefixed `feat:`/`fix:`, of
which the overwhelming majority were CI, website, backend, and spec work; the 29 pull requests behind
them split cleanly into 6 user-facing and 23 `internal`.

The derivation is a function of **the range and the release process, never of the released commit's
contents**. That independence is load-bearing rather than incidental: the obvious implementation —
asking GitHub's release-notes generator to render the range — reads its configuration from the
`target_commitish`, which made the changelog's shape a property of the shipped bits and left a build
whose commit predated that configuration permanently un-releasable. A build is chosen for release
because it is the one that was tested; a derivation that can refuse it for what its commit happens to
contain breaks that, irreversibly, for bits that can no longer be changed.

The changelog exists to be **read by App Store customers** (its one automated consumer is the App
Store release, capability `ios-appstore-release`), so it is plain text with no links, no issue
numbers, and no markdown. Its correctness rests on a gate and a report rather than on care: a pull
request carrying **no** label fails its own gate, because a changelog cannot report what it was never
told about; and whatever the derivation cannot categorize is excluded but **named** in a report beside
the changelog, because a release must not be blocked by history from before that gate while an
uncategorized change must not vanish unremarked.

Decision record: `changes/archive/2026-07-31-derive-release-notes-from-pull-requests` (the derivation
resolves pull requests itself and carries its own mapping), superseding
`changes/archive/2026-07-31-derive-release-notes-from-labels` (the labels, the gate, and the plain-text
rendering).

## Requirements
### Requirement: Every pull request carries a changelog label

Every pull request SHALL carry exactly one of the labels `enhancement`, `bug`, or `internal`. A
workflow triggered on pull requests SHALL fail the pull request when it carries **none** of them, and
SHALL report the required set. The check SHALL read the pull request's **live** labels rather than the
triggering event's payload, because a label applied moments after the PR is created races the
`opened` webhook snapshot.

The gate SHALL be a **required status check** in the committed branch ruleset
(`.github/rulesets/main.json`), and SHALL be the only
thing that *prevents* an uncategorized change: the derivation reports what it could not categorize but
does not refuse it, so without the gate an unlabelled pull request would be absent from the changelog
and discovered — if at all — only in the release run that already shipped without it.

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

### Requirement: One table maps labels to changelog headings

The derivation SHALL carry a **single table** mapping each user-facing label to its changelog heading,
and a single set of labels excluded from the changelog; that table SHALL be the only place either is
stated. It SHALL declare one heading per user-facing label (`enhancement`, `bug`) and SHALL exclude
`internal`. No other script, workflow, or spec SHALL restate which label belongs under which heading.

The table SHALL NOT declare a catch-all heading matching every label: a pull request that escapes the
label gate must surface as uncategorized, never be published to customers under a residual heading.

The table SHALL live with the derivation rather than in a separate configuration file, so that it is
loaded from the same place the derivation is and cannot be sourced from the commits being described.

#### Scenario: Internal work is excluded

- **WHEN** a changelog is derived for a range containing pull requests labelled `internal`
- **THEN** none of them appears in the changelog

#### Scenario: The headings come from one place

- **WHEN** a changelog heading is renamed
- **THEN** the derivation's table is the only place that changes

#### Scenario: No residual heading exists

- **WHEN** a pull request carrying no changelog label is somehow merged
- **THEN** it appears under no heading, rather than under a catch-all

### Requirement: A changelog is derived from a commit range's pull requests

The changelog for a range SHALL be derived from the **pull requests** the range contains and their
labels, never from commit subjects — merges are rebase-only, so a pull request contributes several
commits and its title is the one statement of what it changed.

The derivation SHALL resolve the range's pull requests by **enumerating the range's commits and
associating each with the pull requests that contain it**, keeping only those **merged into the
default branch** and taking their union, deduplicated. A promoted build's origin commit is on the
default branch by construction, so a pull request reachable only from another branch cannot describe
the range.

The derivation SHALL depend only on **the commit range and the released-from process** — never on the
contents of any commit in the range. No configuration, mapping, or rendering used by the derivation
SHALL be read out of the range's own commits, so that **any** commit range is derivable regardless of
what its commits contain.

The derivation SHALL render **plain text**: each heading the mapping declares, followed by one `- `
bullet per pull request carrying that heading's label, ordered by ascending pull request number. Each
bullet SHALL be the pull request's title with the conventional-commit `type(scope):` prefix removed
and the first letter capitalized, and SHALL NOT carry an author, a pull request number, a URL, or
markdown.

A pull request the mapping cannot categorize — carrying neither a heading's label nor an excluded one
— and a commit associating with no pull request merged into the default branch SHALL be **excluded
from the changelog and reported** (see the reconciliation requirement), and SHALL NOT fail the
derivation. The label gate already prevents an uncategorized pull request from merging, so anything
uncategorized is history from before that gate, and refusing to release present work on account of it
would block shipping while fixing nothing.

#### Scenario: A range renders as grouped plain text

- **WHEN** a changelog is derived for a range whose pull requests carry `enhancement` and `bug` labels
- **THEN** the result is plain text listing each pull request's title as a `- ` bullet under its
  label's heading, with no author, number, URL, or markdown

#### Scenario: A conventional-commit prefix is stripped

- **WHEN** a contributing pull request is titled `feat(ui): split the join range into two lists`
- **THEN** its bullet reads `Split the join range into two lists`

#### Scenario: A rebased commit still finds its pull request

- **WHEN** a pull request's commits were rebased onto the default branch, so the branch carries commit
  identities the pull request's own branch never held
- **THEN** the derivation still associates those commits with that pull request, and the pull request
  contributes its title once however many commits it contributed

#### Scenario: A range predating any part of the mechanism still derives

- **WHEN** every commit in the range predates the introduction of the derivation and its mapping
- **THEN** the changelog derives normally from those commits' pull requests and their labels

#### Scenario: An uncategorized pull request is dropped, not fatal

- **WHEN** the range contains a pull request carrying none of the changelog labels
- **THEN** it appears in no heading, the derivation succeeds, and it is named in the reconciliation
  report

### Requirement: The derivation reconciles the range in a report

Alongside the changelog, the derivation SHALL emit a **report that accounts for every pull request in
the range**: the count of pull requests found and their split into published, excluded-as-internal,
and uncategorized; the roster of excluded `internal` pull requests; and each uncategorized pull request
or unassociated commit, distinguished from the deliberate exclusions.

The changelog and the report SHALL travel on **separate channels**, so that the artifact a customer
reads is never mixed with the accounting an operator reads, and the report SHALL also carry the
rendered changelog so that one place shows both what will be published and what will not.

#### Scenario: A release reconciles

- **WHEN** a changelog is derived for a range of 29 pull requests, 6 of them user-facing and 23
  labelled `internal`
- **THEN** the report states those counts, lists the 23 excluded pull requests, and reports no
  uncategorized ones

#### Scenario: An uncategorized pull request is distinguishable from an excluded one

- **WHEN** the range contains both `internal` pull requests and a pull request with no changelog label
- **THEN** the report presents them separately, so the deliberate exclusions do not hide the anomaly

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

