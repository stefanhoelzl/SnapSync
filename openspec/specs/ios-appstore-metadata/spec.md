# ios-appstore-metadata Specification

## Purpose

Makes the **repo the source of truth for the App Store *text* listing** of app `6781692480` — description,
keywords, promotional text, support URL, marketing URL (and, later, "what's new") — so the listing is
version-controlled, reviewable in a PR, and reproducible, instead of hand-typed into the App Store Connect
(ASC) web console and living only there. Two jobs carry it, mirroring `ios-ci` / `ios-testflight-delivery`.
A credential-free **`appstore-metadata-validate`** gate runs on **every** ref (a required status check —
capability `branch-protection`) and fails the merge on any character-limit, URL-format, or unknown-key
violation *before* it can reach Apple. **`appstore-metadata-apply`** runs on **`main` only**, resolves the
app's currently **editable** App Store version at run time, and applies the committed per-locale files to
that version's localizations **declaratively** — the file wins; drift entered in the console is overwritten.

The sync is **text only**: screenshots and app previews are binaries with no CLI path and are out of scope.
The apply owns the one safety guarantee the tooling does not — it edits **only** a version in an editable
state, **never** one in review, and **never** creates a version. It reuses the **existing Admin ASC key**,
runs on `ubuntu` with no Apple toolchain, introduces no new secret, and — like `ios-deliver`/`ios-promote` —
posts no required check, so a failed apply is **red but blocks nothing**. The metadata tool (`asc`) is a
pinned, checksum-verified binary; **no fastlane, no Ruby**.

Decision record: `changes/archive/2026-07-15-sync-appstore-metadata-from-repo`

## Requirements
### Requirement: The repo is the declarative source of truth for the listing text

The committed per-locale metadata files SHALL be the source of truth for the App Store text listing — the
version-localization fields (description, keywords, promotional text, support URL, marketing URL, and
optionally what's-new) and the app-info fields, one JSON file per locale, laid out per the Purpose. On a push
to `main`, `appstore-metadata-apply` SHALL apply those files to App Store Connect, overwriting any value that
differs — including one edited directly in the ASC web console.

#### Scenario: A main push applies the committed listing
- **WHEN** a commit is pushed to `refs/heads/main` and the app has an editable version
- **THEN** `appstore-metadata-apply` writes every field present in the per-locale files to that version's localizations

#### Scenario: A console hand-edit is overwritten
- **WHEN** a field was changed in the ASC web console after the last apply, and a new commit is pushed to `main`
- **THEN** the apply overwrites that field back to the committed file's value

### Requirement: An absent field is left unmanaged, never deleted

The apply SHALL treat a field **omitted** from a per-locale file as a no-op that leaves the App Store
Connect value unchanged, and SHALL NOT delete listing fields (it SHALL NOT pass the tool's delete/confirm
flags). Declarative overwrite applies only to fields **present** in the files.

#### Scenario: An omitted field retains its live value
- **WHEN** a per-locale file does not contain the `whatsNew` key and an apply runs
- **THEN** the existing `whatsNew` value in App Store Connect is left unchanged, not cleared

### Requirement: Edits target only an editable version, never one in review

`appstore-metadata-apply` SHALL resolve the app's currently **editable** App Store version (state
`PREPARE_FOR_SUBMISSION` or `DEVELOPER_REJECTED`) and apply the files only to that version's localizations.
If **no** version is in an editable state, it SHALL make **no change** and conclude **successfully** (a green
no-op). It SHALL NOT edit a version in `WAITING_FOR_REVIEW`, `IN_REVIEW`, or `PENDING_DEVELOPER_RELEASE`, and
SHALL NOT create a version — editing a version under review can void or restart an in-flight submission.

#### Scenario: An editable version is updated
- **WHEN** the app has a version in `PREPARE_FOR_SUBMISSION`
- **THEN** the apply writes the files to that version's localizations

#### Scenario: No editable version is a green no-op
- **WHEN** the app has no version in an editable state (e.g. the only version is `IN_REVIEW`)
- **THEN** the apply writes nothing and concludes successfully

#### Scenario: A version under review is never touched
- **WHEN** an editable version does not exist but a version is `WAITING_FOR_REVIEW`
- **THEN** the apply does not edit that version and does not create a new one

