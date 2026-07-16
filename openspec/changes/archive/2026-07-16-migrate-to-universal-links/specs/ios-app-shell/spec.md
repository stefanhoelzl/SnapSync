## MODIFIED Requirements

### Requirement: iOS application shell
The system SHALL provide an iOS application built with Compose Multiplatform whose entry point is a
`ComposeUIViewController` (in the `:app:ios` module) that hosts the shared `StatusScreen`. The screen
SHALL render **live** `UiState` observed from an assembled real stack —
`StatusContainerHost.container.stateFlow` — not a static `UiState`. The Swift entry point (`iosApp/`)
SHALL remain a trivial pass-through that obtains the root view controller from `MainViewController()`.
The app SHALL declare the **associated domain** `applinks:<link domain>` in `iosApp.entitlements`
(capability `event-link`), registering it as the handler for the event link's Universal Link, and SHALL
register **no** custom URL scheme (`CFBundleURLTypes` SHALL be absent — the `snapsync` scheme is
retired). The Swift entry SHALL forward an incoming event-link URL — via SwiftUI `onOpenURL`, handling
both cold-launch and warm delivery — as a **raw string** to `SnapSyncRoot.onOpenUrl(_:)`, performing no
parsing in Swift. The forwarded string SHALL be the **complete** URL including its fragment, which
carries the entire payload (capability `event-link`).

The extension target SHALL NOT declare an associated domain: it never handles URLs.

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
- **WHEN** the stock Camera app opens a `https://<link domain>/join#…` event link (cold or warm)
- **THEN** iOS matches the app's associated domain, opens the app, and Swift `onOpenURL` forwards the
  raw URL string — fragment included — to `SnapSyncRoot.onOpenUrl(_:)` without parsing it

#### Scenario: The app registers no custom URL scheme
- **WHEN** the app's `Info.plist` is inspected
- **THEN** it declares no `CFBundleURLTypes`, so a retired `snapsync://` URL reaches nothing

### Requirement: iOS live composition root
The `:app:ios` module SHALL provide a composition-root singleton (`SnapSyncRoot`, `iosMain`) that
owns an app-lifetime `CoroutineScope` (a `SupervisorJob` on the main dispatcher) and assembles the
live stack: the **ledger-backed** `SyncStatusSource` (built from a `LedgerCountsSource`, the permission
source, and the gallery source — see `sync-status`), the PhotoKit permission adapter (as both the
`PermissionStatusSource` and `PermissionRequester`), and the iOS Keychain config store (as both the
`ConfigSource` and `ConfigStore`), composed into a `StatusContainerHost`. It SHALL construct the iOS
`LedgerCountsSource` as a **read-only** reader of the shared App-Group ledger — supplying a
`suspend () -> LedgerCounts` that calls only `iosLedgerBackend().aggregates()` (never a write) — and
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
  `PermissionRequester` — the UI never calls PhotoKit directly

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

### Requirement: Developer launch-environment config trigger
The iOS app SHALL read a `SNAPSYNC_EVENT_LINK` variable from the process environment **once per
process launch** and, when it is present and holds a valid `https://<link domain>/join#…` URL, forward
the raw URL string to `SnapSyncRoot.onOpenUrl(_:)`, which performs the authoritative decode/validate and
drives the join gate (capability `join-event`). Because an opened event link shows a confirmation
gate rather than provisioning silently, the developer trigger's URL SHALL carry **`autoJoin = true`**
so the gate **auto-confirms** headlessly (the headless launch path cannot tap a confirm control): the
app fetches the event details and, on success, enrolls and provisions with no user interaction — a
**different** eventId leaves any current event first and runs the join reconciliation; the **same**
eventId is a no-op that neither re-enrolls nor re-resets (see `event-rejoin-reconciliation` and
`join-event`). Provisioning SHALL NOT force a fresh whole-library upload — re-provision reconciles
against storage (seeding already-stored photos) rather than re-uploading. A `SNAPSYNC_EVENT_LINK` URL
**without** `autoJoin` SHALL open the interactive join gate (which then awaits a tap). The read SHALL
reuse the existing `event-link` decoder and the `onOpenUrl` path verbatim; it SHALL NOT introduce
a second decoder or config-construction path, and SHALL perform no parsing in Swift.

