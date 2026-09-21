## Context

The ledger (`ledgerRow`, schema v10 after `9.sqm`) holds one row per uploadable resource:

```
key · assetId · state · attempt · eventId · creationDate · role · contentType · originalFilename · absent · destinationPath
```

It has four states: `DISCOVERED`, `REQUESTED`, `COMPLETED` and `FAILED`. Three of the columns and one of the states
no longer earn their place:

| concept | its readers today | what it decides |
|---|---|---|
| `attempt` | `SyncEngine.retry` (`failed.attempt + 1`), `UploadCycle.reconstruct` (reads it back), three engine log lines, one writer log line | nothing — the policy is "retry forever", with no budget |
| `eventId` | `backfillEventId` (writes it), the record signatures (thread it) | nothing — no query filters or groups by it; `manifestRows()` returns `""` for it |
| `absent` | the four `absent = 0` SQL filters, `DeviceManifest.kt:95`, `AlbumGather.kt:131`, `clearAbsenceMarks` | nothing — never set since `always-full-enumerate`, whose per-cycle sweep clears earlier marks |
| `FAILED` | `needsJob` (merged with `DISCOVERED`), `UploadCycle.placeFirstEnqueued` (`state == DISCOVERED`) | only whether an enqueue-time album add is repeated, and that repeat is a measured no-op |

`UploadJob.attempt`'s KDoc says *"`0` → create a new platform upload job; `> 0` → retry the existing one"*. No
platform reads it. `UploadCycle` picks between `createJob` and `retryJob` from the source of the job (the
platform's retry queue versus the ledger's work read), and the simulator queue carries its own `isRetry`.

Every `.sqm` raises `Schema.version`, and SQLiter refuses a database newer than the binary (`4.sqm` records the
error text). This is phase 2 of seven, and the programme's only migration. Phase 1 (`always-full-enumerate`, D5)
left `absent` in place for this reason: so that the downgrade boundary is crossed once.

## Goals / Non-Goals

**Goals:**
- One migration, `10.sqm`, that rewrites `FAILED` rows to `DISCOVERED` and drops `attempt`, `eventId` and `absent`.
- A three-state ledger in which a failure returns its row to `DISCOVERED`.
- Every Kotlin trace of the four concepts removed: fields, parameters, sweeps, filters, log fields, fakes.
- The rewrite covered by a test, since the migration verify task cannot see it.

**Non-Goals:**
- Restructuring the stranded repair (phase 5). Only `demoteRequested`'s target state changes.
- The `joinedEventId` marker and `UploadReconciler` (phase 3). Only the seed's `eventId` argument is dropped.
- `UploadLedgerAudit` and the upload arm (phases 3–4).
- The app-driven hang recorded by `always-full-enumerate`, which is accepted as a one-off.
- Any change to the backend, the byte route or the manifest wire format. `eventId` provenance was never sent.

## Decisions

### D1 — One migration: rewrite, then three drops

```sql
UPDATE ledgerRow SET state = 'DISCOVERED' WHERE state = 'FAILED';
ALTER TABLE ledgerRow DROP COLUMN attempt;
ALTER TABLE ledgerRow DROP COLUMN eventId;
ALTER TABLE ledgerRow DROP COLUMN absent;
```

**Valid on both populations.** `9.sqm` showed that devices reach a schema version by two routes that can differ:
created fresh from `Ledger.sq`, or upgraded through the chain. Each statement is checked against both.

- The `UPDATE` touches no schema, so it is shape-independent.
- `attempt` exists on both shapes: fresh `Ledger.sq` has it, and `1.sqm` created it.
- `eventId` exists on both shapes: fresh `Ledger.sq` has it, and `4.sqm` added it.
- `absent` exists on both shapes: fresh `Ledger.sq` has it, and `6.sqm` added it.

SQLite refuses `DROP COLUMN` for a column that is indexed, in the primary key, `UNIQUE`, in a `CHECK`, in a
foreign key, or named by a trigger, view or partial-index predicate. None of these applies: the only indexes are
`ledgerRow_assetId(assetId)` and `ledgerRow_destinationPath(destinationPath)`, and `key` is the primary key. SQLite
has no `DROP COLUMN IF EXISTS`, and none is needed. The dialect is `sqlite-3-35`, and `2.sqm` already drops a
column through both drivers.

**The rewrite comes first.** It does not depend on the drops. Stating it first keeps the file readable as "state,
then shape".

**Alternative: split into two migrations** (state rewrite, then drops). Rejected. Each `.sqm` is a separate
one-way door, and there is no shippable intermediate state: the state merge needs the rewrite (D2), so it needs a
migration of its own anyway.

