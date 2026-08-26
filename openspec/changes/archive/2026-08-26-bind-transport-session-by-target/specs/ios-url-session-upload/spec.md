## RENAMED Requirements

- FROM: `### Requirement: The app-driven tier uses one transport on every host`
- TO: `### Requirement: The transport binding is fixed by the compilation target`

## MODIFIED Requirements

### Requirement: The transport binding is fixed by the compilation target

The app-driven tier's transport SHALL be bound per **compilation target**, never chosen at runtime.
`iosArm64` — every shipped binary — SHALL use a **background** `URLSession`
(`backgroundSessionConfigurationWithIdentifier`), unchanged in every respect including its session
identifier and its `discretionary` / `sessionSendsLaunchEvents` / `allowsCellularAccess` values.
`iosSimulatorArm64`, whose output only ever runs on a simulator, SHALL use a **default** session
configuration.

A device binary SHALL contain **no route** to the default binding, so there is no runtime discriminator
that could be taken wrongly on a device. There SHALL be **no host determination anywhere** in the
composition or the adapters: the process SHALL NOT read `SIMULATOR_DEVICE_NAME` (or any equivalent),
SHALL NOT inspect the running host, and SHALL NOT branch on it in any composition root — `:app:ios` is
wiring-only and decides nothing here. Both app-process transports (`IosDownloadTransport` and
`IosUrlSessionUploadPlatform`, the latter also serving `photo-download`) SHALL obtain their configuration
from **one** seam, so the two cannot diverge.

**Why the simulator cannot use the shipped binding.** A background `URLSession` does not transfer on an
iOS simulator for any third-party process. `nsurlsessiond` resolves each client's **bundle identifier** as
it evaluates the incoming XPC connection, and rejects a client that has none — which is every process an
app author can build there, **including a real installed app declaring a valid `CFBundleIdentifier`**. The
daemon states this as its reason, at error severity:

```
Evaluating new XPC connection … from pid <n> … with client bundle identifier (null)
Process with pid <n> does not have a bundle ID, rejecting connection
… invalidated … xpc_connection_cancel()
```

The client observes `NSCocoaErrorDomain` **4097** (`NSXPCConnectionInterrupted` — accepted, then torn
down; NOT `4099` `Invalid`, and NOT `4102` `CodeSigningRequirementFailure`), then *"failed to create a
background NSURLSessionDownloadTask, as remote session is unavailable"*, and every transfer ends
`NSURLErrorDomain / -1`. Apple's own simulator processes resolve to real bundle identifiers and their
background sessions work.

