# ios-app-shell Specification

## Purpose

The iOS application process: a Compose Multiplatform entry point (`ComposeUIViewController` in
`:app:ios`) hosting the shared `StatusScreen`, plus the live composition root that wires the real seams —
the on-disk native ledger, the permission gate, the Keychain-backed event config, remote-notification
registration and silent-push forwarding, and the enable toggle for the background-upload extension.

This capability is the **platform shell only**. `:app:ios` is wiring, not logic: nothing testable lives
here, so these requirements pin the app's structure, entry points, and OS integrations rather than any
behavior — every decision they reach for belongs to a tested `domain`/`capability` module. It also carries the two
developer launch-environment triggers: `SNAPSYNC_EVENT_LINK`, which forwards a
`https://<link domain>/join#…` event link through the same path as a scanned QR to (re)provision an event;
and `SNAPSYNC_FORGE_STATE`, which mounts
the shared `StatusScreen` over a `StatusContainerHost` assembled from **forged sources** — rendering a live
container, never a static `UiState` — so a marketing/App-Store screenshot of any named state can be captured
without a backend, an attestation, or photo access. Both are dev/test affordances that are inert in
production because a launch env var is only injectable via a developer launch.

Decision record: `changes/archive/2026-06-17-ios-first-target`.
## Requirements
### Requirement: iOS application shell
The system SHALL provide an iOS application built with Compose Multiplatform whose entry point is a
`ComposeUIViewController` (in the `:app:ios` module) that hosts the shared `StatusScreen`. The screen
SHALL render **live** `UiState` observed from an assembled real stack —
`StatusContainerHost.container.stateFlow` — not a static `UiState`. The Swift entry point (`iosApp/`)
SHALL remain a trivial pass-through that obtains the root view controller from `MainViewController()`.
The app SHALL declare the **associated domain** `applinks:<link domain>` in `iosApp.entitlements`
(capability `event-link`), registering it as the handler for the event link's Universal Link, and SHALL
register **no** custom URL scheme (`CFBundleURLTypes` SHALL be absent — the `snapsync` scheme is
retired).

An opened event link SHALL reach `SnapSyncRoot.onOpenUrl(_:)` as a **raw string**, performing no parsing
in Swift, in **both** of these cases:

- a **cold launch** — the link was opened while the app was **not running**; and
- a **warm delivery** — the link was opened while the app was running or suspended in memory.

Cold launch is the case that matters most and SHALL NOT be treated as the incidental one: a recipient
tapping an invite for the first time never has the app running, and bootstrapping that recipient is why
the event link exists (capability `event-link`).

The string forwarded SHALL be the **complete** URL including its fragment, which carries the entire
payload (capability `event-link`) — a truncated URL is an empty invite.

