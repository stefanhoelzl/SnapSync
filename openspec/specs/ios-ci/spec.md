# ios-ci Specification

## Purpose
TBD - created by archiving change ios-first-target. Update Purpose after archive.
## Requirements
### Requirement: Build iOS on every push

The system SHALL run a Codemagic workflow on every push that builds the iOS simulator app and reports a status check used to gate merges. The workflow SHALL build with `xcode: edge` (the latest Xcode beta, providing the iOS 27 beta SDK) and run `xcodebuild` for the iOS simulator. The build is the sole pass/fail gate: the workflow SHALL NOT run tests, boot a simulator, or perform code signing.

#### Scenario: A push triggers the iOS build check
- **WHEN** a commit is pushed to any branch
- **THEN** the Codemagic workflow builds the iOS simulator app with `xcode: edge` and reports an iOS build status check on the pushed commit

#### Scenario: Build success reports a passing check
- **WHEN** the simulator app builds successfully
- **THEN** the iOS build status check concludes as success (green)

#### Scenario: Build failure reports a failing check
- **WHEN** the simulator app fails to build
- **THEN** the iOS build status check concludes as failure (red)

#### Scenario: The workflow is build-only
- **WHEN** the iOS workflow runs
- **THEN** it builds the simulator app only, executing no tests, no simulator boot, and no code signing

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