**Alternative: stop writing the columns and leave them.** Rejected by the programme: dead columns are the kind of
concept this work deletes, and the downgrade cost is paid once either way.

### D2 — The `FAILED` rewrite lives in the migration, not in a read alias

The alias would keep decoding stored text `'FAILED'` as `DISCOVERED` in Kotlin. **It does not work**, which is a
stronger reason to reject it than untidiness. The state-scoped reads compare stored text in SQL:
`selectNeedingJob` filters `state IN :needsJobStates`, bound from `NEEDS_JOB_STATES = [DISCOVERED]`. An aliased
row decodes correctly but is never *selected*. It is not done, so it counts as pending, and it never gets a job.
That is the "pending forever, no error anywhere" failure `8.sqm` was written to prevent. Adding `'FAILED'` to the
bound text would re-introduce a state name the vocabulary no longer has.

**Coverage.** The verify task compares schemas, and a row rewrite changes none. So, as with `8.sqm`,
`SqlDelightLedgerStoreTest` gains the case that proves it (see D6).

### D3 — A failure records `DISCOVERED`; `TerminalOutcome.FAILED` keeps its name

`needsJob` becomes `DISCOVERED` alone. `isDone` and `bytesBelievedStored` become `COMPLETED` alone, which they
already are. Every site that recorded `FAILED` now records `DISCOVERED`:

- `SyncEngine.retry`, through `LedgerWriter.recordFailed`, which keeps its name. The name says why the call is
  made; the state says what it records.
- `TerminalOutcome.FAILED`, through `markTerminal`. This is used by both transports' terminal callbacks and by
  `LedgerWriter.markStranded`.
- `demoteRequested` (`UPDATE … SET state = 'DISCOVERED' WHERE state = 'REQUESTED'`).

`TerminalOutcome` keeps its two cases, and `FAILED` now maps to `LedgerState.DISCOVERED`. The enum names what the
**platform** reported, and "failed" is still what happened. What changes is only the ledger's answer to it. The
type still does its job: the guarded terminal write is the one record operation outside the single writer, and
the type fixes its recordable set at compile time. That set is now `{COMPLETED, DISCOVERED}`, and `REQUESTED`
still cannot be written through it. Its KDoc stops saying "recording `DISCOVERED` … is a compile error" and says
instead that the platform cannot claim a job exists (`REQUESTED`) through a callback.

**Alternative: rename to `TerminalOutcome.RETRY` or `NEEDS_JOB`.** Rejected. It would name the ledger's
consequence rather than the platform's fact, and it would add churn in two iOS adapters for no gain in meaning.

`LedgerWriter.recordDiscovered` keeps its guard (write only keys with no row). Its rationale changes: the guard no
longer protects an attempt count. It still avoids rewriting a row that already says what the write would say, and
it keeps a walk from overwriting a `REQUESTED` row.

### D4 — `UploadJob` is deleted; the engine speaks `UploadRequest`

With `attempt` gone, `UploadJob` would be a one-field wrapper around `UploadRequest`. It has four referencing
files. `SyncEvent.UploadFailed`/`UploadStarted` and `SyncDecision.Upload`/`Retry` carry the `UploadRequest`
directly (`decision.request` instead of `decision.job.request`). `UploadCycle.reconstruct` returns an
`UploadRequest`. `SyncDecision.Retry` stays a distinct arm. Platforms treat both arms the same way, but the arm
still names provenance for logs and the harness journal, which is its stated purpose.

**Alternative: keep `UploadJob(request)`.** Rejected. A wrapper whose only content is the thing it wraps is a
concept that does not earn its place, and removing it now costs a few lines. Keeping it would leave `sync-engine`
describing a type with nothing to say.

### D5 — The event-album placement rule drops its `FAILED` exclusion

`placeFirstEnqueued` keeps its predicate `state == DISCOVERED && key resolved`. After D3 that predicate also
admits a failure waiting to be re-created by the enqueue pass, so such a photo is added to the album again. This
is accepted:

- Re-adding an asset already in the collection is a no-op (measured, simulator, iOS 26.5).
- The shipped album gather (`album-gathers-retroactively`) already re-places the whole own set with no record, on
  every opt-in act and permission observation, so the product no longer relies on "placed once".
- Keeping the exclusion would mean re-inventing a first-attempt/retry distinction in some other form. That is
  exactly what this phase deletes.

The amended requirement says what is true: placement covers the rows about to be enqueued, and a repeat is a no-op.