This requirement fixes the **outcome**, not the mechanism. Which platform callback delivers the link is
an implementation decision recorded with its evidence (decision record below), because it is an
incidental platform detail rather than part of this capability's contract. A previous revision of this
requirement mandated a specific mechanism (SwiftUI's `onOpenURL`) *and* asserted it handled both cases;
the mechanism cannot do so, so a conforming implementation was broken — and nothing could contradict the
spec, because this module is untestable by rule. Pin a mechanism here only where the mechanism **is** the
contract.

Delivery SHALL be **exactly once** per opened link: a link that provisions twice is a bug, and stacking
redundant delivery hooks is how that happens.

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

#### Scenario: A scanned QR opens the app on a COLD launch and forwards the raw URL
- **WHEN** the stock Camera app opens a `https://<link domain>/join#…` event link while the app is **not
  running**
- **THEN** iOS matches the app's associated domain, launches the app, and the raw URL string — fragment
  included — reaches `SnapSyncRoot.onOpenUrl(_:)` without parsing, so the join gate opens on that event

#### Scenario: An event link opened while the app is running is forwarded too
- **WHEN** an event link is opened while the app is running or suspended in memory
- **THEN** the raw URL string — fragment included — reaches `SnapSyncRoot.onOpenUrl(_:)`

#### Scenario: A link is delivered exactly once
- **WHEN** a single event link is opened, in either case
- **THEN** `SnapSyncRoot.onOpenUrl(_:)` is invoked exactly once for it

#### Scenario: The app registers no custom URL scheme
- **WHEN** the app's `Info.plist` is inspected
- **THEN** it declares no `CFBundleURLTypes`, so a retired `snapsync://` URL reaches nothing

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

### Requirement: On-disk native ledger on iOS

The `:domain:engine` module SHALL provide an `iosLedgerBackend()` factory (`iosMain`) that constructs the shared `SqlDelightLedgerBackend` over a `NativeSqliteDriver`, persisting the ledger database **on disk in the `group.app.snapsync` App-Group container** so its contents survive process death and are shared between the app and the background-upload extension. This factory SHALL be the single site that names the database location, SHALL open the database in WAL mode (one cross-process writer plus concurrent readers), and SHALL wire the backend's cross-process change notification (post-on-`put` / observe-in-`changes`, per `sync-ledger`). The same factory SHALL serve both processes; on the OS-driven tier the app process constructs no `LedgerWriter` — it holds the ledger only as a `LedgerBackend` for its read-only aggregates read and the reset-family operations (per `sync-ledger`).

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

### Requirement: Developer launch-environment forge-state trigger

The iOS app SHALL read a `SNAPSYNC_FORGE_STATE` variable from the process environment
**once per process launch** and, when it is present and names a **recognized** forge
state, SHALL assemble a `StatusContainerHost` from **forged sources** for that state — via
a shared **forge factory** (`:domain:presentation`, `commonMain`) — and render the
screen from that host's `container.stateFlow`, exactly as the production shell renders its
live container. The forged screen SHALL therefore render **live** `UiState` from a real
`StatusContainerHost`, **not a static `UiState`**, preserving the shell invariant; the
trigger substitutes the container's **inputs**, never its output.

While a forge state is active, the app SHALL NOT assemble the live stack: the OS-lifecycle hooks
that would boot it (foreground/background scene transitions, remote-notification and push forwarding)
SHALL be inert, because the unsigned simulator the screenshots run in has no App-Group ledger
container, no App Attest, no photo-library grant, and no backend — and touching any of them would
crash the process. Rendering the forged host SHALL be the process's only significant work.

The forge factory SHALL map a recognized state name to forged source values that drive the
real reduction (`StatusContainerHost`) to the intended frame, and SHALL produce **only
frames the real reduction can reach** — it SHALL NOT fabricate a `UiState` the production
reduction never emits. The factory SHALL forge only the inputs the state requires
(typically the permission source, the config source, and the sync-status source) and SHALL
rely on the container's benign production defaults for the rest (e.g. `AlwaysAttested`,
`InMemoryDownloadStatusSource`), so a settled `Joined(InSync)` frame is reached without a
backend, an attestation token, or photo-library access. The recognized state names and
their forged inputs SHALL live in the factory (under test in `commonTest`, running on both
JVM and `iosSimulatorArm64`); `:app:ios` SHALL only read the variable and mount the
factory's host, introducing no state-selection or `UiState`-construction logic in the
wiring-only shell and performing no parsing in Swift.

The trigger SHALL be applied **at most once per process**: it SHALL NOT re-apply on Compose
view or view-controller recreation within the same process. When the variable is **absent**,
the app SHALL behave exactly as without this feature — it SHALL assemble and render the live
production stack (`SnapSyncRoot`) with no forge side effect. When the variable is present but
names an **unrecognized** state, the app SHALL produce no forge side effect and SHALL fall
back to the live production stack.

The trigger SHALL rely on the fact that a process-environment variable is only injectable via
a developer launch (e.g. `pymobiledevice3 developer dvt launch --env`, or a `simctl` launch
`--env`); launches from SpringBoard or TestFlight carry no such variable, so the trigger is
inert in production **with no compile-time guard**.

#### Scenario: A recognized forge state renders that frame live
- **WHEN** the app is cold-launched with `SNAPSYNC_FORGE_STATE` set to a recognized state
  (e.g. `in_sync`)
- **THEN** the app assembles a `StatusContainerHost` from the factory's forged sources for
  that state and renders `container.stateFlow`, showing the corresponding frame
  (e.g. `Joined(SyncHealth.InSync)` with the forged event name)

#### Scenario: The forged screen is the live container, not a static UiState
- **WHEN** a forge state is active
- **THEN** the rendered screen is the shared `StatusScreen` observing a real
  `StatusContainerHost.container.stateFlow` — the same path the production shell uses — and
  no static `UiState` is passed to the screen

#### Scenario: The factory only produces reduction-reachable frames
- **WHEN** the forge factory maps a recognized state name to forged sources
- **THEN** the resulting frame is one the production reduction (`StatusContainerHost`) can
  itself emit from those inputs, and the factory constructs no `UiState` the real reduction
  never produces

#### Scenario: A settled frame needs no backend, attestation, or photo access
- **WHEN** the `in_sync` state is forged
- **THEN** the container reaches `Joined(SyncHealth.InSync)` using the benign default
  `attestedSource` and `downloadSource` with only permission, config, and sync-status
  forged — with no network call, no attestation token, and no photo-library access

#### Scenario: Forge mode does not boot the live stack
- **WHEN** a forge state is active and the app's scene transitions to foreground (or background)
- **THEN** the OS-lifecycle hook is inert — it assembles no live stack, opens no ledger, requests no
  attestation, reads no photo library, and makes no network call — so the process only renders the
  forged screen

#### Scenario: Absent variable renders the live production stack
- **WHEN** the app is launched from SpringBoard or TestFlight with no `SNAPSYNC_FORGE_STATE`
  in its environment
- **THEN** no forge side effect occurs, the live production stack (`SnapSyncRoot`) is
  assembled and rendered, and behavior is identical to the app without this feature, with no
  compile-time flag distinguishing the build

