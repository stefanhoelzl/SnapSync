# Design — foreground poll · Swift transcriber · ProtectedData never created

## Context

Migration step 12. Inputs: the settled device-session forcing proofs (recorded as ground truth for
this step):

- **①** `PHBackgroundResourceUploadProcessingResult` is **Swift-only** — declared in the SDK's
  swiftinterface with no ObjC header — so its construction cannot leave the Swift shell; but it is
  `RawRepresentable` over `Int`, with cases in swiftinterface order `failure`, `processing`,
  `completed`. The raw values used here (0/1/2) are **derived from that case order**; Session D
  verifies them against the SDK on device before merge.
- **②** the 26.1 extension *protocol* carries no deprecation (only its creation method is
  deprecated at 26.4). This change adopts nothing from 26.4 (re-eval ~Sept 2026) and writes no
  prose calling the protocol deprecated.
- **④** zero `deferring` / `running deferred` lines across all production logs: the
  `ProtectedDataGate` defer-queue never fired. Every observed background wake found protected data
  available.

## Goals / Non-Goals

- Goals: kill the Darwin ding for a poll with a declared staleness bound; delete the ProtectedData
  port and its module; reduce Swift to a transcriber with ≤1 pinned decision; keep every other
  production semantic byte-compatible.
- Non-Goals: the iOS 27 async extension protocol; MainViewController's transient-error
  choreography (presentation-side, orthogonal — left for a later step; see D6); any change to the
  three-state `ConfigReader` semantics (unreadable-is-not-absent stands untouched).

## Decisions

### D1 — The poll seats in `feature/status`; the flows own only its lifecycle

The cadence is a **rule** (how stale a foregrounded screen may get — the `sync-status` latency
bound), so it lives in the tested feature (`LedgerCountsPoller`, cadence 2 s, first tick after one
full cadence since foreground entry already refreshes). Start/stop is **order**, so the Foreground
flow starts it and the Background flow stops it — flows may reference features directly; no port
is touched (the poller drives `LedgerCountsSource`, its own feature's read-model). The poll is
tier-neutral: on the app-driven tier it is redundant beside the pump's in-process refresh but
harmless (one cheap read), and a tier conditional would be exactly the enumerated-invokers shape
the laws forbid. `start()` is idempotent-while-live (repeated foreground entries — the property
the old observer's defensive re-register held).

Rejected: a compose-installed subscription (no lifecycle to couple to without inventing one); a
shell-resident timer (the cadence is a rule, and the shell is untested).

### D2 — The unlock-repair is replaced by a trigger-time re-read, not by a slimmer unlock observer

`reloadConfigOnUnlock` existed to repair a config StateFlow seeded from an unreadable
pre-first-unlock read. Analysis of who reads the StateFlow stale:

- The **cycle gates** (both tiers) read fresh per cycle (`ConfigReader.read()`, port-pure — step 7
  D1); they never depended on the StateFlow repair.
- The **UI** cannot render before first unlock (foregrounding requires an unlocked device), so the
  Foreground flow's re-read always runs before a human can see stale state.
- The **receivers' guards** (push/backstop) read the StateFlow — so those flows re-read it first.

The trigger-time re-read (`AppPorts.reloadConfig`, first step of Foreground/SilentPush/
DownloadBackstop) covers every one of these windows **plus** cross-process staleness (the other
process re-provisioned; the unlock notification never signalled that), at the cost of one small
file read per trigger. A minimal NSNotification unlock observer was rejected: it keeps a platform
observer alive for a window ④ shows never occurs, and it repairs strictly less.

Consequence at the never-observed pre-first-unlock wake itself: the work **runs and fails
cleanly** instead of deferring — the adapters already distinguish unreadable from absent
(`ConfigRead.Unavailable`, `KeychainUnavailable`), so nothing mints, clears, or leaves (the
build-297 failure class stays dead at the adapter layer, where it was actually fixed), and the
wake's work converges at the next trigger. What is genuinely given up is defer-and-*resume*: work
landing in that window waits for the next trigger instead of running at the unlock instant. ④
prices that at zero observed occurrences.

### D3 — `reload()` retains the last good value on an unreadable read

New at this cadence: the old reload ran only at the unlock instant (readable by construction); the
new one runs per trigger, where a transient read failure is possible. `configAfterReload`
(`ports/`, pure, tested): Joined/None replace, Unavailable retains — the same keep-last-good
posture as `ReadingLedgerCountsSource`. Without it a transient failure at foreground entry would
clear a good membership and flip the screen to the setup gate.

### D4 — ① resolution: Kotlin decides the raw Int, Swift constructs

`CycleResult.processingResultRawValue()` seats in `ports/` beside `CycleResult` (an exhaustive
`when` — a future case stops compiling instead of slipping through a Swift `default:`), pinned by
commonTest at failure=0 / processing=1 / completed=2 (SKIPPED→2, unchanged posture). The Swift
shell forwards it into `init?(rawValue:)` with `?? .failure` — the nil fallback keeps an untaught
raw value a visible retried failure, exactly the old `default:` posture. This is the **one**
remaining Swift pin; `SwiftShellGuardTest` now counts `??` (the `architecture-guards` spec always
listed it among the guarded keywords — the test had drifted below the spec).

The push completion's `UIBackgroundFetchResult` nuance is **dropped** rather than re-created as a
second construction site: Kotlin's flow always completes, and Swift always reports `.newData`.
The old `.noData` fired only for a payload without `eventId` — a case our backend never sends —
and preserving it would cost either a Swift decision or a second rawValue construction. Recorded
as a (vanishingly small) behavior change; Session D includes a malformed-push check.

### D5 — Lifecycle observation moves to Kotlin (`onLaunch` installs NSNotificationCenter observers)

`UIApplicationDidBecomeActiveNotification` ↔ the scene reaching `.active`;
`UIApplicationWillResignActiveNotification` ↔ leaving it — including the transient `.inactive`
cases (app switcher, incoming call) the old SwiftUI `onChange` also routed to `onBackground`.
Installed from `didFinishLaunchingWithOptions` via a plain `SnapSyncRoot.onLaunch()` call (not
lazily from first view creation: a deterministic install point that precedes the first
`didBecomeActive` on every launch shape, including background launches, which install and simply
never hear `didBecomeActive`). Session D verifies the transition pairs on device.

### D6 — MainViewController's transient-error choreography is NOT absorbed here

It is Kotlin, not Swift; presentation-side (a `UiState`/side-effect concern); and orthogonal to
this step's three sanctioned changes. It stays a counted shell decision (the step-8 survivor
list's standing item) for 13b or a small dedicated change.

### D7 — The delivery-seam guard follows the transcriber

`EventLinkDeliveryTest` now asserts the scene delegate forwards the **whole** `NSUserActivity` to
`SnapSyncRoot.onUserActivity` (the raw-`absoluteString` completeness moves into the tested
`model/` codec `eventLinkFromUserActivity` — pinned including the `NSUserActivityTypeBrowsingWeb`
ABI string — and its continuation-passing form `forwardEventLink`, which exists so the shell's
`onUserActivity` is a straight line: the detekt shell gate counts even a `?.let`, and it is a
decision). Structure assertions (scene delegate installed, cold + warm halves) are unchanged.
