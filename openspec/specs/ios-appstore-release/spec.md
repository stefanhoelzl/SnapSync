# ios-appstore-release Specification

## Purpose

Makes a **dispatched workflow run the trigger for an App Store release**, and the `vX.Y` git tag its **receipt**. Where `ios-testflight-delivery` uploads every `main` build to internal TestFlight, this capability is the *store* channel — and it **promotes one of those already-tested builds** rather than building a new one. One workflow (`ios-appstore-promote.yml`) takes a build number, DERIVES the store version from that build's own marketing version, finds-or-creates the matching App Store version record, attaches the build, applies the App Review details the repo owns, optionally **submits for review**, and — last — creates the tag on the build's origin commit.

Promotion, not a fresh build: every `main` merge already uploads a signed, gated build to App Store Connect (`ios-deliver`), so a release ships the **exact bits that were tested** and reuses a build already present, instead of compiling a never-run copy. The workflow is therefore a **single `ubuntu` job** — no Xcode, no signing certificates, no keychain.

The store version is **derived from the promoted build** (`preReleaseVersion.version`), never chosen by an input, so the version record and the build always match by construction. That version was computed by `ios.yml` as `max(floor, latest tag + minor 1)` (capability `ios-testflight-delivery`), so releasing successive builds walks the version forward, and a major jump is a committed floor bump.

The trigger is a dispatch rather than a tag push because a release must be **re-runnable**: a flaked tag-driven release demanded a delete-and-re-push dance, while a dispatch is a button. The tag is inverted to match — it *records* what shipped instead of commanding it — so it is checked **first** (an existing `vX.Y` refuses the release) and written **last** (a failed run leaves none, and retries cleanly), on the **build's origin commit** (resolved from the build number to its `ios.yml` run).

**Provenance is guaranteed at upload time, not re-checked at release.** `ios-deliver` runs only on `main` and only when both merge gates pass, so every promotable build is from a merged, gate-passing commit. The two surviving guards are that the derived version is well-formed `^\d+\.\d+$` and its `vX.Y` tag is free.

**Submitting is opt-in and gated.** It happens only when explicitly requested, and only if the tool's readiness report finds no blocker; the gate is **ours**, because a submission is outward-facing and effectively irreversible. What App Review reads is repo-owned like the rest of the listing — only the **contact details** live in secrets, because the repository is public and they are personal data. The app has no account or sign-up, so no demo credentials exist to give.

**The release is also the only automated writer of the listing's screenshots** (capability `ios-appstore-metadata`). It uploads them to the version record it prepares — after attaching the build, so the target exists, and before the submit gate, so a submitted version is never carrying the previous release's images. Nothing else uploads them: an upload can only write to a version in an editable state, and this is the only workflow that creates one. The corollary is that a release is **single-shot per version** — the `vX.Y` tag it pushes on success makes the tag-absent guard refuse a re-run — so it cannot correct screenshots it has already uploaded; that is a manual console operation.

**The release also writes the version's release notes, and nobody writes them by hand** (capability `changelog-labels`). `whatsNew` is the one part of the listing whose content differs per release, so it is *derived* — from the labelled pull requests merged between the nearest ancestor `vX.Y` tag of the build's origin commit and that commit — rather than committed. The derivation runs before the first App Store Connect mutation, so an unusable result costs a red run and nothing else; the application runs on every promote, so a promote-only run leaves the version submit-ready. This closes the gap that refused the 0.2 submission: `asc review doctor` reported no blocker on a version whose `en-US` notes were missing, and the submit's own preflight refused it.

