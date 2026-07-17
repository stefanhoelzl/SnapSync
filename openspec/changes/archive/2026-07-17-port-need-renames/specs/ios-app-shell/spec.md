# ios-app-shell — delta for port-need-renames

## MODIFIED Requirements

### Requirement: iOS live composition root
The `:app:ios` module SHALL provide a composition-root singleton (`SnapSyncRoot`, `iosMain`) that
owns an app-lifetime `CoroutineScope` (a `SupervisorJob` on the main dispatcher) and assembles the
live stack: the **ledger-backed** `SyncStatusSource` (built from a `LedgerCountsSource`, the permission
source, and the gallery source — see `sync-status`), the PhotoKit permission adapter (as both the
`PhotoAccessStatusSource` and `PhotoAccessRequester`), and the iOS Keychain config store (as both the
`ConfigSource` and `ConfigStore`), composed into a `StatusContainerHost`. It SHALL construct the iOS
`LedgerCountsSource` as a **read-only** reader of the shared App-Group ledger — supplying a
`suspend () -> LedgerCounts` that calls only `iosLedgerStore().aggregates()` (never a write) — and
SHALL issue **no** storage LIST for upload status. It SHALL own a foreground signal (a `Flow<Boolean>`)
injected into the `StatusContainerHost` and SHALL register, **while foregrounded**, an observer for the
extension's cross-process liveness notification (the Darwin notification posted after each PhotoKit
`process()` run — see `ios-photokit-upload`); both the foreground signal and the liveness notification
SHALL drive `LedgerCountsSource.refresh()` and a fresh status emission. The observer SHALL be registered
on foreground entry and unregistered on background (a suspended app cannot act on the post, and the
foreground re-read is the backstop). It SHALL construct **no `LedgerWriter`** (the ledger read is
read-only; the extension is the sole writer) and **no `EventStatusSource`** (the ledger is private to
the extension, which also owns reconciliation — see `event-rejoin-reconciliation`). It SHALL construct
the `LeaveEvent` use-case — injecting the `ConfigStore` and, as a suspend lambda, the producer disable
(`setUploadJobExtensionEnabled(false)`) — and inject it into the `StatusContainerHost`. It SHALL bind
the **share action** as a `share: (String) -> Unit` lambda that presents a `UIActivityViewController`
carrying the given invite link string (from the current top view controller) and inject it into the
`StatusContainerHost`. The scope SHALL outlive Compose recomposition so the source collector and
container are not torn down with the view. `MainViewController` SHALL render `host.container.stateFlow`
and route the gate intents to `host.onRequestPermission` / `host.onOpenSettings`, the leave action to
`host.onLeaveEvent`, and the share action to `host.onShareInvite`; it SHALL collect the container's
invite URL (`host.inviteUrl`) and pass it to `StatusScreen`. `SnapSyncRoot` SHALL expose
`onOpenUrl(String)` that forwards to the container's `onOpenUrl` intent, and a foreground entry point
(e.g. `onForeground()`/`onBackground()`) that the SwiftUI scene calls on its scene-phase transitions to
drive the foreground signal and the liveness-observer lifecycle.

#### Scenario: The root assembles the real stack

- **WHEN** the iOS app starts
- **THEN** a single `SnapSyncRoot` constructs the ledger-backed `SyncStatusSource` (with the read-only
  `LedgerCountsSource`), the PhotoKit permission adapter, the Keychain config store, and the `LeaveEvent`
  use-case, wires them into one `StatusContainerHost` with a foreground signal, and the screen observes
  that container — constructing no `LedgerWriter` and no `EventStatusSource`, and issuing no storage LIST
  for upload status

#### Scenario: The liveness notification refreshes status while foreground

- **WHEN** the app is foregrounded and the extension posts its cross-process liveness notification
- **THEN** the registered observer triggers `LedgerCountsSource.refresh()` and a fresh status emission,
  with no network read

#### Scenario: The observer is foreground-only

- **WHEN** the app moves to the background
- **THEN** the liveness-notification observer is unregistered, and it is re-registered on the next
  foreground entry (which itself also refreshes status)

#### Scenario: Permission action flows through the container

- **WHEN** the user activates the gate's "Allow access" or "Open Settings"
- **THEN** `MainViewController` invokes the container intent, which calls the injected
  `PhotoAccessRequester` — the UI never calls PhotoKit directly

#### Scenario: An event link flows through the container

- **WHEN** `SnapSyncRoot.onOpenUrl` is called with a `https://<link domain>/join#…` event link
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
  with the invite link, and `SnapSyncRoot` presents a `UIActivityViewController` carrying that
  link — the UI never constructs UIKit directly and observes no result

### Requirement: On-disk native ledger on iOS

The `:domain:engine` module SHALL provide an `iosLedgerStore()` factory (`iosMain`) that constructs the shared `SqlDelightLedgerStore` over a `NativeSqliteDriver`, persisting the ledger database **on disk in the `group.app.snapsync` App-Group container** so its contents survive process death and are shared between the app and the background-upload extension. This factory SHALL be the single site that names the database location, SHALL open the database in WAL mode (one cross-process writer plus concurrent readers), and SHALL wire the backend's cross-process change notification (post-on-`put` / observe-in-`changes`, per `sync-ledger`). The same factory SHALL serve both processes; on the OS-driven tier the app process constructs no `LedgerWriter` — it holds the ledger only as a `LedgerStore` for its read-only aggregates read and the reset-family operations (per `sync-ledger`).

#### Scenario: The ledger persists across launches
- **WHEN** the app writes ledger state, terminates, and relaunches
- **THEN** `iosLedgerStore()` opens the same on-disk database and the prior state is present

#### Scenario: The ledger lives in the App-Group container
- **WHEN** the extension writes the ledger and the app later reads it
- **THEN** both open the same database file in the `group.app.snapsync` container, and the app's read reflects the extension's write

#### Scenario: Native backend honors the ledger contract
- **WHEN** the native-driver-backed `SqlDelightLedgerStore` is exercised against the ledger backend contract
- **THEN** `get`/`put`/`aggregates` and change signals behave identically to the JVM-driver backend
