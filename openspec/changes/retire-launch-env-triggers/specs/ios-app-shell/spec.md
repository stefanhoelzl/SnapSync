## REMOVED Requirements

### Requirement: Developer launch-environment config trigger

**Reason**: `SNAPSYNC_EVENT_LINK` was already reachable through the shipped control channel without a
launch variable. The env var routed `LaunchEnvMembership.run(openUrl = ::onOpenUrl)` into
`shell.onOpenUrl(url)`; the already-wired `onSceneContinueActivity` platform entry point routes
`deliverUserActivity` → `forwardEventLink(…, ::onOpenUrl)` into the same `shell.onOpenUrl(url)`, additionally
exercising the real `NSUserActivity` decode and activity-type filter. The trigger was a lower-fidelity
duplicate of a path that already existed.

**Migration**: `POST /os/onSceneContinueActivity?arg=<https://…/join#…>` on a build carrying
`-Psnapsync.rig=true`. The join gate, its `autoJoin` auto-confirmation, and every downstream provision
behave identically — none of that behavior lived in this requirement, and none of it is removed.

### Requirement: Developer launch-environment CREATE trigger

**Reason**: An event can be created the way a user creates one. `onCreateEvent(name, startsAt, endsAt)`
mints and opens the real join gate, and `onConfirmJoin(cutoff, until, direction, saveToAlbum)` carries
exactly the fields the `base64url(JSON)` payload carried. Keeping a headless mint alongside it meant two
creation paths, one of which bypassed the tap-gated pending join. The `ensureAttested()` pre-refresh this
requirement justified was for a **cold launch** ("so the attest-gated create is not lost to a cold-launch
401"); driven over a channel the app is already running, and a rejected token is dropped and re-minted on a
`401` regardless (capability `device-attestation`).

**Migration**: `POST /user/create` → poll `GET /device/state` for `JoiningEvent(eventId, phase)` →
`POST /user/confirmJoin`. Mint-only is `create`, read the id, then `POST /user/cancelJoin`. The greppable
`created eventId=<uuid>` log oracle is replaced by the `/device/state` read.

### Requirement: Developer launch-environment LEAVE trigger

**Reason**: Leaving has a real user path — `StatusContainerHost.onLeaveEvent()` → `commands.leave()` is what
the UI button fires. A dev trigger beside it was a second way to drive the same command.

**Migration**: `POST /user/leave`.

### Requirement: Developer launch-environment RESET-STATE trigger

**Reason**: The behavior this requirement carried is independent of how the reset is invoked, and was
recorded here only because a launch variable was its only caller. It moves to its own capability, keyed on
the feature that performs it.

**Migration**: `POST /device/reset`. The contract — clear four stores, retain three download-row shapes,
prune under the download feature's own lock, leave the attestation credential untouched — is stated in full
by the new capability `device-state-reset`, with the running-process behavior it previously did not have to
describe.

### Requirement: Ordered application of membership-mutating launch triggers

**Reason**: There is no longer a set of triggers read at once that needs an order imposed on it. Each
operation is a separate blocking request, so a caller that awaits a response observes the state the previous
one produced — the property this requirement existed to guarantee. `LaunchEnvMembership`, whose sole purpose
was applying four optional launch variables in a fixed order, has no caller left.

**Migration**: Issue the requests in the order wanted; each returns before the next is sent. The
`reset → leave` safety rule (so that a leave crossing backends is a no-op rather than a `DELETE` aimed at the
wrong backend) is no longer enforced by an ordering and is stated in the `rig-channel` runbook. Forge
precedence is not migrated: forge is no longer a mode of this shell and cannot reach these operations at all.

### Requirement: Developer launch-environment LOG-EXPORT trigger

**Reason**: Superseded by the control channel before this change. `DeviceLogSource` carries
`enum Process { APP, EXTENSION }` and `tail()`, and the channel's log route is a pass-through to it, so the
copy-into-`Documents` plus relaunch plus `apps pull` sequence reaches nothing the channel does not.

**Migration**: `GET /device/logs?process=extension&bytes=<n>`. One reduction is real and is stated in
`diagnostic-logging`: `tail()` reads only the current file, so a rolled `.1` sibling is no longer reachable.
`bytes` is caller-specified, so the live tail itself is not bounded more tightly than before.

### Requirement: Developer launch-environment forge-state trigger

**Reason**: Forge is no longer a mode of the iOS application shell. It is a separate Xcode target over its
own module, with its own entry point and framework, linking neither `:app:ios` nor the live graph — so
`CompositionMode.Forge`, the `ForgeShell` delegate and the shell's outer mode switch are deleted. Forge
inertness stops being fifteen no-op `Shell` members that must each be kept inert and becomes a property the
binary cannot express: `SnapSyncRoot` is not in it.

**Migration**: Build the `SnapSyncForge` target and select the state as before. The one product-facing claim
this requirement carried — that a committed marketing capture depicts the real `StatusScreen` in a state the
real reduction can reach, rather than a fabricated frame — moves to `ios-appstore-metadata`, beside the
requirement that makes the committed raws the source of truth for the listing. The forge mechanism itself is
unspec'd, as `:test:rig` and `:test:harness-driver` are: it renders the real screen over forged sources and
holds no contract of its own.

## MODIFIED Requirements

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
the host and the screen. The composed graph SHALL construct the iOS
`LedgerCountsSource` as a **read-only** reader of the shared App-Group ledger — supplying a
`suspend () -> LedgerCounts` that calls only `iosLedgerStore().aggregates()` (never a write) — and
SHALL issue **no** storage LIST for upload status. On the OS-driven tier the composed graph SHALL
construct **no `LedgerWriter`** (the ledger read is read-only; the extension is the sole writer)
and **no `EventStatusSource`** (the ledger is private to the extension, which also owns
reconciliation — see `event-rejoin-reconciliation`).

The root SHALL resolve its composition **once per process** through the pure sealed resolver
(`model/`'s `resolveComposition`), whose **only** input SHALL be the one OS capability fact — whether
the ≥26.1 background-upload API is present. There SHALL be no developer input to this resolution: no
launch-environment variable, no build property, and no runtime override, so the tier a process runs is a
function of the device it runs on. The root SHALL switch
on the resolved `CompositionMode` in exactly **one** place, selecting the live shell delegate with the
resolved tier's mechanism thunks bound in the same switch. Every OS entry
point (`onForeground` / `onBackground` / `onOpenUrl` / `onPushToken` / `onSilentPush` /
`runUploadHeartbeat` / `runDownloadBackstop` / `handleBackgroundUrlSession`) SHALL be a thin
delegator to that resolved delegate, re-checking no tier flag.

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

#### Scenario: The tier is resolved once, from the OS alone

- **WHEN** the process starts on a device whose OS supports the OS-driven tier
- **THEN** the resolver yields the OS-driven tier, and no launch variable, build property, or runtime
  request can select the other one

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
