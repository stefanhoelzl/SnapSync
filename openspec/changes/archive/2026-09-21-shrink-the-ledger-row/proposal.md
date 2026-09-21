## Why

The ledger row carries three columns that nothing needs and one state that duplicates another.

- **`attempt`** limits nothing. `SyncEngine` states the policy as *"retry forever … No attempt budget, no give-up"*.
  Its only readers are the retry increment, three log lines, and `UploadCycle.reconstruct`, which reads the count
  back so the next increment has something to increment. `UploadJob.attempt`'s KDoc claims the value tells a new
  job from a retry, but no platform reads it. The simulator job queue tracks retries with its own `isRetry`.
- **`eventId`** is read by nothing. `Ledger.sq` calls it *"provenance, not dedup state"*. No query filters or
  groups by it, and `manifestRows()` already returns `""` for it. Keeping it costs a sweep on every cycle
  (`backfillEventId`), a parameter on every write, and a required constructor argument on `SyncEngine`.
- **`absent`** has been inert since `always-full-enumerate`. Nothing sets it, and a per-cycle sweep clears what
  earlier builds left. That change's design (D5) deferred the column to this phase so that the downgrade boundary
  is crossed once.
- **`FAILED`** and **`DISCOVERED`** are one fact to a producer: `needsJob` already merges them. With `attempt`
  gone, the only remaining difference is one rule in `event-album` that avoids placing a re-created failure a
  second time. That rule protects nothing: re-adding an asset already in the album is a measured no-op, and the
  album gather shipped by `album-gathers-retroactively` re-places everything with no record.

This is phase 2 of a seven-phase simplification of the upload path. It is the programme's only schema migration,
by design, because every `.sqm` closes a downgrade door.

## What Changes

- **BREAKING (schema, one-way):** a new migration `10.sqm` (schema v10 → v11):
  - rewrites every `FAILED` row to `DISCOVERED`;
  - drops `attempt`, `eventId` and `absent` from `ledgerRow`.

  `Ledger.sq` is updated to match. Once a device runs this build, no earlier build can open its ledger (SQLiter
  refuses a database newer than the binary). A rollback is a **roll-forward**: a further `11.sqm` re-adds the
  three columns with defaults, shipped with the old Kotlin.
- **The ledger has three states:** `DISCOVERED` (needs a job), `REQUESTED` (a job exists) and `COMPLETED`
  (done). `FAILED` is removed from `LedgerState`, and a failed upload returns its row to `DISCOVERED`. This
  covers:
  - the engine's `UploadFailed` record;
  - both transports' terminal callbacks (`TerminalOutcome.FAILED` now records `DISCOVERED`);
  - the stranded pass;
  - `demoteRequested`.
- **`attempt` is removed everywhere:** the column, `LedgerEntry.attempt`, `UploadJob.attempt`, the `attempt`
  parameter on every record operation, the retry increment, and the `attempt=` field in the engine's log lines.
  `SyncDecision.Retry` stays as an arm; it is still the answer to a failure, carrying a freshly minted request.
- **`eventId` provenance is removed:**
  - the column and `LedgerEntry.eventId`;
  - `backfillEventId` and its per-cycle sweep;
  - the `eventId` parameter on every write;
  - `SyncEngine`'s `eventId` constructor parameter;
  - the argument in the re-join seed's row construction.
- **The absence mark is removed:**
  - the column and `LedgerEntry.absent`;
  - `clearAbsenceMarks` and its per-cycle sweep;
  - the four `absent = 0` SQL filters;
  - the `filterNot { it.absent }` reads in `DeviceManifest.kt` and `AlbumGather.kt`.
- **`event-album`:** the rule that a re-created failure is not placed again is removed. Placement covers the
  admitted, resolved rows about to receive a job whose state is `DISCOVERED`, which now includes a failure
  waiting to be re-created. A repeat is a no-op.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `sync-ledger`: three states instead of four; the column list, complete schema statement, record signatures and
  entry fields lose `attempt`, `eventId` and `absent`; the provenance requirement and its backfill sweep are
  removed; the absence-mark sweep is removed; `demoteRequested` and the guarded terminal write record
  `DISCOVERED` for a failure; a new migration requirement for `10.sqm` covers its rewrite and its test.
- `sync-engine`: `UploadJob` carries no attempt; failure adjudication records `DISCOVERED` and answers `Retry`
  without an increment; the resource-changed decision names three states.
- `event-album`: the enqueue-time placement rule no longer excludes a re-created failure; the gather reads
  every ledger row (there is no absence mark).
- `ios-url-session-upload`: a failed or stranded transfer is recorded `DISCOVERED`; the stranded pass and the
  ledger top-up are restated without `FAILED`.
- `ios-photokit-upload`: completion/retry adjudication, re-registration's demote, cap-aware creation and deletion
  are restated without `FAILED` or `attempt`.
- `device-manifest`: the projection reads no absence mark.
- `upload-lifecycle`: the producer seam's text names three states.
- `upload-state-reconciliation`: the re-join seed builds rows without provenance.
- `harness-world-model`: the world's upload-job lifecycle records a failure as `DISCOVERED` and shows no attempt
  counter derived from the ledger.
- `full-stack-harness`: in the world-inspector controls, failing a job re-creates it (its creation count rises)
  instead of incrementing an engine attempt.

## Impact

- **Code:**
  - `:domain`: `model/` (`Ledger.kt`, `SyncModel.kt`, `DeviceManifest.kt`); `ports/` (`LedgerStore`,
    `TransferRecord` KDoc); `feature/upload` (`SyncEngine`, `LedgerWriter`, `UploadCycle`, `Reconciler`,
    `StrandedKeys`, `DemoteRequested` KDoc); `feature/album` (`AlbumGather`); `compose/` (engine construction).
  - `:adapter:generic:app` (`Ledger.sq`, the new `10.sqm`, `SqlDelightLedgerStore`).
  - `:adapter:generic:fake` (`InMemoryLedgerStore`).
  - The iOS adapters (`PhotoKitJobMapping`, `IosUrlSessionUploadPlatform`), for KDoc and log text only; their
    `TerminalOutcome.FAILED` call sites keep compiling.
  - `:test:world` (the ledger contracts and seeds), `:test:integration`, `:app:desktop` (the world inspector), and
    the two test-local ledger fakes in `domain/feature` commonTest.
- **Schema:** `10.sqm`. The committed `ledger/databases/9.db` snapshot stays as it is; the verify task applies
  `9.sqm` and `10.sqm` to it and compares the result with `Ledger.sq`. The row rewrite is invisible to that task
  and is covered by `SqlDelightLedgerStoreTest`.
- **Downgrade:** closed for every device that launches this build. Every merge to `main` reaches internal
  TestFlight, and no external tester is reached until an App Store promote.
- **Observability:** the retry count leaves the engine's log lines. No Bugsink query, diagnostic dump field or
  harness assertion reads it.
- **Out of scope:**
  - Restructuring the stranded repair (phase 5); only `demoteRequested`'s target changes here.
  - The `joinedEventId` marker and `UploadReconciler` (phase 3), apart from dropping the seed's `eventId`
    argument.
  - `UploadLedgerAudit` and the upload arm (phases 3–4).
