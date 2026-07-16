## MODIFIED Requirements

### Requirement: The repo is the declarative source of truth for the listing text

The committed per-locale metadata files SHALL be the source of truth for the App Store text listing — the
version-localization fields (description, keywords, promotional text, support URL, marketing URL, and
optionally what's-new) and the app-info fields (name, subtitle, privacy policy URL), one JSON file per
locale, laid out per the Purpose. On a push to `main`, `appstore-metadata-apply` SHALL apply those files
to App Store Connect, overwriting any value that differs — including one edited directly in the ASC web
console. The apply SHALL resolve the app's app-info at run time from the app id, and SHALL NOT depend on
a stored or hardcoded app-info id.

#### Scenario: A main push applies the committed listing
- **WHEN** a commit is pushed to `refs/heads/main` and the app has an editable version
- **THEN** `appstore-metadata-apply` writes every field present in the per-locale files to that version's localizations

#### Scenario: A console hand-edit is overwritten
- **WHEN** a field was changed in the ASC web console after the last apply, and a new commit is pushed to `main`
- **THEN** the apply overwrites that field back to the committed file's value

#### Scenario: The app-info fields reach App Store Connect
- **WHEN** `app-info/<locale>.json` sets `subtitle` or `privacyPolicyUrl` and an apply runs on `main`
- **THEN** the apply writes those values to the app's app-info localization for that locale, resolving the
  app-info from the app id with no stored id

### Requirement: Validation gates the merge and needs no credentials

`appstore-metadata-validate` SHALL validate the committed metadata files **offline** — enforcing App Store
character limits (`description` ≤ 4000, `keywords` ≤ 100, `promotionalText` ≤ 170, `whatsNew` ≤ 4000,
`subtitle` ≤ 30), URL syntactic validity, and rejection of unknown keys — on **every** ref, using **no**
App Store Connect credentials. A violation SHALL fail the job. This job is a required status check
(capability `branch-protection`), so an invalid listing file cannot merge.

#### Scenario: An over-length field fails the gate
- **WHEN** a per-locale file's `keywords` string exceeds 100 characters on a PR branch
- **THEN** `appstore-metadata-validate` fails and the PR cannot merge

#### Scenario: An over-length subtitle fails the gate
- **WHEN** an `app-info/<locale>.json` file's `subtitle` exceeds 30 characters on any ref
- **THEN** `appstore-metadata-validate` fails and the PR cannot merge

#### Scenario: Validation runs without credentials
- **WHEN** `appstore-metadata-validate` runs on any ref
- **THEN** it validates the files offline and requires no App Store Connect API key
