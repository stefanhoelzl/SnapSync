## MODIFIED Requirements

### Requirement: Validation gates the merge and needs no credentials

`appstore-metadata-validate` SHALL validate the committed metadata files **offline** — enforcing App Store
character limits (`description` ≤ 4000, `keywords` ≤ 100, `promotionalText` ≤ 170, `whatsNew` ≤ 4000,
`subtitle` ≤ 30), URL syntactic validity, and rejection of unknown keys — on **every** ref, using **no**
App Store Connect credentials. A violation SHALL fail the job. This job is a required status check in
the committed branch ruleset (`.github/rulesets/main.json`), so an invalid listing file cannot merge.

#### Scenario: An over-length field fails the gate
- **WHEN** a per-locale file's `keywords` string exceeds 100 characters on a PR branch
- **THEN** `appstore-metadata-validate` fails and the PR cannot merge

#### Scenario: An over-length subtitle fails the gate
- **WHEN** an `app-info/<locale>.json` file's `subtitle` exceeds 30 characters on any ref
- **THEN** `appstore-metadata-validate` fails and the PR cannot merge

#### Scenario: Validation runs without credentials
- **WHEN** `appstore-metadata-validate` runs on any ref
- **THEN** it validates the files offline and requires no App Store Connect API key