Measured 2026-08-25 on macOS 26.5.2 / Xcode 26.6, iOS 26.2 and 26.5, with a **foreground control
succeeding against the same URL in the same process**; three client shapes (a bare Kotlin/Native test
binary, an installed signed app, an installed unsigned app) all failed identically, and six candidate fixes
were tested — ad-hoc signature, an Apple Development identity, no signature at all,
`application-identifier`/`team-identifier`/`get-task-allow`, a second runtime, and any entitlement — none
of which works. ⏰ Re-measure at the next iOS major. Decision records:
`changes/archive/2026-08-25-correct-simulator-background-session-claims` (the refusal and its mechanism),
`changes/bind-transport-session-by-target` (this binding, and the quoted refusal line — which supersedes
that record's statement that the daemon "does not state that as its reason").

**What the simulator binding does NOT provide, and SHALL NOT be claimed to.** A default session runs
in-process and dies with it. It SHALL NOT be treated as evidence of any of:

- transfers continuing across app suspension or termination;
- the OS relaunching a terminated app to deliver `handleEventsForBackgroundURLSession` — device-only by
  vendor guidance (Quinn, *Testing Background Session Code*: "Test on a real device, not in Simulator";
  r. 16532261), and independently unmeasurable there since no transfer can outlive the process;
- reattachment to a prior process's tasks — `getAllTasks` can never find one;
- the behaviour of `__NSURLBackgroundSession`, including the invalidation defect
  (`changes/archive/2026-07-12-fix-download-session-lifecycle` D5). Measured 2026-08-25: after the daemon
  rejects and cancels the connection, the client session does **not** call `didBecomeInvalidWithError`
  (observed for ~10 s after the transfer settled, n=1), so that host never reaches the invalidation path
  the defect lives on.

Because a default session never sends `URLSessionDidFinishEventsForBackgroundURLSession`, a
`handleEventsForBackgroundURLSession` wake on that target holds its receipt to the deadline and expires
(`ios-app-shell`; `architecture-guards`, "OS completion handlers are held in one type"). That expiry SHALL
be **predicted rather than diagnosed**: the process SHALL state its binding and this consequence when the
session is constructed, so the expiry line is not read as a fault. Nothing SHALL synthesise the drain — a
transport that reported events drained without the OS having delivered any would make a simulator run
indistinguishable from a device one, which is exactly the false confidence
`fix-download-session-lifecycle` D5 refused.

The binding SHALL be **reportable**: the process SHALL expose which binding it holds, so a caller reads it
rather than inferring it from a log line or a stall.

This supersedes `changes/archive/2026-08-09-delete-simulator-session-downgrade` **D1** and
`changes/archive/2026-08-25-correct-simulator-background-session-claims` **D1**; neither archive is edited.
The first's ground — "with no behavioural difference between hosts there is no axis" — rested on a probe
that aimed at a closed port and read `NSURLErrorUnknown` as a connection refusal. The second declined this
binding on the ground that it "removes the only host that exercises `__NSURLBackgroundSession`", and left
the door open in terms ("If it is ever wanted, it is a separate change with its own proposal"); the
measurement above retires that ground, because the simulator never reaches that class's invalidation path.
What is superseded in both is the ground, never the refusal of a **runtime** host determination, which
this requirement restates unchanged.

Wherever the app-driven tier is selected on a device whose OS supports the OS-driven tier, the PhotoKit
upload extension SHALL NOT be registered (`upload-lifecycle`, "Exactly one producer per process"), so the two
tiers are never simultaneously live and the `sync-ledger` single-record-writer invariant holds.

#### Scenario: A shipped binary contains no route to the default binding

- **WHEN** the `iosArm64` binary is built
- **THEN** it contains only the background configuration, no runtime host check exists anywhere in the
  composition or the adapters, and no code path in it can yield a default session configuration

#### Scenario: The device binding is unchanged by the seam

- **WHEN** the app-driven tier runs on a physical device, before and after this seam is introduced
- **THEN** it creates a background `URLSession` with the same identifier and the same `discretionary`,
  `sessionSendsLaunchEvents` and `allowsCellularAccess` values in both cases

#### Scenario: Both transports share one binding

- **WHEN** the download transport and the app-driven upload platform each construct their session
- **THEN** both obtain the configuration from the same seam, so no build can hold a background binding for
  one and a default binding for the other

#### Scenario: A simulator transfers bytes, over a session that survives nothing

- **WHEN** the app-driven tier starts an upload, or the download transport starts a transfer, on an iOS
  simulator against a reachable server
- **THEN** the transfer completes over a default session, and the run is not treated as evidence of
  suspension survival, OS relaunch, task reattachment, or `__NSURLBackgroundSession` behaviour

#### Scenario: A background-events wake on a simulator expires, and says so in advance

- **WHEN** a `handleEventsForBackgroundURLSession` wake is driven on a simulator
- **THEN** the receipt is held to its deadline and expires because the session never reports its events
  drained, the process has already stated that this binding cannot report them, and no drain is synthesised

#### Scenario: The binding is readable, not inferred

- **WHEN** a caller asks a running process which transport binding it holds
- **THEN** it is answered directly, without reading a log line or waiting for a transfer to stall

#### Scenario: The app-driven tier does not enable the extension

- **WHEN** the app-driven tier is live on a device whose OS is ≥26.1 — because the photo grant is partial, or
  because a later runtime selection chose it
- **THEN** `setUploadJobExtensionEnabled(true)` is not called for that producer, only the app-driven producer
  is live, and exactly one process holds the `LedgerWriter`

### Requirement: Module placement and testing split

The app-driven adapters (`IosUrlSessionUploadPlatform`, `IosBackgroundScheduler`) SHALL live in the
app-only adapter module `:adapter:ios:app-only` — linked only by the main app process, never the
extension (before migration step 4 they lived in `:app:ios:url-session-upload`, deleted by that
step) — depending on the extension-safe adapter module `:adapter:ios:ext-safe` for the shared
`IosDiscovery` walk. The
`BackgroundUploadPump` and `BackgroundScheduler` pump logic SHALL live in `:domain` — the pump in
`feature/upload`, the scheduler seam in `ports/` (seated by migration step 5; formerly
`:capability:upload`) — `jvm()`-enabled and harness-covered. The pump and scheduler logic SHALL be
tested on JVM and
`iosSimulatorArm64`; the `URLSession` adapter SHALL be faked in the harness (like the PhotoKit
adapter). The transport MAY be exercised end-to-end on a simulator, over that target's **default**
session binding (see "The transport binding is fixed by the compilation target") — which evidences the
request, the delegate, the staging move and the outcome path, and evidences **none** of the
background-session properties that requirement enumerates. `BGProcessingTask` **timing** and true-suspend
behaviour remain device-only.

#### Scenario: Pump lives in the platform-free core
- **WHEN** the modules are assembled
- **THEN** `BackgroundUploadPump` is in `:domain` `feature/upload`, and the iOS adapters are in `:adapter:ios:app-only`, which composes `:adapter:ios:ext-safe`; the pump and the `uploadCore`-assembled cycle are composed with the adapters in the app's composition root, not by the adapter module

#### Scenario: A simulator end-to-end run is scoped to what it shows
- **WHEN** the transport is exercised end-to-end on a simulator
- **THEN** the bytes move and the outcome path is exercised, and the run is recorded as evidencing neither
  suspension survival nor OS relaunch
