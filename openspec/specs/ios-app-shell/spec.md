# ios-app-shell Specification

## Purpose

The iOS application process: a Compose Multiplatform entry point (`ComposeUIViewController` in
`:app:ios`) hosting the shared `StatusScreen`, plus the live composition root that wires the real seams —
the on-disk native ledger, the permission gate, the Keychain-backed event config, remote-notification
registration and silent-push forwarding, and the enable toggle for the background-upload extension.

This capability is the **platform shell only**. `:app:ios` is wiring, not logic: nothing testable lives
here, so these requirements pin the app's structure, entry points, and OS integrations rather than any
behavior — every decision they reach for belongs to a tested `domain`/`capability` module.

It carries **no developer launch-environment triggers**. It once carried several, inert in production only
because a launch env var is injectable solely via a developer launch — a property of how the app is started
rather than of what it contains, and therefore not containment at all. They are gone: the dev/test surface
is now the build-time-only control channel, which a production build does not contain (capability
`module-architecture`), and a guard fails the build if a `SNAPSYNC_*` literal returns to production Kotlin
(capability `architecture-guards`). Forging a screenshot state likewise moved out of this shell into its own
binary target rather than remaining a mode of it.

Decision record: `changes/archive/2026-06-17-ios-first-target`; the retirement of the launch triggers and
the forge target's extraction: `changes/archive/2026-08-24-retire-launch-env-triggers`.
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
hook the platform actually invoked (capability `diagnostic-logging`). That is what MEASURED the iOS 18
gap below: two hooks indistinguishable in the log would have read as "a link arrived once" and settled
nothing.

**This requirement is currently UNMET on iOS 18.7.9, measured, for the warm case.** Bugsink
`SNAPSYNC-25` + `SNAPSYNC-26` (iPhone XS, build 607, one 80-second window): three warm taps each
brought the app to the front — the foreground entry point fired every time, so iOS *did* activate the
app from the link — and none reached `onOpenUrl`; the third was while the device was unjoined, ruling
out join and switch logic. The cold half then delivered first try on a fresh process. The requirement
is **not** weakened to match: the outcome it states is the contract, and a platform that does not meet
it is a defect under investigation, not a contract to rewrite. The evidence and the expiry trigger live
in capability `architecture-guards`.

The scene delegate SHALL **record every callback it receives**, not only those carrying a link — the
connection (including one carrying no activity at all), a continuation UIKit announces before
attempting it, and the scene lifecycle. Without this, a delivery that fails is indistinguishable from a
link the platform never routed to the app, and those two have different causes and different fixes.
That is not a hypothetical: separating them is exactly what the dumps above could not do, and it cost
the investigation a device session. These recorders SHALL decide nothing and route nothing — a
diagnostic that changes behavior is no longer a diagnostic.

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
upload mechanisms this OS can carry). Every platform touch the root once supplied as an inline lambda
SHALL be a
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
the host and the screen. The composed graph SHALL construct the iOS
`LedgerCountsSource` as a **read-only** reader of the shared App-Group ledger — supplying a
`suspend () -> LedgerCounts` that calls only `iosLedgerStore().aggregates()` (never a write) — and
SHALL issue **no** storage LIST for upload status. While the **OS-driven mechanism** is the resolved
one the composed graph SHALL construct **no `LedgerWriter`** (the ledger read is read-only; the
extension is the sole writer) and **no `EventStatusSource`** (the ledger is private to the extension,
which also owns reconciliation — see `event-rejoin-reconciliation`). Constructing the app-driven
mechanism is what brings a writer into this process, so the single-writer invariant (`sync-ledger`)
holds by which mechanism is resolved, not by which OS this is.

The root SHALL **supply the inputs to mechanism resolution and select no mechanism itself.** It
supplies the mechanisms this OS can carry, the plain fact of whether the OS carries the OS-driven one,
and the source of any development override; which mechanism runs is `upload-lifecycle`'s
("The upload mechanism is resolved, never selected"), re-read at every transition, and this spec SHALL
NOT restate that rule. The root SHALL construct the OS-driven mechanism **only** where its
registration selector exists, so a lower system cannot reach a trapping call. Every OS entry
point (`onForeground` / `onBackground` / `onOpenUrl` / `onPushToken` / `onSilentPush` /
`runUploadHeartbeat` / `runDownloadBackstop` / `handleBackgroundUrlSession`) SHALL be a thin
delegator to a single live shell delegate, re-checking no tier and re-resolving nothing.

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
- **THEN** a single `SnapSyncRoot` constructs the platform
  adapters and calls `snapSyncApp`, which composes the ledger-backed `SyncStatusSource` (with the
  read-only `LedgerCountsSource`), the flows, and the user-tap command bundle over the PhotoKit
  permission adapter and the file-backed config store; the root wires the result into one
  `StatusContainerHost` — constructing no `LedgerWriter` while the OS-driven mechanism is resolved,
  no `EventStatusSource`, and issuing no storage LIST for upload status

#### Scenario: The root supplies resolution's inputs and selects no mechanism

- **WHEN** the root assembles the graph on a device whose OS carries the OS-driven mechanism
- **THEN** it supplies both mechanisms, the OS-presence fact and the override source as inputs, and
  selects none of them itself — the resolver owned by `upload-lifecycle` decides, at every transition

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
  the `ConfigStore` (the file-backed config store, whose App-Group file is its only storage),
  updating the `ConfigSource`

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

### Requirement: Remote-notification capability declaration

