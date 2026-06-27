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
refresh cadence is gated on it. It SHALL construct the `LeaveEvent` use-case — injecting the
`ConfigStore`, the `LedgerBackend`, the `EventStatusSource`, and as suspend lambdas the producer
disable (`setUploadJobExtensionEnabled(false)`) and the discovery-cursor clear — and inject it into
the `StatusContainerHost`. It SHALL bind the **share action** as a `share: (String) -> Unit` lambda
that presents a `UIActivityViewController` carrying the given invite deeplink string (from the
current top view controller) and inject it into the `StatusContainerHost`. The scope SHALL outlive
Compose recomposition so the source collector and container are not torn down with the view.
`MainViewController` SHALL render `host.container.stateFlow` and route the gate intents to
`host.onRequestPermission` / `host.onOpenSettings`, the leave action to `host.onLeaveEvent`, and the
share action to `host.onShareInvite`; it SHALL collect the container's invite URL (`host.inviteUrl`)
and pass it to `StatusScreen`. `SnapSyncRoot` SHALL expose `onOpenUrl(String)` that forwards to the
container's `onOpenUrl` intent, and SHALL expose a foreground entry point (e.g.
`onForeground()`/`onBackground()`) that the SwiftUI scene calls on its scene-phase transitions to
drive the foreground signal. No temporary upload-job probe SHALL remain (the spike is removed).

#### Scenario: The root assembles the real stack

- **WHEN** the iOS app starts
- **THEN** a single `SnapSyncRoot` constructs the real ledger-backed `LedgerSyncStatusSource` (with the
  PhotoKit-backed `ObservedCompletionsSource`), the PhotoKit permission adapter, the Keychain
  config store, and the `LeaveEvent` use-case, wires them into one `StatusContainerHost` with a
  foreground signal, and the screen observes that container

#### Scenario: Permission action flows through the container

- **WHEN** the user activates the gate's "Allow access" or "Open Settings"
- **THEN** `MainViewController` invokes the container intent, which calls the injected
  `PermissionRequester` — the UI never calls PhotoKit directly

#### Scenario: A deeplink flows through the container

- **WHEN** `SnapSyncRoot.onOpenUrl` is called with a `snapsync://` URL
- **THEN** it forwards to the container's `onOpenUrl` intent, which decodes and (on success) saves via
  the Keychain `ConfigStore`, updating the `ConfigSource`

#### Scenario: The leave action flows through the container into the use-case

- **WHEN** the user confirms the leave action in the joined layer
- **THEN** `MainViewController` invokes `host.onLeaveEvent`, which runs the injected `LeaveEvent` —
  disabling the extension via the injected lambda, resetting the ledger, clearing the discovery
  cursor, clearing the Keychain config, and setting `EventStatus` to `Idle` — and the screen returns
  to the setup gate

#### Scenario: The share action flows through the container into the platform share

- **WHEN** the user activates the share action in the joined layer
- **THEN** `MainViewController` invokes `host.onShareInvite`, which calls the injected `share` lambda
  with the invite deeplink, and `SnapSyncRoot` presents a `UIActivityViewController` carrying that
  deeplink — the UI never constructs UIKit directly and observes no result

#### Scenario: The invite URL is supplied to the screen

- **WHEN** an event is configured
- **THEN** `MainViewController` collects `host.inviteUrl` and passes it to `StatusScreen`, which renders
  the join QR for it in the joined layer

#### Scenario: Foreground transition drives the refresh cadence

- **WHEN** the SwiftUI scene becomes active and calls the foreground entry point
- **THEN** the foreground signal turns true and the container begins refreshing the
  `ObservedCompletionsSource` while pending work remains; when the scene resigns active, the signal
  turns false and refreshing stops
