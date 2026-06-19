## MODIFIED Requirements

### Requirement: Build iOS on every push

The system SHALL run a **GitHub Actions** job (`ios-build` in `.github/workflows/ios.yml`) on every push that builds the iOS **device (`iphoneos`, arm64)** app on a **`macos-26` hosted runner**, linking the `iosArm64` framework with the runner's **GM Xcode** (no Xcode beta), and reports a stable status-check context (`ios-build`) used to gate merges. On **every** ref the job SHALL produce a **signed archive** of the device app (signing — capability `ios-testflight-delivery`); the archive compiles `iosArm64`, so the `ios-build` check reflects whether the device app builds. From that single archive the job SHALL deliver across **two channels**: a **development-signed IPA artifact on every ref** (capability `ios-sideload-delivery`) and an **App Store build uploaded to TestFlight on `refs/heads/main` only** (capability `ios-testflight-delivery`). The job SHALL run no tests and boot no simulator. The device app SHALL be compiled exactly once per push.

#### Scenario: A push triggers the iOS build check
- **WHEN** a commit is pushed to any branch
- **THEN** the `ios-build` job builds the iOS device (arm64) app on `macos-26` with the runner's GM Xcode and reports an `ios-build` status check on the pushed commit

#### Scenario: Build success reports a passing check
- **WHEN** the device app's signed archive compiles successfully
- **THEN** the `ios-build` status check concludes as success (green)

#### Scenario: Build failure reports a failing check
- **WHEN** the device app fails to compile
- **THEN** the `ios-build` status check concludes as failure (red)

#### Scenario: Every ref archives and publishes a sideload artifact
- **WHEN** the `ios-build` job runs on any ref
- **THEN** it produces a signed archive of the device app and publishes a development-signed IPA artifact (capability `ios-sideload-delivery`), executing no tests and no simulator boot, while the `ios-build` check still reflects whether the device app compiles

#### Scenario: Only main delivers to TestFlight
- **WHEN** the `ios-build` job runs on `refs/heads/main`
- **THEN** it additionally exports an App Store IPA and uploads it to TestFlight (capability `ios-testflight-delivery`); on any other ref those steps are skipped

#### Scenario: No beta Xcode is used
- **WHEN** the iOS workflow runs
- **THEN** it uses the `macos-26` runner's GM Xcode and does not select an Xcode beta SDK
