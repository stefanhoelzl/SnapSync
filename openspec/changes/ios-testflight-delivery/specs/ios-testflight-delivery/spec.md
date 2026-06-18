## ADDED Requirements

### Requirement: Signed device build delivered to TestFlight on every push to main

The system SHALL provide a GitHub Actions job (`ios-release` in `.github/workflows/ios.yml`) that, on every push to `main`, builds the iOS **device** app (`iosArm64`), code-signs it, archives it, exports a signed IPA, and uploads it to **TestFlight** via App Store Connect. The job SHALL be gated to `main` (`if: github.ref == 'refs/heads/main'`) and SHALL run only after the `ios-build` and `ios-test` gates pass (`needs: [ios-build, ios-test]`), with **no path filter**, so every green `main` commit produces a TestFlight build. The job SHALL run on a `macos-26` hosted runner with the runner's GM Xcode.

#### Scenario: A green push to main uploads a build to TestFlight
- **WHEN** a commit is pushed to `main` and the `ios-build` and `ios-test` gates pass
- **THEN** the `ios-release` job builds, signs, archives, and uploads a signed IPA to TestFlight via App Store Connect

#### Scenario: A push to a non-main branch does not upload
- **WHEN** a commit is pushed to a branch other than `main`
- **THEN** the `ios-release` job is skipped and no TestFlight upload occurs, while the `ios-build` and `ios-test` gates still run

#### Scenario: Release does not gate merges
- **WHEN** the iOS workflow runs on a pull-request branch
- **THEN** the `ios-release` job is skipped and is not a required status check; only `ios-build` and `ios-test` gate merges

### Requirement: Cloud-managed code signing

The `ios-release` job SHALL sign via **cloud-managed signing**: `xcodebuild -allowProvisioningUpdates` authenticated by an App Store Connect API key with the **Admin** role, which manages **both** the distribution certificate and the App Store provisioning profile in the cloud, for the archive and the export. The pipeline SHALL NOT use fastlane or `match`, SHALL NOT import or store a distribution certificate, and SHALL NOT pre-create or store a provisioning profile. The signed IPA SHALL be uploaded to TestFlight via `Apple-Actions/upload-testflight-build`.

#### Scenario: Signing assets are cloud-managed, not stored
- **WHEN** the device app is archived and exported
- **THEN** `xcodebuild -allowProvisioningUpdates` obtains the distribution certificate and App Store profile via the Admin App Store Connect API key, with no certificate or profile imported, committed, or stored as a secret

#### Scenario: Upload uses the official Apple action
- **WHEN** the signed IPA is ready
- **THEN** it is uploaded to TestFlight via `Apple-Actions/upload-testflight-build` authenticated by the same API key

### Requirement: Signing credentials are never stored in the Actions cache

All signing and upload credentials (the App Store Connect API key) SHALL exist only as **encrypted GitHub Secrets** and SHALL NOT be written to, or restored from, the GitHub Actions cache. Only the Kotlin/Native (`~/.konan`) toolchain is cached.

#### Scenario: No credentials in cache
- **WHEN** the `ios-release` job runs
- **THEN** the App Store Connect API key is sourced from a GitHub Secret and is never stored in or restored from the Actions cache; only `~/.konan` is cached

### Requirement: Monotonic build numbers from the CI run

The app's `CURRENT_PROJECT_VERSION` (CFBundleVersion) SHALL be injected at build time from `github.run_number`, and `MARKETING_VERSION` SHALL be a fixed pre-release value (e.g. `0.1.0`). Each uploaded build SHALL therefore carry a unique, monotonically increasing build number for the marketing version, so TestFlight never rejects a duplicate.

#### Scenario: Two pushes produce two increasing build numbers
- **WHEN** two commits are pushed to `main` in sequence
- **THEN** each produces a TestFlight build whose `CFBundleVersion` equals its `github.run_number`, and the second is strictly greater than the first

### Requirement: The build is App-Store-Connect uploadable

The app SHALL include a **1024×1024 opaque** (no alpha channel) app icon in its asset catalog, so the uploaded build is not rejected for a missing or invalid app icon.

#### Scenario: Upload is not rejected for a missing icon
- **WHEN** a build is uploaded to TestFlight
- **THEN** App Store Connect accepts it without a missing-/invalid-app-icon rejection

### Requirement: Export compliance is pre-declared

The app `Info.plist` SHALL set `ITSAppUsesNonExemptEncryption` to `NO`, and the export SHALL use an `ExportOptions.plist` with `method` `app-store-connect`, so uploads do not block on a manual export-compliance prompt.

#### Scenario: Upload does not block on export compliance
- **WHEN** a build is uploaded to TestFlight
- **THEN** it is not held for a manual export-compliance answer, because `ITSAppUsesNonExemptEncryption` is already declared `NO`

### Requirement: Signing and upload credentials are configured as secrets

The `ios-release` job SHALL source all Apple credentials from GitHub Secrets — the **Admin** App Store Connect API key (`ASC_KEY_ID`, `ASC_ISSUER_ID`, and `ASC_API_PRIVATE_KEY` holding the raw `.p8` PEM contents). The Apple **Team ID** SHALL be committed in `Config.xcconfig` (it is not a secret).

#### Scenario: Credentials come from secrets, Team ID from config
- **WHEN** the `ios-release` job signs and uploads
- **THEN** the App Store Connect API key is read from GitHub Secrets, and the Team ID is read from the committed `Config.xcconfig`
