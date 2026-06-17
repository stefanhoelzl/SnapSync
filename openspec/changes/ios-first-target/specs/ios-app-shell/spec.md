## ADDED Requirements

### Requirement: iOS application shell

The system SHALL provide an iOS application built with Compose Multiplatform whose entry point is a `ComposeUIViewController` (in a new `:app:ios` module) that hosts the shared `StatusScreen`. For this first target the screen SHALL render a single static `UiState` with no live data source, matching the desktop app's current lack of live-ledger wiring.

#### Scenario: Launching the app shows the status screen
- **WHEN** the iOS app is launched in the simulator
- **THEN** a `ComposeUIViewController` presents the shared `StatusScreen` rendering a single static `UiState`

#### Scenario: UI is the real shared screen, not a placeholder
- **WHEN** the status screen is displayed
- **THEN** it is the same `StatusScreen` composable the desktop app uses (from `:domain:ui`, themed via `AppTheme`), not an iOS-specific placeholder

### Requirement: Buildable for the iOS simulator

The `:app:ios` module and its full module dependency closure SHALL compile for the `iosSimulatorArm64` target, and an Xcode project (`iosApp/`) SHALL build a runnable simulator `.app` via `xcodebuild`. The shared modules SHALL also declare the `iosArm64` (device) target for codegen correctness, though this first target does not build a device app and requires no code signing.

#### Scenario: Simulator app builds
- **WHEN** `xcodebuild` builds the `iosApp` scheme for the iOS simulator
- **THEN** the app and every module in its dependency closure compile for `iosSimulatorArm64` and a `.app` bundle is produced

#### Scenario: No code signing required
- **WHEN** the simulator app is built
- **THEN** the build completes without code-signing assets (no Apple Developer certificate or provisioning profile)
