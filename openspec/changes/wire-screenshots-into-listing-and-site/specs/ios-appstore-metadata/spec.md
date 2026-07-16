## REMOVED Requirements

### Requirement: Text fields only — screenshots and previews are out of scope

**Reason**: The premise expired. This requirement scoped the sync to text because those binaries "have no
`app-store-connect` CLI path" — but the **already-pinned** `asc` 2.8.2 ships a stable `screenshots upload`
(with `--replace`, `--skip-existing`, `--dry-run`). Managing screenshots by hand was a workaround for a gap
that no longer exists, and it left the listing half-declarative: the text version-controlled, the binaries
that sell it living only in the ASC console.

**Migration**: Screenshots are now managed from the repo — see "The repo is the source of truth for the
listing's screenshots" below. App **previews** (video) remain out of scope and are still managed manually;
the jobs SHALL NOT upload, replace, or delete them.

## ADDED Requirements

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
