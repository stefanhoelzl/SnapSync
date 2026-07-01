## MODIFIED Requirements

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
