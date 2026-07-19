## ADDED Requirements

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

## MODIFIED Requirements

### Requirement: The build is attached to its App Store version record

The workflow SHALL resolve the promoted build in App Store Connect by its `build_number` (`CFBundleVersion`), waiting until it is discoverable and `processingState` `VALID`. The **store version** SHALL be **derived from that build's own marketing version** (`preReleaseVersion.versionString`), read from the same fetch that resolves the build — never from an input. The workflow SHALL locate the App Store version record whose `versionString` equals that derived store version and whose platform is `IOS`, **creating it if absent** and reusing it if present, and SHALL attach the build to that record. Because the record's `versionString` is created **from** the build's own version, the two always match. Attaching a build SHALL NOT by itself submit the version for App Store Review. The attach step SHALL be idempotent: a record already carrying the build is a green no-op.

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

## REMOVED Requirements

### Requirement: A release only builds a merged, fully-green commit
**Reason**: Release-time verification is retired. The release no longer builds; it promotes a build that `ios-deliver` already uploaded, which runs only on `main` and only when both merge gates pass — so provenance (merged, gate-passing) is guaranteed at upload time and re-checking is redundant.
**Migration**: See ADDED "A release promotes an already-gated build" (provenance guarantee + the two surviving guards: derived version format, tag absent).

### Requirement: Release builds use the production APNs environment
**Reason**: The release builds no archive, so there is no release build to configure. The promoted build is a `main` `ios-deliver` build, already built Release with `APS_ENVIRONMENT=production` / `APNS_ENV=production`.
**Migration**: Production APNs for the promoted build is guaranteed by capability `ios-testflight-delivery`, requirement "Distribution builds use the production APNs environment".

### Requirement: The release version is injected per release, never committed
**Reason**: The store version is no longer injected on an `xcodebuild` line (the release builds nothing); it is **derived** from the promoted build's own marketing version, which `ios.yml` computed and baked at build time.
**Migration**: See capability `ios-testflight-delivery`, requirement "Monotonic build numbers from the CI run" (the computed `max(floor, latest tag + minor 1)` version) and capability requirement "The build is attached to its App Store version record" (the store version is derived from the build).
