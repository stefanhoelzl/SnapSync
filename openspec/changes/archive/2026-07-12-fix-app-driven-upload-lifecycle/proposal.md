## Why

On iOS 18–26.0 — the tier **every current user runs** — joining or switching an event with photo access
already granted **tears the upload arm down and starts nothing**. `SnapSyncRoot.provisionEvent` calls
`enableBackgroundUpload()` with no tier branch; on the app-driven tier that resolves to
`UrlSessionUploadController.leave()` — cancelling in-flight transfers, cancelling the `BGProcessingTask`
heartbeat, and wiping **both** the ledger and the discovery cursor — after which
`setUploadExtensionEnabled(true)` is a no-op below 26.1. Nothing runs a cycle. The user sees *"I joined and
nothing uploaded"*; on a switch they also see status regress to 0-of-N while the whole post-cutoff library
re-uploads.

Underneath sit two contract failures, both dating to `2026-07-04-add-url-session-upload`:

1. **`event-rejoin-reconciliation` is written extension-only** (*"**The extension** SHALL run a join
   reconciliation…"*). It was never generalized when a second tier arrived, so the app-driven tier
   reconciles **nothing**. Reinstall → rejoin and leave → rejoin therefore re-upload every resource already
   in the device's byte partition **today, with no other bug present** — and the root `CLAUDE.md` claim that
   *"re-provision no longer forces a fresh whole-library upload"* is simply false on this tier.

2. **The tier was specified against a stale premise** — an *event-scoped* ledger and byte store, which had
   been replaced by the **device-global** layout four days earlier (`2026-06-30-dedup-files-device-manifests`).
   That one false premise produced two incoherent bullets that the code faithfully implements: *"cancel
   in-flight tasks **for the old event**"* (the byte URL is `/files/devices/<deviceId>/<file>` — there are no
   per-event tasks; cancelling re-uploads identical bytes to an identical URL) and *"leave **clears the
   ledger**"* (which directly contradicts `event-rejoin-reconciliation`, where leave **keeps** the ledger
   because it is device-global and valid across events).

The root cause is structural: **no capability owns the upload lifecycle.** It is smeared across two tier
specs and `SnapSyncRoot.kt` — the module the project's own hard rule declares *"wiring-only and untested"*.
No test references `SnapSyncRoot` or `UrlSessionUploadController`, and the original change's on-device
lifecycle verification (task 7.3, *"re-provision against a fresh event… and leave"*) was **archived
unchecked**. A third tier (the iOS 27 async protocol) is already anticipated; on the current structure it
will break the same way.

## What Changes

- **New capability `upload-lifecycle`** — names the thing that had no owner. A tier-neutral `UploadProducer`
  seam with exactly **two** verbs, `start()` and `stop()`, and a tested orchestrator in `:capability:upload`
  deciding which verb fires on provision / permission-grant / leave. **There is no destructive verb.**
  `UrlSessionUploadController.leave()` is **deleted**, not moved: the leave-on-provision edge becomes
  *unrepresentable* rather than merely fixed. Exactly one producer is constructed per process, so the two
  tiers cannot both be live.
- **Reconciliation becomes tier-neutral and compiler-mandatory.** `event-rejoin-reconciliation` is
  generalized from "the extension" to "the upload tier", and `reconcile` becomes a **required,
  non-defaulted** constructor parameter of `UploadCycle` — sitting beside `photoCutoff`, which the project
  already made non-defaulted for exactly this reason. A tier that forgets to reconcile no longer compiles.
- **Re-provision cancels nothing.** In-flight transfers target a device-global, event-independent URL and
  remain valid across a switch. The cycle re-reads config and its marker-gated reconcile does the rest.
- **BREAKING (contract):** **leave no longer clears the ledger or the discovery cursor** on any tier. The
  device-global ledger is dedup state that survives leave, switch, and rejoin. `LedgerBackend.clear()` loses
  its only production call site.
- **`enableBackgroundUpload()` / `disableExtension()` / `setUploadExtensionEnabled()` are dissolved** into
  the two producers. Tier selection collapses to a single `if` at composition.
