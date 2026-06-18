# ios-ci Delta Specification

## MODIFIED Requirements

### Requirement: Build iOS on every push

The system SHALL run a **GitHub Actions** workflow (`.github/workflows/ios.yml`) on every push that builds the iOS simulator app on a **`macos-26` hosted runner** and reports a status check used to gate merges. The workflow SHALL build with the runner's **GM Xcode** (no Xcode beta / `xcode: edge`) and run `xcodebuild` for the iOS simulator. The build is the sole pass/fail gate: the workflow SHALL NOT run tests, boot a simulator, or perform code signing. The job SHALL post a stable status-check context (`ios-build`).

#### Scenario: A push triggers the iOS build check
- **WHEN** a commit is pushed to any branch
- **THEN** the GitHub Actions workflow builds the iOS simulator app on `macos-26` with the runner's GM Xcode and reports an `ios-build` status check on the pushed commit

#### Scenario: Build success reports a passing check
- **WHEN** the simulator app builds successfully
- **THEN** the `ios-build` status check concludes as success (green)

#### Scenario: Build failure reports a failing check
- **WHEN** the simulator app fails to build
- **THEN** the `ios-build` status check concludes as failure (red)

#### Scenario: The workflow is build-only
- **WHEN** the iOS workflow runs
- **THEN** it builds the simulator app only, executing no tests, no simulator boot, and no code signing

#### Scenario: No beta Xcode is used
- **WHEN** the iOS workflow runs
- **THEN** it uses the `macos-26` runner's GM Xcode and does not select an Xcode beta SDK
