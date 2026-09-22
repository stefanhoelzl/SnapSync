## ADDED Requirements

### Requirement: Background tasks are forwarded by the identifier the OS delivered

Each `BGTaskScheduler` registration in the Swift shell SHALL forward the delivered task's own `task.identifier`,
with a completion that completes the task, to the single inbound-port member `onBackgroundTask(identifier,
completion)`; no registration SHALL name a Kotlin entry specific to one task. The core SHALL route the identifier
to its handler (the download import-tail backstop, the upload heartbeat) and SHALL release the completion, logged,
for an identifier it does not know. The registrations' identifier literals stay where the runtime-identity guard
pins them (capability `architecture-guards`).

Two registrations with the same shape, each naming its own Kotlin entry, compile whichever entry they name; the
only Swift → Kotlin cross the compiler would not reject was that pair. Forwarding the identifier the OS delivered
removes the choice from Swift.

#### Scenario: The download backstop task fires

- **WHEN** the OS launches the `app.snapsync.download.backstop` task
- **THEN** Swift forwards that identifier to `onBackgroundTask`, the core runs the download backstop flow under
  the backstop deadline, and the task is completed after the work

#### Scenario: A registration block is copied for a new task

- **WHEN** a third registration is added by copying an existing block
- **THEN** it still forwards the identifier the OS delivers, so a copy cannot route the new task to an existing
  task's handler; an identifier the core does not know is released and logged

