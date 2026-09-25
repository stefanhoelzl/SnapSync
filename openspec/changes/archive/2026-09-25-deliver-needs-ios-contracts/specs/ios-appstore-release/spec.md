## MODIFIED Requirements

### Requirement: A release promotes an already-gated build

The App Store release SHALL **promote an existing App Store Connect build** rather than build a new archive. The workflow `.github/workflows/ios-appstore-promote.yml` SHALL take a **required `build_number` input** (the `CFBundleVersion` of the build to promote) and a `submit` boolean defaulting to false; it SHALL NOT take a `version` input. It SHALL run as a **single `ubuntu-latest` job** with **no** Xcode, archive, export, upload, keychain, or signing certificate.

The promoted build's provenance is guaranteed at **upload time**, not re-verified at release time: `ios-deliver` (capability `ios-testflight-delivery`) uploads a build only on `refs/heads/main` and only when every merge gate (`ios-build`, `ios-test`, `ios-contracts`, capability `ios-ci`) concludes successfully, so every build in the pool is from a merged, gate-passing commit — its device compile, its simulator test suite, and its in-app port contracts and all-real journeys all green. The workflow SHALL therefore NOT re-check the released commit's ancestry of `main` or its check-runs.

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
