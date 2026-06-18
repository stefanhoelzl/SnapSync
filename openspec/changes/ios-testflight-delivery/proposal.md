## Why

The iOS app currently only builds as an **unsigned simulator app** in CI (`ios-ci`); there is no way to run it on a physical iPhone. The developer works on Linux with **no Mac**, so the only path onto real hardware is to build, sign, and upload from the GitHub Actions macОС runner and install via **TestFlight** — the distribution channel the design already chose (§1, §8 "ops"). Standing this pipeline up now, against the trivial app shell, proves the entire signing/delivery path end-to-end **before** the upload extension exists, so the first failure is unambiguously a *pipeline* problem and never *app logic*.

## What Changes

- Add a **signed iOS release pipeline** as a new `ios-release` **job in `.github/workflows/ios.yml`** that, on every green push to `main` (gated `if: github.ref == 'refs/heads/main'`, `needs: [ios-build, ios-test]`), builds the device app (`iosArm64`), code-signs it, archives it, and uploads it to **TestFlight internal testing**.
- Signing is **cloud-managed**: `xcodebuild -allowProvisioningUpdates` with an **Admin** App Store Connect API key manages the distribution certificate *and* provisioning profile in the cloud; `Apple-Actions/upload-testflight-build` uploads. All credentials are **encrypted GitHub Secrets** — no imported certificate, nothing in the Actions cache, no fastlane, no `match`.
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

- **New CI:** an `ios-release` job added to `.github/workflows/ios.yml` (alongside the `ios-build` and `ios-test` gates), gated to `main` and to those gates passing; it links the `iosArm64` framework and signs on its own runner.
- **Xcode project:** `iosApp/Configuration/Config.xcconfig` (TEAM_ID), `iosApp/iosApp.xcodeproj/project.pbxproj` (signing + version settings), `iosApp/iosApp/Info.plist` (export-compliance key), new `iosApp/ExportOptions.plist`.
- **GitHub Secrets (3):** the **Admin** App Store Connect API key — `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_API_PRIVATE_KEY` (raw `.p8` PEM). (No certificate secrets: cloud signing manages the cert.)
- **Manual Apple-account prerequisites** (outside the repo, one-time): mint a Distribution certificate (`openssl` CSR on Linux → Developer portal → `.p12`); create an App Store Connect API key (App Manager role); register the `app.snapsync` bundle id and create the app record; add the developer as an internal TestFlight tester.
- **No application/domain code changes** — the shared modules and the shell are unchanged; this is purely delivery infrastructure.
- **Out of scope:** the PHBackgroundResourceUploadJobExtension target, App Groups, entitlements, and any real upload functionality (later slices). The iOS-27 background-upload entitlement may later force an explicit (hand-made) provisioning profile for the extension target; that is deferred until the extension exists.
