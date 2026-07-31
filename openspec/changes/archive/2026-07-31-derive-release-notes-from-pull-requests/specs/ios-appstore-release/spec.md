## MODIFIED Requirements

### Requirement: A release writes the version's release notes, derived from the changelog

The release workflow SHALL write the promoted version's **release notes** (`whatsNew`) to its `en-US`
version localization, from the changelog derived for the range between the **nearest ancestor `vX.Y`
tag of the build's origin commit** and that origin commit (capability `changelog-labels`). Both
endpoints already exist in the run: the origin commit is the one the release tags, so consecutive
releases delimit an exact range. When the origin commit has **no** ancestor `vX.Y` tag the range is
open-ended — that build is the first release, for which Apple requires no notes.

The notes SHALL be **derived, never read from a committed file**: they are the one part of the listing
whose content differs for every release, and a hand-written field is a step no gate names (a missing
`en-US: whatsNew` refused the 0.2 submission).

The derivation SHALL NOT depend on the **contents** of the commit being promoted, so that **every**
build App Store Connect holds is promotable: a build is chosen by number precisely because it is the
one the operator validated, and a release process that could refuse a build for what its commit
happened to contain would break that promise for bits that can no longer be changed.

The derivation SHALL happen **before the first App Store Connect mutation of the run**, and the
workflow SHALL fail there — mutating nothing — if the rendered notes exceed the field's 4000-character
limit. The rendered notes SHALL be echoed into the run's **summary**, together with the derivation's
reconciliation report (capability `changelog-labels`), so the operator reads what will be published
and what was withheld from it in the same place, before deciding to submit.

The notes SHALL be applied on **every** release run, whether or not the version is being submitted, so
that a promote-only run leaves the version submit-ready; the application SHALL happen **after** the
build is attached, so the version record exists, and **before** the submission readiness gate. The
repo-derived value SHALL overwrite a value edited in the ASC web console, like the review details and
the screenshots.

Only the `en-US` localization SHALL be written. The derived changelog is English, and writing it into
another locale's listing would be worse than the submission preflight naming that locale's notes as
missing.

#### Scenario: A promoted version carries notes nobody wrote at release time

- **WHEN** a build whose origin commit is a descendant of `v0.1` is promoted
- **THEN** the version's `en-US` `whatsNew` holds the changelog derived from the labelled pull
  requests merged between `v0.1` and that origin commit

#### Scenario: An older build is as promotable as a newer one

- **WHEN** two builds carry the same marketing version and the older one's origin commit predates a
  change to how release notes are derived
- **THEN** promoting either produces the release notes its own range calls for, and neither is
  refused for what its commit contains

#### Scenario: Notes are applied without submitting

- **WHEN** a release runs with the `submit` input false
- **THEN** the release notes are still applied, and the version's missing-`whatsNew` submission
  blocker is cleared

#### Scenario: Over-long notes fail before anything is mutated

- **WHEN** the rendered notes exceed 4000 characters
- **THEN** the run fails before any App Store Connect mutation and no version record is created,
  attached, or modified

#### Scenario: The run summary accounts for what was left out

- **WHEN** a release derives notes for a range whose pull requests are mostly labelled `internal`
- **THEN** the run summary carries the rendered notes and the reconciliation report naming those
  excluded pull requests, rather than the notes alone

#### Scenario: A range with no user-facing pull request still produces notes

- **WHEN** every pull request since the previous release is labelled `internal`
- **THEN** the version's `whatsNew` holds the committed fallback sentence rather than empty text, and
  the release proceeds

#### Scenario: A console hand-edit of the notes is overwritten

- **WHEN** `whatsNew` was edited in the ASC web console and a release runs for that version
- **THEN** the run overwrites it with the derived changelog

#### Scenario: The first release needs no previous tag

- **WHEN** the promoted build's origin commit has no ancestor `vX.Y` tag
- **THEN** the derivation runs against an open-ended range rather than failing
