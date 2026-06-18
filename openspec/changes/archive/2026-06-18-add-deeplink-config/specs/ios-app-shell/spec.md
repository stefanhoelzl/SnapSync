## MODIFIED Requirements

### Requirement: iOS application shell

The system SHALL provide an iOS application built with Compose Multiplatform whose entry point is a
`ComposeUIViewController` (in the `:app:ios` module) that hosts the shared `StatusScreen`. The screen
SHALL render **live** `UiState` observed from an assembled real stack —
`StatusContainerHost.container.stateFlow` — not a static `UiState`. The Swift entry point (`iosApp/`)
SHALL remain a trivial pass-through that obtains the root view controller from `MainViewController()`.
The app SHALL register the custom `snapsync` URL scheme in `Info.plist` (`CFBundleURLTypes`), and the
Swift entry SHALL forward an incoming `snapsync://` URL — via SwiftUI `onOpenURL`, handling both
cold-launch and warm delivery — as a **raw string** to `SnapSyncRoot.onOpenUrl(_:)`, performing no
parsing in Swift.

#### Scenario: Launching the app shows live status
- **WHEN** the iOS app is launched
- **THEN** a `ComposeUIViewController` presents the shared `StatusScreen` rendering the current
  `UiState` from the live container, updating as config, permission, and ledger state change

#### Scenario: UI is the real shared screen, not a placeholder
- **WHEN** the status screen is displayed
- **THEN** it is the same `StatusScreen` composable the desktop app uses (from `:domain:ui`, themed
  via `AppTheme`), not an iOS-specific placeholder

#### Scenario: First frame is honest
- **WHEN** the app launches with photo access already granted and the ledger has not yet been read
- **THEN** the first frame is `UiState.Loading` ("Loading …"), never a guessed `NeverSynced` that
  later corrects (subject to the setup gate: if config is absent the first frame is `UiState.Setup`)

#### Scenario: A scanned QR opens the app and forwards the raw URL
- **WHEN** the stock Camera app opens a `snapsync://config?…` URL (cold or warm)
- **THEN** Swift `onOpenURL` forwards the raw URL string to `SnapSyncRoot.onOpenUrl(_:)` without
  parsing it

### Requirement: iOS live composition root

The `:app:ios` module SHALL provide a composition-root singleton (`SnapSyncRoot`, `iosMain`) that
owns an app-lifetime `CoroutineScope` (a `SupervisorJob` on the main dispatcher) and assembles the
live stack: `iosLedgerBackend() → LedgerWatcher → LedgerSyncStatusSource(watcher, permission, scope)`,
the PhotoKit permission adapter (as both the `PermissionStatusSource` and `PermissionRequester`), and
the iOS Keychain config store (as both the `ConfigSource` and `ConfigStore`), composed into a
`StatusContainerHost`. The scope SHALL outlive Compose recomposition so the source collector and
container are not torn down with the view. `MainViewController` SHALL render `host.container.stateFlow`
and route the gate intents to `host.onRequestPermission` / `host.onOpenSettings`. `SnapSyncRoot` SHALL
expose `onOpenUrl(String)` that forwards to the container's `onOpenUrl` intent.

#### Scenario: The root assembles the real stack
- **WHEN** the iOS app starts
- **THEN** a single `SnapSyncRoot` constructs the real ledger-backed `LedgerSyncStatusSource`, the
  PhotoKit permission adapter, and the Keychain config store, wires all three into one
  `StatusContainerHost`, and the screen observes that container

#### Scenario: Permission action flows through the container
- **WHEN** the user activates the gate's "Allow access" or "Open Settings"
- **THEN** `MainViewController` invokes the container intent, which calls the injected
  `PermissionRequester` — the UI never calls PhotoKit directly

#### Scenario: A deeplink flows through the container
- **WHEN** `SnapSyncRoot.onOpenUrl` is called with a `snapsync://` URL
- **THEN** it forwards to the container's `onOpenUrl` intent, which decodes and (on success) saves via
  the Keychain `ConfigStore`, updating the `ConfigSource`
