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
the forge target's extraction: `changes/archive/2026-08-24-retire-launch-env-triggers`; the scene-rebuild
rule, the create-don't-reuse contract and the placeholder's backdrop:
`changes/archive/2026-08-26-stop-rebuilding-the-composed-scene`; subscribing for process-metric reports at process start: `changes/archive/2026-09-14-add-os-exit-attribution`.

Decision record for the inbound ports and the shell as their driving adapter: `changes/archive/2026-09-22-shell-as-driving-adapter`.
Decision record for the share command recording whether the sheet was presented:
`changes/archive/2026-09-23-contract-platform-handoffs`.
Decision record for its seam, failure, state and concurrency rules: `changes/archive/2026-09-23-harden-seam-bug-classes`.
Decision record for each wake's own work, the one opportunistic tail, and "time is up" learned only from the operating system: `changes/archive/2026-09-25-own-work-per-wake`.

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

Whether a scene is composed and whether an already-composed scene is **rebuilt** are two different
questions, and the second SHALL be answered from **what the shell has already handed out**, never from
which observer of the activation notification happened to run first. The shell advances a rebuild signal
as it hands each scene out, by a pure, tested rule: handing out a placeholder advances it; handing out a
live scene carries it forward unchanged. The app-level foreground entry and the first evaluation of the
platform view therefore race harmlessly — BOTH orderings SHALL produce a correct result, which is what
makes their order not worth contracting.

That signal SHALL be **monotonic** across the life of a process. The UI framework rebuilds on a CHANGE in
the value, not on its magnitude, so a signal that fell back would rebuild exactly as surely as one that
rose. A rule that answered from the mode most recently handed out satisfies every sentence above and is
still wrong for this reason: once the placeholder is replaced, the most recent mode is the live one, the
answer falls, and the next ordinary foreground rebuilds a scene that did not need rebuilding. Measured on
a simulator (iOS 26, 2026-08-26) as a third scene construction in one process, on the first warm
foreground.

A rebuild SHALL create a new view controller. The shell SHALL NOT hand the platform a controller it has
already installed: the framework's contract is that its factory *creates* the controller and that
teardown *removes* it, so returning an already-installed instance makes one object simultaneously the
thing the incoming host adopts and the thing the outgoing host removes — measured on device to leave the
scene detached and the screen blank until the process restarts.

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
scene has been composed. The delivery hooks SHALL include **both** the scene delegate's callbacks and
SwiftUI's `.onOpenURL` on the `WindowGroup`. Both are declared because neither covers every case —
measured, on the builds named below — and NOT because the mechanism that makes each fire is understood.
It is not: the same modifier was measured failing in an earlier delegate configuration and firing in
this one, which no available explanation accounts for. The contract is therefore the outcome, and the
redundancy is what makes the outcome robust to an explanation nobody has.

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

Delivery SHALL work **whether or not the app is already running**, and the shell SHALL NOT rely on any
single platform hook to achieve that. Neither available hook is sufficient alone, and both statements
are measured, on the configuration and builds named: with a custom scene delegate installed, the scene
delegate's continuation does not fire on iOS 18.7.9 while the app is running (builds 681/683, iPhone
XS; `scene(_:willContinueUserActivityWithType:)` announces and nothing follows, from Notes, WhatsApp
and Safari's smart banner alike), and SwiftUI's `.onOpenURL` fired for only 2 of 4 deliveries on iOS
26.6 (build 687, SE2). The union of the two delivered in every measured configuration. A previous
revision of this paragraph asserted that iOS 18.7.9 does not call `scene(_:continue:)` at all — a claim
about the **platform**, disproved on that same OS build once `.onOpenURL` was restored. Scope such
claims to the build and configuration measured; expiry: re-measure at the next iOS major, and whenever
a delivery hook is added or removed.

The scene delegate SHALL **record every callback it receives**, not only those carrying a link — the
connection (including one carrying no activity at all), a continuation UIKit announces before
attempting it, and the scene lifecycle. Without this, a delivery that fails is indistinguishable from a
link the platform never routed to the app, and those two have different causes and different fixes.
That is not a hypothetical: separating them is exactly what the dumps above could not do, and it cost
the investigation a device session. These recorders SHALL decide nothing and route nothing — a
diagnostic that changes behavior is no longer a diagnostic.

Delivery SHALL be **exactly once** per opened link — and that SHALL be enforced by the app, in tested
code, rather than assumed of the platform (capability `event-link`). A link that provisions twice is a
bug, and the platform demonstrably delivers the same link more than once: measured on build 687, the
same URL arrived twice on an iOS 18.7.9 cold launch (~130 ms apart) and twice on iOS 26.6 both while
running (8 ms) and cold (105 ms). Because the app deduplicates, **more than one delivery hook MAY be
live**, and that redundancy is the availability strategy rather than a hazard: no hook is reliable on
every OS, and a hook that fires twice costs nothing once the second delivery is a no-op. This inverts a
previous revision, which forbade "stacking redundant delivery hooks" — correct while delivery was not
idempotent, and obsolete now that it is. What a future reader SHALL still not do is add a hook and
*assume* it composes: each one forwards under its own entry-point name so a dump can count deliveries,
and the count is what proves this requirement.

The mechanism by which the shell re-asks for the root view controller at activation is an
implementation decision, not part of this contract — but it SHALL key on a signal that fires however the
app is opened, **including a headless developer launch** that foregrounds the process without connecting
a scene session. A scene-level callback does not satisfy that (measured 2026-08-06: a `dvt launch` app
never received `sceneDidBecomeActive` and showed a black screen), and losing the headless path would cost
this project the only way an agent can see the app at all.

The deferred placeholder SHALL paint the platform's own system background colour rather than being left
untinted. A launch screen with no configured content, an untinted placeholder and a detached scene are
otherwise the same white, which is indistinguishable to a reporter and to whoever reads their dump; it
also flashes white on every cold launch in dark mode. This SHALL NOT be read as a promise that the three
are visually distinguishable in every appearance — in light mode the system colour IS white, and the log
line below is what separates them.

The **resolved rebuild signal** SHALL be recorded in the device log alongside the resolved mode, under the
same platform-invocation logging (capability `diagnostic-logging`). Recording only that the activation
entry point ran is not sufficient: what distinguishes a healthy process from one carrying a stale rebuild
signal is the VALUE it answered, and without it the distinction can only be inferred from the absence of a
deferred-mode line elsewhere in the log.

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
  notification; their order is not contracted BECAUSE the rebuild rule above makes both orderings
  correct, not because the consequences were left open)

#### Scenario: A later activation does not rebuild the scene
- **WHEN** an app whose scene is already composed is backgrounded and brought forward again, repeatedly
- **THEN** the rebuild signal reads the same value at every one of those activations, so the platform is
  not asked for the root view controller again, the same scene is presented, and screen-local state such
  as an open settings surface or a half-typed report survives

#### Scenario: A process that composed its scene before the first activation is not rebuilt
- **WHEN** the activation notification is observed before the platform view is first evaluated, so the
  very first resolution is the live mode and no placeholder is ever installed
- **THEN** that activation and every later one request no rebuild, and the app remains rendered — the
  shape that previously left a live scene installed against a stale rebuild signal and blanked the
  screen on the next ordinary foreground (Bugsink SNAPSYNC-15, SNAPSYNC-24)

#### Scenario: A rebuild never reuses an installed controller
- **WHEN** the shell is asked for the root view controller a second time in one process
- **THEN** it returns a newly created controller rather than one it has already handed out, so the
  outgoing host's teardown cannot detach the controller the incoming host just adopted

#### Scenario: The placeholder is not bare white
- **WHEN** a process composes the deferred placeholder
- **THEN** the placeholder paints the platform system background colour, so a dark-appearance device
  shows no white flash on the way to the live scene

#### Scenario: The log names the rebuild signal, not just the entry point
- **WHEN** the app becomes active
- **THEN** the device log records the value the activation entry point answered, so a report can be read
  for whether that process was carrying a stale rebuild signal without inferring it from what is missing

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

#### Scenario: A link opened while the app is already running still arrives
- **WHEN** an event link is opened from another app — a messenger, Notes, or a browser's smart banner —
  while the app is running or suspended in memory
- **THEN** the complete URL, fragment included, reaches `SnapSyncRoot.onOpenUrl(_:)`, by whichever
  delivery hook the platform invokes

#### Scenario: The platform delivering twice provisions once
- **WHEN** the platform delivers the same opened link through more than one hook — measured on a cold
  launch as the scene delegate's connection followed ~130 ms later by SwiftUI's `.onOpenURL`
