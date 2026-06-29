## MODIFIED Requirements

### Requirement: iOS live composition root

The `:app:ios` module SHALL provide a composition-root singleton (`SnapSyncRoot`, `iosMain`) that
owns an app-lifetime `CoroutineScope` (a `SupervisorJob` on the main dispatcher) and assembles the
live stack: the **listing-backed** `SyncStatusSource` (built from a `CompletedAssetsSource`, a
`PendingManifestsSource`, the permission source, and the gallery source — see `sync-status`), the
PhotoKit permission adapter (as both the `PermissionStatusSource` and `PermissionRequester`), and the
iOS Keychain config store (as both the `ConfigSource` and `ConfigStore`), composed into a
`StatusContainerHost`. It SHALL construct the iOS `CompletedAssetsSource` (the HTTP completeness-listing
reader) and `PendingManifestsSource` (the App-Group manifest reader/pruner), and SHALL own a foreground
signal (a `Flow<Boolean>`) injected into the `StatusContainerHost` that drives the `CompletedAssetsSource`
refresh on foreground entry (the manifest `URLSession` completion drives the other refresh). It SHALL
construct **no ledger type** and **no `EventStatusSource`** (status is read from the listing; the
ledger is private to the extension, which also owns reconciliation — see `event-rejoin-reconciliation`).
It SHALL construct the `LeaveEvent` use-case — injecting the `ConfigStore` and, as a suspend lambda, the
producer disable (`setUploadJobExtensionEnabled(false)`) — and inject it into the `StatusContainerHost`.
It SHALL bind the **share action** as a `share: (String) -> Unit` lambda that presents a
`UIActivityViewController` carrying the given invite deeplink string (from the current top view
controller) and inject it into the `StatusContainerHost`. The scope SHALL outlive Compose recomposition so
the source collector and container are not torn down with the view. `MainViewController` SHALL render
`host.container.stateFlow` and route the gate intents to `host.onRequestPermission` / `host.onOpenSettings`,
the leave action to `host.onLeaveEvent`, and the share action to `host.onShareInvite`; it SHALL collect the
container's invite URL (`host.inviteUrl`) and pass it to `StatusScreen`. `SnapSyncRoot` SHALL expose
`onOpenUrl(String)` that forwards to the container's `onOpenUrl` intent, and a foreground entry point
(e.g. `onForeground()`/`onBackground()`) that the SwiftUI scene calls on its scene-phase transitions to
drive the foreground signal.

#### Scenario: The root assembles the real stack

- **WHEN** the iOS app starts
- **THEN** a single `SnapSyncRoot` constructs the listing-backed `SyncStatusSource` (with the
  `CompletedAssetsSource` and `PendingManifestsSource`), the PhotoKit permission adapter, the Keychain
  config store, and the `LeaveEvent` use-case, wires them into one `StatusContainerHost` with a foreground
  signal, and the screen observes that container — constructing no ledger type and no `EventStatusSource`

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
  disabling the extension via the injected lambda and clearing the Keychain config (no ledger or
  `EventStatus` operation) — and the screen returns to the setup gate

#### Scenario: The share action flows through the container into the platform share

- **WHEN** the user activates the share action in the joined layer
- **THEN** `MainViewController` invokes `host.onShareInvite`, which calls the injected `share` lambda
  with the invite deeplink, and `SnapSyncRoot` presents a `UIActivityViewController` carrying that
  deeplink — the UI never constructs UIKit directly and observes no result

### Requirement: Enable the background-upload extension on grant

When photo-library access is (or becomes) full (`.readWrite` → `GRANTED`), the app SHALL enable the
background-upload extension (`PHPhotoLibrary.setUploadJobExtensionEnabled(true)`) so the system can
invoke it. The app SHALL **not** run any join, fetch, enumeration, or seed, and SHALL **not** disable the
extension around a join — reconciliation runs **inside the extension**, gated by its `joinedEventId`
marker (see `event-rejoin-reconciliation`). The app creates no upload jobs, performs no uploads, and
constructs no ledger type. The enable call SHALL be idempotent-safe to repeat on each grant/foreground.

#### Scenario: Granting full access enables the extension directly

- **WHEN** photo-library permission transitions to `GRANTED` with a configured event
- **THEN** the app calls `setUploadJobExtensionEnabled(true)` without fetching, enumerating, or seeding —
  the extension self-reconciles on its next cycle

#### Scenario: The app never uploads or seeds

- **WHEN** the app is running with a configured event
- **THEN** it creates no upload jobs, performs no library enumeration for a seed, and constructs no ledger type
