## MODIFIED Requirements

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
download-backstop scheduling (a `BackgroundScheduler` for the backstop task), the device identity
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
controller and jobs, the album coordinator, the `flow/` trigger instances (Foreground · Background
· SilentPush · DownloadBackstop · Provision), and the **user-tap command bundle**
(`model/`'s `UserCommands`: leave · create · commitJoin · share · requestAccess · openSettings —
seated in `model/` since migration step 9, because the presentation zone has no edge to
`flow/`; live instances are still built and decorated only in
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

- **WHEN** the process is launched in the background by the download backstop or a
  background-`URLSession` relaunch, without the host-assembly path running
- **THEN** touching the composed graph installs no permission-grant collector and runs no launch
  reconcile, so no registration is written and no engine is armed