- **THEN** each delivery is logged under its own entry-point name, and the link is acted on exactly
  once (capability `event-link`)

#### Scenario: A delivery hook is present for both machineries
- **WHEN** the Swift shell is inspected
- **THEN** it installs a scene delegate handling the connection and continuation callbacks, AND
  declares `.onOpenURL` on the `WindowGroup`, so neither machinery is relied on alone

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
supplies them as `AppPorts`. The root SHALL supply **no** function-typed field that reads a platform value,
performs a platform effect, or calls back into the composed core; the function-typed fields that remain
are factories for collaborators this OS may or may not carry (the upload mechanisms), each pinned by the
composition seam gate with that reason. Every platform touch the root once
supplied as an inline lambda SHALL be a port instead: the share sheet (`SharePresenter`), the
limited-library picker (`PhotoAccessRequester.choosePhotos`), the download-staging root
(`StagedBytes.stagingRoot`), the wall clock (`Clock`), the backend leave (`LeaveNotifier`), the
trigger-time membership re-read (a refresh on the config port, bound to the file-backed adapter), the
process's background time (the need-named background-time port — "keep this process running, and tell me
when time is up" — bound to `:adapter:ios:app-only`'s `beginBackgroundTask` adapter; spec
`module-architecture`), the device identity
(`DeviceIdentity`, bound to the Keychain identity), and the album-exclusion read (the `AlbumManager` port the
bundle already carries) — a lambda in any of those places is an adapter written in the composition root
(spec `module-architecture`, "Ports are the I/O boundary named for the need"). The build's marketing
version and baked upload host SHALL be supplied as plain values. Seams whose body is core machinery —
the attestation refresh, the push registration, the provision — SHALL NOT be supplied by the root at all:
`snapSyncApp` builds them from the core it composes. Adapter outbound callbacks the root must still wire
because of a construction cycle (the HTTP client's rejection, version-refusal and served hooks) SHALL each
be a single call into a core entry point that `compose/` exposes for that purpose. Given those ports, `snapSyncApp` composes the feature graph: the **ledger-backed**
`SyncStatusSource` (built from a `LedgerCountsSource`, the permission source, and the gallery
source — see `sync-status`), the **foreground-gated status-counts poll** (`StatusCountsPoller`,
started/stopped by the Foreground/Background flows — see `sync-status`), the attestation,
upload-arm, join/leave/create use-cases, the download
controller and jobs, the album coordinator, the process-wide opportunistic **tail runner** (see "Each OS
wake does its own work, then hands the rest to one opportunistic tail"), the `flow/` trigger instances
(Foreground · Background · SilentPush · Provision — the download backstop flow is deleted with its task),
and the **user-tap command bundle**
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
`suspend () -> LedgerCounts` that calls only `iosLedgerStore().assetProgress()` (never a write;
capability `sync-status`) — and
SHALL issue **no** storage LIST for upload status, and SHALL construct **no `EventStatusSource`**. The
app's uploader — and the `LedgerWriter` its cycle holds — SHALL be composed on **every** tier, iOS ≥26.1
under a full grant included, where the extension may hold a `LedgerWriter` at the same moment: both
uploaders are active, and the app's cycle creates whenever its own admission admits (`upload-lifecycle`).
Two processes holding a writer is not a violation: the ledger's invariant is which **code** may perform
which write, each write guarded and one transaction (`sync-ledger`), not how many processes hold a writer.
The app additionally performs the reset family at membership transitions (see "The app resets the upload
ledger at membership transitions on every tier") — the extension holds no reconciliation of its own.
Decision record: `changes/both-uploaders-active`; the tail runner, the background-time port and the deleted
download backstop: `changes/archive/2026-09-25-own-work-per-wake` (D1, D5, D7).

The root SHALL **supply the inputs to the upload transitions and decide no upload behaviour itself.** It
supplies the app's uploader, the OS-driven registration where this OS carries its selector, the plain fact
of whether the OS carries the OS-driven mechanism at all (`osSupportsOsDrivenUpload`), and — in a rig
build only — the source of the per-uploader development switch (the app's creation on/off, the extension's
registration on/off). Whether the extension may be registered (`extensionRegistrable`: the OS carries it
**and** the grant is `GRANTED`), and whether each uploader may create, are `upload-lifecycle`'s, re-read at
every transition, and this spec SHALL NOT restate those rules. The root SHALL construct the OS-driven
registration **only** where its selector exists, so a lower system cannot reach a trapping call. Every OS
entry point — the members of the inbound port (`onForeground` / `onBackground` / `onOpenUrl` /
`onPushToken` / `onSilentPush` / `onBackgroundTask`, with its expiry forwarding / `onBackgroundTransfers`;
spec `module-architecture`, "OS entry points cross an inbound port") — SHALL be reached by Kotlin
delegation to the core's implementation, re-checking no tier and deciding nothing.

The permission-grant subscriptions (the upload permission-change transition; sole-creator album ensure —
see `event-album`) SHALL be installed by an explicit `AppCore.installPermissionSubscriptions()`
(`compose/`) invoked **only from the root's host-assembly path**, which SHALL also run the upload
**launch reconcile** explicitly (`upload-lifecycle`, "Launch reconciles by comparison; only a join forces
the repair"). The upload subscription SHALL NOT treat the permission StateFlow's replayed value as a
transition. A cold background wake (the upload heartbeat, a silent push, or a
background-`URLSession` relaunch) that merely touches the composed graph SHALL NOT install them and SHALL
run no launch reconcile. The host-assembly path SHALL be reached from the **foreground entry only**: no
background entry — a silent push, a background transfer, a background task, a delivered push token — assembles
the host, because everything a background wake's own work and its tail need is built by the composed graph.

The root SHALL observe the app's foreground/background lifecycle **from Kotlin**: a plain
`onLaunch()` entry — called by the Swift `AppDelegate` from `didFinishLaunchingWithOptions`, a
statement with no decision — installs process-lifetime `NSNotificationCenter` observers for
`UIApplicationDidBecomeActiveNotification` (→ `onForeground`) and
`UIApplicationWillResignActiveNotification` (→ `onBackground`), replacing the SwiftUI
`scenePhase` split (a Swift decision the transcriber law forbids). The foreground entry drives the
Foreground flow (which re-reads the membership, refreshes status, and **starts** the
foreground-gated poll); the background entry drives the Background flow (which **stops** the poll;
it arms no download backstop — that task is deleted). A background launch installs the observers and simply never receives
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
  `StatusContainerHost` — constructing no `EventStatusSource`, and issuing no storage LIST for upload
  status

#### Scenario: The root supplies the transitions' inputs and decides nothing

- **WHEN** the root assembles the graph on a device whose OS carries the OS-driven mechanism
- **THEN** it supplies the app's uploader, the registration, the OS-presence fact and (in a rig build) the
  per-uploader switch source as inputs, and decides neither registration nor creation itself — the
  registration fact and the admissions owned by `upload-lifecycle` decide, at every transition

#### Scenario: The app uploads on the OS-driven tier too

- **WHEN** the app runs on iOS ≥26.1 under a full grant with an upload-inclusive membership and the
  extension registered
- **THEN** the app's uploader is composed and armed, and its cycle creates jobs for `DISCOVERED` rows
  alongside the extension's

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
  uploads via the arm's leave transition — deregistering the extension, cancelling the app's transfers,
  stopping its heartbeat — then clearing the upload ledger through the `LedgerStore`'s reset
  family, then clearing the persisted config — the App-Group file; no `LedgerWriter` is constructed
  for it and no `EventStatus` operation runs) — and the screen returns to the setup gate

#### Scenario: The share action flows through the command bundle into the platform share

- **WHEN** the user activates the share action in the joined layer
- **THEN** `MainViewController` invokes `host.onShareInvite`, which fires the bundle's `share`
  command with the invite link, and the `SharePresenter` port the root supplied —
  `:adapter:ios:app-only`'s `IosShareSheet`, whose presenter walk is adapter technology mechanics —
  presents a `UIActivityViewController` carrying that link; the UI never constructs UIKit directly
  and observes no result, while the command records whether the sheet was presented — on the tap's own
  log line, and at a severity that reaches crash reporting when it was not

#### Scenario: The picker reaches the platform through the permission port

- **WHEN** the user activates "Choose more photos" under a partial grant
- **THEN** the bundle's `choosePhotos` command calls `PhotoAccessRequester.choosePhotos()` on the
  main lane, and the resulting selection arrives only through the selection-change seam — the root
  supplies no separate picker lambda

#### Scenario: The root supplies no platform or core-glue lambda

- **WHEN** the root builds `AppPorts`
- **THEN** every platform touch is a port or a plain value, and no field is bound to a call into the
  composed core — the attestation refresh, push registration and provision are built by `snapSyncApp`

#### Scenario: A cold background wake installs no grant subscription

- **WHEN** the process is launched in the background by the upload heartbeat, a silent push or a
  background-`URLSession` relaunch, without the host-assembly path running
- **THEN** touching the composed graph installs no permission-grant collector and runs no launch
  reconcile, so no registration is written and no engine is armed

### Requirement: On-disk native ledger on iOS

The `:adapter:ios:ext-safe` module SHALL provide an `iosLedgerStore()` factory (iOS-only source) that constructs the shared `SqlDelightLedgerStore` (`:adapter:generic:app`) over a `NativeSqliteDriver`, persisting the ledger database **on disk in the `group.app.snapsync` App-Group container** so its contents survive process death and are shared between the app and the background-upload extension. (Before migration step 4 the factory and store lived in `:domain:engine`.) This factory SHALL be the single site that names the database location, SHALL open the database in WAL mode (writes from either process serialized by SQLite, each one guarded and one transaction, plus concurrent readers; cross-process contention is absorbed by the busy timeout and is unmeasured), and SHALL wire the backend's cross-process change notification (post-on-write / observe-in-`changes`, per `sync-ledger`). The same factory SHALL serve both processes, and on **every** tier the app process holds the ledger for its own uploader's `LedgerWriter` as well — on the OS-driven tier alongside the extension's — besides its read-only per-asset progress read (`assetProgress()`, capability `sync-status`), the transport's guarded terminal write, and the reset-family operations it invokes at membership transitions — `clear` at a leave, `resetTo`/`clear` at the join-time load (per `sync-ledger`). Decision record: `changes/both-uploaders-active`.

#### Scenario: The ledger persists across launches
- **WHEN** the app writes ledger state, terminates, and relaunches
- **THEN** `iosLedgerStore()` opens the same on-disk database and the prior state is present

#### Scenario: The ledger lives in the App-Group container
- **WHEN** the extension writes the ledger and the app later reads it
- **THEN** both open the same database file in the `group.app.snapsync` container, and the app's read reflects the extension's write

#### Scenario: Native backend honors the ledger contract
- **WHEN** the native-driver-backed `SqlDelightLedgerStore` is exercised against the ledger backend contract
- **THEN** `get`, the guarded record write, `aggregates` and change signals behave identically to the JVM-driver backend

### Requirement: Enable the background-upload extension on grant

The app SHALL keep the background-upload extension registered on iOS ≥26.1 so the system can invoke it, for
the **whole membership — from the join to the leave** — wherever the OS allows it (`extensionRegistrable`:
the OS carries the selector and photo-library access is full, `.readWrite` → `GRANTED`), for **every**
direction, download-only memberships included (`upload-lifecycle`, "Membership transitions reconcile the
upload mechanisms in one tested place"): forced through the disable→enable toggle at a join (it repairs a
stale record a bare enable fails on with `3202`), and — at a permission change or a launch — registered
through the same toggle only when the OS reports it absent under `GRANTED`. A reconfigure, a re-provision
of the joined event and a permission change SHALL NOT deregister it; only a leave (a switch's leave
included) deregisters it. A download-only membership's registration costs an OS launch whose cycle
declines on the policy. A grant SHALL **not** run any join, listing fetch, enumeration, or seed. The ledger
is loaded at the **join** — by the provision, before the join transition registers the extension
(capability `join-event`; see "The app resets the upload ledger at membership transitions on every tier")
— so the extension's first cycle after the enable already sees it, and the extension holds no
reconciliation of its own. On this tier the app **also** uploads: its uploader is armed whenever photo
access is usable and creates jobs alongside the extension's (`upload-lifecycle`). Registration SHALL be
idempotent-safe to repeat. Decision record: `changes/both-uploaders-active`.

#### Scenario: Granting full access registers the extension directly

- **WHEN** photo-library permission transitions to `GRANTED` with a configured event and the OS
  reports the extension not registered
- **THEN** the app registers it through the disable→enable toggle without fetching, enumerating, or seeding —
  the ledger the extension's next cycle reads is the one the join already loaded

#### Scenario: A download-only membership is registered too

- **WHEN** the device joins with direction `DownloadOnly` on iOS ≥26.1 under a full grant
- **THEN** the extension is registered at the join like any other, and stays registered until the leave

#### Scenario: Narrowing the grant never deregisters

- **WHEN** photo-library permission changes from `GRANTED` to `LIMITED`, `DENIED` or `NOT_DETERMINED`
  with the extension registered
- **THEN** no registration call is made, and the extension's in-flight jobs are not wiped

#### Scenario: The app uploads on this tier too, and seeds only at a join

- **WHEN** the app is running with an upload-inclusive configured event on iOS ≥26.1 under a full grant
- **THEN** its uploader creates jobs for `DISCOVERED` rows through its own cycle's `LedgerWriter`, and its
  only ledger writes outside that cycle and the transport's guarded terminal write are the reset family at
  a join, a switch, or a leave

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

The app SHALL register for remote notifications at **every app entry** — every cold start, whether the
process is launched into the foreground or the background, and every foreground entry
(`UIApplication.registerForRemoteNotifications`), which is how it asks the OS for the current APNs token, and,
when the OS delivers the APNs device token (`didRegisterForRemoteNotificationsWithDeviceToken`), SHALL forward
the token — as the encoded token string plus the compile-time `env` — into the Kotlin push seam (`:domain`
`feature/push` over the `ports/` token source). Whether a delivered token is then written to the backend is
the push feature's decision (capability `push-registration`, "Registration timing — launch, join, and
rotation": only when it differs from the last registration the backend accepted, plus on join and on a fresh
credential), never the shell's. The ask SHALL be a plain statement in the Kotlin root — in its `onLaunch`
entry, at every cold start, and in its `didBecomeActive` observer, at every foreground entry — deciding
nothing; that observer also fires after a brief interruption (Control Center, an incoming call), and the
repeated answer that follows is absorbed by the push feature's comparison. A registration failure
(`didFailToRegisterForRemoteNotificationsWithError`) SHALL be logged and SHALL NOT crash or block the
app. The Swift `AppDelegate` SHALL perform **no** decision logic — it is a pass-through to Kotlin,
consistent with the existing deeplink / background-URL-session hooks. Decision record:
`changes/archive/2026-09-25-own-work-per-wake` (D12).

#### Scenario: The token is asked for at every app entry

- **WHEN** the app cold-starts in the foreground or the background, or enters the foreground
- **THEN** it registers for remote notifications, asking the OS for the current APNs token

#### Scenario: The delivered device token reaches the push seam

- **WHEN** the OS delivers the APNs device token to the `AppDelegate`
- **THEN** the token string and the compile-time `env` are forwarded to the Kotlin push registration
  path, which writes the device config to the backend when the token, environment or device identity
  differ from the last registration the backend accepted

#### Scenario: A registration error does not crash the app

- **WHEN** remote-notification registration fails
- **THEN** the failure is logged and the app continues running normally

### Requirement: Push registration is started by the shared composition

Push registration SHALL be **started** by an explicit installer on the composed core (`compose/`), and
that installer SHALL be invoked **once, from one place**: the shared host composition (`snapSyncHost`; spec
`module-architecture`, "One shared composition"), as it composes the graph — before, and independent of, host
assembly. A process composes its graph on **every cold start**, whether it was launched into the foreground or
in the background by a silent push, a background-`URLSession` relaunch or the upload heartbeat, none of which
assembles the host; so the subscription is installed on every cold start. The root SHALL NOT invoke the
installer, and host assembly SHALL NOT invoke it: the root hands over the platform-shaped pieces it built as
ports and nothing else. The installer SHALL be **idempotent** all the same — once per process — so no second
path can install the subscription twice. Unlike
the permission-grant subscriptions (see "iOS live composition root"), which a cold background wake SHALL NOT
install, this subscription SHALL be installed there too, so a rotated APNs token or a renewed device credential
is published from whichever wake learns it; the cost is rare, because it publishes only on a changed
(`token`, `env`, `deviceId`) triple, on join, or on a fresh credential (capability `push-registration`).
Decision record: `changes/archive/2026-09-25-own-work-per-wake` (D12).

The registration client, the token source and the last-registered record remain **constructed by the
shell**: all are platform objects — a Ktor client over the shell's shared HTTP stack, the compile-time APNs
environment, and an App-Group file — and
`:domain` builds no platform object. What moves is the ordering and the subscription, which is where the
behaviour is.

The installer SHALL own the subscription that re-sends a registration after a new device token is
obtained — **every** new device token, a periodic renewal as much as a first mint or a re-attestation
(`DeviceAttestation.tokenChanged` fires on each), unconditionally, whatever the persisted last-registered
value holds (capability `push-registration`). That retry is not an optimisation: the app otherwise writes its
registration only when the APNs token, environment or device identity changes (decision record
`changes/archive/2026-09-25-own-work-per-wake`, D12), so a registration refused because the credential was rejected is
otherwise never re-sent, and the device goes unregistered — no silent pushes, no download wakes, and none of
the wake-driven attestation renewals that depend on them.

The reason this belongs in the composition rather than the shell is that it is a **join between two
features that are blind to each other** — the trust feature emits that a new token exists, the push feature
consumes it — and a join is a behaviour, not wiring. Assembled in the shell it is unreachable by the world
harness (which composes the shared composition, not the root) and untestable by law (`:app:*` Kotlin is
wiring-only and untested), so nothing would observe it being removed.

#### Scenario: A refused registration is re-sent after a fresh credential

- **WHEN** a push registration write is refused, and the app subsequently obtains a new device token
- **THEN** the registration is written again without waiting for the OS to deliver another APNs token

#### Scenario: A renewal re-registers

- **WHEN** the app renews its device token on a wake, with an unchanged APNs token, environment and device
  identity
- **THEN** the registration is written again, because a fresh credential publishes unconditionally

#### Scenario: The shell installs and decides nothing

- **WHEN** the root composes the app
- **THEN** it supplies the platform pieces it built as ports, invokes no installer — the shared host
  composition installs the subscription — and holds no registration ordering or retry logic of its own

#### Scenario: A token rotation learned in a background cold start is published from that wake

- **WHEN** the process is cold-started in the background (by a silent push, a background-`URLSession`
  relaunch or the upload heartbeat), the host is never assembled, and the OS delivers an APNs token that
  differs from the last registration the backend accepted
- **THEN** the push-registration subscription is installed in that process and publishes the new token from
  that wake, without waiting for a foreground launch

#### Scenario: The installer runs once per process

- **WHEN** a process cold-started in the background later assembles its host
- **THEN** the push-registration subscription is not installed a second time, and a delivered token is
  published at most once

### Requirement: Forward an incoming silent push to the receiver seam

The `AppDelegate` SHALL forward an incoming remote notification's `userInfo` dictionary **whole**
to `SnapSyncRoot.onSilentPush(userInfo:completion:)`, performing no field extraction, parsing, or
decision in Swift (the transcriber law — the `eventId` extraction is the tested `model/` payload
codec, applied inside the `flow/SilentPush` trigger), and SHALL pass a completion that signals the
OS fetch completion handler. Kotlin SHALL always release the completion — including for a payload
with no usable `eventId`, which fans out to no arm (an unanswered `content-available` push costs
the app its future background wakes).

Kotlin SHALL release that completion right after the push's **own work** — the membership re-read and
attestation prelude, then the union read and the download enqueue the push exists to cause (capability
`push-registration`) — and no later. Its own work is not "the synchronous portion": releasing before the
union read and the enqueue have run leaves them to race a suspension. Everything else a push used to hold
the handler for — importing staged downloads, the upload top-up, the discovery walk and manifest publish —
is handed to the opportunistic tail (see "Each OS wake does its own work, then hands the rest to one
opportunistic tail"), which runs under the process's background time rather than under this handler. The
release SHALL NOT wait on a deadline of ours: the background task begun no later than the handover (see "OS
completion handlers are released only after their work completes") covers the own work too, and if the
operating system signals through it that background time is up while the own work is still running, the handler
is released at once, the own work runs on until the process is suspended, and no tail follows (see "Time is up
is learned only from the operating system" and "Expiry stops work cooperatively at the next boundary").

The push's wake SHALL join the tail only when the pushed event is the device's active event, read from the
membership the prelude has just re-read (capability `push-registration`, "Silent-push receive seam"); otherwise
it ends its background time once the handler is released. A push assembles no host.

Decision record: `changes/archive/2026-09-25-own-work-per-wake` (D1, D3, D5).

#### Scenario: An incoming push is routed to Kotlin whole

- **WHEN** the app receives a silent remote notification
- **THEN** the `AppDelegate` forwards the complete `userInfo` and a completion to Kotlin, with no
  parsing or decision in Swift

#### Scenario: The handler is released after the push's own work, not before it

- **WHEN** a silent push for the joined event arrives
- **THEN** the OS completion handler is released after the union read and the download enqueue have
  returned, and the logged duration for the entry point covers that work

#### Scenario: The rest of the push's work runs in the tail, after the release

- **WHEN** a silent push's own work has finished and staged downloads or upload work remain
- **THEN** the handler is released without waiting for them, and the import, top-up and walk run in the
  opportunistic tail under the process's background time

#### Scenario: A push for another event wakes no tail

- **WHEN** a silent push names an event other than the device's active one
- **THEN** the handler is released after the download arm's own guards decline, no tail is requested, and the
  background time is ended

#### Scenario: A malformed payload still releases the handler

- **WHEN** a silent push arrives whose payload carries no usable `eventId`
- **THEN** no receiver runs, the miss is logged, and the OS completion handler is still called

### Requirement: Background entry points record protected-data state

Every background entry point of both processes SHALL log, to the device diagnostic log (capability
`diagnostic-logging`), the protected-data state it observed. The entry points are the app's silent-push
handler, its background-`URLSession` handler, and the extension's `process()`. (The app's download
import-tail backstop was one; it is deleted — `changes/archive/2026-09-25-own-work-per-wake`, D7.)

The **app** SHALL log protected-data availability read through the need-named `ProtectedStorage` port
("is protected storage readable now?"), whose iOS adapter in `:adapter:ios:app-only` asks `UIApplication`;
the line SHALL be written by the inbound port's implementation in `compose/` (`module-architecture`, "OS
entry points cross an inbound port"), not by the shell. The port feeds this diagnostic only and SHALL NOT
gate any work. The
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
  is traceable to the silent push, the URL-session handler, or the extension cycle

#### Scenario: The protected-storage read reaches the log through a port

- **WHEN** the app's silent-push or background-transfer entry runs
- **THEN** the logged protected-data state is the `ProtectedStorage` port's answer, and no `:domain` code
  names `UIApplication`

### Requirement: Background triggers re-read the membership and fail cleanly before first unlock

Every OS-callback trigger flow acting on the persisted membership SHALL **re-read** it (the
foreground and silent-push flows — the download-backstop flow, which was the third, is deleted with its
task: `changes/archive/2026-09-25-own-work-per-wake`, D7) into the config StateFlow before acting
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
  and a silent push or foreground entry fires
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

Every OS-supplied completion handler the shell receives SHALL be released right after that wake's **own
work** has completed — the work the event it delivers is about — and SHALL NOT be held for anything else.
Those handlers are the background-`URLSession` handler for **each** session
(`handleEventsForBackgroundURLSession`, reaching the core as `onBackgroundTransfers`), each `BGTask`'s
`setTaskCompleted` (reaching it as `onBackgroundTask`), and the silent-push fetch handler.
Releasing one declares to the system that the app is done and may be suspended; releasing it
while the wake's own work is merely *queued* is what freezes the process mid-flight, and holding it for
work the event is not about spends a budget the platform bounds and polices.

What each wake's own work is, and when its handler is released:

- **silent push** — the union read and the download enqueue (after the shared membership re-read and
  attestation prelude); released when they return (see "Forward an incoming silent push to the receiver
  seam").
- **background-`URLSession` wake, download session** — staging the delivered files; released at the
  session's `urlSessionDidFinishEvents` once staging is done, **not** after the photo-library imports,
  which are the tail's first step.
- **background-`URLSession` wake, upload session (iOS 18–26.0)** — the transport recording the terminal
  outcomes the session delivered; released at `urlSessionDidFinishEvents`.
- **`BGTask`** — the grant of time *is* the task, so its tail is its work: the upload heartbeat has no own
  work beyond the prelude, and runs the tail (① import, ② top-up, ③ walk → manifest). `setTaskCompleted`
  SHALL be held until that tail has finished, or until the task's expiration handler fires — then at once,
  whichever comes first.

For the push and `URLSession` wakes, whatever remains after the own work runs in the opportunistic tail
under the process's **background time** (the background-time port, bound to `beginBackgroundTask`; spec
`module-architecture`). **That background task SHALL be begun no later than the OS handler is handed over**
— before the own work starts, not after it — and SHALL be held until the tail it hands to has finished, or be
ended at once on its expiry. It therefore covers the own work, any wait for a signal the release
depends on, and the tail: there is no instant between the handover and the tail's end at which the process
holds nothing, and a signal that never comes ends in Apple's expiry rather than in a handler held forever.
This is the one statement of the rule; every other requirement that relies on it refers here. Background time
is per app, not per task, so holding it after the release costs the wake no time and gains the one expiry
signal these two handlers do not carry. A foreground entry, which is handed no handler, SHALL hold the same
background time across its own work and its tail (see "Time is up is learned only from the operating system").

There SHALL be **no deadline of ours** on any handler: no per-entry-point constant, no timer that releases a
handler and lets its work run on. A handler whose own work has not finished is released only when the
operating system says time is up — at once, on that signal, with the own work left to run on until the process
is suspended (see "Time is up is learned only from the operating system" and "Expiry stops work cooperatively
at the next boundary").
Measured in the field (iPhone XS, iOS 18.7.9, builds 605–609) the self-chosen 20 s bound was itself the
damage: it released on 45 % of download wakes and 66 % of upload-session wakes, iOS suspended the app
≤ 0.4 s later, and import batches needing 20–40 s were cut off mid-import.

The handler SHALL be carried by one type (`ports/OsCompletions`) with exactly two release paths: one that
takes the own work as a `suspend` block and releases after it, on every path, a throw included — so that
releasing before the own work is not expressible at a call site — and one for the operating system's expiry,
which releases at once. Each handler SHALL be released **exactly once**, by whichever path reaches it first,
even when the two race on different threads. That type SHALL live in `:domain` `ports/`, not in `:app:*` — the shell is wiring-only and untested by rule, so behaviour placed there cannot
be covered. The inbound port's implementation in `compose/` SHALL construct it from the raw handler it
receives as a port argument (`module-architecture`, "OS entry points cross an inbound port"); the shell
SHALL hand the handler over by delegation and construct nothing, and Swift SHALL continue to forward an
opaque handler and decide nothing.

Where a handler's release depends on a later signal — a background-`URLSession` wake is handed a handler at
`handleEventsForBackgroundURLSession` and waits for the session to report its events drained — the wait
for that signal is covered by the background task begun at the handover (above), so a signal that never
arrives ends in the operating system's expiry and a release, never in a handler held forever.

**Every outstanding handler SHALL be released**, and none SHALL be replaced. Where a second handover for
the same session can arrive before the first release, each handler SHALL be held independently, and the
drain signal SHALL release every handler outstanding at that moment once the own work it feeds is done. A
single stored slot cannot express this: the earlier handler is overwritten and never called, which costs
the app its future background wakes.

**A background-`URLSession` handler SHALL be released on the main thread**, as its owning API requires
(`URLSessionDelegate.urlSessionDidFinishEvents(forBackgroundURLSession:)`: *"Because the provided
completion handler is part of UIKit, you must call it on your main thread."*). This applies to the
release only; where the hold waits is unconstrained. A release on the expiry runs on the thread the expiry
arrives on, which for this handler is the main thread already: its expiry is the background time's,
`beginBackgroundTask`'s expiration handler, which UIKit calls on the main thread. No such requirement is stated for the silent-push
fetch handler or for `BGTask` completion, and none SHALL be extended to them by this rule.

Decision record: `changes/archive/2026-09-25-own-work-per-wake` (D1, D3, D5).

#### Scenario: A wake's own work completes before the handler is released

- **WHEN** an OS wake triggers its own work and that work completes
- **THEN** the OS completion handler is released after the own work finishes, and the logged duration
  for that entry point reflects the own work rather than the dispatch

#### Scenario: A download relaunch releases after staging, not after importing

- **WHEN** the download session relaunches the app with finished transfers
- **THEN** the handler is released on the main thread at `urlSessionDidFinishEvents` once the delivered
  files are staged, and the staged files are imported afterwards by the tail under the process's
  background time

#### Scenario: The tail is covered before the handler goes

- **WHEN** a silent push or a `URLSession` wake releases its handler with tail work remaining
- **THEN** the process already holds a background task begun no later than the handover, and the tail
  runs under it until it finishes or the operating system signals its expiry

#### Scenario: No clock of ours releases a handler

- **WHEN** a wake's own work runs longer than any constant the code could name
- **THEN** its handler is not released on a timer and its work is not left running unheld; the handler
  is released when the work finishes, or when the operating system's expiry signal has stopped it

#### Scenario: Releasing early is not expressible

- **WHEN** a new OS entry point is added that releases its handler without awaiting its own work
- **THEN** the handler type offers no such call — only a release after the own work, or on the operating
  system's expiry — so the shape does not compile

#### Scenario: A BGTask is completed after its tail or on its expiry

- **WHEN** a `BGTask` runs and the operating system fires its expiration handler before the tail has
  finished
- **THEN** the tail is asked to stop and `setTaskCompleted` is called at once, through the completion the
  core holds — the unit in flight is not waited for and nothing starts after it; never from the shell's
  expiration handler directly, and never on a constant of ours

#### Scenario: A drain signal that never arrives still ends in a release

- **WHEN** the OS hands over a background-`URLSession` handler and the session never reports its events
  drained
- **THEN** the handler is released when the background task begun at the handover expires, and the
  expiry is logged

#### Scenario: A second handover does not orphan the first

- **WHEN** a second `handleEventsForBackgroundURLSession` for the same session arrives before the first
  handler has been released
- **THEN** both handlers are held, and the drain signal releases both — neither is discarded nor released
  early to make room for the other

#### Scenario: The URLSession handler is released on the main thread

- **WHEN** a background-`URLSession` handler is released, whether after its own work or on the operating
  system's expiry
- **THEN** the release runs on the main thread, even though the drain signal is delivered on a
  session-owned queue and the work ran off the main thread

### Requirement: Background-wake requests carry an explicit request timeout

The shared HTTP client SHALL configure an explicit request timeout rather than relying on the platform
session's defaults. A request left to the platform default is unbounded in practice on a background wake:
the session runs in-process, so a suspended app services nothing, its wall-clock idle timer expires
unobserved, and the task reports only when the app next runs — producing failures reported as minutes or
tens of minutes that are neither network measurements nor honest durations.

The timeout SHALL be short enough to bound the network portion of any span that holds an OS completion
handler — a silent push's union read is its own work, so the handler waits on it. Callers already
treat a failed fetch as "keep last-good state", so a fast failure costs a retry and never correctness.

This timeout is **not** a deadline in the sense of "Time is up is learned only from the operating system",
and that rule SHALL NOT be read as deleting it. It releases no handler, ends no background task and stops no
unit of work; it is a property of one network request, set from a measurement of the network (answers
while awake arrive in well under the ceiling; a request starved by suspension reports the distance to the
next wake instead). What that rule forbids is a clock of ours deciding **how long a wake may run** — and
with no such clock left, this ceiling is what keeps a stalled request from holding a handler until the
operating system's expiry. Decision record: `changes/archive/2026-09-25-own-work-per-wake` (D3).

#### Scenario: A request starved by suspension fails fast on resume

- **WHEN** a background-wake request is interrupted by suspension and the app next runs
- **THEN** the request fails within the configured timeout rather than reporting the whole
  suspension interval

#### Scenario: A union fetch failure keeps last-good state

- **WHEN** the union fetch fails on its timeout during a background wake
- **THEN** last-good download state is retained and no rows are dropped

### Requirement: The app subscribes for process-metric reports at process start

The app shell SHALL register the process-metric subscriber (capability `crash-reporting`) during its
own initialization — the path that runs on **every** process start, whatever woke it — and SHALL NOT
seat it on the deferred application graph.

Two measured facts force this, and neither is a preference:

1. **The platform accumulates nothing until first asked.** Reports begin accruing only after the
   process first touches the platform's metric manager, and never retroactively. A launch that does
   not subscribe is attribution nobody gets back, so the earliest unconditional path is the only
   correct seat.
2. **Delivery is one-shot.** Reports wait indefinitely while no subscriber exists, but once handed
   over they are not redelivered. Subscribing without a live consumer therefore converts a report the
   OS was holding safely into a discarded one — strictly worse than not subscribing at all.

From (2): **subscribing and handling SHALL be inseparable.** Whatever registers the subscriber SHALL
already be able to handle what arrives, on every launch shape — including a background wake that never
assembles the application graph.

Registration SHALL NOT force assembly of the deferred application graph, preserving the existing
property that a cold background wake assembles only what that wake needs.

The subscriber SHALL be retained for the process lifetime, and the platform surface it uses SHALL be
linked only into the app process — the background-upload extension SHALL NOT link it.

#### Scenario: A background wake receives a report

- **WHEN** the process is woken in the background and never assembles the application graph
- **THEN** the subscriber is registered and the report is handled, rather than delivered to nothing

#### Scenario: Registration does not assemble the graph

- **WHEN** the process starts and registers the subscriber
- **THEN** the deferred application graph is not forced, and nothing reads protected data or opens a
  store earlier than it otherwise would

#### Scenario: The extension does not subscribe

- **WHEN** the background-upload extension process runs
- **THEN** it links no process-metric platform surface and registers no subscriber

### Requirement: The app resets the upload ledger at membership transitions on every tier

The app process SHALL perform the upload ledger's membership-transition writes **itself, on every upload
tier** — iOS 18–26.0, iOS ≥26.1 under a partial grant, and iOS ≥26.1 under a full grant, where the
extension's cycle may be running at the same moment. They are the ledger's **reset family**
(`sync-ledger`), code-owned writes the membership use-cases perform through the `LedgerStore`, each one
transaction, safe against a cycle of either process landing between its read and its write:

- **At a join or a switch** — the provision's join-time load (capability `join-event`): `resetTo` the
  per-device listing on a successful fetch, `clear()` on a failed one. It runs in the app, before the
  config is saved and before the join transition brings up a mechanism, and only when the membership changes
  (never on a re-provision of the joined event).
- **At a leave** — `LeaveEvent`'s `clear()` of the upload ledger, after the mechanisms are stood down and before
  the config is cleared (capability `leave-event`).

The extension SHALL hold no membership-transition logic: it constructs no join marker, runs no in-cycle
reconciliation against the listing, and has no leave-side action — a membership change is always an
explicit app action, and the app is the process that performs it. The reset family SHALL go through the
`LedgerStore` directly, never through a cycle's `LedgerWriter`: which code may perform which ledger write
is the invariant (`sync-ledger`), not which process holds a writer.

Decision record: `changes/archive/2026-09-21-join-loads-leave-clears` (D1–D4); the code-ownership
restatement: `changes/both-uploaders-active`.

#### Scenario: A join on the OS-driven tier is loaded by the app

- **WHEN** the device joins an event on iOS ≥26.1 under a full grant
- **THEN** the app fetches the per-device listing and `resetTo`s the ledger through the `LedgerStore`
  before the join transition registers the extension, writing only through the reset family, and the extension's first cycle
  reads the loaded ledger

#### Scenario: A failed listing fetch clears instead

- **WHEN** the join-time listing fetch fails or times out, on any tier
- **THEN** the app `clear()`s the ledger and the join completes — nothing gates the upload mechanism

#### Scenario: A leave clears the ledger on the OS-driven tier

- **WHEN** the user leaves the event on iOS ≥26.1 under a full grant
- **THEN** the app disables the extension, then `clear()`s the ledger through the `LedgerStore`, then
  clears the config — the extension runs no leave-side reconciliation

#### Scenario: A re-provision of the joined event does not reset

- **WHEN** a provision reaches the app for the event the device is already joined to
- **THEN** the ledger is neither cleared nor loaded

### Requirement: The app removes the orphaned join-marker key at process start

The app SHALL remove the App-Group `NSUserDefaults` key `rejoin.joinedEventId` on every process start. The
key was the retired join marker's persistence (the in-cycle re-join reconciliation compared it with the
configured event); no code reads or writes it any more. The removal SHALL be idempotent —
`removeObjectForKey` on an absent key is a no-op — so it SHALL carry **no bookkeeping**: no "done" flag,
no version check, and no ordering against any other start-up work. A removal that fails or does not take
effect SHALL be tolerated silently; the next process start repeats it. The literal SHALL survive only at
this removal site, beside the other App-Group constants in `:adapter:ios:ext-safe`, where it stays a
pinned runtime-identity literal (capability `architecture-guards`) so that a drifted key fails the build
instead of silently removing nothing. The extension SHALL NOT read or write
the key.

The reason is **rollback**, not tidiness. A build from before this change compares the configured event
with the marker on every cycle. With the key removed it finds no marker, runs its reconciliation once and
`resetTo`s the ledger from the per-device listing — correct whatever this build left behind (an empty
ledger after a leave, a loaded one after a join). Left in place, a device that left and re-joined the
**same** event under this build would present a matching marker to a reverted build, which would then skip
its seed and re-upload the device's whole window.

Decision record: `changes/archive/2026-09-21-join-loads-leave-clears` (D8).

#### Scenario: The orphaned key is removed at start

- **WHEN** the app process starts on a device whose App-Group `NSUserDefaults` holds `rejoin.joinedEventId`
- **THEN** the key is removed, and nothing records that the removal happened

#### Scenario: The removal is a no-op when the key is absent

- **WHEN** the app process starts and the key is absent
- **THEN** the removal completes without effect

#### Scenario: A reverted build re-seeds rather than trusting a stale marker

- **WHEN** a device left and re-joined the same event under this build and is then updated to a build
  that predates it
- **THEN** the older build finds no join marker, reconciles once, and `resetTo`s the ledger from the
  per-device listing instead of re-uploading the window

### Requirement: OS entry points delegate upload triggers to the app's uploader

Every OS entry point that drives upload work SHALL delegate to the **app's uploader**, unconditionally —
foreground entry, a silent push, the upload heartbeat background task, and a photo-selection change — on
every tier (`upload-lifecycle`, "Triggers are delivered to the mechanism and declined explicitly"). It
reaches the uploader either as its own work (a selection change's snapshot-fed discovery → manifest, an
upload-session relaunch's terminal recording) or through the opportunistic tail's upload steps (top-up;
walk → manifest, under a full grant), which every wake's tail runs — the heartbeat's and foreground's included,
neither of which runs an upload unit as its own work (see "Each OS wake does its own work, then hands the rest to one
opportunistic tail"). The
uploader decides at its entry gate, through the app's own admission, whether it may create. The
root SHALL NOT bind per-tier upload behaviour, and no entry point SHALL re-check a tier, the grant, or the
registration. The one condition on reaching the tail is a silent push's **event** guard — its wake joins the
tail only for the device's active event (capability `push-registration`) — which is about which event a push
names, not about which uploader may act. Decision record: `changes/both-uploaders-active`; own work and the tail:
`changes/archive/2026-09-25-own-work-per-wake` (D1, D2).

The entry point's implementation — the inbound port's, in `compose/` — SHALL hold its own OS wake's
completion handler in the handler-carrying type (see "OS completion handlers are released only after their
work completes") across its own work, and hand the rest to the tail runner — so the uploader holds no trigger
and no OS completion handler at all: the runner calls its tail units as plain `suspend` functions (capability
`ios-url-session-upload`), and no entry point names a deadline. A unit that declines still returns, so the
handler is still released.

A cold background launch reaches the engine like any other entry: nothing about the host having been assembled
decides whether the trigger does work.

#### Scenario: A background wake reaches the app engine

- **WHEN** the OS invokes an upload-driving entry point
- **THEN** the entry point holds that wake's handler across its own work, reaches the app's uploader as
  own work or through the tail, and releases the handler when its own work completes, or at once on the
  operating system's expiry

#### Scenario: A cold heartbeat wake does real work

- **WHEN** the upload heartbeat launches the app in the background, with no host assembled, and photo
  access is usable
- **THEN** the tail runs its import, its top-up and — under a full grant — its walk → manifest, the task is
  completed after that tail (or on its expiry), and the next heartbeat is scheduled

#### Scenario: No entry point re-checks a tier

- **WHEN** the upload-driving entry points are inspected, in the inbound port's implementation and in the shell
- **THEN** none of them branches on an upload tier, the grant or the registration, and none binds a
  per-tier thunk; the only comparison among them routes a background task or transfer channel by the
  identifier the OS delivered

### Requirement: Background tasks are forwarded by the identifier the OS delivered

Each `BGTaskScheduler` registration in the Swift shell SHALL forward the delivered task's own `task.identifier`,
with a completion that completes the task, to the single inbound-port member `onBackgroundTask(identifier,
completion)`; no registration SHALL name a Kotlin entry specific to one task. The core SHALL route the identifier
to its handler (the upload heartbeat — the only background task left once the download import-tail backstop is
deleted) and SHALL release the completion, logged, for an identifier it does not know. The registrations'
identifier literals stay where the runtime-identity guard pins them (capability `architecture-guards`).

Each registration SHALL also install the task's `expirationHandler` as a **forward into the core**, through the
inbound port and keyed by the same delivered identifier (the shape is the port's: spec `module-architecture`, "OS
entry points cross an inbound port"). The expiration handler SHALL NOT complete the task itself: completing it
from Swift while the core still holds the task's completion races the core's own release, and it answers the
operating system's "time is up" without stopping the work the task was granted time for. The core SHALL answer the
forwarded expiry (`onBackgroundTaskTimeUp`) by requesting the running tail's stop and releasing the completion at
once, without waiting for the unit in flight (see "Expiry stops work cooperatively at the next boundary"); the
completion passed to `onBackgroundTask` SHALL remain the only
path to `setTaskCompleted`. An expiry forwarded for an identifier the core holds no task for SHALL be logged and
otherwise ignored.

Two registrations with the same shape, each naming its own Kotlin entry, compile whichever entry they name; the
only Swift → Kotlin cross the compiler would not reject was that pair. Forwarding the identifier the OS delivered
removes the choice from Swift — for the task and for its expiry alike.

The download backstop's identifier (`app.snapsync.download.backstop`) SHALL leave the Swift registrations and
`Info.plist`'s `BGTaskSchedulerPermittedIdentifiers` in the same build, because registering an unlisted identifier,
or listing one never registered, is an operating-system error.

Decision record: `changes/archive/2026-09-25-own-work-per-wake` (D3, D7).

#### Scenario: The upload heartbeat task fires

- **WHEN** the OS launches the `app.snapsync.upload.heartbeat` task
- **THEN** Swift forwards that identifier to `onBackgroundTask`, the core runs the prelude and then the tail,
  and the task is completed after the tail

#### Scenario: The operating system expires a running task

- **WHEN** the OS fires a running task's `expirationHandler`
- **THEN** Swift forwards the expiry with the delivered identifier and calls nothing else; the core requests the
  tail's stop and completes the task at once through the completion the core holds — once — and the unit in
  flight starts nothing after it

#### Scenario: A registration block is copied for a new task

- **WHEN** a registration is added by copying an existing block
- **THEN** it still forwards the identifier the OS delivers, for the task and its expiry, so a copy cannot route
  the new task to an existing task's handler; an identifier the core does not know is released and logged

#### Scenario: The backstop identifier is gone from both places

- **WHEN** the Swift shell and `Info.plist` are inspected
- **THEN** neither names `app.snapsync.download.backstop`

### Requirement: Each OS wake does its own work, then hands the rest to one opportunistic tail

Every app-process OS entry point SHALL run only the work the event it delivers is about — its **own work** —
after the shared prelude (the membership re-read and the attestation refresh), and SHALL hand everything
else to one **opportunistic tail**:

| wake | own work |
|---|---|
| silent push | the union read, the plan and the download enqueue |
| download-session relaunch | staging the delivered files |
| upload-session relaunch (iOS 18–26.0) | the transport recording the delivered terminal outcomes |
| upload heartbeat `BGTask` (iOS 18–26.0) | none beyond the prelude — the task is a grant of time, and its work is the tail |
| limited-grant selection change | snapshot-fed discovery → manifest publish |
| foreground | the download reconcile (union read, plan, enqueue), the stored-upload settle, the staged-byte reclaim, the status refresh and the membership refresh |
| a download staged in a running process (no relaunch delivered it) | recording the staging |

A wake's tail SHALL follow whatever its own work answered: a failed union read, or a foreground flow that
threw, still hands the wake to its tail — the staged imports and the known rows do not depend on it. A silent
push's wake joins the tail only when the pushed event is the device's active event (capability
`push-registration`, "Silent-push receive seam").

The tail SHALL run these steps, in this order: **① import staged downloads**; **② upload top-up** — re-create
retry-spent failures and enqueue the known `DISCOVERED` rows; **③ discovery walk → manifest publish**, under a
full grant only. When ③ added rows, the tail SHALL loop back to ②; otherwise it ends. The order is a cost
order: staged bytes are already paid for and are what the member sees, and the walk is the one costly,
unbounded step. The tail runs until it is done or until the operating system says time is up (see "Time is up
is learned only from the operating system"), whichever comes first. Under a **partial grant** the tail SHALL
run ① and ② only, never ③, and its ② SHALL read no library: it resolves rows from the in-memory selection
snapshot, and is withheld while that snapshot is unread (capability `limited-photo-access`). A limited grant's
discovery is the selection-change wake's own work and the cold-launch baseline, and nothing else.

Not every request needs every step. An upload completion needs ② alone (below); a download staged in a running
process needs ① alone — a staged photo changes nothing the top-up or the walk would see, and a burst stages one
resource at a time, so a walk per staging is exactly the waste the tail exists to avoid. Every other wake, and a
membership transition that arms the uploader (capability `upload-lifecycle`), requests the whole tail.

**Foreground goes through the runner too.** Foreground entry's own work is the table's; the import drain, the
top-up and the walk it used to run concurrently with that work SHALL instead be requested of the one tail
runner, like every other wake's. There SHALL be no second, concurrent path by which foreground imports or
uploads; the download reconcile in particular SHALL NOT drain staged imports itself — the drain is ① of the
tail, in every wake, foreground included (capability `photo-download`). The one import outside the tail is the
once-per-process interrupted-import sweep at host assembly, which drains under the same per-asset claims as ①
and so can never import an asset twice (capability `photo-download`, "Import without foreground; staged by
the wake, imported by the tail").

There SHALL be **one** tail runner per process, and it SHALL be **single-flight**. A wake that requests the tail
while none runs starts it. A wake that requests it while one is running SHALL **join** it: the running tail SHALL
make **exactly one more pass** covering the steps the joiners need — the union of their steps, however many
joined, coalesced into that one pass (a staged download and a freed slot together need ① and ②, not a walk) —
so no request is lost, no second runner starts, and no request is queued behind the tail (capability
`ios-url-session-upload`, "A wake that joins a running tail keeps its obligations", which also states what a
joining caller awaits and how it re-arms). A wake's own work SHALL run **outside** the runner, so no wake's own
work ever waits behind another wake's walk — measured on an iPhone XS, a push waited 22.5 s behind a walk it did
not need.

An **upload completion** (a transfer finishing and freeing a slot) SHALL trigger step ② only — never a walk.
The transport has already recorded the terminal outcome, so what the freed slot needs is a top-up, and the
manifest projects ledger rows in every state, so only discovery changes it: the walk and the publish are one
unit, and a completion needs neither. Measured before this rule: a full cycle, walk included, ran per
completion — 16 cycles in 30 s on the XS in the background, the walk 74 % of each; 101 cycles for 100 uploads
on the SE2.

The tail runner and its order SHALL live in tested `:domain` code (composed in `compose/`), never in the
shell. Background wakes run the process in the kernel's `darwinbg` role (~9× CPU clamp after the first ~1 s,
measured on an SE2), so work a wake does not need is the scarcest thing it can spend.

Decision record: `changes/archive/2026-09-25-own-work-per-wake` (D1, D2).

#### Scenario: A push wake does its own work and leaves the rest to the tail

- **WHEN** a silent push arrives with staged downloads and `DISCOVERED` rows outstanding
- **THEN** the push's own work (union read, download enqueue) runs and its handler is released; then the tail
  imports the staged downloads, tops up the uploads, and walks — in that order

#### Scenario: A walk that finds new rows loops to the top-up

- **WHEN** the tail's walk adds rows to the ledger
- **THEN** the tail runs the top-up again before it ends, so the new rows are enqueued in the same tail

#### Scenario: An overlapping wake does not wait behind a walk

- **WHEN** a silent push arrives while another wake's tail is walking
- **THEN** the push's own work runs at once, outside the runner, and its handler is released without waiting
  for the walk; the push then joins the running tail rather than starting a second one, and that tail makes
  one more pass covering the steps the push needs

#### Scenario: Several joiners share one more pass

- **WHEN** a completion, a push and a heartbeat all request the tail while it is running
- **THEN** no request is lost and no second tail starts: when the current pass ends, the running tail makes
  exactly one more pass covering what all three need, and then ends

#### Scenario: The heartbeat's work is the tail

- **WHEN** the upload heartbeat `BGTask` runs
- **THEN** after the prelude it requests the tail — ① import, ② top-up, ③ walk → manifest — and does no other
  work of its own

#### Scenario: Foreground does not run a second upload or import path

- **WHEN** the app enters the foreground while staged downloads and `DISCOVERED` rows are outstanding
- **THEN** its own work (the download reconcile, the stored-upload settle, the status and membership refresh)
  runs, the staged imports, the top-up and the walk run as the tail through the one runner, and no import,
  top-up or walk runs concurrently with that tail outside it

#### Scenario: An upload completion does not walk

- **WHEN** an upload transfer completes and frees a slot
- **THEN** the top-up runs; no discovery walk and no manifest publish run because of it

#### Scenario: A partial grant skips the walk

- **WHEN** a tail runs under a limited grant
- **THEN** it imports and tops up from the selection snapshot, reads no library, and runs no discovery walk —
  a limited grant's discovery is the selection-change wake's own work

#### Scenario: A staging in a running process imports without walking

- **WHEN** a download finishes staging while the app is already running
- **THEN** the staging is recorded and the tail's import runs for it; no top-up and no walk run because of it

#### Scenario: A failed own work still gets its tail

- **WHEN** a silent push's union read fails while downloads are staged
- **THEN** the handler is released, and the tail still imports the staged downloads

### Requirement: Time is up is learned only from the operating system

The app SHALL learn that a wake's time is up **only** from the operating system's own signals, and SHALL
choose no deadline of its own for how long a wake may run:

- a **`BGTask`** — its `expirationHandler`, forwarded into the core through the inbound port (see "Background
  tasks are forwarded by the identifier the OS delivered");
- a **silent push** and a **background-`URLSession` wake** — the expiration handler of the background task
  begun through the background-time port (`UIApplication.beginBackgroundTask`) no later than the OS handler is
  handed over (see "OS completion handlers are released only after their work completes", which states that
  rule once for every such wake), because neither handler carries an expiry signal of its own;
- a **foreground entry** — the same background time, taken at the entry and held across its own work and its
  tail, so a tail the member leaves running by switching away stops on Apple's signal rather than being frozen
  mid-unit.

The per-entry-point receipt deadlines (`ReceiptDeadlines`: silent push 20 s, background events 20 s,
`BGTask` 120 s) SHALL NOT exist, and no replacement constant SHALL bound a handler hold or a background task.
Nor SHALL a unit of work carry a timeout of the app's own choosing: in particular the shared `UploadCycle`
SHALL hold **no** self-chosen timeout on the device-manifest publish (the former 12 s
`deviceManifestTimeoutMs`), on **either** tier — the app's uploader and the upload extension alike (capability
`ios-photokit-upload`).
The app SHALL NOT read `backgroundTimeRemaining` (or any equivalent estimate) to decide what to run or when to
stop: Apple's contract is its signal and a cooperative stop, and an estimate is not a signal.

This removes the rule that a deadline "can never make the outcome worse than releasing immediately would
have". It could: releasing a handler while the work runs on is a release into a suspension, and in the field
(iPhone XS, iOS 18.7.9) the app was suspended ≤ 0.4 s after the 20 s release, cutting import batches that
needed 20–40 s. Apple's recipe for a push or `URLSession` wake's follow-on work is a background task; background
time is per app, so taking one costs the wake nothing.

A per-request network timeout (see "Background-wake requests carry an explicit request timeout") is not a
deadline in this sense: it bounds one request, releases nothing, and stops no wake — so it still bounds each
request the manifest publish makes.

Decision record: `changes/archive/2026-09-25-own-work-per-wake` (D3).

#### Scenario: A BGTask learns of its expiry from the OS

- **WHEN** the operating system fires a running `BGTask`'s expiration handler
- **THEN** the core receives that expiry through the inbound port, requests the tail's stop and completes the
  task at once

#### Scenario: A push's tail learns of its expiry from the background task

- **WHEN** the background task holding a silent push's tail is told by the operating system that its time is up
- **THEN** the tail is asked to stop, the push's handler is released if it is still held, and the background
  task is ended at once

#### Scenario: No constant of ours ends a wake

- **WHEN** the app-process source is searched for a duration that bounds a handler hold or a background task,
  or for a read of `backgroundTimeRemaining`
- **THEN** none is found

#### Scenario: The manifest publish carries no timeout of ours

- **WHEN** the shared upload cycle publishes the device manifest, in the app process or in the upload extension
- **THEN** the publish is not wrapped in a timeout the cycle chose; only the per-request HTTP timeout bounds
  its requests

### Requirement: Expiry stops work cooperatively at the next boundary

When the operating system signals that time is up, the app SHALL answer it **at once** and stop its work
**cooperatively**. The expiration handler SHALL request the tail's stop, release every OS handler the wake
still holds, and end the background task — or, for a `BGTask`, complete the task through the completion the
core holds — before it returns, never waiting for the unit in flight. That unit — a photo-library change
block, a ledger write, a store transaction, a network request — SHALL NOT be cancelled (an in-flight
photo-library transaction cannot be recalled anyway): it runs on until it completes or the process is
suspended. After the stop **no new unit SHALL start**: the tail checks the stop between its steps, and a step
that iterates checks it between two items (two imports, two job creations). The work SHALL NOT keep running
past the signal as it did under "release the handler and let the work run on", which this replaces.

Answering at once is Apple's recipe — a `BGTask`'s expiration handler is to "cancel ongoing work … as short a
time as possible", and an expiry left unanswered is a watchdog's to decide, which means a termination — and it
is safe because every unit is already a safe retry: staged bytes and their store rows survive, ledger writes
are idempotent upserts, and an import that never reports keeps its claim. So a unit the suspension cuts short,
and work a stop leaves undone, is resumed by the next wake's own work or tail, or by the next foreground.

The expiration handler runs on the thread the operating system calls it on and SHALL return promptly: it only
flips the stop, releases and ends — each of those at most once, whichever path reaches it first. The tail SHALL
NOT be held hostage by one wait either: an import the tail is awaiting gives way to a stop (and to a joining
request), leaving that import claimed and running, and the tail starts nothing further (capability
`photo-download`, "A stalled import blocks no other work").

A stop SHALL be a no-op while no tail runs, so **a wake whose time is already up when its own work ends SHALL
request no tail** — a tail requested then would run with no time left to run in. That includes a wake whose
background time the operating system refused at the handover, which the background-time port reports as an
immediate expiry (spec `module-architecture`, "Background time is an outbound port named for the need").

Decision record: `changes/archive/2026-09-25-own-work-per-wake` (D4).

#### Scenario: An import in flight is not waited for

- **WHEN** the operating system signals expiry while the tail is committing one staged photo to the library
- **THEN** the stop is requested and the background task is ended at once; that commit is not cancelled — it
  records its outcome if the process runs long enough, and is retried from its staged bytes otherwise — and no
  further import starts

#### Scenario: The expiration handler returns at once

- **WHEN** an expiration handler fires
- **THEN** it requests the stop, releases the OS handler the wake still holds, ends the background task, and
  returns without waiting for the running unit

#### Scenario: Work left by a stop resumes later

- **WHEN** a stop leaves staged downloads unimported or rows un-enqueued
- **THEN** the next wake's tail or the next foreground picks them up from the store, with no duplicate import
  or upload

#### Scenario: A wake whose time is up requests no tail

- **WHEN** the operating system's expiry arrives — or its background time is refused at the handover — before a
  silent push's own work has finished
- **THEN** the push's handler is released at once, and when the own work ends no tail is requested

### Requirement: The discovery walk is atomic under a stop

A stop that arrives while the tail's discovery walk is in flight SHALL **abandon** that walk: its decide stage
writes nothing durable, so nothing it saw is recorded, and the next wake's tail walks again. A partial walk
SHALL NOT be treated as authoritative — it would retract photos it never reached — and the walk SHALL NOT be
chunked into resumable pieces: after the stage-1 performance work it costs ~0.3 s in `darwinbg` on the SE2's
event window, too little for chunking to repay its complexity.

Decision record: `changes/archive/2026-09-25-own-work-per-wake` (D6).

#### Scenario: A stop during the walk records nothing from it

- **WHEN** the operating system signals expiry while the tail's walk is enumerating
- **THEN** the walk is abandoned, no ledger row is added or retracted and no manifest is published from it,
  and the next tail walks in full

#### Scenario: A partial walk retracts nothing

- **WHEN** a walk is abandoned before reaching every asset
- **THEN** no photo it did not reach is treated as gone