Decision record: `changes/archive/2026-07-15-add-appstore-release-pipeline` (the pipeline),
`changes/archive/2026-07-16-close-appstore-submission-gaps` (the copyright — and why it is set at
record creation rather than by the declarative metadata push),
`changes/archive/2026-07-16-dispatch-driven-release-and-submission` (the dispatch trigger, the
tag-as-receipt inversion, the submission path — and why an unlisted request *follows* a submission
while App Store Connect's "Private" is a one-way door to org-only distribution),
`changes/archive/2026-07-19-promote-appstore-builds` (promote an existing build instead of building
fresh; the store version derived from the build; provenance as an upload-time guarantee),
`changes/archive/2026-07-30-upload-screenshots-on-promote` (the release became the only automated
writer of the listing's screenshots — why the upload sits between the attach and the submit gate,
and why `cancel-in-progress` deliberately stays `true`),
`changes/archive/2026-07-31-derive-release-notes-from-labels` (the release notes derived from the
labelled pull requests — why the pull request is the unit rather than the commit, why the derivation
refuses an unconfigured generation, and why the generator's config is read from the promoted build's
own commit)
## Requirements
### Requirement: A release promotes an already-gated build

The App Store release SHALL **promote an existing App Store Connect build** rather than build a new archive. The workflow `.github/workflows/ios-appstore-promote.yml` SHALL take a **required `build_number` input** (the `CFBundleVersion` of the build to promote) and a `submit` boolean defaulting to false; it SHALL NOT take a `version` input. It SHALL run as a **single `ubuntu-latest` job** with **no** Xcode, archive, export, upload, keychain, or signing certificate.

The promoted build's provenance is guaranteed at **upload time**, not re-verified at release time: `ios-deliver` (capability `ios-testflight-delivery`) uploads a build only on `refs/heads/main` and only when both merge gates (`ios-build`, `ios-test`, capability `ios-ci`) conclude successfully, so every build in the pool is from a merged, gate-passing commit. The workflow SHALL therefore NOT re-check the released commit's ancestry of `main` or its check-runs.

Two guards SHALL remain: the **derived store version** (capability requirement "The build is attached to its App Store version record") SHALL match `^\d+\.\d+$`, and the **`vX.Y` tag** SHALL NOT already exist.

#### Scenario: The release promotes an existing build, not a fresh one
- **WHEN** the workflow is dispatched with `build_number` N
- **THEN** it runs a single `ubuntu` job that promotes the existing App Store Connect build N, performing no archive, export, upload or signing

#### Scenario: Provenance is not re-verified at release time
- **WHEN** a build is promoted
- **THEN** the workflow does not check the released commit's ancestry of `main` or its check-runs, relying on `ios-deliver`'s upload-time gating

#### Scenario: A build whose derived version already shipped is refused
- **WHEN** the promoted build's derived store version `X.Y` already has a `vX.Y` tag
- **THEN** the workflow fails before any App Store Connect mutation, and does not move the existing tag

### Requirement: The build is attached to its App Store version record

The workflow SHALL resolve the promoted build in App Store Connect by its `build_number` (`CFBundleVersion`), waiting until it is discoverable and `processingState` `VALID`. The **store version** SHALL be **derived from that build's own marketing version** (`preReleaseVersion.version`), read from the same fetch that resolves the build — never from an input. The workflow SHALL locate the App Store version record whose `versionString` equals that derived store version and whose platform is `IOS`, **creating it if absent** and reusing it if present, and SHALL attach the build to that record. Because the record's `versionString` is created **from** the build's own version, the two always match. Attaching a build SHALL NOT by itself submit the version for App Store Review. The attach step SHALL be idempotent: a record already carrying the build is a green no-op.

#### Scenario: The store version is derived from the build
- **WHEN** the promoted build carries marketing version `1.2`
- **THEN** the workflow finds-or-creates the `1.2` App Store version record (platform `IOS`) and attaches the build, without reading a version from any input

#### Scenario: A later release creates its record
- **WHEN** the derived version is `1.1` and no `1.1` App Store version record exists
- **THEN** the workflow creates the `1.1` record and attaches the build

#### Scenario: Attaching does not submit
- **WHEN** the build has been attached and submission was not requested
- **THEN** the workflow concludes without submitting the version for App Store Review

#### Scenario: Re-running an attached release is a green no-op
- **WHEN** the workflow is re-run for a version record that already has the build attached
- **THEN** it makes no further App Store Connect mutation and concludes successfully

### Requirement: Release identity and credentials

The workflow SHALL authenticate to App Store Connect with the **existing** Admin API key (`ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_API_PRIVATE_KEY`) and SHALL NOT introduce a new App Store Connect credential. It MAY read non-credential App Review contact details from repository secrets, which grant no access to App Store Connect and exist only because the repository is public and the values are personal data (capability requirement "App Review details come from the repo and secrets"). Because the release **promotes an already-built build**, it injects **no** build number and holds **no** signing certificate or keychain.

#### Scenario: No new App Store Connect credential
- **WHEN** the workflow resolves, attaches, and optionally submits the build
- **THEN** it authenticates using only the existing Admin App Store Connect API key, and introduces no new credential that grants App Store Connect access

#### Scenario: The promotion holds no signing material
- **WHEN** the release workflow runs
- **THEN** it uses no keychain and no signing certificate, because it builds and signs nothing

### Requirement: The release workflow never gates merges

`.github/workflows/ios-appstore-promote.yml` SHALL post **no** branch-protection status check and SHALL NOT be added to `.github/rulesets/main.json`: it runs only when explicitly dispatched and never as part of a pull request's checks, so requiring it would freeze every merge. A failed release SHALL conclude the run as **failure (red)** so it is visible, while blocking no merge.

#### Scenario: A failed release blocks nothing
- **WHEN** any step of a dispatched release fails
- **THEN** the run concludes as failure (red) and is plainly visible, while no merge is blocked and no required check is affected

### Requirement: A created version record carries the copyright

When the release workflow **creates** an App Store version record, it SHALL set the record's `copyright`
attribute in the create request, so the record is never born without one. The copyright SHALL be a
committed constant of the form `YYYY Name`, where `YYYY` is the year of **first publication** — it SHALL
NOT track the current calendar year.

The workflow SHALL NOT modify the copyright of a version record that **already exists**: an existing
record is reused as-is (capability requirement "The build is attached to its App Store version record"),
so a value set by hand in the ASC web console is left intact. Copyright is therefore enforced at birth
rather than reconciled per run — it is a version **attribute**, so it cannot ride the declarative
per-locale metadata push (whose schema is closed to it), and the App Store Connect API does not expose it
for read-back through the metadata tool, making per-run drift detection impossible.

#### Scenario: A created record is born with the copyright
- **WHEN** the release workflow creates the App Store version record for a `vX.Y` tag because none exists
- **THEN** the create request sets the record's `copyright`, and the resulting record carries it

#### Scenario: An existing record's copyright is not touched
- **WHEN** the release workflow finds an App Store version record that already exists for the store version
- **THEN** it reuses that record and makes no change to its `copyright`

#### Scenario: The copyright year does not track the calendar
- **WHEN** a release runs in a calendar year later than the app's first publication
- **THEN** the copyright applied to a newly created record still carries the year of first publication

### Requirement: A dispatch drives an App Store release and records it as a tag

App Store releases SHALL be performed by `.github/workflows/ios-appstore-promote.yml` triggered by `on: workflow_dispatch`, taking a **required `build_number` input** and a `submit` boolean defaulting to false. The store version SHALL be **derived** from the promoted build (capability requirement "The build is attached to its App Store version record"), and the workflow SHALL fail fast if that derived version does not match `^\d+\.\d+$`. Because the scheme is two-part, a patch is expressed as a minor bump (there is no `X.Y.Z`).

The workflow SHALL **create the `vX.Y` tag** (`v` + the derived version) on the **build's origin commit**, resolved from `build_number` to the `ios.yml` run whose `run_number` equals it (restricted to `head_branch=main`) and thence to its `head_sha`. If that origin commit cannot be resolved (for example the run was deleted), the workflow SHALL **fail** rather than tag a guessed or wrong commit. The tag message SHALL record the promoted `build_number`. The workflow SHALL verify **before any App Store Connect mutation** that the tag does not already exist and fail if it does, and SHALL create the tag **only after** every other step has succeeded — so a failed run leaves no tag and can be retried, and a released tag is never moved.

The `concurrency` group SHALL key on the `build_number` input, not on `github.ref`. The workflow SHALL hold `contents: write` permission for the tag push.

#### Scenario: A dispatched promotion records the derived version as a tag
- **WHEN** the workflow is dispatched with `build_number` N and build N's marketing version is `1.2`
- **THEN** on success it creates the tag `v1.2` on build N's origin commit, with the build number in the tag message

#### Scenario: A malformed derived version fails fast
- **WHEN** the promoted build's marketing version does not match `^\d+\.\d+$` (for example a pre-change `0.1.0`)
- **THEN** the workflow fails before any App Store Connect mutation

#### Scenario: An existing tag refuses the release before mutating
- **WHEN** the derived version's `vX.Y` tag already exists
- **THEN** the workflow fails before any App Store Connect mutation and does not move the existing tag

#### Scenario: An unresolvable origin commit fails rather than mis-tags
- **WHEN** build N's origin commit cannot be resolved from an `ios.yml` run
- **THEN** the workflow fails with a clear message rather than creating a `vX.Y` tag on a wrong or placeholder commit

#### Scenario: A failed release leaves no tag
- **WHEN** a dispatched release fails at any step before the tag is created
- **THEN** no `vX.Y` tag exists, and re-dispatching is not blocked by the tag guard

### Requirement: Submission is explicit, and refused when the version is not ready

The workflow SHALL submit the version for App Store Review **only** when the `submit` input is explicitly true,
and SHALL NOT submit otherwise. Before submitting, it SHALL run the metadata tool's readiness report
(`asc review doctor`) and SHALL refuse to submit — concluding the run as failure and reporting the blocking
checks — if **any** blocking check is present.

The gate is the workflow's own, not the tool's: a submission is outward-facing and effectively irreversible, so
an unready version SHALL NOT reach App Review even when submission was explicitly requested.

#### Scenario: An explicit submit reaches App Review
- **WHEN** the `submit` input is true and `asc review doctor` reports no blocking check
- **THEN** the workflow submits the version for App Store Review

#### Scenario: An unready version is refused even when submit was requested
- **WHEN** the `submit` input is true and `asc review doctor` reports one or more blocking checks
- **THEN** the workflow does not submit, concludes as failure, and reports the blocking checks

#### Scenario: The default release does not submit
- **WHEN** the workflow is dispatched without setting `submit`
- **THEN** the version is attached and tagged, and is not submitted for App Store Review

### Requirement: App Review details come from the repo and secrets

The workflow SHALL apply the version's App Store review details on **every** release run, whether or not the
version is being submitted, so that a release that does not submit still leaves the version submit-ready. It SHALL
find-or-create the version's review detail and apply the committed values, overwriting any that differ —
including one edited directly in the ASC web console.

The **reviewer notes** SHALL be a committed file, and SHALL NOT be added as a key to the metadata tool's
canonical per-locale files, whose schema is closed and rejects unknown fields — an unknown key there fails
`appstore-metadata-validate`, a required status check, and freezes merges. The notes file SHALL NOT be
structured per locale: an App Store review detail is version-scoped and carries no locale.

The App Review **contact details** (name, phone, email) SHALL be supplied from repository secrets, and SHALL
NOT be committed to the repository nor passed as workflow inputs — the repository is public, and both would
publish them. The workflow SHALL declare that **no demo account is required**, because the app has no account
or sign-up and therefore no credentials to supply.

#### Scenario: A release applies the committed review notes
- **WHEN** a release run reaches the review-details step
- **THEN** the version's review detail carries the committed notes file's content, and the contact details from secrets

#### Scenario: Review details are applied without submitting
- **WHEN** a release runs with the `submit` input false
- **THEN** the review details are still applied, and the version's missing-review-details blocker is cleared

#### Scenario: A console hand-edit is overwritten
- **WHEN** the review notes were changed in the ASC web console after the last release, and a release runs
- **THEN** the run overwrites them back to the committed file's content

#### Scenario: The notes file does not break metadata validation
- **WHEN** the reviewer-notes file is committed and `appstore-metadata-validate` runs
- **THEN** validation succeeds and the file is not decoded as canonical metadata

#### Scenario: No demo account is offered
- **WHEN** the review details are applied
- **THEN** they declare that no demo account is required

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

The derivation SHALL happen **before the first App Store Connect mutation of the run**, and the
workflow SHALL fail there — mutating nothing — if the rendered notes exceed the field's 4000-character
limit. The rendered notes SHALL be echoed into the run's output, so the operator can read exactly what
was published.

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

#### Scenario: Notes are applied without submitting

- **WHEN** a release runs with the `submit` input false
- **THEN** the release notes are still applied, and the version's missing-`whatsNew` submission
  blocker is cleared

#### Scenario: Over-long notes fail before anything is mutated

- **WHEN** the rendered notes exceed 4000 characters
- **THEN** the run fails before any App Store Connect mutation and no version record is created,
  attached, or modified

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

