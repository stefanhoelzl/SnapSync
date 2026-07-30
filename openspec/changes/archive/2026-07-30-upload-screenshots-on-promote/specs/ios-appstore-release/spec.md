## ADDED Requirements

### Requirement: A release uploads the listing screenshots to the version it prepares

The release workflow SHALL upload the App Store listing screenshots to the App Store version record it
attached the build to, compositing them from the committed raw captures and the committed per-locale
headline file (capability `ios-appstore-metadata`). It SHALL do so **after** attaching the build — so the
version record exists and is resolvable — and **before** the submission readiness gate, so a version that
is submitted carries the current screenshots rather than a previous release's.

This SHALL be the **only** automated upload of the listing's screenshots (capability
`ios-appstore-metadata`). The workflow SHALL NOT pass the version to the upload script: the script resolves
the editable version itself, keeping one implementation of the editable-version gate and the `--replace`
semantics.

Because the release pushes the `vX.Y` tag on success and refuses a run whose tag already exists, a release
SHALL be understood as **single-shot per version**: it cannot be re-dispatched to correct the screenshots of
a version it already prepared. Such corrections are manual and outside this capability.

The job SHALL install the image-composition tooling it needs (an ImageMagick binary and a bold sans font),
and SHALL remain a single `ubuntu` job with no Xcode, signing certificate, or keychain, introducing no new
App Store Connect credential.

#### Scenario: A promoted version receives the committed screenshots

- **WHEN** the workflow attaches build N to its derived `X.Y` App Store version record
- **THEN** it composites the listing images from the committed raws and headline file and replaces that
  version's screenshot set with exactly that set

#### Scenario: A submitted version carries current screenshots

- **WHEN** the workflow is dispatched with `submit: true`
- **THEN** the screenshots are uploaded before the readiness gate runs, so the version submitted for review
  carries the committed screenshots

#### Scenario: The upload does not choose the version

- **WHEN** the screenshot upload runs during a release
- **THEN** the workflow passes no version to the upload script, which resolves the editable version itself

#### Scenario: A failed upload does not block a merge

- **WHEN** the screenshot upload fails during a release
- **THEN** the release run concludes red, posting no required status check and blocking no merge

#### Scenario: A prepared version's screenshots cannot be corrected by re-running

- **WHEN** a release has already succeeded for version `X.Y` and its screenshots need correcting
- **THEN** re-dispatching that build fails at the tag-absent guard before any App Store Connect mutation,
  and the correction is made manually

### Requirement: A cancelled release may leave an unpublished screenshot set partial

The release workflow SHALL retain `cancel-in-progress: true` on its per-`build_number` concurrency group.
Because the screenshot upload replaces the set destructively, a cancelled run MAY leave the target version's
screenshot set incomplete. This is accepted: the target is a version in an editable state
(`PREPARE_FOR_SUBMISSION` / `DEVELOPER_REJECTED`), which is **not** on the public storefront, so no
publicly visible set is ever partial, and a subsequent release or inputs-changed push restores it.

#### Scenario: A superseded release leaves no public partial set

- **WHEN** a release run is cancelled by a newer dispatch of the same `build_number` while replacing the
  screenshot set
- **THEN** only the unpublished editable version's set may be incomplete, and no version on the storefront
  is modified

#### Scenario: A later run restores the set

- **WHEN** a release is re-dispatched after a cancelled run left the set incomplete
- **THEN** the upload replaces the set with the full committed set
