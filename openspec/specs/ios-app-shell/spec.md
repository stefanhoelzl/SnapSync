# ios-app-shell Specification

## Purpose
TBD - created by archiving change ios-first-target. Update Purpose after archive.
## Requirements
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

### Requirement: Portrait-only orientation

The iOS app SHALL be presented in upright portrait orientation only. The app SHALL target iPhone only (`TARGETED_DEVICE_FAMILY = "1"`), and its `Info.plist` SHALL declare `UISupportedInterfaceOrientations` as exactly `[UIInterfaceOrientationPortrait]` (no `~ipad` variant, which is not honored for an iPhone-only target), so the UI never rotates to landscape or to upside-down portrait. Targeting iPhone only also avoids the App Store iPad-multitasking validation rule (TMS-90474) that requires a universal app to declare all four orientations. The lock SHALL be the static plist declaration; no runtime per-view-controller orientation override is used.

#### Scenario: Rotating an iPhone to landscape does not rotate the UI
- **WHEN** the app is running on an iPhone and the device is turned to a landscape orientation
- **THEN** the UI stays in upright portrait and does not rotate to landscape or upside-down

#### Scenario: Running on an iPad stays in portrait
- **WHEN** the app runs on an iPad (in iPhone-compatibility mode, since it targets iPhone only) and the device is rotated
- **THEN** the UI stays in upright portrait and does not present a landscape layout

### Requirement: Buildable for the iOS simulator

The `:app:ios` module and its full module dependency closure SHALL compile for the `iosSimulatorArm64` target, and an Xcode project (`iosApp/`) SHALL build a runnable simulator `.app` via `xcodebuild`. The shared modules SHALL also declare the `iosArm64` (device) target. The **simulator** build SHALL require no code signing; a signed **device** archive is produced separately by the `ios-testflight-delivery` capability and is not part of the simulator build or the merge gate.

#### Scenario: Simulator app builds
- **WHEN** `xcodebuild` builds the `iosApp` scheme for the iOS simulator
- **THEN** the app and every module in its dependency closure compile for `iosSimulatorArm64` and a `.app` bundle is produced

#### Scenario: No code signing required for the simulator build
- **WHEN** the simulator app is built
- **THEN** the build completes without code-signing assets (no Apple Developer certificate or provisioning profile)

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

### Requirement: On-disk native ledger on iOS

The `:domain:engine` module SHALL provide an `iosLedgerBackend()` factory (`iosMain`) that constructs the shared `SqlDelightLedgerBackend` over a `NativeSqliteDriver`, persisting the ledger database **on disk in the `group.app.snapsync` App-Group container** so its contents survive process death and are shared between the app and the background-upload extension. This factory SHALL be the single site that names the database location, SHALL open the database in WAL mode (one cross-process writer plus concurrent readers), and SHALL wire the backend's cross-process change notification (post-on-`put` / observe-in-`changes`, per `sync-ledger`). The same factory SHALL serve both processes; read-only access in the app is enforced structurally by handing out the ledger as a `LedgerReader`/`LedgerWatcher` (the app never constructs a `LedgerWriter`).

#### Scenario: The ledger persists across launches
- **WHEN** the app writes ledger state, terminates, and relaunches
- **THEN** `iosLedgerBackend()` opens the same on-disk database and the prior state is present

#### Scenario: The ledger lives in the App-Group container
- **WHEN** the extension writes the ledger and the app later reads it
- **THEN** both open the same database file in the `group.app.snapsync` container, and the app's read reflects the extension's write

#### Scenario: Native backend honors the ledger contract
- **WHEN** the native-driver-backed `SqlDelightLedgerBackend` is exercised against the ledger backend contract
- **THEN** `get`/`put`/`aggregates` and change signals behave identically to the JVM-driver backend

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

### Requirement: Developer launch-environment config trigger

The iOS app SHALL read a `SNAPSYNC_DEEPLINK` variable from the process environment **once per
process launch** and, when it is present and holds a valid `snapsync://config?…` URL, provision the
event **identically to a scanned deeplink** — forwarding the raw URL string to
`SnapSyncRoot.onOpenUrl(_:)`, which performs the authoritative decode/validate and, on success,
provisions the event: a **different** eventId resets the ledger and runs the join reconciliation; the
**same** eventId against a non-empty ledger is a no-op (see `event-rejoin-reconciliation`).
Provisioning SHALL NOT force a fresh whole-library upload — re-provision reconciles against storage
(seeding already-stored photos) rather than re-uploading. The read SHALL reuse the existing
`deeplink-config` decoder and the `onOpenUrl` path verbatim; it SHALL NOT introduce a second decoder
or config-construction path, and SHALL perform no parsing in Swift.

The trigger SHALL be applied **at most once per process**: it SHALL NOT re-apply on Compose view or
view-controller recreation within the same process. A subsequent **cold launch** with the variable
still set SHALL provision again (which reconciles; it does not force a re-upload).

When the variable is **absent**, the app SHALL behave exactly as without this feature (no
provisioning side effect). The trigger SHALL rely on the fact that a process-environment variable is
only injectable via a developer launch (e.g. `pymobiledevice3 developer dvt launch --env`); launches
from SpringBoard or TestFlight carry no such variable, so the trigger is inert in production **with
no compile-time guard**. When the variable is present but holds an invalid or non-`snapsync://`
value, the app SHALL produce no provisioning side effect (the existing decoder rejects it).

#### Scenario: Cold launch with the variable provisions once
- **WHEN** the app is cold-launched with `SNAPSYNC_DEEPLINK` set to a valid `snapsync://config?v=3&d=…`
  URL for an event not currently configured
- **THEN** the app provisions that event exactly as a scanned QR would — resetting the ledger and
  running the join reconciliation — and forcing a view/view-controller recreation within that same
  process does not re-apply the trigger or re-reset the ledger

#### Scenario: A subsequent cold launch re-provisions and reconciles
- **WHEN** the app is launched again in a fresh process with `SNAPSYNC_DEEPLINK` still set
- **THEN** the app re-provisions and reconciles against storage; it does **not** force a fresh
  whole-library re-upload

#### Scenario: Re-provision does not re-upload already-stored photos
- **WHEN** the variable provisions an event whose photos are already stored, against an empty ledger
- **THEN** those photos are seeded `COMPLETED` by the join and are not re-uploaded

#### Scenario: Production launch is inert
- **WHEN** the app is launched from SpringBoard or via TestFlight, with no `SNAPSYNC_DEEPLINK` in its
  environment
- **THEN** no provisioning side effect occurs and behavior is identical to the app without this
  feature, with no compile-time flag distinguishing the build

#### Scenario: Invalid environment value is rejected
- **WHEN** the app is cold-launched with `SNAPSYNC_DEEPLINK` set to a malformed or non-`snapsync://`
  value
- **THEN** the existing decoder rejects it and no provisioning side effect occurs