## MODIFIED Requirements

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
source — see `sync-status`), the **foreground-gated status-counts poll** (`StatusCountsPoller`,
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
Decision record: `changes/both-uploaders-active`.

The root SHALL **supply the inputs to the upload transitions and decide no upload behaviour itself.** It
supplies the app's uploader, the OS-driven registration where this OS carries its selector, the plain fact
of whether the OS carries the OS-driven mechanism at all (`osSupportsOsDrivenUpload`), and — in a rig
build only — the source of the per-uploader development switch (the app's creation on/off, the extension's
registration on/off). Whether the extension may be registered (`extensionRegistrable`: the OS carries it
**and** the grant is `GRANTED`), and whether each uploader may create, are `upload-lifecycle`'s, re-read at
every transition, and this spec SHALL NOT restate those rules. The root SHALL construct the OS-driven
registration **only** where its selector exists, so a lower system cannot reach a trapping call. The root
SHALL implement the app's inbound port `PlatformEntries` (`onForeground` / `onBackground` / `onOpenUrl` /
`onPushToken` / `onSilentPush` / `onBackgroundTask` / `onBackgroundTransfers`) **by Kotlin delegation** to
the implementation the shared composition builds (`module-architecture`, "OS entry points cross an inbound
port"), supplying it only the in-process hooks it cannot name — the host's `onOpenUrl`, the host's lazy
assembly, and the push-token delivery — and the adapter identifiers it routes by, as data. The root SHALL
hold no hand-written forwarding for a port member, re-check no tier, and decide nothing; the only entry
points it writes by hand are the ones outside the port (`onLaunch`, `onUserActivity`, and the log-only scene
and registration-failure callbacks).

The permission-grant subscriptions (the upload permission-change transition; sole-creator album ensure —
see `event-album`) SHALL be installed by an explicit `AppCore.installPermissionSubscriptions()`
(`compose/`) invoked **only from the root's host-assembly path**, which SHALL also run the upload
**launch reconcile** explicitly (`upload-lifecycle`, "Launch reconciles by comparison; only a join forces
the repair"). The upload subscription SHALL NOT treat the permission StateFlow's replayed value as a
transition. A cold background wake (the download backstop, the upload heartbeat, a silent push, or a
background-`URLSession` relaunch) that merely touches the composed graph SHALL NOT install them and SHALL
run no launch reconcile.

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
`MainViewController` SHALL render `host.container.stateFlow` and pass `StatusScreen` the callback bundle
built by the shared factory over the host (`sync-status-screen`, "The screen's callback bundle is built in
one place") — writing no tap → intent binding of its own — together with the root's shared
`CutoffFormatter` (the screen carries no system-reading default). `SnapSyncRoot` SHALL expose
`onUserActivity(NSUserActivity)` — the scene delegate forwards every delivered activity **whole**,
and the tested `model/` filter-and-dispatch (`forwardEventLink`) keeps only a browsing-web
activity with a URL and routes its complete `absoluteString` to `onOpenUrl(String)`, which
reaches the container's `onOpenUrl` intent (through the inbound port's implementation and the root's host
hook).

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
- **THEN** the shared callback bundle `MainViewController` renders with invokes the container intent,
  which fires the bundle's `requestAccess`/`openSettings` command, whose compose-built body calls the
  `PhotoAccessRequester` port — the UI never calls PhotoKit directly and names no port

#### Scenario: An event link flows through the container

- **WHEN** `SnapSyncRoot.onUserActivity` receives a browsing-web activity carrying a
  `https://<link domain>/join#…` event link
- **THEN** the tested filter routes the complete URL to `onOpenUrl`, which forwards (through the
  inbound port's implementation and the root's host hook) to the container's `onOpenUrl` intent, which decodes and (on success) saves via
  the `ConfigStore` (the file-backed config store, whose App-Group file is its only storage),
  updating the `ConfigSource`

#### Scenario: The leave action flows through the command bundle into the use-case

- **WHEN** the user confirms the leave action in the joined layer
- **THEN** the shared callback bundle invokes `host.onLeaveEvent`, which fires the bundle's `leave`
  command — cancelling in-flight downloads, then running the composed `LeaveEvent` (stopping the
  uploads via the arm's leave transition — deregistering the extension, cancelling the app's transfers,
  stopping its heartbeat — then clearing the upload ledger through the `LedgerStore`'s reset
  family, then clearing the persisted config — the App-Group file; no `LedgerWriter` is constructed
  for it and no `EventStatus` operation runs) — and the screen returns to the setup gate

#### Scenario: The share action flows through the command bundle into the platform share

- **WHEN** the user activates the share action in the joined layer
- **THEN** the shared callback bundle invokes `host.onShareInvite`, which fires the bundle's `share`
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
- **THEN** touching the composed graph installs no permission-grant collector and runs no launch
  reconcile, so no registration is written and no engine is armed

#### Scenario: The root writes no forwarding for a port member

- **WHEN** the OS invokes a `PlatformEntries` member on `SnapSyncRoot`
- **THEN** the call reaches the shared composition's implementation through compiler-generated delegation,
  and no hand-written body in the root stands between them

### Requirement: Background entry points record protected-data state

Every background entry point of both processes SHALL log, to the device diagnostic log (capability
`diagnostic-logging`), the protected-data state it observed. The entry points are the app's download
import-tail backstop, its silent-push handler, its background-`URLSession` handler, and the extension's
`process()`.

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
  is traceable to the backstop, the silent push, the URL-session handler, or the extension cycle

#### Scenario: The protected-storage read reaches the log through a port

- **WHEN** the app's silent-push, backstop, or background-transfer entry runs
- **THEN** the logged protected-data state is the `ProtectedStorage` port's answer, and no `:domain` code
  names `UIApplication`

### Requirement: OS completion handlers are released only after their work completes

Every OS-supplied completion handler the shell receives SHALL be released only after the work that wake
triggered has completed, or after a per-entry-point deadline has expired, whichever comes first. Those
handlers are the background-`URLSession` handler for **each** session
(`handleEventsForBackgroundURLSession`, reaching the core as `onBackgroundTransfers`), each `BGTask`'s
`setTaskCompleted` (reaching it as `onBackgroundTask`), and the silent-push fetch handler.
Releasing one declares to the system that the app is done and may be suspended; releasing it
while the wake's work is merely *queued* is what freezes the process mid-flight.

The handler SHALL be carried by a type whose only release path takes the work as a `suspend` block, so
that releasing early is not expressible at a call site. That type SHALL live in `:domain` `ports/`, not in
`:app:*` — the shell is wiring-only and untested by rule, so behaviour placed there cannot be covered.
The inbound port's implementation in `compose/` SHALL construct it from the raw handler it receives as a
port argument (`module-architecture`, "OS entry points cross an inbound port"); the shell SHALL hand the
handler over by delegation and construct nothing, and Swift SHALL continue to forward an opaque handler and
decide nothing.

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

### Requirement: OS entry points delegate upload triggers to the app's uploader

Every OS entry point that drives upload work SHALL delegate to the **app's uploader**, unconditionally —
foreground entry, a silent push, the upload heartbeat background task, and a photo-selection change — on
every tier (`upload-lifecycle`, "Triggers are delivered to the mechanism and declined explicitly"). The
uploader's cycle decides at its entry gate, through the app's own admission, whether it may create. The
root SHALL NOT bind per-tier upload behaviour, and no entry point SHALL re-check a tier, the grant, or the
registration. Decision record: `changes/both-uploaders-active`.

The entry point's implementation — the inbound port's, in `compose/` — SHALL construct the `OsReceipt` for its own
OS wake, using the deadline named for that wake, and SHALL hold it across the delegated call —
so the engine receives a plain `suspend` trigger and never holds a raw
OS completion handler. A cycle that declines still returns, so the handler is still released.

A cold background launch reaches the engine like any other entry: nothing about the host having been assembled
decides whether the trigger does work.

#### Scenario: A background wake reaches the app engine

- **WHEN** the OS invokes an upload-driving entry point
- **THEN** the entry point holds a receipt for that wake's deadline, delegates to the app's uploader,
  and releases the handler when the delegated work completes or the deadline expires

#### Scenario: A cold heartbeat wake does real work

- **WHEN** the upload heartbeat launches the app in the background, with no host assembled, and photo
  access is usable
- **THEN** the engine runs a cycle and the next heartbeat is scheduled

#### Scenario: No entry point re-checks a tier

- **WHEN** the upload-driving entry points are inspected, in the inbound port's implementation and in the shell
- **THEN** none of them branches on an upload tier, the grant or the registration, and none binds a
  per-tier thunk; the only comparison among them routes a background task or transfer channel by the
  identifier the OS delivered

