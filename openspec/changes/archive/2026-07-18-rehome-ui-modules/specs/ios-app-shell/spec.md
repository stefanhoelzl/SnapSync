# ios-app-shell — delta for rehome-ui-modules

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
- **THEN** it is the same `StatusScreen` composable the desktop app uses (from `:ui:screens`, themed
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

### Requirement: iOS live composition root
The `:app:ios` module SHALL provide a composition-root singleton (`SnapSyncRoot`, `iosMain`) that
owns an app-lifetime `CoroutineScope` (a `SupervisorJob` on the main dispatcher) and assembles the
live stack **through the shared composition** `snapSyncApp` (`:domain` `compose/`, spec
`module-architecture` "One shared composition"): the root constructs the platform adapters and
supplies them as `AppPorts` — platform effect lambdas included (the protected-data gate, the
liveness deregistration, the backstop scheduling, the share-sheet presentation, and the resolved
tier's mechanism thunks) — and `snapSyncApp` composes the feature graph: the **ledger-backed**
`SyncStatusSource` (built from a `LedgerCountsSource`, the permission source, and the gallery
source — see `sync-status`), the attestation, upload-arm, join/leave/create use-cases, the download
controller and jobs, the album coordinator, the `flow/` trigger instances (Foreground · Background
· SilentPush · DownloadBackstop · Provision), and the **user-tap command bundle**
(`model/`'s `UserCommands`: leave · create · commitJoin · share · requestAccess · openSettings —
seated in `model/` since migration step 9, because the armed presentation gate forbids
`:ui:presentation` naming `flow/`; live instances are still built and decorated only in
`compose/`), which the root injects into the `StatusContainerHost` — presentation fires commands
only through the bundle and references no feature command, port, or flow callable directly. The
root passes the PhotoKit permission adapter's **permission StateFlow** and the Keychain config
adapter's **config StateFlow** into the host (since step 9 the host's read-model inputs are bare
StateFlows — presentation names no `ports/` type), supplies the same permission adapter as
`AppPorts.photoAccessRequester` (the port the bundle's `requestAccess`/`openSettings` commands are
bound to in `compose/`), and passes the Keychain adapter as both the `ConfigSource` and
`ConfigStore` in `AppPorts`. The root SHALL bind the `Clock`/`TimeZoneSource` ports' system
adapters (`:adapter:generic`'s `SystemClock`/`SystemTimeZone`) into the **one shared, pure**
`CutoffFormatter` (its now/zone arrive injected — the through-ports repayment of step 9) handed to
the host, the screen, and the forge factory; the formatter is root-owned rather than
`AppCore`-owned deliberately, so the forge composition reaches it with no route to the live
graph. The composed graph SHALL construct the iOS
`LedgerCountsSource` as a **read-only** reader of the shared App-Group ledger — supplying a
`suspend () -> LedgerCounts` that calls only `iosLedgerStore().aggregates()` (never a write) — and
SHALL issue **no** storage LIST for upload status. On the OS-driven tier the composed graph SHALL
construct **no `LedgerWriter`** (the ledger read is read-only; the extension is the sole writer)
and **no `EventStatusSource`** (the ledger is private to the extension, which also owns
reconciliation — see `event-rejoin-reconciliation`).

The root SHALL resolve its composition **once per process** through the pure sealed resolver
(`model/`'s `resolveComposition` over the parsed `LaunchDirectives` and `OsFacts`) and SHALL switch
on the resolved `CompositionMode` in exactly **one** place, selecting a per-mode shell delegate
(forge or live, with the live tier's mechanism thunks bound in the same switch). Every OS entry
point (`onForeground` / `onBackground` / `onOpenUrl` / `onPushToken` / `onSilentPush` /
`runUploadHeartbeat` / `runDownloadBackstop` / `handleBackgroundUrlSession`) SHALL be a thin
delegator to that resolved delegate, re-checking no forge or tier flag.

The permission-grant subscriptions (upload-arm start on grant; sole-creator album ensure — see
`event-album`) SHALL be installed by an explicit `AppCore.installPermissionSubscriptions()`
(`compose/`) invoked **only from the root's host-assembly path**: a cold background wake (the
download backstop or a background-`URLSession` relaunch) that merely touches the composed graph
SHALL NOT install them, so no producer start fires off the permission StateFlow's replay outside
host assembly.

The root SHALL register, **while foregrounded**, an observer for the extension's cross-process
liveness notification (the Darwin notification posted after each PhotoKit `process()` run — see
`ios-photokit-upload`); the observer SHALL be registered on foreground entry and unregistered on
backgrounding (via the Background flow's injected deregistration effect — a suspended app cannot
act on the post, and the foreground re-read is the backstop), and the notification SHALL drive
`LedgerCountsSource.refresh()` and a fresh status emission. The scope SHALL outlive Compose
recomposition so the source collector and container are not torn down with the view.
`MainViewController` SHALL render `host.container.stateFlow` and route the gate intents to
`host.onRequestPermission` / `host.onOpenSettings`, the leave action to `host.onLeaveEvent`, and
the share action to `host.onShareInvite`; it SHALL collect the container's invite URL
(`host.inviteUrl`) and pass it to `StatusScreen`, together with the root's shared
`CutoffFormatter` (the screen carries no system-reading default). `SnapSyncRoot` SHALL expose `onOpenUrl(String)`
that reaches the container's `onOpenUrl` intent (through the live delegate), and
foreground/background entry points the SwiftUI scene calls on its scene-phase transitions to drive
the Foreground/Background flows and the liveness-observer lifecycle.

#### Scenario: The root assembles the real stack

- **WHEN** the iOS app starts
- **THEN** a single `SnapSyncRoot` resolves the composition mode once, constructs the platform
  adapters and calls `snapSyncApp`, which composes the ledger-backed `SyncStatusSource` (with the
  read-only `LedgerCountsSource`), the flows, and the user-tap command bundle over the PhotoKit
  permission adapter and the Keychain config store; the root wires the result into one
  `StatusContainerHost` — constructing no `LedgerWriter` on the OS-driven tier and no
  `EventStatusSource`, and issuing no storage LIST for upload status

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
- **THEN** `MainViewController` invokes the container intent, which fires the bundle's
  `requestAccess`/`openSettings` command, whose compose-built body calls the
  `PhotoAccessRequester` port — the UI never calls PhotoKit directly and names no port

#### Scenario: An event link flows through the container

- **WHEN** `SnapSyncRoot.onOpenUrl` is called with a `https://<link domain>/join#…` event link
- **THEN** it forwards (through the live delegate) to the container's `onOpenUrl` intent, which
  decodes and (on success) saves via the Keychain `ConfigStore`, updating the `ConfigSource`

#### Scenario: The leave action flows through the command bundle into the use-case

- **WHEN** the user confirms the leave action in the joined layer
- **THEN** `MainViewController` invokes `host.onLeaveEvent`, which fires the bundle's `leave`
  command — cancelling in-flight downloads, then running the composed `LeaveEvent` (stopping the
  producer via the tier-neutral arm and clearing the Keychain config; no ledger or `EventStatus`
  operation) — and the screen returns to the setup gate

#### Scenario: The share action flows through the command bundle into the platform share

- **WHEN** the user activates the share action in the joined layer
- **THEN** `MainViewController` invokes `host.onShareInvite`, which fires the bundle's `share`
  command with the invite link, and the shell-supplied lambda presents a `UIActivityViewController`
  carrying that link — the UI never constructs UIKit directly and observes no result

#### Scenario: A cold background wake installs no grant subscription

- **WHEN** the process is launched in the background by the download backstop or a
  background-`URLSession` relaunch, without the host-assembly path running
- **THEN** touching the composed graph installs no permission-grant collector, and no upload
  producer starts off the permission StateFlow's replayed `GRANTED` value

### Requirement: Developer launch-environment forge-state trigger

The iOS app SHALL read a `SNAPSYNC_FORGE_STATE` variable from the process environment
**once per process launch** and, when it is present and names a **recognized** forge
state, SHALL assemble a `StatusContainerHost` from **forged sources** for that state — via
a shared **forge factory** (`:ui:presentation`, `commonMain` — re-homed from
`:domain:presentation` at migration step 9; the factory receives the shell's `CutoffFormatter`,
constructing no system-clock-reading formatter itself) — and render the
screen from that host's `container.stateFlow`, exactly as the production shell renders its
live container. The forged screen SHALL therefore render **live** `UiState` from a real
`StatusContainerHost`, **not a static `UiState`**, preserving the shell invariant; the
trigger substitutes the container's **inputs**, never its output.

While a forge state is active, the app SHALL NOT assemble the live stack: the OS-lifecycle hooks
that would boot it (foreground/background scene transitions, remote-notification and push forwarding,
the background-task and background-`URLSession` handlers) SHALL be inert, because the unsigned
simulator the screenshots run in has no App-Group ledger container, no App Attest, no photo-library
grant, and no backend — and touching any of them would crash the process. Rendering the forged host
SHALL be the process's only significant work; OS completion handlers SHALL still be invoked (they are
the OS's, and an unanswered one costs the app future background wakes).

The forge decision SHALL be made **once per process** by the pure sealed composition resolver
(`model/`'s `resolveComposition`, unit-tested precedence): forge excludes the live-stack boot and
wins **unconditionally over an event link** — a screenshot run that also carries
`SNAPSYNC_EVENT_LINK` renders the forged frame and provisions nothing. Forge inertness SHALL be
**structural**, not guarded: the shell's single mode switch selects a forge delegate that holds no
reference to the live stack, so no entry point can boot it — there is no per-entry-point flag to
forget.

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

#### Scenario: Forge wins over an event link
- **WHEN** the app is cold-launched with both a recognized `SNAPSYNC_FORGE_STATE` and a
  `SNAPSYNC_EVENT_LINK` in its environment
- **THEN** the forged frame renders, the event link is ignored, and nothing is provisioned — the
  resolver's precedence (unit-tested) makes the live boot unreachable