The iOS app SHALL declare the push capability required to receive silent remote notifications: the
`aps-environment` entitlement in `iosApp.entitlements` (`development` for dev/sideloaded builds,
`production` for TestFlight/App Store, driven by the build configuration) and the `remote-notification`
value in `UIBackgroundModes` in `Info.plist` (so a `content-available` push can wake the app in the
background). The APNs environment the device registers (`sandbox` | `production`) SHALL be a
compile-time value read from the bundled `Deployment.plist` (`apnsEnv`, capability
`deployment-configuration`), consistent with the `aps-environment` the entitlement declares — both
derived from the one build-channel discriminator, so they cannot disagree. The read SHALL go through the
single adapter-side reader in `:adapter:ios:ext-safe`, never an inline bundle read in `:app:ios`, because
the absent-key default is a decision and the shell is gated to hold none.

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

### Requirement: OS completion handlers are released only after their work completes

Every OS-supplied completion handler the shell receives SHALL be released only after the work that wake
triggered has completed, or after a per-entry-point deadline has expired, whichever comes first. Those
handlers are the background-`URLSession` handler for **each** session
(`handleEventsForBackgroundURLSession`), each `BGTask`'s `setTaskCompleted`, and the silent-push fetch
handler. Releasing one declares to the system that the app is done and may be suspended; releasing it
while the wake's work is merely *queued* is what freezes the process mid-flight.

The handler SHALL be carried by a type whose only release path takes the work as a `suspend` block, so
that releasing early is not expressible at a call site. That type SHALL live in `:domain` `ports/`, not in
`:app:*` — the shell is wiring-only and untested by rule, so behaviour placed there cannot be covered.
The shell SHALL construct it from the raw handler at the Kotlin edge; Swift SHALL continue to forward an
opaque handler and decide nothing.

The deadline SHALL be a per-entry-point constant, and where the OS offers its own expiry signal that
signal SHALL take precedence over the constant. When the deadline expires the handler SHALL be released
and the outstanding work SHALL continue rather than being cancelled, so the deadline can never make the
outcome worse than releasing immediately would have.

**The deadline SHALL begin at the handover**, not when whatever the release waits for reports. Where a
handler's release depends on a later signal — a background-`URLSession` wake is handed a handler at
`handleEventsForBackgroundURLSession` and waits for the session to report its events drained — the
interval between the handover and that signal SHALL be inside the bound, because it is exactly the
interval in which the signal may never arrive.

"At the handover" is exact to within one dispatch onto the composition's lane, measured at 5–12 ms against
a 20 s bound. The requirement is not that the clock start on the calling thread — it must not, since that
thread belongs to the OS — but that no *signal-shaped* wait sit outside it.

**Every outstanding handler SHALL be released**, and none SHALL be replaced. Where a second handover for
the same session can arrive before the first release, each handler SHALL be held independently, with its
own deadline running from its own handover, and the drain signal SHALL release every handler outstanding
at that moment. A single stored slot cannot express this: the earlier handler is overwritten and never
called, which costs the app its future background wakes.

**A background-`URLSession` handler SHALL be released on the main thread**, as its owning API requires
(`URLSessionDelegate.urlSessionDidFinishEvents(forBackgroundURLSession:)`: *"Because the provided
completion handler is part of UIKit, you must call it on your main thread."*). This applies to the
release only; where the hold waits is unconstrained. No such requirement is stated for the silent-push
fetch handler or for `BGTask` completion, and none SHALL be extended to them by this rule.

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

#### Scenario: A drain signal that never arrives is still bounded

- **WHEN** the OS hands over a background-`URLSession` handler and the session never reports its events
  drained
- **THEN** the handler is released on the deadline measured from the handover, and the expiry is logged

#### Scenario: A second handover does not orphan the first

- **WHEN** a second `handleEventsForBackgroundURLSession` for the same session arrives before the first
  handler has been released
- **THEN** both handlers are held, and the drain signal releases both — neither is discarded nor released
  early to make room for the other

#### Scenario: The URLSession handler is released on the main thread

- **WHEN** a background-`URLSession` handler is released, whether after its work or on its deadline
- **THEN** the release runs on the main thread, even though the drain signal is delivered on a
  session-owned queue and the work ran off the main thread

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

### Requirement: OS entry points delegate upload triggers to the resolved mechanism

Every OS entry point that drives upload work SHALL delegate to the **resolved upload mechanism**
(`upload-lifecycle`, "The upload mechanism is resolved, never selected") rather than to a tier-dependent
thunk bound at composition — foreground entry, a silent push, the upload heartbeat background task, and
a photo-selection change. The root SHALL NOT bind per-tier upload behaviour, and no
entry point SHALL re-check a tier.

A mechanism is always resolved, so an entry point always has a delegate ("A mechanism is always
resolved"). The entry point SHALL construct the `OsReceipt` for its own OS wake, using the deadline
named for that wake, and SHALL hold it across the delegated call — so the mechanism receives a plain
`suspend` trigger and never holds a raw OS completion handler. This preserves "OS completion handlers
are released only after their work completes" while removing every mechanism's ability to violate it.

Binding upload behaviour per tier in the root is what previously made a forced build unable to reach a
mechanism it had not composed. Delegating to the resolved mechanism removes the root's opportunity to
answer that question at all.

#### Scenario: A background wake reaches the resolved mechanism

- **WHEN** the OS invokes an upload-driving entry point
- **THEN** the entry point holds a receipt for that wake's deadline, delegates to the resolved mechanism,
  and releases the handler when the delegated work completes or the deadline expires

#### Scenario: No entry point re-checks a tier

- **WHEN** the shell's upload-driving entry points are inspected
- **THEN** none of them branches on an upload tier, and none binds a per-tier thunk

