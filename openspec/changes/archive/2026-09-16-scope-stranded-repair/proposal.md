## Why

Both recoveries for a `REQUESTED` row whose transfer was lost are **ledger-wide**, and each silently assumes
it is the only transport that could be carrying such a row. The app-driven cycle records `FAILED` every
`REQUESTED` row its `URLSession` holds no task for; the PhotoKit re-register deletes every `REQUESTED` row
and resets the shared discovery cursor. Today that is true, because exactly one mechanism runs. It stops
being true the moment the two run side by side over the one App-Group ledger (the "tierless" direction):
the app's pass would declare the extension's whole live queue lost, and the PhotoKit restart would delete
the app's in-flight rows. The ownership has to come from facts each transport already has, because the
ledger deliberately carries no owner column and PhotoKit's pending jobs cannot be enumerated from the app.

This change makes each recovery scoped to what it can actually know, and does so while one mechanism still
runs, so the behavior change is observable in isolation. It merges what was planned as two phases (the
per-cycle scope and the restart-side repair): scoping the per-cycle pass alone would abandon the rows a
hand-off from PhotoKit to the app-driven mechanism leaves `REQUESTED`, which today only that ledger-wide
pass recovers.

## What Changes

- **The per-cycle stranded pass is scoped to the transport's own lost transfers.** The cycle records
  `FAILED` only `REQUESTED` rows the transport reports as **lost** — begun by it and no longer held — instead
  of every `REQUESTED` row with no live transfer. On the app-driven transport a transfer is "begun" while its
  staged file exists, so a row it never staged (a PhotoKit job) is never a candidate.
- **A restart repair runs at every mechanism start.** A mechanism's `start()` demotes to `FAILED` every
  `REQUESTED` row that no transfer can still settle: on the app-driven mechanism, every `REQUESTED` row
  without a live task (consumed by the next cycle); on the PhotoKit mechanism, every `REQUESTED` row, between
  the disable and the enable. This covers the hand-off from PhotoKit, a missed cancellation completion, and
  rows stranded before this change shipped.
- **`clearRequested` is replaced by `demoteRequested`.** The reset-family bulk operation marks `REQUESTED`
  rows `FAILED` instead of deleting them. **BREAKING** (internal seam): `LedgerStore.clearRequested()` is
  removed.
- **The PhotoKit repair no longer resets the discovery cursor.** `FAILED` rows are returned by the ledger's
  work read without a walk, so the reset the delete needed is gone. `stop()` becomes the disable alone, and
  the narrow `deregister()` hand-off verb is no longer distinct from it.
- **The cursor carve-out in `upload-lifecycle` is removed**; no mechanism clears its discovery cursor on
  `stop()` any more.
- **The staging orphan sweep folds into the cycle.** The transport reports lost transfers and the cycle
  discards them after its pass, once none of their rows can still be `REQUESTED`. `sweepStaging()` at
  `start()` is removed — it deleted the very marker the scoped pass reads.
- **`BackgroundTransfer` gains `lostKeys()` and `discard(keys)`**; `liveKeys()` stays. Transports that cannot
  enumerate (PhotoKit, the simulator substitute, the world fake) answer `null` / do nothing.
- Not changed: the pass still runs every cycle, still reads `REQUESTED` rows only, still writes through the
  guarded write, and still consults no storage listing. No user-visible behavior changes on today's
  single-mechanism app beyond rare, accepted duplicate uploads.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `ios-url-session-upload`: the stranded reconciliation's candidate set (lost, not merely not-live); a
  restart repair at `start()`; the transport reports lost transfers and discards on the cycle's instruction;
  the orphan sweep leaves `start()` and folds into the cycle's pass.
- `ios-photokit-upload`: the disable repair demotes `REQUESTED` rows instead of clearing them and resets no
  cursor; the hand-off deregister and `stop()` become the same disable; the transport answers no lost set.
- `sync-ledger`: `clearRequested()` is replaced by the reset-family `demoteRequested()`; the cursor paragraph
  of "Lifecycle transitions never clear the ledger" loses its tier-repair clause.
- `upload-lifecycle`: the discovery-cursor carve-out on `stop()` and the "repair does not fire when the
  incoming mechanism reconciles precisely" scenario are removed; relinquishing the OS-driven mechanism no
  longer needs a verb narrower than its `stop()`.
- `harness-world-model`: the world's job-queue double answers no lost set, as it answers no live set.

## Impact

- `:domain` `ports/` — `BackgroundTransfer` (`lostKeys`, `discard`), `LedgerStore` (`demoteRequested`
  replaces `clearRequested`).
- `:domain` `feature/upload` — `UploadCycle` (scoped pass, restart flag, discard), `StrandedKeys`,
  `OsDrivenUploadMechanism` (repair between disable and enable; `stop()` = disable),
  `ClearRequested.kt` → the demote helper, `UploadMechanismTable`/`RelinquishThenRun` binding if
  `deregister()` collapses into `stop()`.
- `:domain` `compose/` — the relinquish binding (`relinquishOsRegistration`).
- `:adapter:generic:app` — SQLDelight `demoteRequested` statement replacing `deleteRequested`.
- `:adapter:generic:fake` — `InMemoryLedgerStore`.
- `:adapter:ios:app-only` — `IosUrlSessionUploadPlatform` (`lostKeys`, `discard`; `sweepStaging` removed).
- `:adapter:ios:ext-safe` — `IosPhotoKitUploadPlatform` and the simulator `UploadJobQueue` answer `null`.
- `:app:ios` — `UrlSessionUploadController.start()` signals the restart instead of sweeping.
- `:test:world` — ledger contract (`demoteRequested`), world upload fakes.
- `architecture/` — regenerated diagrams where the port surface changed.
- Out of scope, recorded for later phases: launch-time registration comparison (must still trigger a
  restart when stray `REQUESTED` rows exist) and running both mechanisms at once (the app mechanism's start
  must then not demote rows PhotoKit is still carrying).
