# ios-appstore-release Specification

## Purpose
TBD - created by archiving change add-appstore-release-pipeline. Update Purpose after archive.
## Requirements
### Requirement: A `vX.Y` tag drives an App Store release

App Store releases SHALL be performed by a dedicated workflow `.github/workflows/ios-release.yml` triggered by `on: push: tags: ['v*']`. The workflow SHALL accept only tags matching `^v\d+\.\d+$` (two-part, Apple-style) and SHALL fail fast on any other tag shape. The **store version** SHALL be the tag with its leading `v` removed (`v1.0` → `1.0`), used as the build's `MARKETING_VERSION` and as the App Store version record's `versionString`. Because the scheme is two-part, a patch is expressed as a minor bump (there is no `X.Y.Z`).

#### Scenario: A well-formed tag releases its version
- **WHEN** the tag `v1.2` is pushed
- **THEN** `ios-release.yml` runs and treats `1.2` as the store version for the build and the App Store version record

#### Scenario: A malformed tag fails fast
- **WHEN** a tag that does not match `^v\d+\.\d+$` is pushed (e.g. `v1`, `v1.2.3`, `1.0`, `release-1`)
- **THEN** the workflow fails before building or uploading anything

### Requirement: The release version is injected from the tag, never committed

The workflow SHALL set `MARKETING_VERSION` to the tag-derived store version on the `xcodebuild` command line, overriding the committed fallback for that build only, and SHALL NOT edit any committed version file (`Config.xcconfig` or `project.pbxproj`). The injected version SHALL apply to **both** the app and the background-upload extension targets in the one archive, so their `CFBundleShortVersionString` stay in lockstep. Because no commit carries a version bump, `main`/alpha builds continue to carry the pinned fallback and no first-of-version Beta App Review is triggered by a release.

#### Scenario: The store version is baked without a commit
- **WHEN** `ios-release.yml` archives the app for tag `v1.2`
- **THEN** `xcodebuild` is invoked with `MARKETING_VERSION=1.2`, both the app and extension bundles carry `CFBundleShortVersionString` `1.2`, and no committed file is modified by the run

#### Scenario: A release does not disturb the alpha channel
- **WHEN** a `vX.Y` tag is released
- **THEN** the committed `MARKETING_VERSION` fallback is unchanged, so subsequent `main` builds still carry the fallback and no first-of-version Beta App Review is triggered on the alpha channel

### Requirement: A release only builds a merged, fully-green commit

Before building or uploading, the workflow SHALL verify that the tagged commit is an **ancestor of `origin/main`** and that **every check-run on the tagged commit's SHA concluded `success`**, excluding the release workflow's own in-progress runs; if either check fails, the workflow SHALL fail before building or uploading. The green check SHALL include the "allowed-red" delivery plumbing (`ios-deliver`, `ios-promote`): a commit whose full pipeline — including promotion to alpha — is not green SHALL NOT reach the App Store. Because `ios-promote` is idempotent (capability `ios-testflight-delivery`), a cancelled or red `ios-promote` on the target commit is remedied by re-running that job until green and re-pushing the tag.

#### Scenario: A tag off main is rejected
- **WHEN** a `vX.Y` tag points at a commit that is not an ancestor of `origin/main`
- **THEN** the workflow fails before building or uploading

#### Scenario: A tag on a red commit is rejected
- **WHEN** a `vX.Y` tag points at a commit on `main` that has any non-`success` check-run (other than the release workflow's own runs), such as a cancelled `ios-promote`
- **THEN** the workflow fails before building or uploading, and the remedy is to re-run the idempotent `ios-promote` to green and re-push the tag

#### Scenario: A tag on a fully-green main commit proceeds
- **WHEN** a `vX.Y` tag points at a commit that is an ancestor of `origin/main` and whose every check-run (excluding the release run's own) concluded `success`
- **THEN** the workflow proceeds to build, upload and attach

### Requirement: The build is attached to its App Store version record

After uploading the build to App Store Connect, the workflow SHALL locate the App Store version record whose `versionString` equals the store version and whose platform is `IOS`, **creating it if absent** and reusing it if present, and SHALL attach the uploaded build to that record. The workflow SHALL NOT submit the version for App Store Review — it stops once the build is attached. The attach step SHALL be idempotent: re-running against a version record that already has the build attached SHALL conclude successfully without a duplicate mutation.

#### Scenario: First release reuses the pre-existing record
- **WHEN** `v1.0` is released and an editable `1.0` App Store version record already exists with no build
- **THEN** the workflow reuses that record and attaches the uploaded `1.0` build to it, without creating a second record

#### Scenario: A later release creates its record
- **WHEN** `v1.1` is released and no `1.1` App Store version record exists
- **THEN** the workflow creates the `1.1` record (platform `IOS`) and attaches the uploaded build to it

#### Scenario: The release stops before submit
- **WHEN** the build has been attached to its version record
- **THEN** the workflow concludes without submitting the version for App Store Review

#### Scenario: Re-running an attached release is a green no-op
- **WHEN** the workflow is re-run for a version record that already has the build attached
- **THEN** it makes no further App Store Connect mutation and concludes successfully

### Requirement: Release builds use the production APNs environment

The release archive SHALL be built with `APS_ENVIRONMENT=production` and `APNS_ENV=production`, so the App Store build's `aps-environment` entitlement is `production` and can receive production APNs pushes.

#### Scenario: The release build is production-APNs
- **WHEN** `ios-release.yml` archives the app for a `vX.Y` tag
- **THEN** `xcodebuild` is invoked with `APS_ENVIRONMENT=production` and `APNS_ENV=production`, and the resulting build's `aps-environment` entitlement is `production`

### Requirement: Release identity and credentials

The release build's `CURRENT_PROJECT_VERSION` (build number) SHALL be injected from the release workflow's `github.run_number`. Because the store version is a fresh `MARKETING_VERSION` train, distinct from the alpha channel's fallback, build numbers never collide across the two channels. The workflow SHALL authenticate to App Store Connect with the **existing** Admin API key (`ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_API_PRIVATE_KEY`) and SHALL NOT introduce a new secret.

#### Scenario: Build number comes from the run
- **WHEN** `ios-release.yml` builds for a tag in a run whose `github.run_number` is N
- **THEN** the release build's `CFBundleVersion` is N

#### Scenario: No new credential
- **WHEN** the workflow uploads and attaches the build
- **THEN** it uses only the existing Admin App Store Connect API key and introduces no new secret

### Requirement: The release workflow never gates merges

`ios-release.yml` SHALL post **no** branch-protection status check and SHALL NOT be added to `.github/rulesets/main.json`: it never runs on a pull-request branch, so requiring it would freeze every merge. A failed release SHALL conclude the run as **failure (red)** so it is visible, while blocking no merge.

#### Scenario: A failed release blocks nothing
- **WHEN** the build, upload, or attach step fails on a `vX.Y` tag
- **THEN** the `ios-release` run concludes as failure (red) and is plainly visible, while no merge is blocked and no required check is affected

