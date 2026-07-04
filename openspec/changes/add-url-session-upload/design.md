## Context

SnapSync's only upload path is PhotoKit's OS-driven background-upload subsystem
(`PHBackgroundResourceUploadExtension`, iOS 26.1+). The `sync-refactor` work already made the upload
**orchestration** platform-free: `UploadCycle` + the `UploadJobPlatform` seam live in the
`jvm()`-enabled `:capability:upload`, with the PhotoKit adapter (`IosUploadJobPlatform`) as one
implementation and a JVM `FakePlatform` (20 `UploadCycleTest` cases) as another. The engine + SQL
ledger are the single source of upload truth.

The host app already installs on **iOS 18** (app targets deploy to `18.0`; only the extension target
is `26.1`). On an iOS 18–26.0 device the app joins events and shows status but cannot upload — the
`backgroundUploadSupported()` guard (`isOperatingSystemAtLeastVersion(26,1,0)`) disables the extension
path with nothing behind it. A real sub-26 device needs to back up photos.

The pivotal realization: a background `URLSession` is structurally the **same kind of thing** as the
PhotoKit OS-job queue — a cross-process durable queue that redelivers results in a later process. So
the app-driven tier is *another `UploadJobPlatform` implementation*, not a new seam, and `UploadCycle`
is reused unchanged. This matches `sync-refactor.md` §1/§8, which kept the wide seam and explicitly
**rejected** a narrow seam that hides the job lifecycle (it regresses harness coverage).

## Goals / Non-Goals

**Goals:**
- Back up photos on iOS 18–26.0 over a background `URLSession` + `BGProcessingTask`, selected per OS
  version, reusing the engine, ledger, discovery, status, reconcile, and edge contract unchanged.
- Reuse `UploadCycle` and the `UploadJobPlatform` seam (second real implementation) so the app-driven
  path inherits the existing JVM/simulator orchestration test coverage.
- Preserve the ledger single-record-writer invariant on both tiers (the writer's *process* flips).
- Align the declared minimum iOS with reality (18, two tiers) and rename the capability by mechanism.

**Non-Goals:**
- No change to the iOS ≥26.1 PhotoKit path's runtime behavior (only a rename + shared-discovery
  recomposition).
- No new upload seam, no engine/ledger schema change, no change to the edge/bunny contract.
- No exactly-once guarantee (deterministic keys + idempotent PUT make at-least-once sufficient).
- Not lowering any code deployment target (already 18.0) — only the *declared/documented* floor and
  the docs.

## Decisions

### D1 — Reuse `UploadJobPlatform`, do not add a seam
The background `URLSession` maps onto the existing verbs without fabrication: `createJob` starts a
`uploadTask(fromFile:)`; the adapter's own cap surfaces as `LIMIT_EXCEEDED`; `fetchAckJobs` drains
delegate completions; `fetchRetryJobs` returns **empty** (no OS free retry — a capability the seam
already tolerates); `acknowledge` becomes real temp-file cleanup; `retryJob` is cancel+recreate.
*Alternative rejected:* a new `BackgroundUploadExecutor` sibling seam — it re-introduces exactly the
narrow seam `sync-refactor.md` §8 rejected, and would fork `UploadCycle`, losing shared test coverage.

### D2 — Correctness by at-least-once + ledger-as-truth
Deterministic per-resource keys and an idempotent edge PUT mean a duplicate send overwrites the same
object harmlessly. The ledger is the only durable state; the `URLSession` task list is a transient
executor reconciled by `taskDescription == key`. *Alternative rejected:* strict exactly-once tracking
— more bookkeeping and crash-window edge cases for a property idempotency already provides.

### D3 — The pump reimplements the OS scheduler; `BackgroundScheduler` is a seam
On ≥26.1 the OS invokes `process()` and re-invokes on `PROCESSING`. On <26.1 a `BackgroundUploadPump`
(in `:capability:upload`, platform-free) drives `UploadCycle.run()` from four triggers (foreground,
`BGProcessingTask`, session relaunch, per-completion), single-flight with a trailing re-run, re-arming
on `PROCESSING`. Re-arm scheduling is a `BackgroundScheduler` seam so the logic is JVM/sim-testable
against a fake. *Alternative rejected:* putting scheduling logic in the Swift shell — it would be
untested; only the irreducible `BGTaskScheduler`/delegate wiring stays in the shell.

### D4 — Two background engines: relaunch drain + heartbeat
The self-sustaining drain is the **relaunch ping-pong** (`handleEventsForBackgroundURLSession`), not
`BGProcessingTask`. `BGProcessingTask`'s only unique role is the cold-start / new-photo kick when the
session is idle. Policy: `requiresNetworkConnectivity = true`, `requiresExternalPower = false` (a
background-backup people run intentionally; the heartbeat should be frequent enough to catch new
captures). *Alternative rejected:* charging-required (stalls the first whole-library pass for days);
skip `BGProcessingTask` (no trigger for new photos when the session is idle).

### D5 — Precise reconciliation replaces `clearRequested` on <26.1
`clearRequested` exists only because the OS-job API cannot enumerate live jobs and disable wipes them
silently → blanket unstick. A background `URLSession` **can** enumerate (`getAllTasks`), so stranded
`REQUESTED` rows are reconciled precisely (no live task + not stored → surface as failed → recreate).
The blanket `clearRequested`, the disable→enable toggle, and the cross-process WAL-contention hazard
(the `sync-refactor` pre-task-1 bug) simply cannot occur when a single process owns the ledger.

