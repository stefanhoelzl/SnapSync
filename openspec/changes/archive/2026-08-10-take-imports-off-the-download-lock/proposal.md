## Why

The per-asset photo-library import runs **under** `DownloadController`'s mutex, so a stalled library blocks
every reconcile, import, leave, switch and `onResourceStaged` behind it. `onResourceStaged` is the sharp
edge: it is called from the background `URLSession` delegate inside an OS-granted wake, so blocking it can
cost that wake its staging work. Observed in the field (`SNAPSYNC-6`): the lock was held from 09:03:37 until
the process died.

`gate-absence-on-unreported-imports` (merged) deliberately kept the lock and raised `IMPORT_DEADLINE` from
5 s to 30 s, widening this exposure sixfold. Its D6 named the trade and scheduled the repair: *"Removing the
lock instead is the follow-up change, not this one."* This is that change.

## What Changes

- The lock covers the **decision**, not the **work**: selection, the claim, the staged-resource read and
  every store write run under it; `PhotoLibraryImporter.import` is the only thing outside it.
- A **claim** replaces the mutual exclusion the lock's *span* was providing — a private set in the
  controller, taken under the lock before the platform call, so two triggers cannot both find one asset
  importable. It is read by import selection, by adjudication's *absent* gate, and by the prune.
- The drain claims **one ref at a time** with an attempted-set. Claiming the batch up front lets a single
  non-reporting import strand every other ref in it; without the attempted-set a permanently-failing asset
  live-locks the drain.
- **BREAKING** `DownloadStore.pruneNonTerminal` takes a required `protecting` set and returns the staged
  paths it stranded, in one transaction. It needs `protecting` only now: a ref can be claimed **before** its
  change block runs, so rows exist with no marker yet. `stagedPathsOfPrunableAssets` is absorbed and removed.
- **BREAKING** `IMPORT_DEADLINE` and `ImportResult.TimedOut` are removed. The deadline's stated job was
  bounding the lock; the wake is already bounded by `OsReceipt`, which releases the OS handler on its own
  deadline and lets the work run on. A per-import clock that expires against live transactions is the
  mistake D1 already named.
- **BREAKING** `UnreportedImports` is removed with its only writer. Its single reader — adjudication's
  *absent* gate — is answered by the claim, which is what "a transaction may still be open in this process"
  now means. `D3` (one reader) and `D6` (the deadline) are superseded; `D1` (a fact, never a clock) and `D2`
  (in memory, its erasure load-bearing) survive, carried by the claim.
- Every marker write two unsynchronized writers can reach is **addressed by what it expects to find**:
  `clearCreatedLocalId` gains the expected marker and a result, matching `confirmCreatedLocalId`'s existing
  guard. Adjudication's caller-side re-checks collapse into those writes, and `isUnconfirmedWith` — which
  existed only to support check-then-act under a lock the other writer does not take — is removed.
- `recordCreatedLocalId` reports when its update **lands on no row**, at `Error` severity. That is the
  silent failure `protecting` exists to prevent, and today it is invisible until a downloaded photo is
  re-uploaded days later.
- A stuck import becomes visible through `Logger.invocation` around the per-asset import, replacing the
  deleted deadline-expiry line.
- `ResetDeviceState` performs its download prune through an injected suspend effect bound in `compose/`, so
  the reset takes the controller's lock rather than reading a snapshot of what is claimed.
- The world importer gains a lever that **suspends** after writing its marker and resumes with a
  test-chosen outcome, replacing the `TimedOut`-shaped abandonment levers. It models the live transaction
  rather than a report about one, and its `recordCreatedLocalId` parameter stops being defaulted.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `photo-download`: the lock requirement is rewritten (it covers the decision, and a claim provides mutual
  exclusion); the bounded-import requirement and its stop-the-drain rule are removed with the deadline;
  adjudication's *absent* gate is restated against an import running in this process rather than an
  unreported ref.
- `download-store`: `pruneNonTerminal` protects claimed rows and returns what it stranded, in one
  transaction; the marker writes are marker-addressed and report whether they applied; a marker write that
  lands on no row is loud.
- `diagnostic-logging`: the abandoned-import expiry scenario is removed; an import that never returns, and a
  marker write that lands on nothing, are the facts that must be visible instead.
- `harness-world-model`: the abandonment levers are replaced by a suspend-and-resume lever, so a test can
  drive concurrent triggers while a transaction is genuinely open.
- `ios-app-shell`: the `SNAPSYNC_RESET_STATE` trigger no longer clears *every* non-terminal download row —
  it spares rows carrying a created-asset marker (already true, and previously unstated) and rows whose
  import is in flight (new, and only knowable to the download feature, so the reset takes that feature's
  lock rather than a snapshot).

## Impact

- `:domain` — `feature/download` (`DownloadController`, `UnreportedImports` deleted),
  `feature/membership/ResetDeviceState`, `ports/DownloadStore`, `ports/DownloadSeams`, `compose/SnapSyncApp`.
- `:adapter:generic:app` — `SqlDelightDownloadStore` and `DownloadStore.sq`.
- `:adapter:generic:fake` — `InMemoryDownloadStore`.
- `:adapter:ios:app-only` — `IosPhotoLibraryImporter` (deadline, `forgetUnreported`, the loud no-op).
- `:app:ios` — `SnapSyncRoot` wiring only (the `UnreportedImports` instance it used to own is gone).
- `:test:world` — `FakePhotoLibraryImporter`, `DownloadStoreContract`, `World`.
- `:test:integration` — `UnreportedImportIntegrationTest` deleted, replaced by live-transaction coverage.
- Behavioural risk concentrates in the controller half; the store half is provable by deterministic contract
  tests on both impls and both platforms. On-device acceptance is required (`SNAPSYNC-6` is the class of bug
  the device has already found twice after tests and reviewers passed it).
