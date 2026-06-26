## MODIFIED Requirements

### Requirement: iOS live composition root

The `:app:ios` module SHALL provide a composition-root singleton (`SnapSyncRoot`, `iosMain`) that
owns an app-lifetime `CoroutineScope` (a `SupervisorJob` on the main dispatcher) and assembles the
live stack: `iosLedgerBackend() → LedgerWatcher → LedgerSyncStatusSource(watcher, permission, gallery,
observedCompletions, scope)`, the PhotoKit permission adapter (as both the `PermissionStatusSource`
and `PermissionRequester`), and the iOS Keychain config store (as both the `ConfigSource` and
`ConfigStore`), composed into a `StatusContainerHost`. It SHALL construct the iOS
`ObservedCompletionsSource` (the read-only PhotoKit upload-job reader) and inject it into the source,
and SHALL own a foreground signal (a `Flow<Boolean>`) injected into the `StatusContainerHost` so its
refresh cadence is gated on it. The scope SHALL outlive Compose recomposition so the source collector
and container are not torn down with the view. `MainViewController` SHALL render
`host.container.stateFlow` and route the gate intents to `host.onRequestPermission` /
`host.onOpenSettings`. `SnapSyncRoot` SHALL expose `onOpenUrl(String)` that forwards to the
container's `onOpenUrl` intent, and SHALL expose a foreground entry point (e.g.
`onForeground()`/`onBackground()`) that the SwiftUI scene calls on its scene-phase transitions to
drive the foreground signal. No temporary upload-job probe SHALL remain (the spike is removed).

#### Scenario: The root assembles the real stack

- **WHEN** the iOS app starts
- **THEN** a single `SnapSyncRoot` constructs the real ledger-backed `LedgerSyncStatusSource` (with the
  PhotoKit-backed `ObservedCompletionsSource`), the PhotoKit permission adapter, and the Keychain
  config store, wires them into one `StatusContainerHost` with a foreground signal, and the screen
  observes that container

#### Scenario: Permission action flows through the container

- **WHEN** the user activates the gate's "Allow access" or "Open Settings"
- **THEN** `MainViewController` invokes the container intent, which calls the injected
  `PermissionRequester` — the UI never calls PhotoKit directly

#### Scenario: A deeplink flows through the container

- **WHEN** `SnapSyncRoot.onOpenUrl` is called with a `snapsync://` URL
- **THEN** it forwards to the container's `onOpenUrl` intent, which decodes and (on success) saves via
  the Keychain `ConfigStore`, updating the `ConfigSource`

#### Scenario: Foreground transition drives the refresh cadence

- **WHEN** the SwiftUI scene becomes active and calls the foreground entry point
- **THEN** the foreground signal turns true and the container begins refreshing the
  `ObservedCompletionsSource` while pending work remains; when the scene resigns active, the signal
  turns false and refreshing stops