### Requirement: The version and localization are resolved at run time

The apply SHALL locate the target by `(app 6781692480 → editable version → locale)` at run time, and SHALL
NOT depend on a stored `appStoreVersionLocalization` id or a hardcoded version path. Localization ids are
version-specific (every new version mints fresh ids), and the ASC **version string** (e.g. `1.0`) is not the
build's `MARKETING_VERSION` (e.g. `0.1.0`) — so neither may be hardcoded.

#### Scenario: A fresh version's localizations are found by locale
- **WHEN** a new App Store version has been created with new localization ids
- **THEN** the apply finds the `en-US` localization by locale on the resolved editable version, using no stored id

### Requirement: Applied on main only; other refs never write

`appstore-metadata-apply` SHALL run only on pushes to `refs/heads/main` (guarded by
`if: github.ref == 'refs/heads/main'`). On any other ref it SHALL NOT run and SHALL write nothing to App
Store Connect.

#### Scenario: A non-main push writes nothing
- **WHEN** a commit is pushed to any ref other than `refs/heads/main`
- **THEN** `appstore-metadata-apply` does not run and no App Store Connect metadata is modified

### Requirement: Validation gates the merge and needs no credentials

`appstore-metadata-validate` SHALL validate the committed metadata files **offline** — enforcing App Store
character limits (`description` ≤ 4000, `keywords` ≤ 100, `promotionalText` ≤ 170, `whatsNew` ≤ 4000), URL
syntactic validity, and rejection of unknown keys — on **every** ref, using **no** App Store Connect
credentials. A violation SHALL fail the job. This job is a required status check (capability
`branch-protection`), so an invalid listing file cannot merge.

#### Scenario: An over-length field fails the gate
- **WHEN** a per-locale file's `keywords` string exceeds 100 characters on a PR branch
- **THEN** `appstore-metadata-validate` fails and the PR cannot merge

#### Scenario: Validation runs without credentials
- **WHEN** `appstore-metadata-validate` runs on any ref
- **THEN** it validates the files offline and requires no App Store Connect API key

### Requirement: Text fields only — screenshots and previews are out of scope

The jobs SHALL manage only text metadata (version-localization and app-info text fields) and SHALL NOT
upload, replace, or delete screenshots or app previews. Those binaries have no `app-store-connect` CLI path
and remain managed manually.

#### Scenario: Screenshots are untouched by a sync
- **WHEN** an apply runs against a version that already has uploaded screenshots
- **THEN** the screenshots are left unchanged

### Requirement: Apply never blocks a merge and never fails silently

`appstore-metadata-apply` SHALL post **no** required status check (it SHALL NOT be added to
`.github/rulesets/main.json`) and SHALL NOT use `continue-on-error`: a failed resolution or apply SHALL
conclude the job as **failure (red)**, visibly, while blocking no merge (the commit is already merged and the
job is not a required check).

#### Scenario: An apply flake is red but blocks nothing
- **WHEN** the apply fails on `main` (e.g. an App Store Connect error)
- **THEN** the job concludes as failure (red) and no merge is blocked

### Requirement: Reuses the Admin key; no new secret; no Apple toolchain

Both jobs SHALL run on an `ubuntu` runner. `appstore-metadata-apply` SHALL authenticate with the **existing**
Admin App Store Connect API key (`ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_API_PRIVATE_KEY`) and SHALL NOT introduce
a new secret, require Xcode, a keychain, or any signing certificate.

#### Scenario: Apply runs on ubuntu with only the existing key
- **WHEN** `appstore-metadata-apply` runs
- **THEN** it uses only the existing Admin App Store Connect key, with no Xcode, keychain, or signing certificate

### Requirement: The metadata tool is pinned and integrity-verified

The `asc` metadata CLI SHALL be pinned to a specific release tag and its downloaded binary SHALL be verified
against the release's published SHA-256 checksum before execution; a mismatch SHALL abort the job before any
apply. The pipeline SHALL NOT use fastlane or Ruby.

#### Scenario: A checksum mismatch aborts before applying
- **WHEN** the downloaded `asc` binary's SHA-256 does not match the pinned checksum
- **THEN** the job fails before any App Store Connect mutation

