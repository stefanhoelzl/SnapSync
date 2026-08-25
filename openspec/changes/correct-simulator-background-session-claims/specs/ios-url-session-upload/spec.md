## MODIFIED Requirements

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
adapter). The transport SHALL NOT be exercised end-to-end on a simulator: no background `URLSession`
transfer runs on that host (see "The app-driven tier uses one transport on every host"), so an
end-to-end attempt there measures nothing. `BGProcessingTask` **timing** remains device-only.

#### Scenario: Pump lives in the platform-free core
- **WHEN** the modules are assembled
- **THEN** `BackgroundUploadPump` is in `:domain` `feature/upload`, and the iOS adapters are in `:adapter:ios:app-only`, which composes `:adapter:ios:ext-safe`; the pump and the `uploadCore`-assembled cycle are composed with the adapters in the app's composition root, not by the adapter module

### Requirement: The app-driven tier uses one transport on every host

The app-driven tier SHALL use **one** transport on every host: uploads SHALL run over a background
`URLSession` regardless of the host the process runs on.

There SHALL be no host determination anywhere in the composition or the adapters: the process SHALL NOT read
`SIMULATOR_DEVICE_NAME` (or any equivalent), and no simulator-specific session configuration SHALL exist.

**The hosts are NOT equivalent, and this requirement holds in spite of that.** A background `URLSession`
does not transfer on an iOS simulator for any third-party process. `nsurlsessiond` resolves each client's
**bundle identifier** as it evaluates the incoming XPC connection, and drops the connection when that
identifier is `(null)` — which it is for every process an app author can build there, **including a real
installed app declaring a valid `CFBundleIdentifier`**. The client observes `NSCocoaErrorDomain` **4097**
(`NSXPCConnectionInterrupted` — accepted, then torn down; NOT `4099` `Invalid`, and NOT `4102`
`CodeSigningRequirementFailure`), then *"failed to create a background NSURLSessionDownloadTask, as remote
session is unavailable"*, and every transfer ends `NSURLErrorDomain / -1`. Apple's own simulator processes
resolve to real bundle identifiers and their background sessions work.

Measured 2026-08-25 on macOS 26.5.2 / Xcode 26.6, iOS 26.2 and 26.5, with a **foreground control
succeeding against the same URL in the same process**; three client shapes (a bare Kotlin/Native test
binary, an installed signed app, an installed unsigned app) all failed identically, and six candidate fixes
were tested — ad-hoc signature, an Apple Development identity, no signature at all,
`application-identifier`/`team-identifier`/`get-task-allow`, a second runtime, and any entitlement — none
of which works. The daemon logs `(null)` and then drops the connection; it does not state that as its
reason, so the causal link is a correlation across those clients rather than an explicit refusal message.
⏰ Re-measure at the next iOS major. Decision record:
`changes/correct-simulator-background-session-claims`.

This requirement therefore rests on a **choice**, not on host equivalence: a simulator-only foreground
downgrade would make that host appear to work while removing the only host that exercises
`__NSURLBackgroundSession`, the class `fix-download-session-lifecycle` D5's defect lives in. This
supersedes `changes/archive/2026-08-09-delete-simulator-session-downgrade` **D1**, whose stated ground —
"with no behavioural difference between hosts there is no axis" — rested on a probe that aimed at a closed
port and read `NSURLErrorUnknown` as a connection refusal; that archive is not edited.

**Whether the OS relaunches a terminated app to deliver `handleEventsForBackgroundURLSession` on a
simulator remains unproven, and is now UNMEASURABLE on that host** — it requires a background transfer that
outlives the process, and none can exist there.

Wherever the app-driven tier is selected on a device whose OS supports the OS-driven tier, the PhotoKit
upload extension SHALL NOT be registered (`upload-lifecycle`, "Exactly one producer per process"), so the two
tiers are never simultaneously live and the `sync-ledger` single-record-writer invariant holds.

#### Scenario: The transport does not vary by host

- **WHEN** the app-driven tier runs on an iOS simulator
- **THEN** it creates the same background `URLSession` it creates on a physical device, and no code path
  selects a foreground session for any host

#### Scenario: A simulator transfers no bytes, and that is the host

- **WHEN** the app-driven tier starts an upload on an iOS simulator
- **THEN** the task fails with `NSURLErrorDomain / -1` and nothing is transferred, and this is recorded as
  a measured limitation of that host rather than diagnosed as a defect in the tier

#### Scenario: The app-driven tier does not enable the extension

- **WHEN** the app-driven tier is live on a device whose OS is ≥26.1 — because the photo grant is partial, or
  because a later runtime selection chose it
- **THEN** `setUploadJobExtensionEnabled(true)` is not called for that producer, only the app-driven producer
  is live, and exactly one process holds the `LedgerWriter`
