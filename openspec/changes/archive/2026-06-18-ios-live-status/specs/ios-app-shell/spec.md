## MODIFIED Requirements

### Requirement: iOS application shell

The system SHALL provide an iOS application built with Compose Multiplatform whose entry point is a `ComposeUIViewController` (in the `:app:ios` module) that hosts the shared `StatusScreen`. The screen SHALL render **live** `UiState` observed from an assembled real stack — `StatusContainerHost.container.stateFlow` — not a static `UiState`. The Swift entry point (`iosApp/`) SHALL remain a trivial pass-through that obtains the root view controller from `MainViewController()`.

#### Scenario: Launching the app shows live status
- **WHEN** the iOS app is launched
- **THEN** a `ComposeUIViewController` presents the shared `StatusScreen` rendering the current `UiState` from the live container, updating as permission and ledger state change

#### Scenario: UI is the real shared screen, not a placeholder
- **WHEN** the status screen is displayed
- **THEN** it is the same `StatusScreen` composable the desktop app uses (from `:domain:ui`, themed via `AppTheme`), not an iOS-specific placeholder

#### Scenario: First frame is honest
- **WHEN** the app launches with photo access already granted and the ledger has not yet been read
- **THEN** the first frame is `UiState.Loading` ("Loading …"), never a guessed `NeverSynced` that later corrects

## ADDED Requirements

### Requirement: iOS live composition root

The `:app:ios` module SHALL provide a composition-root singleton (`SnapSyncRoot`, `iosMain`) that owns an app-lifetime `CoroutineScope` (a `SupervisorJob` on the main dispatcher) and assembles the live stack: `iosLedgerBackend() → LedgerWatcher → LedgerSyncStatusSource(watcher, permission, scope)` and the PhotoKit permission adapter (as both the `PermissionStatusSource` and `PermissionRequester`), composed into a `StatusContainerHost`. The scope SHALL outlive Compose recomposition so the source collector and container are not torn down with the view. `MainViewController` SHALL render `host.container.stateFlow` and route the gate intents to `host.onRequestPermission` / `host.onOpenSettings`.

#### Scenario: The root assembles the real stack
- **WHEN** the iOS app starts
- **THEN** a single `SnapSyncRoot` constructs the real ledger-backed `LedgerSyncStatusSource` and the PhotoKit permission adapter, wires them into one `StatusContainerHost`, and the screen observes that container

#### Scenario: Permission action flows through the container
- **WHEN** the user activates the gate's "Allow access" or "Open Settings"
- **THEN** `MainViewController` invokes the container intent, which calls the injected `PermissionRequester` — the UI never calls PhotoKit directly

### Requirement: On-disk native ledger on iOS

The `:domain:engine` module SHALL provide an `iosLedgerBackend()` factory (`iosMain`) that constructs the shared `SqlDelightLedgerBackend` over a `NativeSqliteDriver`, persisting the ledger database **on disk in the app sandbox** so its contents survive process death. This factory SHALL be the single site that names the database location (the App-Group container path is deferred to the background-extension capability).

#### Scenario: The ledger persists across launches
- **WHEN** the app writes ledger state, terminates, and relaunches
- **THEN** `iosLedgerBackend()` opens the same on-disk database and the prior state is present

#### Scenario: Native backend honors the ledger contract
- **WHEN** the native-driver-backed `SqlDelightLedgerBackend` is exercised against the ledger backend contract
- **THEN** `get`/`put`/`aggregates` and change signals behave identically to the JVM-driver backend