Failures re-created by `recreateRetrySpent` (the platform's returned jobs) never pass through the enqueue pass, so
they are still not placed there. The rule does not need to say so, because it describes only the enqueue pass.

### D6 — The migration test

Add a `SqlDelightLedgerStoreTest` case: *migration v10 to v11 rewrites FAILED rows to DISCOVERED and drops the
three columns*.

**Setup.** Build the v10 table with the existing v9-table helper (`9.sqm` adds no column, so the v9 table is the
v10 table) plus the destination index. Plant rows with raw `INSERT`, because the current enum can no longer write
`FAILED`:

- a `FAILED` row carrying manifest detail and a `destinationPath`;
- a `DISCOVERED` row;
- a `REQUESTED` row;
- a `COMPLETED` row;
- a row with `absent = 1`;
- a row with `eventId = ''`.

**Assertions.** Migrate, then check that:

- the `FAILED` row reads back `DISCOVERED` with every surviving column unchanged;
- the other states are untouched;
- `rowsNeedingJob()` returns both the rewritten row and the original `DISCOVERED` one;
- the formerly `absent = 1` row appears in `manifestRows()` and in the aggregates;
- no column named `attempt`, `eventId` or `absent` remains (`PRAGMA table_info`).

The schema half is also checked by `verifyCommonMainLedgerDatabaseMigration`, which applies `9.sqm` and `10.sqm`
to the committed `9.db` snapshot and compares the result with `Ledger.sq`. The snapshot is not regenerated: an
older snapshot exercises more of the chain.

### D7 — Seed and fake construction

`UploadReconciler`'s seed builds `LedgerEntry(key, assetIdFromUploadKey(key), COMPLETED)` with no provenance. This
is the one edit to that file. `InMemoryLedgerStore` (in `:adapter:generic:fake`) and the two test-local copies in
`domain/feature` commonTest lose the same fields, the provenance sweep and the mark sweep. `FakeHonestyTest` keeps
the public surface honest.

## Risks / Trade-offs

- **[One-way door]** Once a device launches this build, no earlier build opens its ledger. → Every merge reaches
  only internal TestFlight; no external user is on any build until an App Store promote. The rollback is a
  roll-forward (see Migration Plan) and has been checked to be feasible. The data it cannot restore (retry counts,
  provenance, `FAILED` versus `DISCOVERED`) is read by nothing.
- **[Retry count lost from logs]** `failed key=… attempt=N` no longer tells a first failure from the fifth. →
  Accepted by the design session. The key repeats in the log, so a count can be recovered by grepping the key.
  No Bugsink query, dump field or harness assertion reads the number.
- **[Album re-placement]** A photo the member manually removed from the event album while its upload was failing
  is added again when the enqueue pass re-creates it. → Already true of the gather on every opt-in act and
  permission observation; the album is not a record of user curation.
- **[A missed site]** A leftover `'FAILED'` literal in SQL would compile, and would silently match nothing. → The
  `.sq` file is the only place state literals appear (`markTerminal`'s `'REQUESTED'`, `demoteRequested`,
  `selectRequestedKeys`). The task list greps for `FAILED` across `*.sq`/`*.sqm`/`*.kt` before `build`.

## Migration Plan

1. Ship `10.sqm`, the updated `Ledger.sq` and the Kotlin in **one PR** (`internal` label: no customer-visible
   behavior).
2. The verify task checks the schema half against the committed `9.db` snapshot; `SqlDelightLedgerStoreTest`
   checks the rewrite; `IosLedgerStoreTest` runs the shared contracts on the native driver in CI.
3. **Rollback is a roll-forward, not a revert.** Reverting the Kotlin while keeping `10.sqm` fails: the reverted
   `Ledger.sq` names the three columns, so the verify task rejects it, and every query that selects them would
   fail at runtime. Removing `10.sqm` instead leaves every upgraded device at a schema newer than the binary,
   which SQLiter refuses to open. A rollback therefore ships:
   - an `11.sqm` that re-adds `attempt INTEGER NOT NULL DEFAULT 0`, `eventId TEXT NOT NULL DEFAULT ''` and
     `absent INTEGER NOT NULL DEFAULT 0`;
   - the old Kotlin, with `Ledger.sq`'s `attempt` gaining `DEFAULT 0` (an `ADD COLUMN NOT NULL` requires a default,
     and the verify task compares the two).

   `FAILED` is not restored and does not need to be: the old code treats a `DISCOVERED` row exactly as it treats a
   `FAILED` one (`needsJob`). The only difference is the album-placement repeat, which is a no-op.

## Open Questions

None. The route (OpenSpec), the one-PR shape, the `event-album` amendment and the roll-forward rollback were
settled with the user and the design session.
