## ADDED Requirements

### Requirement: Signed device build delivered to TestFlight on every push to main

The system SHALL provide a GitHub Actions workflow (`.github/workflows/ios-release.yml`) that, on every push to `main`, builds the iOS **device** app (`iosArm64`), code-signs it, archives it, exports a signed IPA, and uploads it to **TestFlight** via App Store Connect. The trigger SHALL be `push` to `main` with **no path filter**, so every commit on `main` produces a TestFlight build. The workflow SHALL run on a `macos-26` hosted runner with the runner's GM Xcode.

#### Scenario: A push to main uploads a build to TestFlight
- **WHEN** a commit is pushed to `main`
- **THEN** the `ios-release.yml` workflow builds, signs, archives, and uploads a signed IPA to TestFlight via App Store Connect

#### Scenario: A push to a non-main branch does not upload
- **WHEN** a commit is pushed to a branch other than `main`
- **THEN** the release workflow does not run and no TestFlight upload occurs

#### Scenario: Release is additive to the merge gate
- **WHEN** a commit is pushed to `main`
- **THEN** the unsigned simulator gate (`ios-ci` / the `ios-build` check) still runs unchanged, and the release workflow runs independently without altering that gate

### Requirement: Code signing via official Apple Actions and an auto-managed profile

The workflow SHALL import the Apple **Distribution** certificate using `Apple-Actions/import-codesign-certs` from a base64-encoded `.p12` GitHub Secret into a temporary keychain, and SHALL obtain the provisioning profile by running `xcodebuild` with `-allowProvisioningUpdates` authenticated by the App Store Connect API key (auto-managed profile). The pipeline SHALL NOT use fastlane or `match`, SHALL NOT pre-create or store a provisioning profile, and SHALL NOT create a Distribution certificate on the runner (the imported certificate is the signing identity).

#### Scenario: Certificate imported from a secret into a temporary keychain
- **WHEN** the release workflow runs
- **THEN** the Distribution certificate is imported from the base64 `.p12` secret into a temporary keychain via `Apple-Actions/import-codesign-certs`

#### Scenario: Provisioning profile is auto-managed, not stored
- **WHEN** the device app is signed
- **THEN** `xcodebuild -allowProvisioningUpdates` obtains/refreshes the provisioning profile using the App Store Connect API key, with no profile committed to the repo or stored as a secret

### Requirement: Signing certificate is never stored in the Actions cache

The Distribution certificate and all signing/upload credentials SHALL exist only as **encrypted GitHub Secrets**. The certificate `.p12` SHALL NOT be written to, or restored from, the GitHub Actions cache.

#### Scenario: No certificate in cache
- **WHEN** the release workflow runs
- **THEN** the certificate is sourced from a GitHub Secret and is never stored in or restored from the Actions cache

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

The workflow SHALL source all Apple credentials from GitHub Secrets — the Distribution certificate (`DIST_CERT_P12_BASE64`, base64-encoded), its password (`DIST_CERT_PASSWORD`), and the App Store Connect API key (`ASC_KEY_ID`, `ASC_ISSUER_ID`, and `ASC_API_PRIVATE_KEY` holding the raw `.p8` PEM contents). The Apple **Team ID** SHALL be committed in `Config.xcconfig` (it is not a secret).

#### Scenario: Credentials come from secrets, Team ID from config
- **WHEN** the release workflow signs and uploads
- **THEN** the certificate, certificate password, and App Store Connect API key are read from GitHub Secrets, and the Team ID is read from the committed `Config.xcconfig`
