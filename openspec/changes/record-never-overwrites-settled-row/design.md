## Context

The ledger (`sync-ledger`) is the upload engine's durable per-key memory, in one App-Group SQLite database. Its
record path is `LedgerWriter.record` → `LedgerStore.put` → `INSERT OR REPLACE`. The seam's contract says backends
store verbatim, "last write wins"; the precedence rules live in Kotlin, which was sound while one sequential
writer existed (`changes/archive/2026-06-12-sync-engine-ledger`, D7). Two guarded writes were already carved out as
named operations, because a platform callback writes without the cycle's lock: `markTerminal` (only while
`REQUESTED`) and `promoteUploaded` (only while `UPLOADED`).

This change is phase M1 of a larger design in which the OS-driven extension and the in-app background session
both record into the one ledger. M1 stands alone: the guard is harmless with one writer.

What the tree shows today:

- **Writes that can reach a settled row.** The cycle's first-failure pass (`UploadCycle`, `fetchRetryJobs`) calls
  `adjudicateFailure` → `SyncEngine.retry` → `recordFailed`, then `platform.retryJob`, then `UploadStarted` →
  `recordRequested`, with no state check. The retry-spent pass (`recreateRetrySpent`) already skips
  `isDone` rows in Kotlin. The walk's `UploadStarted` follows an engine decision that skips settled rows.
  `recordCompleted`'s only trigger, `SyncEvent.UploadCompleted`, is sent by tests alone.
- **The absence mark.** `markAbsent` sets `absent = 1`; nothing sets it back to 0 except the blind upsert, which
  writes `absent = 0` whenever a record touches the row. For an unsettled row that is how a restore heals today
  (the walk re-derives it → `Work` → `recordRequested`). For a `COMPLETED` row the engine writes nothing, so a
  restored asset stays absent and out of the device manifest.

## Goals / Non-Goals

**Goals:**

- A record write can never overwrite a row in a done state, enforced atomically inside one SQL statement.
- The guard's state set is decided in Kotlin and bound as a parameter, never a literal in SQL.
- A declined record is observable.
- A restored asset is listed again, without re-uploading anything.

**Non-Goals:**

- Changing any engine or cycle decision. The first-failure pass keeps retrying (see D4).
- `recordDiscovered`'s own "only when no row exists" rule, and the guards of `markTerminal` / `promoteUploaded`.
- Retiring `UPLOADED` (a separate phase, M9), or retiring the single-record-writer invariant (M8).
- Any schema change.

## Decisions

### D1 — Remove `put`; the guarded record is the only per-row upsert

Once the record path uses a guarded write, `put` has no production caller: `resetTo` uses its own query. Keeping
`put` for test seeding would leave an unguarded upsert on the port for the next production write to reach for,
and two upsert semantics in every fake. So `put` goes, and tests seed through the guarded record write — for a
fresh key it is exactly the old upsert — or through `resetTo` when a test needs a settled row replaced.

*Considered:* making `put` itself conditional ("put never overwrites a settled row") — the same end state, but the
name would keep promising a verbatim store. *Considered:* a named guarded operation beside a retained `put` — sound
only while `put` had a production job, and it no longer does.

### D2 — One guarded statement, `INSERT … ON CONFLICT(key) DO UPDATE … WHERE state NOT IN :doneStates`

```sql
recordUnlessSettled:
INSERT INTO ledgerRow (key, assetId, state, attempt, eventId, creationDate, role, contentType,
                       originalFilename, absent, destinationPath)
VALUES (…)
ON CONFLICT(key) DO UPDATE SET
    assetId = excluded.assetId, state = excluded.state, attempt = excluded.attempt,
    eventId = excluded.eventId, creationDate = excluded.creationDate, role = excluded.role,
    contentType = excluded.contentType, originalFilename = excluded.originalFilename,
    absent = excluded.absent, destinationPath = excluded.destinationPath
WHERE ledgerRow.state NOT IN :doneStates;
```

It is executed with `SELECT changes()` in the same transaction, which is how `markTerminal` answers "did this
apply?". The store signals `changes` only when the write applied. `absent` stays in the `SET`, so a record
over an unsettled row still clears the mark as it does today.

The guard lives in the statement, not in `LedgerWriter`. The writer already reads the prior row to preserve
manifest detail, and that read must not become the guard: across two processes a read-then-write is exactly the
race this phase exists to close.

*Spiked:* SQLDelight 2.3.2 with the `sqlite-3-35` dialect generates this query, including the list bind
(`doneStates: Collection<LedgerState>`). `verifyCommonMainLedgerDatabaseMigration`, `compileKotlinJvm` and
`compileKotlinIosArm64` pass. On the JVM driver (SQLite 3.51.3), `FAILED`, `REQUESTED` and `COMPLETED` over a
`COMPLETED` row each report 0 changes and leave the row intact; new insert, `FAILED → REQUESTED` and
`REQUESTED → COMPLETED` report 1. Upsert-with-`WHERE` needs SQLite ≥ 3.24, and iOS 18's system library is well
past that. The iOS runtime itself is exercised by the contract test's `iosSimulatorArm64Test` run in CI.

