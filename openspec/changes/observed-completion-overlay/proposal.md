## Why

On iOS the system marks each background upload **succeeded** continuously as bytes land, but the
ledger only advances when the OS coarsely invokes the upload extension's `process()` — so the status
screen freezes at "X in progress" and then jumps straight to "completed" when the extension finally
runs and records `COMPLETED` in a batch. The user sees no progress. An on-device spike confirmed the
main app process can read the system's succeeded-but-unacknowledged jobs in real time
(`PHAssetResourceUploadJob.fetchJobsWithAction(.acknowledge)` returns them; the count climbed 1→2→4
then dropped to 0 only when the extension acked), and that this read is side-effect-free. So the app
can surface live progress between the extension's coarse runs without changing who writes the ledger.

## What Changes

- The app projects a **read-side overlay**: a pending photo is shown complete as soon as every one of
  its still-outstanding resources is observed as a succeeded upload job — advancing both the "n of N"
  count and the "in progress" caption, and reaching the terminal COMPLETE state, before the extension
  records anything. The **ledger stays single-writer** (the extension); this is a projection only.
- A new `ObservedCompletionsSource` seam yields the set of succeeded resource keys; the iOS impl reads
  PhotoKit upload jobs from the **app** process (read-only — never acknowledges, so the extension stays
  the sole writer). A no-op impl on desktop makes the overlay a no-op there.
- The status container **polls** the source on status-screen foreground and re-polls while foreground
  AND pending > 0, stopping when drained or backgrounded (polling is the only way to observe job
  success between the extension's coarse runs — there is no notification).
- The ledger's read face gains a consistent **`snapshot`** (completed + newestCompletionAt as SQL
  scalars, plus the pending rows grouped by photo) so the overlay can intersect the backlog with the
  observed keys. The watcher's public surface moves from the `aggregates` flow to `snapshot`; the
  scalar `aggregates()` read is kept and reused for `completed`/`newestCompletionAt`.
- A **sticky** retention rule keeps an observed key in effect until the ledger snapshot confirms it
  `COMPLETED`, so the handoff from observed→recorded never blinks backward.
- The cross-process ledger **ding is coalesced to once per extension `process()` cycle** (instead of
  per row write), because each app-side read is now heavier (it materializes the pending rows).
- The temporary on-device spike (`SnapSyncRoot.probeUploadJobs` + the `iOSApp.swift` scenePhase hook)
  is replaced by the real source and a foreground signal.

## Capabilities

### New Capabilities
- `observed-completion-overlay`: the `ObservedCompletionsSource` seam (succeeded resource keys +
  `refresh()`), the foreground-and-pending-gated refresh cadence, the sticky-retention operator, and
  the pure overlay merge that promotes a pending photo to complete once all its outstanding resources
  are observed. Lives in `:domain:status`; pure logic is tested in `commonTest`.

### Modified Capabilities
- `sync-ledger`: `LedgerWatcher` exposes a point-in-time `snapshot` (completed, newestCompletionAt,
  pendingByAsset) in place of the `aggregates` flow; the backend gains a pending-resource read; the
  cross-process Darwin ding is posted **once per writer `process()` cycle**, not after every `put`.
- `sync-status`: `LedgerSyncStatusSource` consumes the `snapshot` and the (sticky) observed key set and
  applies the overlay before minting `SyncProgress`.
- `ios-background-upload`: the app process reads `PHAssetResourceUploadJob` succeeded jobs (the iOS
  `ObservedCompletionsSource`), and the extension posts the cross-process ledger ding once after
  `cycle.run()`.
- `ios-app-shell`: the SwiftUI scene's foreground transition feeds an injected foreground signal into
  the status stack (replacing the spike).

## Impact

- **Modules**: `:domain:engine` (watcher snapshot, `selectPending`, trim aggregates, Darwin ding),
  `:domain:status` (seam + sticky + overlay + source wiring), `:domain:presentation` (poll driver in
  `StatusContainerHost`), `:app:ios` (PhotoKit fetch impl + scenePhase wiring, remove spike),
  `:app:desktop` (no-op source + always-foreground when constructing the container).
- **Behavior**: progress advances live and COMPLETE can be reached from observation before any ledger
  write. `state == COMPLETE && lastFinishedAt == null` is already tolerated by the container (renders
  "just now"). No backend/edge endpoint and no device storage credentials are added; the device makes
  no new network calls (observation is local PhotoKit state).
- **Non-goals**: no multi-writer ledger, no S3/edge LIST endpoint, no change to upload mechanics.