### D6 — Single-record-writer invariant generalized; writer process flips
The invariant is "exactly one record-writer; process placement is a platform binding." ≥26.1: the
extension writes. <26.1: the app writes (no extension). The `sync-ledger` spec is generalized (and the
extension-specific `clearRequested` narrative demoted to one PhotoKit-tier example); the process
binding is stated per tier in `ios-photokit-upload` / `ios-url-session-upload`.

### D7 — Per-slot temp staging in the App-Group container
Background `URLSession` uploads from a file. Materialize a resource's temp file only when a slot frees
(bounded to ~cap), in the App-Group container; delete on completion; orphan-sweep on launch. This
re-introduces the temp-file handling §3.4 said the OS "dissolves" — bounded and iOS-side. *Alternative
rejected:* pre-staging a batch or the whole library (large/unbounded temp-file disk).

### D8 — Rename by mechanism; symmetric spec pair
`ios-background-upload → ios-photokit-upload`, new `ios-url-session-upload` — mirroring the platform
classes (`IosPhotoKitUploadPlatform` / `IosUrlSessionUploadPlatform`). Both tiers *are* background
upload, so the old name was the ambiguous one. **Module names stay asymmetric on purpose:**
`:app:ios:photokit-extension` keeps "extension" because it is the only one that is a separate appex
build target; the app-driven code is main-app-composed.

### D9 — Module topology
`:capability:upload` gains `BackgroundScheduler` + `BackgroundUploadPump` (unused on ≥26.1; modules
aren't per-OS, composition roots select). PhotoKit stays out of `:capability:upload`, so the shared
`IosDiscovery` (change-token walk + request builder + token archiver, used by both adapters — chosen
as a shared **object**, not inheritance) goes in a new iosMain-only `:app:ios:photokit-discovery`. The
app-driven adapters go in a new main-app-composed `:app:ios:url-session-upload`. *Alternatives
rejected:* fold adapters into `:app:ios` (breaks "wiring-only"); PhotoKit into `:capability:upload`
(breaks "orchestration is platform-free"); duplicate the discovery code (rejected in favor of sharing).

## Risks / Trade-offs

- **[Embed-on-18 gate — highest]** A 26.1 appex is embedded in an app installable on iOS 18; if iOS
  refuses to install/launch the host because of the higher-min embedded extension, the whole plan
  shifts. → **Prove on the real sub-26 device first, before building the adapter.** Mitigation if it
  blocks: conditional/weak embedding of the appex.
- **[First whole-library backup is slow on old iOS]** Background windows are opportunistic. → The
  relaunch ping-pong + a network-gated (not power-gated) heartbeat maximize throughput; foreground use
  drains fast. No UI change (per decision) — the storage-listing reconcile converges status on both
  tiers.
- **[OS can drop background transfers]** User force-quit cancels transfers and iOS won't relaunch. →
  Precise `getAllTasks` reconciliation on next launch recreates lost rows (at-least-once, idempotent).
- **[Pump concurrency]** Multiple triggers could run `UploadCycle` in parallel against the
  single-writer ledger. → Single-flight pump with trailing re-run.
- **[Delta model cannot express a capability rename]** → Author the MODIFIED delta under the current
  `ios-background-upload` folder so it validates now; an implementation task renames the base **and**
  the delta folder to `ios-photokit-upload` together (updating the two cross-references in
  `deeplink-config`/`gallery-status`) before archive, keeping delta-dir and base-dir names consistent.

## Migration Plan

Behavior-preserving and additive; sequenced as independent PRs (tests-first, mirroring
`sync-refactor.md`):
1. Spec/doc: `sync-ledger` generalization + `ios-background-upload` "≥26.1" qualifiers + the
   capability rename to `ios-photokit-upload` (git mv base + delta folders, update two cross-refs).
2. `:capability:upload`: `BackgroundScheduler` seam + `BackgroundUploadPump` (JVM-tested against fakes;
   no iOS yet).
3. `:app:ios:photokit-discovery`: extract shared `IosDiscovery`; rename adapter to
   `IosPhotoKitUploadPlatform`; recompose `:app:ios:photokit-extension` (behavior-preserving).
4. `:app:ios:url-session-upload`: `IosUrlSessionUploadPlatform` + `IosBackgroundScheduler`.
5. `:app:ios`: tier branch at `backgroundUploadSupported()`; thin Swift wiring (BGTask registration,
   `URLSession` delegate, `handleEventsForBackgroundURLSession`); `Info.plist`
   `BGTaskSchedulerPermittedIdentifiers` + `processing` background mode.
6. On-device: the **embed-on-18 gate proven first** (front-runs 4–5), then real-drain verification on
   the sub-26 device.

**Rollback:** the tier is gated by `backgroundUploadSupported()`; reverting the composition-root branch
disables the app-driven path with no effect on the ≥26.1 tier.

## Open Questions

- Exact concurrency cap for the background `URLSession` (working assumption ~4) — tune on device
  against temp-file disk pressure and throughput.
- `BGProcessingTask` earliest-begin cadence for the heartbeat — tune against how promptly new captures
  should upload when the app stays closed.
- Whether an in-foreground PhotoKit-change observer should also trigger the pump (nice-to-have for
  immediate upload of a just-taken photo while foregrounded).
