## MODIFIED Requirements

### Requirement: The repo is the source of truth for the listing's screenshots

The committed raw captures under `screenshots/` SHALL be the source of truth for the App Store listing's
screenshots, and the committed per-locale headline file SHALL be the source of truth for the copy composited
onto them. The screenshot upload SHALL build each listing image from those committed inputs and upload the
resulting set to App Store Connect, replacing the target set so that the committed inputs — and only they —
determine the screenshots of the version being released. It SHALL NOT upload, replace, or delete app
previews.

The upload SHALL have exactly **one** automated caller: the App Store release (capability
`ios-appstore-release`), which uploads to the version record it prepares. There SHALL be **no**
push-triggered screenshot upload — a push to any ref, including `main`, SHALL NOT modify the listing's
screenshots. Correcting the screenshots of a version the release has already prepared is a **manual**
operation, because a release is single-shot per version.

#### Scenario: The committed inputs determine the released screenshot set

- **WHEN** an App Store release prepares a version record
- **THEN** it composites the listing images from the committed raw captures and headline file and replaces
  that version-localization's screenshot set with exactly that set

#### Scenario: A push does not touch the listing's screenshots

- **WHEN** a commit changing `screenshots/` or the headline file is pushed to `refs/heads/main`
- **THEN** no screenshot is uploaded, replaced, or deleted in App Store Connect

#### Scenario: A screenshot added in the console is removed at the next release

- **WHEN** a screenshot was uploaded through the ASC web console, and a subsequent release prepares a version
- **THEN** that screenshot is not present in the resulting set, because the committed inputs define it

#### Scenario: App previews are untouched

- **WHEN** the upload runs against a version that has app previews
- **THEN** the app previews are left unchanged

### Requirement: Screenshots are uploaded only to an editable version, never one in review

The screenshot upload SHALL resolve the app's currently **editable** App Store version (state
`PREPARE_FOR_SUBMISSION` or `DEVELOPER_REJECTED`) and upload only to that version's localizations, using the
same resolve-then-refuse gate as the metadata apply. If **no** version is in an editable state it SHALL make
**no change** and conclude **successfully**. It SHALL NOT modify a version in `WAITING_FOR_REVIEW`,
`IN_REVIEW`, or `PENDING_DEVELOPER_RELEASE`, and the upload itself SHALL NOT create a version. The gate is
ours, not the tool's: replacing a screenshot set is destructive, and the tool's behaviour on an in-review
version is undefined.

A release creates its version record in a separate, preceding step (capability `ios-appstore-release`); the
upload step consumes that record and never creates one, so the upload may never resolve a target by
creating it.

#### Scenario: An editable version receives the set

- **WHEN** the app has a version in `PREPARE_FOR_SUBMISSION`
- **THEN** the upload sends the composited set to that version's localizations

#### Scenario: No editable version is a green no-op

- **WHEN** the upload runs and the app has no version in an editable state
- **THEN** it sends nothing and concludes successfully

#### Scenario: A version under review is never touched

- **WHEN** no editable version exists but a version is `WAITING_FOR_REVIEW`
- **THEN** the upload does not modify that version's screenshots and does not create a new version

#### Scenario: The upload never creates its own target

- **WHEN** the screenshot upload runs during an App Store release
- **THEN** it resolves the version record created by the preceding attach step, and creates no version itself

## REMOVED Requirements

### Requirement: The screenshot upload runs only when its inputs change

**Reason**: The job it describes (`appstore-screenshots-upload`, in `appstore-screenshots.yml`) is deleted,
so the trigger it constrains no longer exists. The requirement was also unsatisfiable in its intent: it gated
the upload on the composite's **inputs** changing, while the upload's ability to do anything depends on a
**version state** it does not observe. A capture refresh merged while no version was editable concluded green
having uploaded nothing, and nothing re-ran it once a release later created an editable version — so the
committed raws could silently never reach the shipping listing, with every run green.

**Migration**: The screenshots now reach App Store Connect through the App Store release (capability
`ios-appstore-release`), which uploads to the version record it prepares. An operator refreshing captures or
headline copy commits them as before; the landing page still rebuilds on merge (`site-deploy.yml` triggers on
`screenshots/**` independently), and the next release carries them to the store. Correcting a version the
release has already prepared — a bad headline caught after a promote, or a fix after a rejection — is a
**manual upload in the App Store Connect console**, because a promote is single-shot per version (the `vX.Y`
tag is pushed on success and the tag-absent guard refuses a re-run).

The clause exempting `appstore-metadata-validate` from any such gate is preserved by that job's own
requirement ("Validation gates the merge and needs no credentials"), which is unchanged: it still runs on
every ref with no path filter, so the required check always posts.