- The app-driven producer's `start()` **arms the first `BGProcessingTask`** (today nothing ever does, so the
  heartbeat's cold-start kick never exists).
- The `SNAPSYNC_FORCE_URLSESSION_UPLOAD` dev flag stops meaning two things. It currently *also* downgrades
  the transport to a **foreground** session (contradicting this tier's own spec, which states a background
  `URLSession` runs in the simulator) and, on a ≥26.1 device, still fires `setUploadExtensionEnabled(true)`
  — enabling **both** tiers and two `LedgerWriter`s. Simulator detection is derived, not conflated. This is
  a **prerequisite for verification**: the XR is a tester's phone, so the SE2 is the only agent-driveable
  device, and today it cannot faithfully impersonate the app-driven tier.
- **The on-device lifecycle verification the original change never ran** is carried here, on both tiers
  (iPhone XR / 18.7.9 app-driven; iPhone SE2 / 26.5 PhotoKit-no-regression).

Out of scope: the download-side `SIGABRT` (`crash` workspace) and the event-switch download teardown
(`download-switch` workspace) — the latter is sequenced behind the former.

## Capabilities

### New Capabilities
- `upload-lifecycle`: the tier-neutral upload-arm lifecycle — the `UploadProducer` seam (`start`/`stop`, no
  destructive verb), which verb fires on provision / permission-grant / leave / direction change, the
  single-producer-per-process rule, and the invariant that **no lifecycle transition destroys durable dedup
  state**.

### Modified Capabilities
- `ios-url-session-upload`: "App-driven lifecycle" rewritten — re-provision cancels nothing and runs a
  cycle (no disable→enable toggle, no ledger wipe); leave stops transfers without wiping the ledger/cursor;
  enable arms the first `BGProcessingTask`; the transport is a background `URLSession` regardless of the
  tier-force flag.
- `event-rejoin-reconciliation`: generalized from **the extension** to **the upload tier** — the
  marker-gated reconcile runs in the shared `UploadCycle` on **both** tiers, before any upload job is
  created, and defers the cycle when the device listing fetch fails.
- `ios-photokit-upload`: "Re-provision resets sync state" scoped explicitly to **this** tier — the
  disable→enable toggle is this tier's producer `start()`, not universal host-app behavior.
- `sync-ledger`: `clear()` is **not** the leave or re-provision mechanism. The device-global ledger survives
  both; divergence from storage is repaired by `resetTo` in reconciliation.

## Impact

**Code**
- `:capability:upload` — new `UploadProducer` seam + tested lifecycle orchestrator; `UploadCycle` gains a
  required `reconcile` parameter (touches **both** tiers — the one genuinely shared edit).
- `app/ios/.../SnapSyncRoot.kt` — `provisionEvent`, `enableBackgroundUpload`, `disableExtension`,
  `setUploadExtensionEnabled`, `enableBackgroundUploadOnGrant`, the force flag, tier selection.
- `app/ios/.../UrlSessionUploadController.kt` — implements `UploadProducer`; `disable()` → `stop()`;
  `leave()` **deleted**; wires the reconciler (`:capability:membership` is already on its classpath).
- New `PhotoKitUploadProducer` in `:app:ios` absorbing the disable→enable toggle + `clearRequested` repair.
- `app/ios/photokit-extension/.../UploadExtensionRoot.kt` — reconcile moves from the root into the cycle.

**Tests** — the lifecycle orchestrator gets `commonTest` coverage (JVM + `iosSimulatorArm64`): provision
with GRANTED + upload-direction calls `start()` and **never** a destructive verb. This is the test that would
have caught the bug and could not exist before, because the logic lived in the untested app shell.

**Risk** — the shared `UploadCycle` edit and the new PhotoKit producer touch the **working** ≥26.1 tier.
Bought down by the two-device on-device verification, which is why the force-flag fix is in scope.

**Docs** — root `CLAUDE.md`'s re-provision claim needs a tier qualifier (it is true only on ≥26.1).