#### Scenario: Unrecognized value falls back to the live stack
- **WHEN** the app is cold-launched with `SNAPSYNC_FORGE_STATE` set to a value the factory
  does not recognize
- **THEN** no forge side effect occurs and the app assembles and renders the live production
  stack

#### Scenario: The trigger applies at most once per process
- **WHEN** a forge state is active and the Compose view or view controller is recreated
  within the same process
- **THEN** the trigger is not re-applied, and a subsequent **cold launch** with the variable
  still set forges again in the fresh process

### Requirement: Remote-notification capability declaration

The iOS app SHALL declare the push capability required to receive silent remote notifications: the
`aps-environment` entitlement in `iosApp.entitlements` (`development` for dev/sideloaded builds,
`production` for TestFlight/App Store, driven by the build configuration) and the `remote-notification`
value in `UIBackgroundModes` in `Info.plist` (so a `content-available` push can wake the app in the
background). The APNs environment the device registers (`sandbox` | `production`) SHALL be a
compile-time value baked from the build configuration (`Config.xcconfig`), consistent with the
`aps-environment` the entitlement declares.

#### Scenario: The app declares the push entitlement and background mode

- **WHEN** the app is built
- **THEN** `iosApp.entitlements` carries `aps-environment` and `Info.plist` `UIBackgroundModes`
  includes `remote-notification`

#### Scenario: Dev builds register the sandbox environment

- **WHEN** a dev/sideloaded build registers its token
- **THEN** the entitlement is `development` and the reported APNs `env` is `sandbox`; a
  TestFlight/App Store build reports `production`

### Requirement: Register for remote notifications and forward the token

On launch the app SHALL register for remote notifications (`UIApplication.registerForRemoteNotifications`)
and, when the OS delivers the APNs device token (`didRegisterForRemoteNotificationsWithDeviceToken`),
forward the token — as the encoded token string plus the compile-time `env` — into the Kotlin push
seam (`:capability:push`) for registration with the backend. A registration failure
(`didFailToRegisterForRemoteNotificationsWithError`) SHALL be logged and SHALL NOT crash or block the
app. The Swift `AppDelegate` SHALL perform **no** decision logic — it is a pass-through to Kotlin,
consistent with the existing deeplink / background-URL-session hooks.

#### Scenario: The delivered device token reaches the push seam

- **WHEN** the OS delivers the APNs device token to the `AppDelegate`
- **THEN** the token string and the compile-time `env` are forwarded to the Kotlin push registration
  path, which writes the device config to the backend

#### Scenario: A registration error does not crash the app

- **WHEN** remote-notification registration fails
- **THEN** the failure is logged and the app continues running normally

### Requirement: Forward an incoming silent push to the receiver seam

The `AppDelegate` SHALL forward an incoming remote notification to the Kotlin `PushReceiver` seam and
then call the OS fetch completion handler, performing no parsing or decision logic in Swift (it is a
pass-through, like the existing deeplink and background-URL-session hooks). The OS entry point is the
app-delegate remote-notification callback that supplies the payload and a completion handler. In this
infrastructure phase the wired receiver logs receipt (capability `push-registration`), so an incoming
silent push is observable without any use-case behavior.

#### Scenario: An incoming push is routed to Kotlin

- **WHEN** the app receives a silent remote notification
- **THEN** the `AppDelegate` forwards it to the Kotlin `PushReceiver` and calls the OS completion
  handler, with no parsing or decision in Swift

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

### Requirement: Background entry points record protected-data state

Every background entry point of both processes SHALL log, to the device diagnostic log (capability
`diagnostic-logging`), the protected-data state it observed. The entry points are the app's download
import-tail backstop, its silent-push handler, its background-`URLSession` handler, and the extension's
`process()`.

The **app** SHALL log protected-data availability directly (it can ask `UIApplication`). The
**extension** cannot: `UIApplication` is unavailable to app extensions and the platform offers no
equivalent, so it SHALL instead log the status returned by each Keychain read it performed — the only
observable proxy available to it, and the one that distinguishes *unreadable* from *absent*.

An end-to-end background wake on a **locked** device cannot be exercised by any test: the simulator has
no lock state, and a background task's scheduling is owned by the operating system and cannot be forced.
These diagnostics are therefore the only means of confirming, from a real device, that background work
reached its protected state — and of diagnosing it when it does not.

#### Scenario: A locked background wake is observable after the fact
- **WHEN** background work runs on a locked device and the device log is subsequently pulled
- **THEN** the log states, for that invocation, whether protected data was available (in the app) or what
  status each Keychain read returned (in the extension)

#### Scenario: A failed protected read is attributable to its trigger
- **WHEN** a Keychain read fails during background work
- **THEN** the logged line carries the entry-point prefix of the trigger that started it, so the failure
  is traceable to the backstop, the silent push, the URL-session handler, or the extension cycle

