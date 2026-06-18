## 1. Manual Apple-account prerequisites (developer, outside the repo, one-time)

- [x] 1.1 Mint an Apple **Distribution** certificate on Linux: `openssl genrsa` → `openssl req` CSR → upload CSR in the Apple Developer portal (Certificates → Apple Distribution) → download the `.cer` → bundle private key + cert into a password-protected `.p12`
- [x] 1.2 Create an **App Store Connect API key** (Users and Access → Integrations → Keys) with the **App Manager** role; save the `.p8`, Key ID, and Issuer ID
- [x] 1.3 Register the **`app.snapsync`** bundle id in the Developer portal (no special capabilities for the shell); if the id is taken, pick an alternative and note it for task 2.1
- [x] 1.4 Create the **app record** in App Store Connect for that bundle id
- [ ] 1.5 Add yourself as an **internal TestFlight tester** (internal testing needs no Beta App Review)
- [x] 1.6 Look up the 10-character **Team ID** (Developer portal → Membership) for task 2.1

## 2. Xcode project signing & versioning

- [x] 2.1 Set `TEAM_ID` in `iosApp/Configuration/Config.xcconfig` (committed — not a secret; use the bundle id from task 1.3 if changed)
- [x] 2.2 In `iosApp/iosApp.xcodeproj/project.pbxproj`, set `MARKETING_VERSION = 0.1.0` for both Debug and Release configs
- [x] 2.3 Confirm the device/release build signs via CI-managed signing (`-allowProvisioningUpdates`) while the simulator gate keeps `CODE_SIGNING_ALLOWED=NO` — adjust `CODE_SIGN_STYLE` / `CODE_SIGN_IDENTITY` ("Apple Distribution") as needed so the two paths don't conflict
- [x] 2.4 Add `ITSAppUsesNonExemptEncryption` = `NO` to `iosApp/iosApp/Info.plist`
- [x] 2.5 Add `iosApp/ExportOptions.plist` with `method` = `app-store-connect` and the Team ID reference
- [x] 2.6 Provide a 1024×1024 **opaque** (no-alpha) app icon in `AppIcon.appiconset` so App Store Connect accepts the upload (the scaffolded icon set declared the slot but shipped no image)
- [x] 2.7 Add `CADisableMinimumFrameDurationOnPhone` = `true` to `Info.plist` — Compose Multiplatform (≥1.7) hard-aborts at launch without it; surfaced on the first real on-device launch (the build/CI path never runs the app)

## 3. Release workflow (initially `.github/workflows/ios-release.yml`; later folded into `ios.yml` — see §7)

- [x] 3.1 Create the workflow triggered on `push` to `main` (no path filter), `runs-on: macos-26`, with a concurrency group that cancels superseded runs on `main`
- [x] 3.2 Steps: checkout → setup-java 25 → setup-gradle → restore `~/.konan` cache (key on `gradle/libs.versions.toml`, matching `ios.yml`)
- [x] 3.3 Import the Distribution cert with `Apple-Actions/import-codesign-certs` from `DIST_CERT_P12_BASE64` + `DIST_CERT_PASSWORD`
- [x] 3.4 Write the App Store Connect API key (raw `.p8` from `ASC_API_PRIVATE_KEY`) to disk for `xcodebuild` auth; wire `ASC_KEY_ID` + `ASC_ISSUER_ID`
- [x] 3.5 `xcodebuild archive` for `generic/platform=iOS` with `-allowProvisioningUpdates` (+ `-authenticationKeyPath/-authenticationKeyID/-authenticationKeyIssuerID`), injecting `CURRENT_PROJECT_VERSION=${{ github.run_number }}`
- [x] 3.6 `xcodebuild -exportArchive` with `ExportOptions.plist` to produce the signed IPA
- [x] 3.7 Upload to TestFlight with `Apple-Actions/upload-testflight-build` using the same API key
- [x] 3.8 Confirm no certificate or profile is written to the Actions cache (only `~/.gradle` / `~/.konan` are cached)

## 4. Secrets configuration

- [x] 4.1 Add the five GitHub Secrets: `DIST_CERT_P12_BASE64` (base64 `.p12`), `DIST_CERT_PASSWORD`, `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_API_PRIVATE_KEY` (raw `.p8` PEM contents)

## 5. Verify end-to-end

- [ ] 5.1 Push to `main`; confirm the `ios-release` job builds, signs, archives, and uploads, while the `ios-build`/`ios-test` gates still run unchanged
- [ ] 5.2 Confirm the build appears in App Store Connect / TestFlight with `CFBundleVersion` = the run number and no export-compliance hold
- [ ] 5.3 Install via the TestFlight app on the physical iPhone and confirm the shared `StatusScreen` launches on real hardware
- [ ] 5.4 Push a second commit to `main` and confirm a new build with a strictly greater build number (no duplicate-build-number rejection)

## 7. Implementation revisions (discovered during branch validation)

- [x] 7.1 Switch to **full cloud signing**: regenerate the App Store Connect API key with the **Admin** role (App Manager can't cloud-sign at export — "Cloud signing permission error"); update the 3 ASC secrets
- [x] 7.2 Drop `import-codesign-certs` + the `DIST_CERT_P12_BASE64`/`DIST_CERT_PASSWORD` secrets — cloud signing manages cert + profile; proven on a green pure-cloud-signing branch build
- [x] 7.3 Delete the now-redundant hand-minted distribution cert, the old App Manager API key, the 2 `DIST_CERT_*` secrets, and local key material
- [x] 7.4 Fold delivery into the single `ios-build` job — unsigned device build on non-`main` (the gate), signed archive + export + TestFlight upload on `main` (steps gated `if: github.ref == 'refs/heads/main'`). One device compile per push; no separate `ios-release` job/workflow. Scope the `ios-ci` "build-only/no-signing" requirement to non-`main`.

## 6. Archive

- [ ] 6.1 After verification, sync specs and run `openspec archive ios-testflight-delivery`; update memory (`ios-testflight-delivery` shipped)
