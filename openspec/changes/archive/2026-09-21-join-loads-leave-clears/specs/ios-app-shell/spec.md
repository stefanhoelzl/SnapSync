## ADDED Requirements

### Requirement: The app resets the upload ledger at membership transitions on every tier

The app process SHALL perform the upload ledger's membership-transition writes **itself, on every upload
tier** — iOS 18–26.0, iOS ≥26.1 under a partial grant, and iOS ≥26.1 under a full grant, where the
extension holds the only `LedgerWriter`. They are the ledger's **reset family** (`sync-ledger`), which a
holder of the `LedgerStore` that is not the record writer MAY invoke:

- **At a join or a switch** — the provision's join-time load (capability `join-event`): `resetTo` the
  per-device listing on a successful fetch, `clear()` on a failed one. It runs in the app, before the
  config is saved and before the upload arm starts a mechanism, and only when the membership changes
  (never on a re-provision of the joined event).
- **At a leave** — `LeaveEvent`'s `clear()` of the upload ledger, after the producer is stopped and before
  the config is cleared (capability `leave-event`).

The extension SHALL hold no membership-transition logic: it constructs no join marker, runs no in-cycle
reconciliation against the listing, and has no leave-side action — a membership change is always an
explicit app action, and the app is the process that performs it. Holding these writes in the app on the
OS-driven tier SHALL NOT bring a `LedgerWriter` into the app process: the reset family goes through the
`LedgerStore` directly, so the single-record-writer invariant (`sync-ledger`) is unchanged.

Decision record: `changes/join-loads-leave-clears` (D1–D4).

#### Scenario: A join on the OS-driven tier is loaded by the app

- **WHEN** the device joins an event on iOS ≥26.1 under a full grant
- **THEN** the app fetches the per-device listing and `resetTo`s the ledger through the `LedgerStore`
  before the arm registers the extension, constructing no `LedgerWriter`, and the extension's first cycle
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

Decision record: `changes/join-loads-leave-clears` (D8).

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

## MODIFIED Requirements

### Requirement: On-disk native ledger on iOS

The `:adapter:ios:ext-safe` module SHALL provide an `iosLedgerStore()` factory (iOS-only source) that constructs the shared `SqlDelightLedgerStore` (`:adapter:generic:app`) over a `NativeSqliteDriver`, persisting the ledger database **on disk in the `group.app.snapsync` App-Group container** so its contents survive process death and are shared between the app and the background-upload extension. (Before migration step 4 the factory and store lived in `:domain:engine`.) This factory SHALL be the single site that names the database location, SHALL open the database in WAL mode (one cross-process writer plus concurrent readers), and SHALL wire the backend's cross-process change notification (post-on-write / observe-in-`changes`, per `sync-ledger`). The same factory SHALL serve both processes; on the OS-driven tier the app process constructs no `LedgerWriter` — it holds the ledger only as a `LedgerStore` for its read-only per-asset progress read (`assetProgress()`, capability `sync-status`) and the reset-family operations it invokes at membership transitions — `clear` at a leave, `resetTo`/`clear` at the join-time load (per `sync-ledger`).

#### Scenario: The ledger persists across launches
- **WHEN** the app writes ledger state, terminates, and relaunches
- **THEN** `iosLedgerStore()` opens the same on-disk database and the prior state is present

#### Scenario: The ledger lives in the App-Group container
- **WHEN** the extension writes the ledger and the app later reads it
- **THEN** both open the same database file in the `group.app.snapsync` container, and the app's read reflects the extension's write

#### Scenario: Native backend honors the ledger contract
- **WHEN** the native-driver-backed `SqlDelightLedgerStore` is exercised against the ledger backend contract
- **THEN** `get`, the guarded record write, `aggregates` and change signals behave identically to the JVM-driver backend


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
SHALL issue **no** storage LIST for upload status. While the **OS-driven mechanism** is the resolved
one the composed graph SHALL construct **no `LedgerWriter`** (the ledger read is read-only; the
extension is the sole writer) and **no `EventStatusSource`**. The app's only ledger touches on that
tier are the read-only status read and the reset family at membership transitions (see "The app resets
the upload ledger at membership transitions on every tier") — the extension holds no reconciliation of
its own. Constructing the app-driven
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
  producer via the tier-neutral arm, then clearing the upload ledger through the `LedgerStore`'s reset
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
- **THEN** touching the composed graph installs no permission-grant collector, and no upload
  producer starts off the permission StateFlow's replayed `GRANTED` value

### Requirement: Enable the background-upload extension on grant

When photo-library access is (or becomes) full (`.readWrite` → `GRANTED`), the app SHALL enable the
background-upload extension (`PHPhotoLibrary.setUploadJobExtensionEnabled(true)`) so the system can
invoke it. A grant SHALL **not** run any join, listing fetch, enumeration, or seed. The ledger is loaded
at the **join** — by the provision, before the arm starts this mechanism (capability `join-event`; see
"The app resets the upload ledger at membership transitions on every tier") — so the extension's first
cycle after the enable already sees it, and the extension holds no reconciliation of its own. The app
creates no upload jobs, performs no uploads, and constructs no `LedgerWriter`. The enable call SHALL be
idempotent-safe to repeat on each grant/foreground.

#### Scenario: Granting full access enables the extension directly

- **WHEN** photo-library permission transitions to `GRANTED` with a configured event
- **THEN** the app calls `setUploadJobExtensionEnabled(true)` without fetching, enumerating, or seeding —
  the ledger the extension's next cycle reads is the one the join already loaded

#### Scenario: The app never uploads, and seeds only at a join

- **WHEN** the app is running with a configured event on iOS ≥26.1 under a full grant
- **THEN** it creates no upload jobs, performs no library enumeration, and constructs no `LedgerWriter` —
  its only ledger writes are the reset family at a join, a switch, or a leave
