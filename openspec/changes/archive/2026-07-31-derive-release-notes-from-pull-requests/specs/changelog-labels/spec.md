## MODIFIED Requirements

### Requirement: Every pull request carries a changelog label

Every pull request SHALL carry exactly one of the labels `enhancement`, `bug`, or `internal`. A
workflow triggered on pull requests SHALL fail the pull request when it carries **none** of them, and
SHALL report the required set. The check SHALL read the pull request's **live** labels rather than the
triggering event's payload, because a label applied moments after the PR is created races the
`opened` webhook snapshot.

The gate SHALL be a **required status check** (capability `branch-protection`), and SHALL be the only
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

## REMOVED Requirements

### Requirement: One committed file maps labels to changelog headings

**Reason**: The mapping lived in `.github/release.yml` because GitHub's release-notes generator read
it; that generator is no longer used, leaving the file with one consumer at a path whose meaning is
"GitHub reads this" — the belief that produced the un-promotable build this change fixes. A file also
cannot satisfy the new independence requirement above while remaining at a path a released commit
might carry a different copy of.

**Migration**: Replaced by *One table maps labels to changelog headings*, below, which keeps every
invariant (single place, `internal` excluded, no catch-all) and moves the mapping into the derivation
itself. `.github/release.yml` is deleted.

## ADDED Requirements

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
