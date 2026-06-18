# ios-ci Specification

## Purpose
Continuous integration that, on every push, builds the iOS device app and runs the shared Kotlin/Native unit tests on a simulator, each reporting a merge-gating status check. Runs on GitHub Actions (`macos-26`, GM Xcode) — the same provider as the Linux build — via two parallel jobs doing only the irreducible Apple delta: `ios-build` links the device (`iosArm64`) framework via `xcodebuild` (build-only, unsigned), and `ios-test` runs `iosSimulatorArm64Test` on a booted simulator. Together they exercise both Kotlin/Native targets. Code signing and TestFlight delivery are separate capabilities.
## Requirements
### Requirement: Build iOS on every push

The system SHALL run a **GitHub Actions** job (`ios-build` in `.github/workflows/ios.yml`) on every push that builds the iOS **device (`iphoneos`, arm64)** app on a **`macos-26` hosted runner** and reports a status check used to gate merges. The job SHALL build with the runner's **GM Xcode** (no Xcode beta) and run `xcodebuild` for a generic iOS **device** destination, unsigned (`CODE_SIGNING_ALLOWED=NO`), linking the `iosArm64` framework. This job is the build gate: it SHALL NOT run tests, boot a simulator, or perform code signing. The job SHALL post a stable status-check context (`ios-build`).

#### Scenario: A push triggers the iOS build check
- **WHEN** a commit is pushed to any branch
- **THEN** the `ios-build` job builds the iOS device (arm64) app on `macos-26` with the runner's GM Xcode and reports an `ios-build` status check on the pushed commit

#### Scenario: Build success reports a passing check
- **WHEN** the device app builds successfully
- **THEN** the `ios-build` status check concludes as success (green)

#### Scenario: Build failure reports a failing check
- **WHEN** the device app fails to build
- **THEN** the `ios-build` status check concludes as failure (red)

#### Scenario: The build job is build-only
- **WHEN** the `ios-build` job runs
- **THEN** it builds the device app only, executing no tests, no simulator boot, and no code signing

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

