# ios-ci Specification

## Purpose
Continuous integration that, on every push, builds the iOS device app and runs the shared Kotlin/Native unit tests on a simulator, each reporting a merge-gating status check. Runs on GitHub Actions (`macos-26`, GM Xcode) — the same provider as the Linux build — via two parallel jobs doing only the irreducible Apple delta: `ios-build` produces a **signed archive** of the device (`iosArm64`) app via `xcodebuild` — the archive is the merge gate and doubles as the TestFlight delivery source — and `ios-test` runs `iosSimulatorArm64Test` on a booted simulator. Together they exercise both Kotlin/Native targets. Code signing and TestFlight delivery are detailed in a separate capability (`ios-testflight-delivery`).
## Requirements
### Requirement: Build iOS on every push

The system SHALL run a **GitHub Actions** job (`ios-build` in `.github/workflows/ios.yml`) on every push that builds the iOS **device (`iphoneos`, arm64)** app on a **`macos-26` hosted runner**, linking the `iosArm64` framework with the runner's **GM Xcode** (no Xcode beta), and reports a stable status-check context (`ios-build`) used to gate merges. On **every** ref the job SHALL produce a **signed archive** of the device app (cloud-managed signing — capability `ios-testflight-delivery`); the archive compiles `iosArm64`, so the `ios-build` check reflects whether the device app builds, and the same archive is the TestFlight delivery source. The job SHALL run no tests and boot no simulator. The device app SHALL be compiled exactly once per push.

#### Scenario: A push triggers the iOS build check
- **WHEN** a commit is pushed to any branch
- **THEN** the `ios-build` job builds the iOS device (arm64) app on `macos-26` with the runner's GM Xcode and reports an `ios-build` status check on the pushed commit

#### Scenario: Build success reports a passing check
- **WHEN** the device app's signed archive compiles successfully
- **THEN** the `ios-build` status check concludes as success (green)

#### Scenario: Build failure reports a failing check
- **WHEN** the device app fails to compile
- **THEN** the `ios-build` status check concludes as failure (red)

#### Scenario: Every ref signs and delivers
- **WHEN** the `ios-build` job runs on any ref
- **THEN** it produces a signed archive of the device app and delivers it to TestFlight (capability `ios-testflight-delivery`), executing no tests and no simulator boot, while the `ios-build` check still reflects whether the device app compiles

#### Scenario: No beta Xcode is used
- **WHEN** the iOS workflow runs
- **THEN** it uses the `macos-26` runner's GM Xcode and does not select an Xcode beta SDK

### Requirement: Toolchain caching

The workflow SHALL cache the Gradle (`~/.gradle`) and Kotlin/Native (`~/.konan`) directories across runs, so a warm build avoids re-downloading the toolchain and recompiling unchanged Kotlin/Native artifacts.

#### Scenario: A warm build reuses the caches
- **WHEN** a workflow runs after a previous run populated the caches
- **THEN** `~/.gradle` and `~/.konan` are restored and the Kotlin/Native toolchain is not re-downloaded

### Requirement: Cancel superseded builds

The workflow SHALL cancel an in-progress build for a ref when a newer push to that ref arrives, so rapid pushes do not pile up and exhaust build minutes.

#### Scenario: A newer push cancels the in-progress iOS build
- **WHEN** a new commit is pushed to a ref that already has a running iOS build
- **THEN** the in-progress build is cancelled and the new one proceeds

### Requirement: Test iOS on every push

The system SHALL run a **GitHub Actions** job (`ios-test` in `.github/workflows/ios.yml`) on every push, **in parallel** with the `ios-build` job, on a **`macos-26` hosted runner**. The job SHALL run `./gradlew iosSimulatorArm64Test`, which boots an iOS simulator and executes the shared modules' `commonTest` suites compiled to **Kotlin/Native** for the `iosSimulatorArm64` target. The job SHALL post a stable status-check context (`ios-test`) used to gate merges. The `ios-test` and `ios-build` jobs together exercise both Kotlin/Native targets — `iosSimulatorArm64` via the test, `iosArm64` via the build.

#### Scenario: A push triggers the iOS test check
- **WHEN** a commit is pushed to any branch
- **THEN** the `ios-test` job runs `iosSimulatorArm64Test` on `macos-26`, booting an iOS simulator and running the shared `commonTest` compiled to Kotlin/Native, and reports an `ios-test` status check on the pushed commit

#### Scenario: Test success reports a passing check
- **WHEN** the Kotlin/Native simulator unit tests all pass
- **THEN** the `ios-test` status check concludes as success (green)

#### Scenario: Test failure reports a failing check
- **WHEN** a Kotlin/Native simulator unit test fails
- **THEN** the `ios-test` status check concludes as failure (red)

#### Scenario: Test runs in parallel with the build
- **WHEN** the iOS workflow runs
- **THEN** the `ios-test` and `ios-build` jobs run as independent parallel jobs (neither waits on the other)
