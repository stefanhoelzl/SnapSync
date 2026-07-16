# ios-appstore-metadata Specification

## Purpose

Makes the **repo the source of truth for the App Store listing** of app `6781692480` — its **text**
(description, keywords, promotional text, support URL, marketing URL, and later "what's new") **and its
screenshots** — so the listing is version-controlled, reviewable in a PR, and reproducible, instead of
hand-typed and hand-uploaded into the App Store Connect (ASC) web console and living only there.

A credential-free **`appstore-metadata-validate`** gate runs on **every** ref (a required status check —
capability `branch-protection`) and fails the merge on any character-limit, URL-format, or unknown-key
violation *before* it can reach Apple. **`appstore-metadata-apply`** runs on **`main` only**, resolves the
app's currently **editable** App Store version at run time, and applies the committed per-locale files to
that version's localizations **declaratively** — the file wins; drift entered in the console is overwritten.

The **screenshots** are the same story told about binaries. They were once out of scope for a reason that
expired: the metadata tool grew a stable upload command, so the only thing keeping them in the console was
inertia. Their source of truth is the **committed raw captures** (produced by the dispatch-only capture
workflow), from which the listing images are composited with committed per-locale headline copy and uploaded
to **replace** the live set — so a screenshot added by hand in the console does not survive. The upload is
gated on those inputs actually changing, because a replace is destructive and re-running it on every
unrelated merge is churn against a public storefront. Headline copy lives **outside** the metadata tool's
schema, which is closed and rejects unknown keys — a key it does not know would fail a required check and
freeze merges. App **previews** (video) remain out of scope and manual.

The applies own the one safety guarantee the tooling does not — they touch **only** a version in an editable
state, **never** one in review, and **never** create a version; the tool's behaviour on an in-review version
is undefined, and replacing a screenshot set is destructive. They reuse the **existing Admin ASC key**, run
on `ubuntu` with no Apple toolchain, introduce no new secret, and — like `ios-deliver`/`ios-promote` — post
no required check, so a failed apply is **red but blocks nothing**. The metadata tool (`asc`) is a pinned,
checksum-verified binary; **no fastlane, no Ruby**.

Decision record: `changes/archive/2026-07-15-sync-appstore-metadata-from-repo` (the text sync),
`changes/archive/2026-07-16-wire-screenshots-into-listing-and-site` (the screenshots),
`changes/archive/2026-07-16-close-appstore-submission-gaps` (the app-info fields — and why
`--include localizations` already covers them, so no app-info id is stored anywhere)

## Requirements
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

### Requirement: The repo is the source of truth for the listing's screenshots

The committed raw captures under `screenshots/` SHALL be the source of truth for the App Store listing's
screenshots, and the committed per-locale headline file SHALL be the source of truth for the copy composited
onto them. `appstore-screenshots-upload` SHALL build each listing image from those committed inputs and
upload the resulting set to App Store Connect, replacing the target set so that the committed inputs — and
only they — determine the live screenshots. It SHALL run on `refs/heads/main` only, and SHALL NOT upload,
replace, or delete app previews.

#### Scenario: The committed inputs determine the live screenshot set

- **WHEN** `appstore-screenshots-upload` runs on `main` with an editable version present
- **THEN** it composites the listing images from the committed raw captures and headline file and replaces
  the target version-localization's screenshot set with exactly that set

#### Scenario: A screenshot added in the console is removed

- **WHEN** a screenshot was uploaded through the ASC web console after the last upload, and the job runs
- **THEN** that screenshot is not present in the resulting set, because the committed inputs define it

#### Scenario: A non-main push uploads nothing

- **WHEN** a commit is pushed to any ref other than `refs/heads/main`
- **THEN** `appstore-screenshots-upload` does not run and no screenshot is modified

#### Scenario: App previews are untouched

- **WHEN** the job runs against a version that has app previews
- **THEN** the app previews are left unchanged

### Requirement: Screenshots are uploaded only to an editable version, never one in review

`appstore-screenshots-upload` SHALL resolve the app's currently **editable** App Store version (state
`PREPARE_FOR_SUBMISSION` or `DEVELOPER_REJECTED`) and upload only to that version's localizations, using the
same resolve-then-refuse gate as the metadata apply. If **no** version is in an editable state it SHALL make
**no change** and conclude **successfully**. It SHALL NOT modify a version in `WAITING_FOR_REVIEW`,
`IN_REVIEW`, or `PENDING_DEVELOPER_RELEASE`, and SHALL NOT create a version. The gate is ours, not the
tool's: replacing a screenshot set is destructive, and the tool's behaviour on an in-review version is
undefined.

#### Scenario: An editable version receives the set

- **WHEN** the app has a version in `PREPARE_FOR_SUBMISSION`
- **THEN** the job uploads the composited set to that version's localizations

#### Scenario: No editable version is a green no-op

- **WHEN** the app has no version in an editable state
- **THEN** the job uploads nothing and concludes successfully

#### Scenario: A version under review is never touched

- **WHEN** no editable version exists but a version is `WAITING_FOR_REVIEW`
- **THEN** the job does not modify that version's screenshots and does not create a new version

### Requirement: The screenshot upload runs only when its inputs change

`appstore-screenshots-upload` SHALL run only when a push to `main` changes the composite's inputs — the
committed raw captures or the committed listing metadata. On a push that changes neither, it SHALL NOT run
and SHALL NOT modify the live set. This gate SHALL NOT be applied to `appstore-metadata-validate` (a
required status check, which must post on every ref or merges freeze) nor to the listing-text apply (whose
declarative overwrite of console edits must not weaken to "eventually").

#### Scenario: A capture refresh reaches the listing

- **WHEN** a push to `main` changes a file under `screenshots/`
- **THEN** `appstore-screenshots-upload` runs

#### Scenario: A headline change reaches the listing

- **WHEN** a push to `main` changes the committed listing metadata and no capture
- **THEN** `appstore-screenshots-upload` runs, re-compositing from the unchanged captures

#### Scenario: An unrelated merge does not touch the listing

- **WHEN** a push to `main` changes neither the captures nor the listing metadata
- **THEN** `appstore-screenshots-upload` does not run and the live screenshot set is not modified

#### Scenario: Validation still posts on every ref

- **WHEN** a push touches neither the captures nor the listing metadata, on any ref
- **THEN** `appstore-metadata-validate` still runs and posts its status check

### Requirement: Screenshot headlines live outside the metadata tool's schema

The per-locale screenshot headline copy SHALL be committed in a file the metadata tool does not decode, so
that it can be version-controlled alongside the listing without breaking validation. It SHALL NOT be added
as a key to the tool's canonical per-locale metadata files, whose schema is closed and rejects unknown
fields — an unknown key there fails `appstore-metadata-validate`, a required check, and freezes merges.

#### Scenario: Headlines do not break validation

- **WHEN** the headline file is committed and `appstore-metadata-validate` runs
- **THEN** validation succeeds and the headline file is not decoded as canonical metadata

#### Scenario: Headline copy is version-controlled per locale

- **WHEN** the headline for a locale is changed and pushed to `main`
- **THEN** the composited listing images for that locale carry the new copy

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

