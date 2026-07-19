# ios-app-shell — delta for foreground-poll-and-swift-transcriber

## MODIFIED Requirements

### Requirement: iOS live composition root
The `:app:ios` module SHALL provide a composition-root singleton (`SnapSyncRoot`, `iosMain`) that
owns an app-lifetime `CoroutineScope` (a `SupervisorJob` on the main dispatcher) and assembles the
live stack **through the shared composition** `snapSyncApp` (`:domain` `compose/`, spec
`module-architecture` "One shared composition"): the root constructs the platform adapters and
supplies them as `AppPorts` — platform effect lambdas included (the trigger-time membership
re-read `reloadConfig` (bound to the config adapter's `reload()`), the backstop scheduling, the
share-sheet presentation, and the resolved
tier's mechanism thunks) — and `snapSyncApp` composes the feature graph: the **ledger-backed**
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
`AppPorts.photoAccessRequester` (the port the bundle's `requestAccess`/`openSettings` commands are
bound to in `compose/`), and passes the file-backed config adapter (the App-Group config store of
record since migration step 11a, with its written-through Keychain copy — capability `event-link`)
as both the `ConfigSource` and
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
  producer via the tier-neutral arm and clearing the persisted config — the App-Group file and its
  written-through Keychain copy; no ledger or `EventStatus`
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

### Requirement: Forward an incoming silent push to the receiver seam

The `AppDelegate` SHALL forward an incoming remote notification's `userInfo` dictionary **whole**
to `SnapSyncRoot.onSilentPush(userInfo:completion:)`, performing no field extraction, parsing, or
decision in Swift (the transcriber law — the `eventId` extraction is the tested `model/` payload
codec, applied inside the `flow/SilentPush` trigger), and SHALL pass a completion that signals the
OS fetch completion handler. Kotlin SHALL always release the completion — including for a payload
with no usable `eventId`, which fans out to no arm (an unanswered `content-available` push costs
the app its future background wakes).

#### Scenario: An incoming push is routed to Kotlin whole

- **WHEN** the app receives a silent remote notification
- **THEN** the `AppDelegate` forwards the complete `userInfo` and a completion to Kotlin, with no
  parsing or decision in Swift, and the OS completion handler is called after the flow's
  synchronous portion

#### Scenario: A malformed payload still releases the handler

- **WHEN** a silent push arrives whose payload carries no usable `eventId`
- **THEN** no receiver runs, the miss is logged, and the OS completion handler is still called

## REMOVED Requirements

### Requirement: Background work defers while protected data is unavailable

**Reason**: settled forcing proof ④ (migration step 12): zero `deferring` / `running deferred`
lines across all production logs — the defer-and-resume queue (`ProtectedDataGate`,
`:domain:keychain`) was dead code, and the port is now never created (the module is deleted). The
properties that made the deferral safe are carried by the adapters and are re-stated in the added
requirement below: every protected read distinguishes *unreadable* from *absent*
(`ConfigRead.Unavailable`, `KeychainUnavailable`), so a pre-first-unlock wake fails cleanly —
minting nothing, clearing nothing, leaving nothing — and converges at the next trigger, whose flow
re-reads the membership first. What is given up is resume-at-unlock for a window never observed in
production.

## ADDED Requirements

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
