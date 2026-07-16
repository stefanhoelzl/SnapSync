## RENAMED Requirements

- FROM: `### Requirement: A `vX.Y` tag drives an App Store release`
- TO: `### Requirement: A dispatch drives an App Store release and records it as a tag`

- FROM: `### Requirement: The release version is injected from the tag, never committed`
- TO: `### Requirement: The release version is injected per release, never committed`

## MODIFIED Requirements

### Requirement: A dispatch drives an App Store release and records it as a tag

App Store releases SHALL be performed by a dedicated workflow `.github/workflows/ios-release.yml` triggered by
`on: workflow_dispatch`, taking a required `version` input and a `submit` boolean input defaulting to
**false**. The workflow SHALL accept only a `version` matching `^\d+\.\d+$` (two-part, Apple-style) and SHALL
fail fast on any other shape. The **store version** SHALL be that input, used as the build's
`MARKETING_VERSION` and as the App Store version record's `versionString`. Because the scheme is two-part, a
patch is expressed as a minor bump (there is no `X.Y.Z`).

The workflow SHALL **create** the `vX.Y` tag (`v` + the version input) on the released commit, as the durable
record of what shipped. It SHALL verify **before building** that the tag does not already exist and SHALL fail
if it does, and it SHALL create the tag **only after** every other step has succeeded — so that a failed run
leaves no tag and can be retried, and a released tag is never moved. The workflow SHALL release the dispatched
ref's commit; it takes no commit or SHA input.

The workflow's `concurrency` group SHALL key on the `version` input, not on `github.ref`, so that concurrent
releases of distinct versions do not cancel one another. The workflow SHALL hold `contents: write` permission
for the tag push.

#### Scenario: A dispatched release builds and records its version
- **WHEN** the workflow is dispatched with `version` `1.2`
- **THEN** it treats `1.2` as the store version for the build and the App Store version record, and on success creates the tag `v1.2` on the released commit

#### Scenario: A malformed version fails fast
- **WHEN** the workflow is dispatched with a `version` that does not match `^\d+\.\d+$` (e.g. `1`, `1.2.3`, `v1.0`, `release-1`)
- **THEN** the workflow fails before building or uploading anything

#### Scenario: An existing tag refuses the release before building
- **WHEN** the workflow is dispatched with a `version` whose `vX.Y` tag already exists
- **THEN** the workflow fails before building or uploading anything, and does not move the existing tag

#### Scenario: A failed release leaves no tag
- **WHEN** a dispatched release fails at any step before the tag is created
- **THEN** no `vX.Y` tag exists, and re-dispatching the same version is not blocked by the tag guard

#### Scenario: Concurrent releases of distinct versions do not cancel each other
- **WHEN** a release of `1.1` is in flight and a release of `1.2` is dispatched
- **THEN** the `1.1` run is not cancelled

### Requirement: The release version is injected per release, never committed

The workflow SHALL set `MARKETING_VERSION` to the store version from the `version` input on the `xcodebuild`
command line, overriding the committed fallback for that build only, and SHALL NOT edit any committed version
file (`Config.xcconfig` or `project.pbxproj`). The injected version SHALL apply to **both** the app and the
background-upload extension targets in the one archive, so their `CFBundleShortVersionString` stay in lockstep.
Because no commit carries a version bump, `main`/alpha builds continue to carry the pinned fallback and no
first-of-version Beta App Review is triggered by a release.

#### Scenario: The store version is baked without a commit
- **WHEN** `ios-release.yml` archives the app for a release dispatched with `version` `1.2`
- **THEN** `xcodebuild` is invoked with `MARKETING_VERSION=1.2`, both the app and extension bundles carry `CFBundleShortVersionString` `1.2`, and no committed file is modified by the run

#### Scenario: A release does not disturb the alpha channel
- **WHEN** a version is released
- **THEN** the committed `MARKETING_VERSION` fallback is unchanged, so subsequent `main` builds still carry the fallback and no first-of-version Beta App Review is triggered on the alpha channel

### Requirement: A release only builds a merged, fully-green commit

Before building or uploading, the workflow SHALL verify that the released commit is an **ancestor of
`origin/main`** and that **every check-run on the released commit's SHA concluded `success`**, excluding
check-runs produced by the release workflow itself; if either check fails, the workflow SHALL fail before
building or uploading. Because a release may be dispatched from any ref, the ancestor check is what confines a
release to merged code, and it SHALL NOT be skipped.

The self-exclusion SHALL identify the workflow's own check-runs by the **check-suites its runs produced for
that commit**, and SHALL therefore exclude **every** run of the release workflow on that commit, whatever its
state — not merely those still in progress. A release that failed leaves a completed, non-success check-run on
the commit; were that treated as foreign, it would refuse every subsequent attempt and render the commit
permanently unreleasable.

The green check SHALL include the "allowed-red" delivery plumbing
(`ios-deliver`, `ios-promote`): a commit whose full pipeline — including promotion to alpha — is not green
SHALL NOT reach the App Store. Because `ios-promote` is idempotent (capability `ios-testflight-delivery`), a
cancelled or red `ios-promote` on the target commit is remedied by re-running that job until green and
re-dispatching.

