## Why

Every ledger record is one blind `INSERT OR REPLACE` (`Ledger.sq` `put`), so a late or stale record can move a
finished photo backwards. It is reachable today: the upload cycle's first-failure pass (`UploadCycle`,
`fetchRetryJobs` → `adjudicateFailure`) records `FAILED` and then `REQUESTED` with no look at the row, so a
row a re-join seeded `COMPLETED` while an old OS job was still out is overwritten, and the photo is
re-uploaded. It becomes routine once the extension and the app record into the one App-Group ledger at the
same time — and there only a guard inside the storage statement holds, because a Kotlin read-then-write
is a cross-process race. The original design anticipated exactly this (`changes/archive/2026-06-12-sync-engine-ledger`,
D7: "re-examine `ON CONFLICT` precedence then").

Separately, a restored photo whose rows are `COMPLETED` is never listed in the event again: deletion marks its
rows absent, and nothing ever clears the mark — the only thing that did was the blind upsert, and the engine
writes nothing for an already-uploaded resource.

## What Changes

- **BREAKING (port)**: `LedgerStore.put(entry)` is **removed**. The only per-row upsert becomes a guarded record
  write, `recordUnlessSettled(entry): Boolean`, one statement that leaves a row in a done state
  (`DONE_STATES`, bound from Kotlin — today `COMPLETED`) untouched and answers whether it applied. The
  reset family (`clear`, `clearRequested`, `resetTo`) keeps replacing rows unconditionally.
- Every `LedgerWriter` record operation goes through the guarded write. A declined record is logged, never
  silent. Legitimate non-settled transitions keep working: a retry `FAILED → REQUESTED`, a stranded transfer
  `REQUESTED → FAILED`.
- The engine's decisions do not change: `UploadFailed` still answers `Retry`, and the cycle still acts on it.
  On a settled key the retry's writes are simply declined and the row stays `COMPLETED`.
- New writer-only `markPresent(assetIds)`: the cycle clears the absence mark of every asset its walk saw in
  the library, so a restored photo is listed in the event again. In the steady state it reads once and
  writes nothing.
- Tests seed through the guarded record write or `resetTo` instead of `put`. Every `LedgerStore` fake honours the guard
  and `markPresent`.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `sync-ledger`: the storage seam loses `put` and "last write wins" and gains the guarded record write and
  `markPresent`; the record operations become guarded; the change signal fires on an applied write; the
  done-state set is also bound into the guarded record; a restored asset's rows are un-marked.
- `sync-engine`: recording on `UploadFailed` / `UploadStarted` is a guarded upsert that never overwrites a
  settled row, not an unconditional one.
- `device-manifest`: a restored asset is listed in the projection again.
- `ios-app-shell`: the native-ledger requirement stops naming `put`.
- `sync-status`: the read-only counts requirement stops naming `put`.

## Impact

- `:domain` — `ports/LedgerStore` (remove `put`, add `recordUnlessSettled`, `markPresent`);
  `feature/upload/LedgerWriter`, `UploadCycle` (the update stage calls `markPresent`).
- `:adapter:generic:app` — `Ledger.sq` (the guarded upsert replaces `put`; `resetTo` uses a plain insert; the
  absent-id read and un-mark), `SqlDelightLedgerStore`. No schema change, no `.sqm` migration.
- `:adapter:generic:fake` `InMemoryLedgerStore`, plus the `domain/feature` `commonTest` doubles
  (`InMemoryLedgerStore`, `FakeLedgerStore`).
- `:test:world` `LedgerStoreContract` and every test that seeds with `put`.
- Runtime: the conditional upsert needs SQLite ≥ 3.24 — the JVM driver bundles 3.51.3, and iOS 18's system
  SQLite is newer. It was spiked: SQLDelight generates it (`doneStates: Collection<LedgerState>`), and on the
  JVM a `FAILED`/`REQUESTED`/`COMPLETED` write over a `COMPLETED` row reports 0 changes and leaves the row as
  it was.
- User-visible: a restored photo reappears in the event; the rare duplicate re-upload of a seeded row stops
  corrupting the ledger. Changelog label `bug`.
