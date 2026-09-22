## RENAMED Requirements

- FROM: `### Requirement: OS entry points delegate upload triggers to the resolved mechanism`
- TO: `### Requirement: OS entry points delegate upload triggers to the app's uploader`

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
registration **only** where its selector exists, so a lower system cannot reach a trapping call. Every OS
entry point (`onForeground` / `onBackground` / `onOpenUrl` / `onPushToken` / `onSilentPush` /
`runUploadHeartbeat` / `runDownloadBackstop` / `handleBackgroundUrlSession`) SHALL be a thin
delegator to a single live shell delegate, re-checking no tier and deciding nothing.

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

### Requirement: OS entry points delegate upload triggers to the app's uploader

Every OS entry point that drives upload work SHALL delegate to the **app's uploader**, unconditionally —
foreground entry, a silent push, the upload heartbeat background task, and a photo-selection change — on
every tier (`upload-lifecycle`, "Triggers are delivered to the mechanism and declined explicitly"). The
uploader's cycle decides at its entry gate, through the app's own admission, whether it may create. The
root SHALL NOT bind per-tier upload behaviour, and no entry point SHALL re-check a tier, the grant, or the
registration. Decision record: `changes/both-uploaders-active`.

The entry point SHALL construct the `OsReceipt` for its own OS wake, using the deadline named for that wake, and
SHALL hold it across the delegated call — so the engine receives a plain `suspend` trigger and never holds a raw
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

- **WHEN** the shell's upload-driving entry points are inspected
- **THEN** none of them branches on an upload tier, the grant or the registration, and none binds a
  per-tier thunk

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