`resetTo` replaces its use of `put` with a plain `INSERT`: it runs after `deleteAll` in one transaction, so no
conflict can occur, and the reset family stays unconditional.

### D3 — The protected set is `DONE_STATES`

Bound from `LedgerState.isDone`, like every other state-scoped query, so a new state must be classified before
it compiles. Today that is `{COMPLETED}`. `UPLOADED` is deliberately not protected: it is being retired, and
guarding it now would add a classification that exists for one phase. `bytesBelievedStored` is not reused —
it answers what the audit compares, a different question.

A consequence: `recordCompleted` over a `COMPLETED` row no longer refreshes `attempt`. Its only trigger is sent by
tests, so nothing in production changes.

### D4 — The engine ignores the answer; the first-failure pass still retries

Traced for a first-failure job whose row is `COMPLETED`: `recordFailed` is declined, `retryJob` re-sends the bytes,
`recordRequested` is declined. If the job succeeds, `markTerminal` (only while `REQUESTED`) is a no-op, and the
backend's resource write converges (`recordResource`, `ON CONFLICT (device_id, asset_id, role) DO UPDATE`). If it
fails, the retry-spent pass skips the settled row and acknowledges the job. The ledger stays `COMPLETED` throughout.

*Considered:* skipping the retry when the row is settled, either through the declined answer or a Kotlin check
mirroring the retry-spent pass. Both leave a `.retry` job untouched, and what PhotoKit does with one has never
been measured (only the acknowledge obligation, error 50008, has). The case is rare — it needs a key whose bytes
are already on the backend and whose client-side job failed — and its cost is one duplicate PUT that converges.
So there is no cycle change for it.

### D5 — A declined record is logged at Warn in `LedgerWriter`

"Absence is never silent" (`module-architecture`). With one writer a declined record means a rare upstream path
fired, which is worth being greppable in `debug.log`. It is not Error: Error reaches crash reporting, and the
outcome is correct. When two writers record (M8), declines become routine and that phase should lower the level.
The line names the key, the refused state and the attempt.

### D6 — `markPresent(assetIds)`: presence is a library fact, taken from the walk's candidates

Every candidate the walk returns exists in the library **now**, so its asset is not absent. The cycle's update
stage calls `markPresent` with the asset ids of **all** candidates — before policy admission, because presence
has nothing to do with scope — after applying the change feed's removals. If one change-token window names an
asset in both lists, the candidate fetch is the later fact and "present" wins.

Shape: one read of the absent asset ids, intersected in Kotlin with the supplied ids, then an update of only the
matches (chunked `WHERE absent = 1 AND assetId IN :ids`), in one deferred transaction. In the steady state the
intersection is empty and nothing is written. It signals `changes` only when it applied. It is not state-guarded:
settled rows are exactly the ones it has to reach. It is writer-only, like `markAbsent`.

*Considered:* an unconditional `UPDATE` per candidate — one write transaction per asset, thousands on a full
enumeration, contending with a second process. *Considered:* un-marking only for admitted resources — an
out-of-scope restored asset would then stay hidden after a later widening, until another full enumeration.

## Risks / Trade-offs

- [A restored asset may not keep its `localIdentifier`, or may not appear in the change feed] → Unmeasured. If iOS
  mints a new id, the old rows correctly stay absent and the photo uploads as a new asset; `markPresent` is harmless
  either way. The contract and cycle tests pin the behavior, not the platform premise.
- [The candidate's `assetId` must be the same normalized value the ledger stores (`/` → `_`)] → Verify while
  implementing; `markAbsent` already receives the normalized form from the change feed.
- [Removing `put` touches many tests] → Mechanical: seeds become guarded record writes, and a test that relied on
  overwriting a settled row fails loudly and is rewritten. No production
  code path loses a behavior.
- [The iOS runtime of the upsert is untested locally] → The contract suite's `iosSimulatorArm64Test` run on
  `macos-26` executes it against the native driver before merge.
- [Warn noise once two writers exist] → Named in D5 for the phase that introduces the second writer.
- [Complexity ceilings] → Two were hit. The `tests` tier's `TooManyFunctions` ceiling is **raised 18 → 19**, with
  a forcing proof: the port now declares 18 functions and a complete double of an interface must implement all of
  them, so no `LedgerStore` double can pass at 18 (`config/detekt/tests.yml` states it). The `harness` tier's
  `LargeClass` on `LedgerStoreContract` is **not** raised: the guarded-write and presence scenarios moved into a base
  class, `LedgerRecordGuardContract`, which the contract extends, so every binding runs both halves unchanged.
