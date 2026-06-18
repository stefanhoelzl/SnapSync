## Why

The iOS app currently only builds as an **unsigned simulator app** in CI (`ios-ci`); there is no way to run it on a physical iPhone. The developer works on Linux with **no Mac**, so the only path onto real hardware is to build, sign, and upload from the GitHub Actions macОС runner and install via **TestFlight** — the distribution channel the design already chose (§1, §8 "ops"). Standing this pipeline up now, against the trivial app shell, proves the entire signing/delivery path end-to-end **before** the upload extension exists, so the first failure is unambiguously a *pipeline* problem and never *app logic*.

## What Changes

- Add a **signed iOS release pipeline**: a new `.github/workflows/ios-release.yml` that, on every push to `main`, builds the device app (`iosArm64`), code-signs it, archives it, and uploads it to **TestFlight internal testing**.
- Signing uses the **official Apple GitHub Actions** (`Apple-Actions/import-codesign-certs`, `Apple-Actions/upload-testflight-build`) plus `xcodebuild -allowProvisioningUpdates` for the provisioning profile, with all credentials in **encrypted GitHub Secrets** (no fastlane, no `match` repo, no certificate in the Actions cache).
- Fill in `TEAM_ID` in `Config.xcconfig` (committed — a Team ID is not a secret) so device builds can sign.
- Switch the app target from automatic to **CI-managed signing** for the device/release build, while the simulator gate (`ios-ci`) stays unsigned and untouched.
- Add **monotonic build numbering**: `MARKETING_VERSION` becomes a pre-release `0.1.0`; `CURRENT_PROJECT_VERSION` is injected from `github.run_number` at build time (TestFlight rejects re-used build numbers).
- Add `ITSAppUsesNonExemptEncryption = NO` to `Info.plist` to skip the per-build export-compliance prompt; add an `ExportOptions.plist` (`method: app-store-connect`).
- The deployment target stays **iOS 16.0** (the shell has no iOS-27 APIs yet); device family stays iPhone+iPad.

## Capabilities

### New Capabilities
- `ios-testflight-delivery`: Builds, signs, and uploads the iOS device app to TestFlight on every push to `main`, using the official Apple Actions + GitHub Secrets and an auto-managed provisioning profile. Covers build numbering, export options, and the required signing credentials.

### Modified Capabilities
- `ios-app-shell`: Its "No code signing required" requirement is scoped to the *simulator* build only; clarify that a **signed device archive** is now also produced (by the new capability) so the two framings do not contradict.

## Impact

- **New CI:** `.github/workflows/ios-release.yml` (independent of the existing `ios.yml` gate; both run on a push to `main`, each linking the Kotlin/Native framework on its own runner).
- **Xcode project:** `iosApp/Configuration/Config.xcconfig` (TEAM_ID), `iosApp/iosApp.xcodeproj/project.pbxproj` (signing + version settings), `iosApp/iosApp/Info.plist` (export-compliance key), new `iosApp/ExportOptions.plist`.
- **GitHub Secrets (5):** `DIST_CERT_P12_BASE64`, `DIST_CERT_PASSWORD`, `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_API_PRIVATE_KEY` (raw `.p8` PEM — the `upload-testflight-build` action wants the key un-encoded).
- **Manual Apple-account prerequisites** (outside the repo, one-time): mint a Distribution certificate (`openssl` CSR on Linux → Developer portal → `.p12`); create an App Store Connect API key (App Manager role); register the `app.snapsync` bundle id and create the app record; add the developer as an internal TestFlight tester.
- **No application/domain code changes** — the shared modules and the shell are unchanged; this is purely delivery infrastructure.
- **Out of scope:** the PHBackgroundResourceUploadJobExtension target, App Groups, entitlements, and any real upload functionality (later slices). The iOS-27 background-upload entitlement may later force an explicit (hand-made) provisioning profile for the extension target; that is deferred until the extension exists.
