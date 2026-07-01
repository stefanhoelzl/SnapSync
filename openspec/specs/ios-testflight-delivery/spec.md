# ios-testflight-delivery Specification

## Purpose
Builds, signs, and uploads the iOS device app to **TestFlight** on pushes to **`main` only**, as a release trail (no Beta App Review). Per-branch installability before merge is served out of band by the ssh-mac build loop (dev infrastructure), not TestFlight. Signing combines **two imported persistent certificates** (Apple Distribution + Apple Development, from GitHub Secrets) with **cloud-managed provisioning profiles** (App Store Connect Admin API key, no fastlane/`match`); the signed archive doubles as the `ios-build` merge gate (capability `ios-ci`), while export and upload are **decoupled** (non-blocking, so delivery flakiness never blocks merges). Covers build numbering, export options, and the required signing credentials.
## Requirements

### Requirement: Signed device build delivered to TestFlight on every push

The system SHALL deliver a signed iOS build to **TestFlight** only on pushes to **`refs/heads/main`**, as part of the `ios-build` job in `.github/workflows/ios.yml`. On `main` the job SHALL export an `app-store-connect` signed IPA from the gate archive and upload it to TestFlight via App Store Connect; on any **other** ref the export-to-TestFlight and upload steps SHALL be skipped (guarded by `if: github.ref == 'refs/heads/main'`). The signed **archive** itself SHALL still be produced on **every** ref (it is the `ios-build` merge gate — see capability `ios-ci`), and the device (`iosArm64`) app SHALL be compiled exactly **once** per push. Per-branch device installability before merge is **not** served by TestFlight; it is served **out of band** by the interactive ssh-mac build loop (dev infrastructure — `.github/workflows/ssh-mac.yml`; see the runbook in `CLAUDE.md`), not by any CI artifact. The job SHALL run on a `macos-26` hosted runner with the runner's GM Xcode.

#### Scenario: A push to main uploads a build to TestFlight
- **WHEN** a commit is pushed to `refs/heads/main`
- **THEN** the `ios-build` job exports an `app-store-connect` signed IPA from the gate archive and uploads it to TestFlight via App Store Connect

#### Scenario: A push to a non-main branch does not upload to TestFlight
- **WHEN** a commit is pushed to any ref other than `refs/heads/main`
- **THEN** the `ios-build` job still archives the device app (the merge gate) but skips the app-store export and the TestFlight upload

#### Scenario: The device app is compiled only once per push
- **WHEN** a commit is pushed
- **THEN** the device (`iosArm64`) framework is compiled exactly once — as the signed archive — never twice

### Requirement: Delivery is decoupled from the merge gate

The export-IPA and upload-to-TestFlight steps SHALL NOT fail the `ios-build` status check when delivery fails. The signed-archive step (which compiles `iosArm64`) is the merge gate; the export and upload steps SHALL run with `continue-on-error` so that a transient App Store Connect or delivery failure leaves the `ios-build` check green and does not block merges, while the failure remains visible in the workflow run.

#### Scenario: A delivery flake does not block merges
- **WHEN** the signed archive compiles but the export or TestFlight upload fails
- **THEN** the `ios-build` status check still concludes as success (green) and merges are not blocked, while the failed step is visible in the run

#### Scenario: A compile failure still fails the gate
- **WHEN** the signed archive fails to compile
- **THEN** the `ios-build` status check concludes as failure (red)

### Requirement: Cloud-managed code signing

The `ios-build` job SHALL sign using **two persistent certificates imported each run** — an Apple **Distribution** certificate and an Apple **Development** certificate (sourced from GitHub Secrets) — imported into one shared, ephemeral keychain, combined with `xcodebuild -allowProvisioningUpdates` authenticated by an App Store Connect API key with the **Admin** role, which **cloud-manages the App Store provisioning profile** for the TestFlight export. Both certs are imported deliberately: an empty runner keychain makes automatic signing mint a **new** cert every run, exhausting Apple's per-account cert cap; `xcodebuild archive` provisions a **development identity in addition to the distribution one**, so persisting only Distribution still churned Development certs — the Development cert is therefore imported even though `ios.yml` no longer exports a development (sideload) IPA. The pipeline SHALL NOT use fastlane or `match`. The signed App Store IPA SHALL be uploaded to TestFlight via `Apple-Actions/upload-testflight-build`.

#### Scenario: Signing reuses imported persistent certs, mints none
- **WHEN** the device app is archived and exported
- **THEN** signing uses the two imported persistent certificates (Distribution and Development) and `xcodebuild -allowProvisioningUpdates` obtains the App Store provisioning profile via the Admin App Store Connect API key, without minting any new certificate

#### Scenario: Development cert import prevents cert-cap churn
- **WHEN** the `ios-build` job archives the device app on any ref
- **THEN** the imported Apple Development certificate satisfies the development identity that `xcodebuild archive` provisions, so no new Development certificate is minted, even though no development IPA is exported

#### Scenario: Upload uses the official Apple action
- **WHEN** the signed App Store IPA is ready on `main`
- **THEN** it is uploaded to TestFlight via `Apple-Actions/upload-testflight-build` authenticated by the App Store Connect API key

### Requirement: Signing credentials are never stored in the Actions cache

All signing and upload credentials — the App Store Connect API key and the two certificate bundles (Distribution and Development `.p12` + passwords) — SHALL exist only as **encrypted GitHub Secrets** and SHALL NOT be written to, or restored from, the GitHub Actions cache. The signing keychain SHALL be ephemeral (created per run, dies with the runner). Only the Kotlin/Native (`~/.konan`) toolchain is cached.

#### Scenario: No credentials in cache
- **WHEN** the `ios-build` job runs on any ref
- **THEN** the App Store Connect API key and both certificate bundles are sourced from GitHub Secrets and are never stored in or restored from the Actions cache; only `~/.konan` is cached

### Requirement: Monotonic build numbers from the CI run

The app's `CURRENT_PROJECT_VERSION` (CFBundleVersion) SHALL be injected at build time from `github.run_number`, and `MARKETING_VERSION` SHALL be a fixed pre-release value (e.g. `0.1.0`). Because `github.run_number` is globally monotonic across all refs, each uploaded build — regardless of branch — SHALL carry a unique, strictly increasing build number for the marketing version, so TestFlight never rejects a duplicate and builds from different branches never collide.

#### Scenario: Two pushes produce two increasing build numbers
- **WHEN** two commits are pushed in sequence (to the same or different branches)
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

On every ref, the `ios-build` job SHALL source all Apple credentials from GitHub Secrets — the **Admin** App Store Connect API key (`ASC_KEY_ID`, `ASC_ISSUER_ID`, and `ASC_API_PRIVATE_KEY` holding the raw `.p8` PEM contents) and the two signing certificates (`SIGNING_CERT_P12_BASE64` / `SIGNING_CERT_PASSWORD` for Distribution and `SIGNING_DEV_CERT_P12_BASE64` / `SIGNING_DEV_CERT_PASSWORD` for Development). The Apple **Team ID** SHALL be committed in `Config.xcconfig` (it is not a secret).

#### Scenario: Credentials come from secrets, Team ID from config
- **WHEN** the `ios-build` job signs and uploads
- **THEN** the App Store Connect API key and both certificate bundles are read from GitHub Secrets, and the Team ID is read from the committed `Config.xcconfig`
