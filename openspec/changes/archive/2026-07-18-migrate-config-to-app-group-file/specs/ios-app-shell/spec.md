# ios-app-shell — delta for migrate-config-to-app-group-file

## MODIFIED Requirements

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
  permission adapter and the file-backed config store; the root wires the result into one
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
  decodes and (on success) saves via the `ConfigStore` (the file-backed config store, which also
  writes through to its Keychain copy), updating the `ConfigSource`

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

### Requirement: Background work defers while protected data is unavailable
The app process SHALL consult `UIApplication.isProtectedDataAvailable` before performing background
work that reads protected state (the Keychain-backed device id and the event config — an App-Group
file under complete-until-first-unlock protection, with a written-through Keychain copy, since
migration step 11a). When protected data
is **unavailable** — the device has not been unlocked since boot — the app SHALL **defer** that work
rather than failing it or dropping it, and SHALL resume it when the system posts
`UIApplicationProtectedDataDidBecomeAvailable`, which fires as soon as the user unlocks.

Deferring SHALL NOT mint a device id, SHALL NOT write any Keychain item, and SHALL NOT clear or reset
any persisted state.

The upload extension has no `UIApplication` (the API is unavailable to app extensions). In the
extension process an unavailable protected-data read SHALL instead surface as an unavailability error
and the cycle SHALL be skipped cleanly, per capability `event-link` (*An unreadable config is not
an absent config*).

#### Scenario: A background wake before first unlock defers rather than failing
- **WHEN** the app is woken in the background (a `BGProcessingTask`, a silent push, or a background
  `URLSession` completion) while protected data is unavailable
- **THEN** the work is deferred, no Keychain write or mint occurs, no persisted state is cleared, and
  the process does not terminate

#### Scenario: Deferred work resumes at unlock
- **WHEN** protected data becomes available after such a deferral
- **THEN** the deferred background work runs, rather than waiting for the operating system's next wake

#### Scenario: Work proceeds normally once protected data is available
- **WHEN** the app is woken in the background while the device is locked but has been unlocked at least
  once since boot
- **THEN** protected data is available, the device id and config are read, and the work proceeds without
  deferral

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