#### Scenario: A release off main is rejected
- **WHEN** a release is dispatched from a ref whose commit is not an ancestor of `origin/main`
- **THEN** the workflow fails before building or uploading

#### Scenario: A release on a red commit is rejected
- **WHEN** a release is dispatched for a commit on `main` that has any non-`success` check-run (other than the release workflow's own runs), such as a cancelled `ios-promote`
- **THEN** the workflow fails before building or uploading, and the remedy is to re-run the idempotent `ios-promote` to green and re-dispatch

#### Scenario: A release on a fully-green main commit proceeds
- **WHEN** a release is dispatched for a commit that is an ancestor of `origin/main` and whose every check-run (excluding the release workflow's own) concluded `success`
- **THEN** the workflow proceeds to build, upload and attach

#### Scenario: A previously failed release does not block its own retry
- **WHEN** a release for a commit failed and left a completed, non-success check-run of the release workflow on that commit, and a release is dispatched for that commit again
- **THEN** the green check ignores that check-run and the release proceeds

### Requirement: The build is attached to its App Store version record

After uploading the build to App Store Connect, the workflow SHALL locate the App Store version record whose
`versionString` equals the store version and whose platform is `IOS`, **creating it if absent** and reusing it
if present, and SHALL attach the uploaded build to that record. Attaching a build SHALL NOT by itself submit
the version for App Store Review; submission is governed by its own requirement and never happens implicitly.
The attach step SHALL be idempotent: re-running against a version record that already has the build attached
SHALL conclude successfully without a duplicate mutation.

#### Scenario: First release reuses the pre-existing record
- **WHEN** `1.0` is released and an editable `1.0` App Store version record already exists with no build
- **THEN** the workflow reuses that record and attaches the uploaded `1.0` build to it, without creating a second record

#### Scenario: A later release creates its record
- **WHEN** `1.1` is released and no `1.1` App Store version record exists
- **THEN** the workflow creates the `1.1` record (platform `IOS`) and attaches the uploaded build to it

#### Scenario: Attaching does not submit
- **WHEN** the build has been attached and submission was not explicitly requested
- **THEN** the workflow concludes without submitting the version for App Store Review

#### Scenario: Re-running an attached release is a green no-op
- **WHEN** the workflow is re-run for a version record that already has the build attached
- **THEN** it makes no further App Store Connect mutation and concludes successfully

### Requirement: Release builds use the production APNs environment

The release archive SHALL be built with `APS_ENVIRONMENT=production` and `APNS_ENV=production`, so the App
Store build's `aps-environment` entitlement is `production` and can receive production APNs pushes.

#### Scenario: The release build is production-APNs
- **WHEN** `ios-release.yml` archives the app for a dispatched release
- **THEN** `xcodebuild` is invoked with `APS_ENVIRONMENT=production` and `APNS_ENV=production`, and the resulting build's `aps-environment` entitlement is `production`

### Requirement: Release identity and credentials

The release build's `CURRENT_PROJECT_VERSION` (build number) SHALL be injected from the release workflow's
`github.run_number`. Because the store version is a fresh `MARKETING_VERSION` train, distinct from the alpha
channel's fallback, build numbers never collide across the two channels.

The workflow SHALL authenticate to App Store Connect with the **existing** Admin API key (`ASC_KEY_ID`,
`ASC_ISSUER_ID`, `ASC_API_PRIVATE_KEY`) and SHALL NOT introduce a new App Store Connect credential. It MAY
read non-credential App Review contact details from repository secrets, which grant no access to App Store
Connect and exist only because the repository is public and the values are personal data (capability
requirement "App Review details come from the repo and secrets").

#### Scenario: Build number comes from the run
- **WHEN** `ios-release.yml` builds a release in a run whose `github.run_number` is N
- **THEN** the release build's `CFBundleVersion` is N

#### Scenario: No new App Store Connect credential
- **WHEN** the workflow uploads and attaches the build
- **THEN** it authenticates using only the existing Admin App Store Connect API key, and introduces no new credential that grants App Store Connect access

### Requirement: The release workflow never gates merges

`ios-release.yml` SHALL post **no** branch-protection status check and SHALL NOT be added to
`.github/rulesets/main.json`: it runs only when explicitly dispatched and never as part of a pull request's
checks, so requiring it would freeze every merge. A failed release SHALL conclude the run as **failure (red)**
so it is visible, while blocking no merge.

#### Scenario: A failed release blocks nothing
- **WHEN** any step of a dispatched release fails
- **THEN** the `ios-release` run concludes as failure (red) and is plainly visible, while no merge is blocked and no required check is affected

## ADDED Requirements

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
- **THEN** the version is built, attached and tagged, and is not submitted for App Store Review

### Requirement: App Review details come from the repo and secrets

The workflow SHALL apply the version's App Store review details on **every** release run, whether or not the
version is being submitted, so that a build-only release leaves the version submit-ready. It SHALL
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
