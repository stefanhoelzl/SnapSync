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

That view controller SHALL be obtained **only while the app is active**. A process launched or woken into
the background — by a silent push, a `BGTask`, or a background `URLSession` event — SHALL compose **no**
scene: no `ComposeUIViewController`, no Compose runtime, and no renderer. The scene SHALL be composed at
the first activation and not before.

This is a **mitigation for a renderer defect, not an architectural preference**, and SHALL be described as
such wherever it is documented. Apple's contract is that a backgrounded app must not submit GPU work
(`kIOGPUCommandBufferCallbackErrorBackgroundExecutionNotPermitted`) and that a backgrounded app's GPU
resources are reclaimed; a Metal-backed renderer is expected to free them on background and rebuild them on
foreground. Compose Multiplatform 1.11.1 does not, and the observable consequence is a scene composed while
invisible, kept for hours, and then presented drawing its texture-backed content — glyph atlas, cached
`ImageBitmap`s, cached vector layers — blank or corrupted, while plain geometry still draws. Two production
reports on different devices, OS majors and upload tiers exhibited exactly that, with no hang, no crash and
no memory signature. **Expiry trigger:** the upstream defect
([CMP-5978](https://youtrack.jetbrains.com/issue/CMP-5978)) fixed in a Compose Multiplatform release this
project adopts — at which point this condition SHALL be re-evaluated and removed if the renderer honours the
contract.

Deferring the scene SHALL NOT defer any other work. Every background trigger runs off the composition root's
`AppCore`, which is independent of the UI container; nothing outside `MainViewController` observes
`renderHost`. A background wake SHALL therefore behave exactly as before, minus the scene.

The decision of whether to compose SHALL be a **pure, tested resolver** consumed by a single `when` in
`:app:ios`, following the sealed-mode pattern the composition-mode resolver already establishes. Swift SHALL
express it as wiring — an assignment or a bound value — and SHALL contain no conditional, so the
transcriber law continues to hold at zero decisions.

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

Link delivery SHALL be independent of scene composition: a link SHALL reach `onOpenUrl` whether or not a
scene has been composed, because the delivery hooks are the scene delegate's and not the scene's.

The string forwarded SHALL be the **complete** URL including its fragment, which carries the entire
payload (capability `event-link`) — a truncated URL is an empty invite.

This requirement fixes the **outcome**, not the mechanism. Which platform callback delivers the link is
an implementation decision recorded with its evidence (decision record below), because it is an
incidental platform detail rather than part of this capability's contract. A previous revision of this
requirement mandated a specific mechanism (SwiftUI's `onOpenURL`) *and* asserted it handled both cases;
the mechanism cannot do so, so a conforming implementation was broken — and nothing could contradict the
spec, because this module is untestable by rule. Pin a mechanism here only where the mechanism **is** the
contract.

Each delivery hook SHALL forward under a **distinct entry-point name**, so the device log names which
hook the platform actually invoked (capability `diagnostic-logging`). Two hooks that are
indistinguishable in the log would leave the OS-version question open exactly as it is today.

Delivery SHALL be **exactly once** per opened link: a link that provisions twice is a bug, and stacking
redundant delivery hooks is how that happens. That constraint is not merely prudence — it is now
measured. A second warm hook (SwiftUI's continuation modifier) was added and removed in one change: a
scene has exactly ONE delegate, this app installs its own for the cold path, and SwiftUI's — which feeds
that modifier — is therefore never created. On device it took 8 warm deliveries with 8 hits on the scene
delegate and **zero** on the modifier. The hooks named in July's matrix are mutually exclusive
configurations, not features that compose; a future reader tempted to "add a second warm path" SHALL
re-derive that before doing so.

The mechanism by which the shell re-asks for the root view controller at activation is an
implementation decision, not part of this contract — but it SHALL key on a signal that fires however the
app is opened, **including a headless developer launch** that foregrounds the process without connecting
a scene session. A scene-level callback does not satisfy that (measured 2026-08-06: a `dvt launch` app
never received `sceneDidBecomeActive` and showed a black screen), and losing the headless path would cost
this project the only way an agent can see the app at all.

The **resolved mode** SHALL be recorded in the device log under the shell's platform-invocation logging
(capability `diagnostic-logging`) — not merely the fact that the entry point ran. The entry point runs in
both cases; what distinguishes them is what it returned, so the log SHALL name it. That line is the
verification of this requirement, and no additional instrumentation is required for it: a report can then be
read for whether a scene was composed before the process was ever active.

The extension target SHALL NOT declare an associated domain: it never handles URLs.

#### Scenario: Launching the app into the foreground shows live status
- **WHEN** the iOS app is launched and becomes active
- **THEN** a `ComposeUIViewController` presents the shared `StatusScreen` rendering the current
  `UiState` from the live container, updating as config, permission, and ledger state change

#### Scenario: A background-launched process composes no scene
- **WHEN** the process is launched or woken by a silent push, a `BGTask`, or a background `URLSession`
  event, and never becomes active
- **THEN** no `ComposeUIViewController` is created, and every scene-mode the device log records for that
  process is the deferred one

#### Scenario: The first activation composes the scene
- **WHEN** a process that was launched into the background is later brought to the foreground
- **THEN** the scene is composed at that activation, and the log records the live mode only at or after
  activation, never before it (the app-level foreground entry and the scene composition ride the same
  notification, so their relative order is not contracted)

#### Scenario: A later activation does not rebuild the scene
- **WHEN** an app whose scene is already composed is backgrounded and brought forward again
- **THEN** the same scene is presented, and screen-local state such as an open settings surface or a
  half-typed report survives

#### Scenario: A background wake still does its work without a scene
- **WHEN** a silent push wakes a process that composes no scene
- **THEN** the wake's reconcile, upload-cycle and download work run exactly as they would with a scene

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

#### Scenario: A link arriving at a process with no scene is still forwarded
- **WHEN** an event link is opened against a process that was woken into the background and has composed
  no scene
- **THEN** the raw URL string still reaches `SnapSyncRoot.onOpenUrl(_:)` exactly once

#### Scenario: A link is delivered exactly once
- **WHEN** a single event link is opened, in either case
- **THEN** `SnapSyncRoot.onOpenUrl(_:)` is invoked exactly once for it

#### Scenario: The log names which hook the platform invoked
- **WHEN** an event link is opened while the app is running
- **THEN** the entry recorded for it names the specific hook that received it, so a warm-delivery gap on
  one OS version is diagnosable from a dump rather than inferred

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
live stack **through the shared composition** `snapSyncApp` (`:domain` `compose/`, spec
`module-architecture` "One shared composition"): the root constructs the platform adapters and
supplies them as `AppPorts` — platform effect lambdas included (the trigger-time membership
re-read `reloadConfig` (bound to the config adapter's `reload()`), the backstop scheduling, and the
resolved
tier's mechanism thunks). Every platform touch the root once supplied as an inline lambda SHALL be a
port instead: the share sheet (`SharePresenter`), the limited-library picker
(`PhotoAccessRequester.choosePhotos`), the download-staging root (`StagedBytes.stagingRoot`), the
wall clock (`Clock`), and the backend leave (`LeaveNotifier`) — a lambda in any of those places is an
adapter written in the composition root (spec `module-architecture`, "Ports are the I/O boundary
named for the need"). Given those ports, `snapSyncApp` composes the feature graph: the **ledger-backed**
`SyncStatusSource` (built from a `LedgerCountsSource`, the permission source, and the gallery
source — see `sync-status`), the **foreground-gated ledger-counts poll** (`LedgerCountsPoller`,
started/stopped by the Foreground/Background flows — see `sync-status`), the attestation,
upload-arm, join/leave/create use-cases, the download
controller and jobs, the album coordinator, the `flow/` trigger instances (Foreground · Background
· SilentPush · DownloadBackstop · Provision), and the **user-tap command bundle**
(`model/`'s `UserCommands`: leave · create · commitJoin · share · requestAccess · openSettings —
seated in `model/` since migration step 9, because the armed presentation gate forbids
`:ui:presentation` naming `flow/`; live instances are still built and decorated only in
`compose/`), which the root injects into the `StatusContainerHost` — presentation fires commands
only through the bundle and references no feature command, port, or flow callable directly. The
root passes the PhotoKit permission adapter's **permission StateFlow** and the file-backed config
adapter's **config StateFlow** into the host (since step 9 the host's read-model inputs are bare
StateFlows — presentation names no `ports/` type), supplies the same permission adapter as
`AppPorts.photoAccessRequester` (the port the bundle's
`requestAccess`/`openSettings`/`choosePhotos` commands are bound to in `compose/`), and passes the file-backed config adapter (the App-Group config store of
record — capability `event-link`)
as both the `ConfigSource` and
`ConfigStore` in `AppPorts`. The root SHALL bind the `Clock`/`TimeZoneSource` ports' system
adapters (`:adapter:generic:app`'s `SystemClock`/`SystemTimeZone`) into the **one shared, pure**
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

The root SHALL observe the app's foreground/background lifecycle **from Kotlin**: a plain
`onLaunch()` entry — called by the Swift `AppDelegate` from `didFinishLaunchingWithOptions`, a
statement with no decision — installs process-lifetime `NSNotificationCenter` observers for
`UIApplicationDidBecomeActiveNotification` (→ `onForeground`) and
`UIApplicationWillResignActiveNotification` (→ `onBackground`), replacing the SwiftUI
`scenePhase` split (a Swift decision the transcriber law forbids). The foreground entry drives the
Foreground flow (which re-reads the membership, refreshes status, and **starts** the
foreground-gated poll); the background entry drives the Background flow (which **stops** the poll
and arms the backstop). A background launch installs the observers and simply never receives
`didBecomeActive`. The scope SHALL outlive Compose
recomposition so the source collector and container are not torn down with the view.
`MainViewController` SHALL render `host.container.stateFlow` and route the gate intents to
`host.onRequestPermission` / `host.onOpenSettings`, the leave action to `host.onLeaveEvent`, and
the share action to `host.onShareInvite`; it SHALL collect the container's invite URL
(`host.inviteUrl`) and pass it to `StatusScreen`, together with the root's shared
`CutoffFormatter` (the screen carries no system-reading default). `SnapSyncRoot` SHALL expose
`onUserActivity(NSUserActivity)` — the scene delegate forwards every delivered activity **whole**,
and the tested `model/` filter-and-dispatch (`forwardEventLink`) keeps only a browsing-web
activity with a URL and routes its complete `absoluteString` to `onOpenUrl(String)`, which
reaches the container's `onOpenUrl` intent (through the live delegate).

#### Scenario: The root assembles the real stack

- **WHEN** the iOS app starts
- **THEN** a single `SnapSyncRoot` resolves the composition mode once, constructs the platform
  adapters and calls `snapSyncApp`, which composes the ledger-backed `SyncStatusSource` (with the
  read-only `LedgerCountsSource`), the flows, and the user-tap command bundle over the PhotoKit
  permission adapter and the file-backed config store; the root wires the result into one
  `StatusContainerHost` — constructing no `LedgerWriter` on the OS-driven tier and no
  `EventStatusSource`, and issuing no storage LIST for upload status

#### Scenario: The foreground poll keeps status live while foreground

- **WHEN** the app is foregrounded and the extension records ledger changes in its own process
- **THEN** the foreground-gated poll re-reads the ledger counts within its cadence and a fresh
  status emission follows, with no network read

#### Scenario: The poll is foreground-only

- **WHEN** the app moves to the background
- **THEN** the Background flow stops the poll, and the next foreground entry (which itself also
  refreshes status) starts it again

#### Scenario: Lifecycle transitions are observed from Kotlin

- **WHEN** the app becomes active, or leaves the active state (including a transient interruption
  such as the app switcher or an incoming call)
- **THEN** the Kotlin-installed `NSNotificationCenter` observers drive `onForeground`,
  respectively `onBackground`, with no scene-phase decision in Swift

#### Scenario: Permission action flows through the container

- **WHEN** the user activates the gate's "Allow access" or "Open Settings"
- **THEN** `MainViewController` invokes the container intent, which fires the bundle's
  `requestAccess`/`openSettings` command, whose compose-built body calls the
  `PhotoAccessRequester` port — the UI never calls PhotoKit directly and names no port

#### Scenario: An event link flows through the container

- **WHEN** `SnapSyncRoot.onUserActivity` receives a browsing-web activity carrying a
  `https://<link domain>/join#…` event link
- **THEN** the tested filter routes the complete URL to `onOpenUrl`, which forwards (through the
  live delegate) to the container's `onOpenUrl` intent, which decodes and (on success) saves via
  the `ConfigStore` (the file-backed config store, which also writes through to its Keychain
  copy), updating the `ConfigSource`

#### Scenario: The leave action flows through the command bundle into the use-case

- **WHEN** the user confirms the leave action in the joined layer
- **THEN** `MainViewController` invokes `host.onLeaveEvent`, which fires the bundle's `leave`
  command — cancelling in-flight downloads, then running the composed `LeaveEvent` (stopping the
  producer via the tier-neutral arm and clearing the persisted config — the App-Group file; no
  ledger or `EventStatus`
  operation) — and the screen returns to the setup gate

#### Scenario: The share action flows through the command bundle into the platform share

- **WHEN** the user activates the share action in the joined layer
- **THEN** `MainViewController` invokes `host.onShareInvite`, which fires the bundle's `share`
  command with the invite link, and the `SharePresenter` port the root supplied —
  `:adapter:ios:app-only`'s `IosShareSheet`, whose presenter walk is adapter technology mechanics —
  presents a `UIActivityViewController` carrying that link; the UI never constructs UIKit directly
  and observes no result

#### Scenario: The picker reaches the platform through the permission port

- **WHEN** the user activates "Choose more photos" under a partial grant
- **THEN** the bundle's `choosePhotos` command calls `PhotoAccessRequester.choosePhotos()` on the
  main lane, and the resulting selection arrives only through the selection-change seam — the root
  supplies no separate picker lambda

#### Scenario: A cold background wake installs no grant subscription

- **WHEN** the process is launched in the background by the download backstop or a
  background-`URLSession` relaunch, without the host-assembly path running
- **THEN** touching the composed graph installs no permission-grant collector, and no upload
  producer starts off the permission StateFlow's replayed `GRANTED` value

### Requirement: On-disk native ledger on iOS

The `:adapter:ios:ext-safe` module SHALL provide an `iosLedgerStore()` factory (iOS-only source) that constructs the shared `SqlDelightLedgerStore` (`:adapter:generic:app`) over a `NativeSqliteDriver`, persisting the ledger database **on disk in the `group.app.snapsync` App-Group container** so its contents survive process death and are shared between the app and the background-upload extension. (Before migration step 4 the factory and store lived in `:domain:engine`.) This factory SHALL be the single site that names the database location, SHALL open the database in WAL mode (one cross-process writer plus concurrent readers), and SHALL wire the backend's cross-process change notification (post-on-`put` / observe-in-`changes`, per `sync-ledger`). The same factory SHALL serve both processes; on the OS-driven tier the app process constructs no `LedgerWriter` — it holds the ledger only as a `LedgerStore` for its read-only aggregates read and the reset-family operations (per `sync-ledger`).

#### Scenario: The ledger persists across launches
- **WHEN** the app writes ledger state, terminates, and relaunches
- **THEN** `iosLedgerStore()` opens the same on-disk database and the prior state is present

#### Scenario: The ledger lives in the App-Group container
- **WHEN** the extension writes the ledger and the app later reads it
- **THEN** both open the same database file in the `group.app.snapsync` container, and the app's read reflects the extension's write

#### Scenario: Native backend honors the ledger contract
- **WHEN** the native-driver-backed `SqlDelightLedgerStore` is exercised against the ledger backend contract
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
seam (`:domain` `feature/push` over the `ports/` token source) for registration with the backend. A registration failure
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

The `AppDelegate` SHALL forward an incoming remote notification's `userInfo` dictionary **whole**
to `SnapSyncRoot.onSilentPush(userInfo:completion:)`, performing no field extraction, parsing, or
decision in Swift (the transcriber law — the `eventId` extraction is the tested `model/` payload
codec, applied inside the `flow/SilentPush` trigger), and SHALL pass a completion that signals the
OS fetch completion handler. Kotlin SHALL always release the completion — including for a payload
with no usable `eventId`, which fans out to no arm (an unanswered `content-available` push costs
the app its future background wakes).

Kotlin SHALL release that completion only after the fan-out has finished or the silent-push deadline
has expired. The flow's work is not "the synchronous portion": the receivers it drives are the fetch,
enqueue and import that the push exists to cause, and releasing before they run leaves them to race a
suspension.

#### Scenario: An incoming push is routed to Kotlin whole

- **WHEN** the app receives a silent remote notification
- **THEN** the `AppDelegate` forwards the complete `userInfo` and a completion to Kotlin, with no
  parsing or decision in Swift

#### Scenario: The handler is released after the fan-out, not before it

- **WHEN** a silent push fans out to the download and upload receivers
- **THEN** the OS completion handler is released after both receivers return or the deadline expires,
  and the logged duration for the entry point covers that work

#### Scenario: A malformed payload still releases the handler

- **WHEN** a silent push arrives whose payload carries no usable `eventId`
- **THEN** no receiver runs, the miss is logged, and the OS completion handler is still called

### Requirement: Background entry points record protected-data state

Every background entry point of both processes SHALL log, to the device diagnostic log (capability
`diagnostic-logging`), the protected-data state it observed. The entry points are the app's download
import-tail backstop, its silent-push handler, its background-`URLSession` handler, and the extension's
`process()`.

The **app** SHALL log protected-data availability directly (it can ask `UIApplication`). The
**extension** cannot: `UIApplication` is unavailable to app extensions and the platform offers no
equivalent, so it SHALL instead log the status returned by each protected read it performed — every
Keychain read and, since migration step 11a, the config-file read — the only
observable proxy available to it, and the one that distinguishes *unreadable* from *absent*.

An end-to-end background wake on a **locked** device cannot be exercised by any test: the simulator has
no lock state, and a background task's scheduling is owned by the operating system and cannot be forced.
These diagnostics are therefore the only means of confirming, from a real device, that background work
reached its protected state — and of diagnosing it when it does not.

#### Scenario: A locked background wake is observable after the fact
- **WHEN** background work runs on a locked device and the device log is subsequently pulled
- **THEN** the log states, for that invocation, whether protected data was available (in the app) or what
  status each protected read (Keychain or config file) returned (in the extension)

#### Scenario: A failed protected read is attributable to its trigger
- **WHEN** a protected read (a Keychain item or the config file) fails during background work
- **THEN** the logged line carries the entry-point prefix of the trigger that started it, so the failure
  is traceable to the backstop, the silent push, the URL-session handler, or the extension cycle

### Requirement: Background triggers re-read the membership and fail cleanly before first unlock

Every OS-callback trigger flow acting on the persisted membership SHALL **re-read** it (the
foreground, silent-push, and download-backstop flows) into the config StateFlow before acting
(`AppPorts.reloadConfig`, bound to the config adapter's `reload()`): cross-process writes and a
pre-first-unlock construction never notify this process's StateFlow, and the receivers' guards
read it. The reload SHALL retain the last good value on an **unreadable** read (the pure
`configAfterReload` rule) — at trigger cadence a transient read failure must not clear a good
membership and flip the screen to the setup gate — and SHALL replace it on a conclusive read
(joined or definitively absent).

A background wake landing **before the first unlock since boot** SHALL run through and fail
cleanly rather than deferring: every protected read distinguishes *unreadable* from *absent*
(capability `event-link`, `device-identity`), so the wake SHALL NOT mint a device id, SHALL NOT
write any Keychain item, and SHALL NOT clear or reset any persisted state; its work converges at
the next trigger. (The prior defer-and-resume gate is deleted — settled proof ④: zero deferrals
ever observed in production.)

#### Scenario: A trigger repairs a stale config StateFlow before acting

- **WHEN** another process has re-provisioned (or the process was constructed before first unlock)
  and a silent push, backstop, or foreground entry fires
- **THEN** the flow re-reads the persisted config first, so the receivers' active-event guards see
  the current membership

#### Scenario: A transient unreadable reload does not clear a good membership

- **WHEN** a trigger-time reload's read reports unreadable while the StateFlow holds a joined
  config
- **THEN** the StateFlow retains the joined config and the screen does not regress to the setup
  gate

#### Scenario: A pre-first-unlock wake fails cleanly and converges

- **WHEN** the app is woken in the background (a `BGProcessingTask`, a silent push, or a background
  `URLSession` completion) while protected data is unavailable
- **THEN** no Keychain write or mint occurs, no persisted state is cleared, the process does not
  terminate, and the work is performed at the next trigger after unlock

#### Scenario: Work proceeds normally once protected data is available

- **WHEN** the app is woken in the background while the device is locked but has been unlocked at
  least once since boot
- **THEN** protected data is available, the device id and config are read, and the work proceeds

### Requirement: Developer launch-environment CREATE trigger

The iOS app SHALL read a `SNAPSYNC_CREATE_EVENT` variable from the process environment **once per
process launch**. When present, its value SHALL be a `base64url(JSON)` payload decoded by a dedicated,
**strict** `model/` codec (rejecting unknown keys, tested in `commonTest` so it runs on both JVM and
`iosSimulatorArm64`) carrying a **required** `name` and the optional keys `startsAt` (a canonical
`…Z` UTC string; default **now**), `endsAt` (a canonical `…Z` UTC string; when absent, the create falls
back exactly as today — the backend stamps the legacy `startsAt + 30d`, capability `event-creation`),
`autoJoin` (default `false`), `minPhotoDate`, `direction`, and `saveToAlbum`. A payload that is absent,
not valid `base64url(JSON)`, missing `name`, or carrying an unknown key SHALL produce **no** side effect.

When the payload is valid the app SHALL mint the event through the **existing attest-gated
`POST /events`** path (the same event-creation client the interactive create uses; it SHALL introduce no
second create path), passing `endsAt` through to that request the same way `startsAt` is passed (an absent
`endsAt` sends none, so the backend applies its fallback), and SHALL ensure an attestation token is fresh
**before** that request so a cold-launch create is not lost to a not-yet-ready token. Then:

- **without `autoJoin`** — the app SHALL mint the event and join **nothing**, emitting the line
  `created eventId=<uuid>` to the device log (`debug.log`) as the headless oracle for the minted id;
- **with `autoJoin`** — the app SHALL forward a **synthesized** `autoJoin` event link (carrying the
  minted `eventId` plus any supplied `minPhotoDate`/`direction`/`saveToAlbum`) through the existing
  `SnapSyncRoot.onOpenUrl(_:)` / join-gate `autoConfirm` path **verbatim**, landing a membership exactly
  as a confirmed scan would. The chosen `minPhotoDate` SHALL be clamped by the join floor
  (`max(chosen, startsAt)`, capability `photo-selection-policy`) like every other join path — the
  trigger grants it no floor exemption.

The trigger SHALL be **non-idempotent**, and this is its honest contract rather than a defect: because
the backend mints a fresh UUID on every `POST /events`, each cold launch with the variable still set
SHALL mint a **new** event (an `autoJoin` re-launch therefore mints a new event and, being a different
id, leaves any current event first). Operators are expected to **unset** the variable after the mint —
the opposite of the `SNAPSYNC_EVENT_LINK` per-build loop, whose re-application is idempotent.

The trigger SHALL be applied **at most once per process** (not re-applied on Compose view or
view-controller recreation). It SHALL rely on the fact that a process-environment variable is only
injectable via a developer launch; launches from SpringBoard or TestFlight carry no such variable, so
the trigger is inert in production **with no compile-time guard**.

#### Scenario: Mint-only cold launch logs the id and joins nothing
- **WHEN** the app is cold-launched with `SNAPSYNC_CREATE_EVENT` set to a valid `base64url(JSON)`
  payload carrying a `name` and **no** `autoJoin`
- **THEN** the app mints the event via `POST /events`, emits `created eventId=<uuid>` to `debug.log`,
  and provisions **no** membership (config stays as it was)

#### Scenario: A supplied endsAt is passed through to the mint
- **WHEN** the app is cold-launched with `SNAPSYNC_CREATE_EVENT` carrying a `name` and an `endsAt`
  (a canonical `…Z` string)
- **THEN** the `POST /events` request carries that `endsAt`, so the minted event's window ends at the
  supplied instant rather than the backend fallback

#### Scenario: An absent endsAt falls back as today
- **WHEN** the app is cold-launched with `SNAPSYNC_CREATE_EVENT` carrying a `name` and **no** `endsAt`
- **THEN** the `POST /events` request sends no `endsAt` and the backend stamps its legacy fallback —
  behavior identical to before this change

#### Scenario: autoJoin cold launch creates and joins in one launch
- **WHEN** the app is cold-launched with `SNAPSYNC_CREATE_EVENT` carrying `autoJoin = true` (optionally
  with `minPhotoDate`/`direction`/`saveToAlbum`)
- **THEN** the app mints the event and, forwarding a synthesized `autoJoin` link through the existing
  `onOpenUrl`/`autoConfirm` path, enrolls and provisions that membership with the chosen cutoff clamped
  to the join floor — no user interaction

#### Scenario: A subsequent cold launch mints a second event
- **WHEN** the app is cold-launched again in a fresh process with `SNAPSYNC_CREATE_EVENT` still set
- **THEN** a **new** event is minted (a fresh `eventId`), reflecting the non-idempotent contract — the
  previous event is not reused, and under `autoJoin` any current membership is left before joining the
  new one

#### Scenario: Attestation is made fresh before the create request
- **WHEN** the app is cold-launched with `SNAPSYNC_CREATE_EVENT` on a device whose attestation token is
  stale or absent
- **THEN** the app obtains a fresh attestation token before issuing `POST /events`, so the create is
  not silently lost to an attestation rejection

#### Scenario: Production launch is inert
- **WHEN** the app is launched from SpringBoard or via TestFlight with no `SNAPSYNC_CREATE_EVENT` in its
  environment
- **THEN** no event is minted and behavior is identical to the app without this feature, with no
  compile-time flag distinguishing the build

#### Scenario: Invalid payload is rejected
- **WHEN** the app is cold-launched with `SNAPSYNC_CREATE_EVENT` set to a value that is not valid
  `base64url(JSON)`, is missing `name`, or carries an unknown key
- **THEN** the strict codec rejects it and no event is minted and no membership side effect occurs

### Requirement: Developer launch-environment LEAVE trigger

The iOS app SHALL read a `SNAPSYNC_LEAVE` variable from the process environment **once per process
launch**. Its **presence** (any value) SHALL trigger leaving the current membership through the existing
leave use-case (capability `leave-event`): cancel in-flight downloads, stop the upload producer, clear
the persisted config, and notify the backend best-effort. When the device is **not** currently joined,
the trigger SHALL be a **no-op**. A failed backend notification SHALL NOT block clearing the local
config (leaving is best-effort by the leave use-case's own contract).

The trigger SHALL be applied **at most once per process** and SHALL rely on the developer-launch-only
injectability of a process-environment variable, so it is inert in production **with no compile-time
guard**.

#### Scenario: Present variable leaves the current event
- **WHEN** the app is cold-launched with `SNAPSYNC_LEAVE` present while joined to an event
- **THEN** the app leaves that membership (downloads cancelled, producer stopped, config cleared,
  backend notified best-effort) and returns to the unjoined resting state

#### Scenario: Present variable while unjoined is a no-op
- **WHEN** the app is cold-launched with `SNAPSYNC_LEAVE` present while **not** joined to any event
- **THEN** no side effect occurs

#### Scenario: Production launch is inert
- **WHEN** the app is launched from SpringBoard or via TestFlight with no `SNAPSYNC_LEAVE` in its
  environment
- **THEN** no leave side effect occurs and behavior is identical to the app without this feature, with
  no compile-time flag distinguishing the build

### Requirement: Developer launch-environment RESET-STATE trigger

The iOS app SHALL read a `SNAPSYNC_RESET_STATE` variable from the process environment **once per
process launch**. Its **presence** (any value) SHALL void this device's durable sync state, so that a
build pointed at a **different backend** starts from nothing rather than from state describing bytes
that backend does not hold.

The trigger SHALL clear **all four** of the following, because clearing fewer leaves the device
silently inert:

- the **upload ledger**, in full — its key is the bare filename and therefore event-independent, so a
  `COMPLETED` row suppresses re-upload regardless of which backend received those bytes;
- the **discovery cursor** (the persisted photo-library change token) — with the cursor retained, the
  next cycle observes no changes and enumerates nothing, so a ledger clear alone still uploads
  nothing; clearing it restores full re-enumeration;
- the **persisted membership config**, **locally only** — the trigger SHALL NOT notify any backend,
  because the event belongs to the backend the device is leaving behind and the newly baked backend
  never knew this device;
- every **non-terminal** download row.

The trigger SHALL **retain** download rows in the terminal imported state. Their recorded local asset
identifier is the suppression handle the upload path reads to avoid re-uploading a downloaded asset;
discarding it would make the device re-upload photos it imported.

The trigger SHALL NOT clear the device's attestation credential: a rejected token is already dropped
and re-minted on a `401` (capability `device-attestation`), so crossing backends heals it without
operator action.

The trigger SHALL be applied **at most once per process** and SHALL rely on the
developer-launch-only injectability of a process-environment variable, so it is inert in production
**with no compile-time guard**.

Decision record: `changes/archive/2026-07-23-add-local-backend-rig`.

#### Scenario: Present variable voids durable sync state
- **WHEN** the app is cold-launched with `SNAPSYNC_RESET_STATE` present on a device holding upload
  ledger rows, a discovery cursor, a membership config, and pending download rows
- **THEN** the ledger is emptied, the discovery cursor is cleared, the membership config is cleared
  with no backend notification, and non-terminal download rows are dropped — leaving the device in the
  unjoined resting state

#### Scenario: Imported downloads survive the reset
- **WHEN** the app is cold-launched with `SNAPSYNC_RESET_STATE` present on a device that has imported
  foreign photos
- **THEN** those imported rows and their recorded local asset identifiers are retained, so the upload
  path still suppresses them and no downloaded photo is re-uploaded

#### Scenario: Reset restores enumeration against a new backend
- **WHEN** a device whose library was already fully uploaded is relaunched with
  `SNAPSYNC_RESET_STATE` present and a newly baked backend host
- **THEN** the next upload cycle re-enumerates the library from scratch and uploads its in-scope
  photos to the new backend, rather than treating them as already complete

#### Scenario: Reset while holding nothing is a no-op
- **WHEN** the app is cold-launched with `SNAPSYNC_RESET_STATE` present on a device with no ledger
  rows, no cursor, no config, and no downloads
- **THEN** no side effect occurs

#### Scenario: Production launch is inert
- **WHEN** the app is launched from SpringBoard or via TestFlight with no `SNAPSYNC_RESET_STATE` in
  its environment
- **THEN** no reset side effect occurs and behavior is identical to the app without this feature, with
  no compile-time flag distinguishing the build

### Requirement: Ordered application of membership-mutating launch triggers

The app SHALL apply the membership-mutating launch triggers (`SNAPSYNC_RESET_STATE`, `SNAPSYNC_LEAVE`,
`SNAPSYNC_CREATE_EVENT`, `SNAPSYNC_EVENT_LINK`) in the **fixed order**
`reset → leave → create → event-link`, **sequentially** — each awaited to completion before the next —
so combinations set in the same launch are well-defined and each later step observes the state the
earlier ones produced (e.g. a `SNAPSYNC_LEAVE` clears the config **before** a `SNAPSYNC_CREATE_EVENT`
mints and the create/join steps read the post-leave membership). Each trigger remains independent: a
launch MAY set any subset, and an absent trigger contributes nothing to the order.

`SNAPSYNC_RESET_STATE` SHALL run **first** so that one launch can void a foreign backend's state and
immediately mint or join against the newly baked backend. Because the reset leaves the device
unjoined, a `SNAPSYNC_LEAVE` set in the same launch is thereby a no-op rather than a backend
notification aimed at the wrong backend.

A **forge** launch (`SNAPSYNC_FORGE_STATE` naming a recognized state) SHALL ignore **all four**
membership-mutating triggers — it resets nothing, mints nothing, leaves nothing, and provisions
nothing. This inertness SHALL be **structural** (the shell's single mode switch selects a forge
delegate holding no reference to the live stack), consistent with the forge-state trigger's existing
precedence over `SNAPSYNC_EVENT_LINK`.

#### Scenario: Leave and create apply in order
- **WHEN** the app is cold-launched with both `SNAPSYNC_LEAVE` present and `SNAPSYNC_CREATE_EVENT` set,
  while joined to an event
- **THEN** the app first leaves the current membership, then mints the new event — the create step
  observes the cleared config (so an `autoJoin` create joins fresh rather than treating it as a switch)

#### Scenario: Reset precedes create in one launch
- **WHEN** the app is cold-launched with both `SNAPSYNC_RESET_STATE` present and
  `SNAPSYNC_CREATE_EVENT` set, on a device carrying state from a different backend
- **THEN** the durable sync state is voided first and the create step then mints and joins against the
  newly baked backend from a clean slate

#### Scenario: Forge wins over create and leave
- **WHEN** the app is cold-launched with a recognized `SNAPSYNC_FORGE_STATE` and any of
  `SNAPSYNC_RESET_STATE` / `SNAPSYNC_CREATE_EVENT` / `SNAPSYNC_LEAVE` in its environment
- **THEN** the forged frame renders, the membership triggers are ignored, nothing is reset, nothing is
  minted, nothing is left, and nothing is provisioned — the forge delegate has no route to the live stack

### Requirement: Developer launch-environment LOG-EXPORT trigger

The iOS app SHALL read a `SNAPSYNC_EXPORT_LOGS` variable from the process environment **once per
process launch**. Its **presence** (any value) SHALL copy the upload extension's log — `ext-debug.log`
in the shared App Group container, and its rolled `.1` sibling when present — into the app's own
`Documents/`, where `pymobiledevice3 apps pull` can reach it.

The trigger exists because the extension's log lives in the App Group (capability
`diagnostic-logging`, so the app process can read it for a diagnostic dump) and an App Group
container is **not** USB-pullable, while the extension itself can never observe a launch environment
variable — the OS launches it. The app is therefore the only process that can perform the copy.

The copy SHALL happen at boot. It therefore yields the extension's history up to its most recent
invocation, which is the whole of it: the extension is not running while an operator pulls.

The trigger SHALL be **independent of the membership-mutating triggers**: it mutates no membership,
participates in no ordering with `reset → leave → create → event-link`, and SHALL apply on a
`SNAPSYNC_FORGE_STATE` launch as well, since copying a file reaches no live-stack seam.

The trigger SHALL be applied **at most once per process** and SHALL rely on the
developer-launch-only injectability of a process-environment variable, so it is inert in production
**with no compile-time guard**, exactly as its siblings are.

#### Scenario: Present variable exports the extension log
- **WHEN** the app is launched with `SNAPSYNC_EXPORT_LOGS` present on a device whose extension has logged
- **THEN** `ext-debug.log` (and its `.1` sibling when present) is copied into the app's `Documents/`,
  and `pymobiledevice3 apps pull app.snapsync Documents/ext-debug.log` returns it

#### Scenario: Export with no extension log is a no-op
- **WHEN** the app is launched with `SNAPSYNC_EXPORT_LOGS` present on a device where the extension has never run
- **THEN** nothing is copied and no error surfaces

#### Scenario: Export applies on a forge launch
- **WHEN** the app is launched with both `SNAPSYNC_EXPORT_LOGS` and a recognized `SNAPSYNC_FORGE_STATE`
- **THEN** the copy is performed and the forged frame renders, provisioning nothing

#### Scenario: Production launch is inert
- **WHEN** the app is launched from SpringBoard or via TestFlight with no `SNAPSYNC_EXPORT_LOGS` in its environment
- **THEN** no copy occurs and behavior is identical to the app without this feature, with no
  compile-time flag distinguishing the build

### Requirement: OS completion handlers are released only after their work completes

Every OS-supplied completion handler the shell receives SHALL be released only after the work that wake
triggered has completed, or after a per-entry-point deadline has expired, whichever comes first. Those
handlers are the background-`URLSession` handler for **each** session
(`handleEventsForBackgroundURLSession`), each `BGTask`'s `setTaskCompleted`, and the silent-push fetch
handler. Releasing one declares to the system that the app is done and may be suspended; releasing it
while the wake's work is merely *queued* is what freezes the process mid-flight.

The handler SHALL be carried by a type whose only release path takes the work as a `suspend` block, so
that releasing early is not expressible at a call site. That type SHALL live in `:domain` `model/`, not in
`:app:*` — the shell is wiring-only and untested by rule, so behaviour placed there cannot be covered.
The shell SHALL construct it from the raw handler at the Kotlin edge; Swift SHALL continue to forward an
opaque handler and decide nothing.

The deadline SHALL be a per-entry-point constant, and where the OS offers its own expiry signal that
signal SHALL take precedence over the constant. When the deadline expires the handler SHALL be released
and the outstanding work SHALL continue rather than being cancelled, so the deadline can never make the
outcome worse than releasing immediately would have.

#### Scenario: A wake's work completes before the handler is released

- **WHEN** an OS wake triggers work and that work completes within the entry point's deadline
- **THEN** the OS completion handler is released after the work finishes, and the logged duration for
  that entry point reflects the work rather than the dispatch

#### Scenario: A deadline releases the handler without cancelling the work

- **WHEN** the work a wake triggered has not completed when the entry point's deadline expires
- **THEN** the OS completion handler is released, the expiry is logged, and the work continues

#### Scenario: Releasing early is not expressible

- **WHEN** a new OS entry point is added that releases its handler without awaiting its work
- **THEN** the handler type offers no such call, so the shape does not compile

#### Scenario: The OS's own expiry wins over the constant

- **WHEN** a `BGTask` reports expiration before the entry point's constant deadline
- **THEN** the handler is released on the OS signal rather than waiting for the constant

### Requirement: The download background task registers an expiration handler

The download import-tail `BGTask` SHALL register an expiration handler with the OS. Without one the
system has no way to reclaim the task before terminating the app, and holding the task until its work
completes would convert a stalled unit of work into a termination.

#### Scenario: An overrunning backstop is reclaimed, not terminated

- **WHEN** the download backstop's work has not completed when the OS signals expiration
- **THEN** the task is completed, the expiry is logged, and the app is not terminated for overrunning

### Requirement: Background-wake requests carry an explicit request timeout

The shared HTTP client SHALL configure an explicit request timeout rather than relying on the platform
session's defaults. A request left to the platform default is unbounded in practice on a background wake:
the session runs in-process, so a suspended app services nothing, its wall-clock idle timer expires
unobserved, and the task reports only when the app next runs — producing failures reported as minutes or
tens of minutes that are neither network measurements nor honest durations.

The timeout SHALL be short enough to bound the network portion of any receipt-held span. Callers already
treat a failed fetch as "keep last-good state", so a fast failure costs a retry and never correctness.

#### Scenario: A request starved by suspension fails fast on resume

- **WHEN** a background-wake request is interrupted by suspension and the app next runs
- **THEN** the request fails within the configured timeout rather than reporting the whole
  suspension interval

#### Scenario: A union fetch failure keeps last-good state

- **WHEN** the union fetch fails on its timeout during a background wake
- **THEN** last-good download state is retained and no rows are dropped
