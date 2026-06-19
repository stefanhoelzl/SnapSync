## MODIFIED Requirements

### Requirement: Signed device build delivered to TestFlight on every push

The system SHALL deliver a signed iOS build to **TestFlight** only on pushes to **`refs/heads/main`**, as part of the `ios-build` job in `.github/workflows/ios.yml`. On `main` the job SHALL export an `app-store-connect` signed IPA from the gate archive and upload it to TestFlight via App Store Connect; on any **other** ref the export-to-TestFlight and upload steps SHALL be skipped (guarded by `if: github.ref == 'refs/heads/main'`). The signed **archive** itself SHALL still be produced on **every** ref (it is the `ios-build` merge gate — see capability `ios-ci`), and the device (`iosArm64`) app SHALL be compiled exactly **once** per push. Per-branch device installability before merge is no longer served by TestFlight; it is served by the development-IPA artifact (capability `ios-sideload-delivery`). The job SHALL run on a `macos-26` hosted runner with the runner's GM Xcode.

#### Scenario: A push to main uploads a build to TestFlight
- **WHEN** a commit is pushed to `refs/heads/main`
- **THEN** the `ios-build` job exports an `app-store-connect` signed IPA from the gate archive and uploads it to TestFlight via App Store Connect

#### Scenario: A push to a non-main branch does not upload to TestFlight
- **WHEN** a commit is pushed to any ref other than `refs/heads/main`
- **THEN** the `ios-build` job still archives the device app (the merge gate) but skips the app-store export and the TestFlight upload

#### Scenario: The device app is compiled only once per push
- **WHEN** a commit is pushed
- **THEN** the device (`iosArm64`) framework is compiled exactly once — as the signed archive — never twice

### Requirement: Cloud-managed code signing

The `ios-build` job SHALL sign using **two persistent certificates imported each run** — an Apple **Distribution** certificate and an Apple **Development** certificate (sourced from GitHub Secrets) — imported into one shared, ephemeral keychain, combined with `xcodebuild -allowProvisioningUpdates` authenticated by an App Store Connect API key with the **Admin** role, which **cloud-manages the provisioning profiles** (App Store profile for the TestFlight export, development profile for the sideload export — capability `ios-sideload-delivery`). Both certs are imported deliberately: an empty runner keychain makes automatic signing mint a **new** cert every run, exhausting Apple's per-account cert cap; `xcodebuild archive` provisions a development identity in addition to the distribution one, so persisting only Distribution still churned Development certs. The pipeline SHALL NOT use fastlane or `match`. The signed App Store IPA SHALL be uploaded to TestFlight via `Apple-Actions/upload-testflight-build`.

#### Scenario: Signing reuses imported persistent certs, mints none
- **WHEN** the device app is archived and exported
- **THEN** signing uses the two imported persistent certificates (Distribution and Development) and `xcodebuild -allowProvisioningUpdates` obtains the provisioning profiles via the Admin App Store Connect API key, without minting any new certificate

#### Scenario: Upload uses the official Apple action
- **WHEN** the signed App Store IPA is ready on `main`
- **THEN** it is uploaded to TestFlight via `Apple-Actions/upload-testflight-build` authenticated by the App Store Connect API key

### Requirement: Signing credentials are never stored in the Actions cache

All signing and upload credentials — the App Store Connect API key and the two certificate bundles (Distribution and Development `.p12` + passwords) — SHALL exist only as **encrypted GitHub Secrets** and SHALL NOT be written to, or restored from, the GitHub Actions cache. The signing keychain SHALL be ephemeral (created per run, dies with the runner). Only the Kotlin/Native (`~/.konan`) toolchain is cached.

#### Scenario: No credentials in cache
- **WHEN** the `ios-build` job runs on any ref
- **THEN** the App Store Connect API key and both certificate bundles are sourced from GitHub Secrets and are never stored in or restored from the Actions cache; only `~/.konan` is cached

### Requirement: Signing and upload credentials are configured as secrets

On every ref, the `ios-build` job SHALL source all Apple credentials from GitHub Secrets — the **Admin** App Store Connect API key (`ASC_KEY_ID`, `ASC_ISSUER_ID`, and `ASC_API_PRIVATE_KEY` holding the raw `.p8` PEM contents) and the two signing certificates (`SIGNING_CERT_P12_BASE64` / `SIGNING_CERT_PASSWORD` for Distribution and `SIGNING_DEV_CERT_P12_BASE64` / `SIGNING_DEV_CERT_PASSWORD` for Development). The Apple **Team ID** SHALL be committed in `Config.xcconfig` (it is not a secret).

#### Scenario: Credentials come from secrets, Team ID from config
- **WHEN** the `ios-build` job signs and uploads
- **THEN** the App Store Connect API key and both certificate bundles are read from GitHub Secrets, and the Team ID is read from the committed `Config.xcconfig`