The trigger reaches the decoder **directly**, bypassing iOS's Universal Link resolution entirely — it is
therefore a test of the decode-and-join path, not of the associated-domain wiring.

The trigger SHALL be applied **at most once per process**: it SHALL NOT re-apply on Compose view or
view-controller recreation within the same process. A subsequent **cold launch** with the variable
still set SHALL run again (which reconciles; it does not force a re-upload).

When the variable is **absent**, the app SHALL behave exactly as without this feature (no
provisioning side effect). The trigger SHALL rely on the fact that a process-environment variable is
only injectable via a developer launch (e.g. `pymobiledevice3 developer dvt launch --env`); launches
from SpringBoard or TestFlight carry no such variable, so the trigger is inert in production **with
no compile-time guard**. When the variable is present but holds an invalid URL, a foreign origin, or a
retired `snapsync://` value, the app SHALL produce no provisioning side effect (the existing decoder
rejects it).

#### Scenario: Cold launch with an autoJoin variable provisions once
- **WHEN** the app is cold-launched with `SNAPSYNC_EVENT_LINK` set to a valid
  `https://<link domain>/join#v=3&d=…` URL carrying `autoJoin = true` for an event not currently configured
- **THEN** the gate auto-confirms — the app fetches details, enrolls, and provisions that event exactly
  as a confirmed scan would, and forcing a view/view-controller recreation within that same process
  does not re-apply the trigger

#### Scenario: A subsequent cold launch re-runs and reconciles
- **WHEN** the app is launched again in a fresh process with `SNAPSYNC_EVENT_LINK` still set
- **THEN** the app re-runs the gate and reconciles against storage; it does **not** force a fresh
  whole-library re-upload

#### Scenario: Re-provision does not re-upload or re-enroll the already-joined event
- **WHEN** the variable provisions the event the device is already joined to, against an empty ledger
- **THEN** already-stored photos are seeded `COMPLETED` by the join, nothing is re-uploaded, and no
  empty-manifest enrollment is re-issued (per `join-event`)

#### Scenario: Production launch is inert
- **WHEN** the app is launched from SpringBoard or via TestFlight, with no `SNAPSYNC_EVENT_LINK` in its
  environment
- **THEN** no provisioning side effect occurs and behavior is identical to the app without this
  feature, with no compile-time flag distinguishing the build

#### Scenario: Invalid environment value is rejected
- **WHEN** the app is cold-launched with `SNAPSYNC_EVENT_LINK` set to a malformed URL, a foreign origin,
  or a retired `snapsync://` value
- **THEN** the existing decoder rejects it and no provisioning side effect occurs

### Requirement: Background work defers while protected data is unavailable
The app process SHALL consult `UIApplication.isProtectedDataAvailable` before performing background
work that reads protected state (the Keychain-backed device id and event config). When protected data
is **unavailable** — the device has not been unlocked since boot — the app SHALL **defer** that work
rather than failing it or dropping it, and SHALL resume it when the system posts
`UIApplicationProtectedDataDidBecomeAvailable`, which fires as soon as the user unlocks.

Deferring SHALL NOT mint a device id, SHALL NOT write any Keychain item, and SHALL NOT clear or reset
any persisted state.

The upload extension has no `UIApplication` (the API is unavailable to app extensions). In the
extension process an unavailable protected-data read SHALL instead surface as an unavailability error
and the cycle SHALL be skipped cleanly, per capability `event-link` (*An unreadable config is not
an absent config*).

#### Scenario: A background wake before first unlock defers rather than failing
- **WHEN** the app is woken in the background (a `BGProcessingTask`, a silent push, or a background
  `URLSession` completion) while protected data is unavailable
- **THEN** the work is deferred, no Keychain write or mint occurs, no persisted state is cleared, and
  the process does not terminate

#### Scenario: Deferred work resumes at unlock
- **WHEN** protected data becomes available after such a deferral
- **THEN** the deferred background work runs, rather than waiting for the operating system's next wake

#### Scenario: Work proceeds normally once protected data is available
- **WHEN** the app is woken in the background while the device is locked but has been unlocked at least
  once since boot
- **THEN** protected data is available, the device id and config are read, and the work proceeds without
  deferral
