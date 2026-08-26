## MODIFIED Requirements

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
