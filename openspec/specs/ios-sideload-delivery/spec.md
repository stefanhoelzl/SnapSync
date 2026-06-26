# ios-sideload-delivery Specification

## Purpose
TBD - created by archiving change sideload-dev-ipa. Update Purpose after archive.
## Requirements
### Requirement: Development IPA published as an artifact on every push

The system SHALL, on **every push** to **any** ref, export a **development-signed** IPA (`ExportOptions` `method` `development`) of the iOS device app from the gate archive and upload it as a **GitHub Actions artifact** in the `ios-build` job. The artifact SHALL use a short retention (`retention-days: 1`) and SHALL carry the build number in its name (`github.run_number`). This artifact is the mechanism by which any branch is installable on a registered physical device **before merge** (a guarantee previously served by TestFlight in capability `ios-testflight-delivery`). There SHALL be **no ref filter** on producing the artifact.

#### Scenario: A push on any branch publishes a development IPA artifact
- **WHEN** a commit is pushed to any ref (including `main`)
- **THEN** the `ios-build` job exports a development-signed IPA from the gate archive and uploads it as a GitHub Actions artifact named with the run number and retained for 1 day

#### Scenario: The artifact installs on a registered device before merge
- **WHEN** the development IPA artifact for a branch build is downloaded and installed on a device whose UDID is registered on the team
- **THEN** the device app installs and runs without any TestFlight upload

### Requirement: Development signing reuses the imported Development certificate

The development export SHALL sign with the already-imported **Apple Development** certificate via **automatic** signing and `xcodebuild -exportArchive -allowProvisioningUpdates`, authenticated by the **Admin** App Store Connect API key, which manages the **development** provisioning profile in the cloud. The export SHALL NOT mint a new certificate. The generated development profile SHALL include the team's **registered device UDIDs**, so the resulting IPA installs on those devices.

#### Scenario: Development export reuses the imported cert, mints no new one
- **WHEN** the development IPA is exported on the runner
- **THEN** `xcodebuild -allowProvisioningUpdates` signs with the imported Apple Development certificate and the ASC Admin key manages the development provisioning profile, and no new certificate is created

#### Scenario: The development profile includes registered devices
- **WHEN** a device's UDID is registered on the team and the development IPA is exported
- **THEN** the embedded development provisioning profile includes that UDID

### Requirement: The development export reuses the single gate archive

The development IPA SHALL be exported from the **same** signed archive that is the `ios-build` merge gate (and, on `main`, the TestFlight delivery source). The device (`iosArm64`) app SHALL be compiled **exactly once** per push; producing the development IPA SHALL NOT trigger a second archive or compile.

#### Scenario: One compile feeds both the gate and the development export
- **WHEN** a commit is pushed
- **THEN** the device app is archived exactly once, and the development IPA is exported from that same archive

### Requirement: Development delivery is non-gating

The development export and artifact-upload steps SHALL run with `continue-on-error` so they do **not** fail the `ios-build` status check. The merge gate is the signed-archive compile alone; a development signing, export, or upload failure SHALL leave the `ios-build` check green and merges unblocked, while the failed step remains visible in the run.

#### Scenario: A development export flake does not block merges
- **WHEN** the signed archive compiles but the development export or artifact upload fails
- **THEN** the `ios-build` status check still concludes as success (green) and merges are not blocked, while the failed step is visible in the run

