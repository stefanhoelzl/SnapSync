## MODIFIED Requirements

### Requirement: Build iOS on every push

The system SHALL run a **GitHub Actions** job (`ios-build` in `.github/workflows/ios.yml`) on every push that builds the iOS **device (`iphoneos`, arm64)** app on a **`macos-26` hosted runner**, linking the `iosArm64` framework with the runner's **GM Xcode** (no Xcode beta), and reports a stable status-check context (`ios-build`) used to gate merges. On every **non-`main`** ref the job SHALL run `xcodebuild build` for a generic iOS **device** destination **unsigned** (`CODE_SIGNING_ALLOWED=NO`); this unsigned build is the merge gate and SHALL run no tests, boot no simulator, and perform no signing. On **`main`** the job SHALL instead produce a **signed archive** of the same device app and deliver it to TestFlight (capability `ios-testflight-delivery`); the archive likewise compiles `iosArm64`, so the `ios-build` check still reflects whether the device app builds. The device app SHALL be compiled exactly once per push.

#### Scenario: A push triggers the iOS build check
- **WHEN** a commit is pushed to any branch
- **THEN** the `ios-build` job builds the iOS device (arm64) app on `macos-26` with the runner's GM Xcode and reports an `ios-build` status check on the pushed commit

#### Scenario: Build success reports a passing check
- **WHEN** the device app builds successfully
- **THEN** the `ios-build` status check concludes as success (green)

#### Scenario: Build failure reports a failing check
- **WHEN** the device app fails to build
- **THEN** the `ios-build` status check concludes as failure (red)

#### Scenario: Non-main builds are unsigned and build-only
- **WHEN** the `ios-build` job runs on a ref other than `main`
- **THEN** it builds the device app unsigned, executing no tests, no simulator boot, and no code signing

#### Scenario: Main builds sign and deliver
- **WHEN** the `ios-build` job runs on `main`
- **THEN** it produces a signed archive of the device app and uploads it to TestFlight, while the `ios-build` check still reflects whether the device app compiles

#### Scenario: No beta Xcode is used
- **WHEN** the iOS workflow runs
- **THEN** it uses the `macos-26` runner's GM Xcode and does not select an Xcode beta SDK
