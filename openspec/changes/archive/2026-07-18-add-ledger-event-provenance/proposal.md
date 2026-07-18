# Proposal: add-ledger-event-provenance

## Why

Migration step 11b of the `module-architecture` plan (`test/architecture/migration/PLAN.md`,
"behavior: the ledger gains `eventId`"). The ledger is the device's durable upload memory, keyed by
the bare event-independent resource filename — deliberately so (spec `sync-ledger`,
"Event-independent key"), which is what makes cross-event dedup free. But the rows carry **no
record of which event they were recorded under**, and the eventual multi-event work needs that
provenance to exist *before* it can be consulted: a column added the day multi-event lands would
start empty for every historical row, with the true value unrecoverable. Recording provenance now,
while the single-membership reality makes it cheap and unambiguous, is the whole step.

The PLAN (rebuilt after adversarial review) names the four traps the design must close, because a
naive `eventId TEXT NOT NULL` column walks into all of them:

- **The value is not available in SQL.** The joined eventId lives in config (an App-Group
  file/Keychain read); a `.sqm` migration script cannot reach it, so the migration cannot fill the
  column truthfully.
- **COMPLETED rows MUST survive** (the `2.sqm` house invariant): a surviving `COMPLETED` row is
  exactly what stops the next discovery cycle from re-uploading an already-stored resource. A
  drop-and-recreate migration would re-upload every member's post-cutoff library.
- **Two processes race the migration on a WAL DB**: the app and the upload extension each open
  `ledger.db` in the App-Group container, and the OS can schedule the extension before the user
  opens the updated app.
- **A naive NOT-NULL column bricks a revert build's INSERTs**: the old binary's column-explicit
  4-column `INSERT OR REPLACE` omits `eventId`, which a NOT-NULL column without a DEFAULT rejects.

## The `.sqm` design (the PLAN requires it here)

`adapter/generic/src/commonMain/sqldelight/ledger/app/snapsync/engine/db/4.sqm` (v4 → v5), one
statement:

```sql
ALTER TABLE ledgerRow ADD COLUMN eventId TEXT NOT NULL DEFAULT '';
```

- **Row-preserving** — `ALTER TABLE … ADD COLUMN` is a catalog-only change (SQLite rewrites the
  schema record, never row data), so every row, especially every `COMPLETED` row, survives. The
  `2.sqm` invariant extends to `4.sqm`.
- **`''` is the pre-provenance sentinel**, not a value: it marks "recorded before the ledger
  carried provenance". The true value is unreachable from SQL, so the migration leaves the
  sentinel and the **single writer's next upload cycle backfills** it: one idempotent
  `UPDATE ledgerRow SET eventId = ? WHERE eventId = ''` (`LedgerStore.backfillEventId`), executed
  once per cycle entry after the re-join reconcile settles — the one seat that runs on **both**
  tiers' cycles (the shared `UploadCycle`) and never in a reader.
- **The PRIMARY KEY stays `key`** (the bare filename). Dedup semantics are unchanged, no read
  consults `eventId`, and the composite-PK `(eventId, key)` recreate is deferred to the
  multi-event change that would pay for it (a PK change forces a table rebuild — everything the
  DEFAULT-column shape avoids).

## Downgrade stance (declared; detail in design.md D4)

**The schema bump is a one-way door; behavior reverts keep the schema.** SQLiter (the native
driver) **refuses to open** a database whose `user_version` is newer than the binary's compiled
schema version — it throws rather than downgrades — so no build compiled at schema v4 can ever
open a migrated v5 `ledger.db`. A workable revert therefore reverts the Kotlin surface while
keeping `4.sqm` and the column; the `DEFAULT ''` is precisely what makes that staged revert
shippable (the old-shaped column-explicit INSERT still works, landing sentinel rows the next
post-re-update cycle sweeps). Residual risk: rows written while reverted are provenance-labeled
by the *then-live* event on re-update — bounded by the single-membership reality and by the
reconciler's authoritative re-baseline on any switch.

## What Changes

- **Schema**: `4.sqm` above; `Ledger.sq` gains the column (with the identical `DEFAULT ''`, so the
  SQLDelight migration-verify task proves migrated ≡ created), `get`/`put` carry it, and a new
  `backfillEventId` query sweeps the sentinel.
- **Model**: `LedgerEntry` gains `eventId` (provenance field; equality and `toString` include it).
- **Port**: `LedgerStore` gains `backfillEventId(eventId)` — writer-family, sentinel-only,
  idempotent, one `changes` ding like the other bulk ops. `LedgerWriter` exposes it and its
  record operations become `recordX(key, assetId, attempt, eventId)`.
- **Threading**: `SyncEngine` gains the cycle's `eventId` (minted per cycle via `engineFor`, where
  the config already arrives); every lifecycle record writes under it. The reconciler's `resetTo`
  seeds carry the reconciled event. `UploadCycle` runs the backfill once per settled cycle.
- **Reads are unchanged**: no query filters by `eventId` (multi-event is future work); aggregates,
  pending-resources, and dedup reads are untouched.
- **Tests**: a v4→v5 migration test (COMPLETED rows survive, sentinel filled, backfill sweeps), a
  staged-revert INSERT test (v4-shaped insert works on v5), contract scenarios for verbatim
  storage/backfill/idempotence/ding, engine- and cycle-level threading tests; all three in-memory
  stores updated honestly.

## Impact

- Specs: `sync-ledger` (schema, record ops, migration, event-independent key, new
  provenance+backfill requirement), `event-rejoin-reconciliation` (seeds carry provenance).
  **No `upload-lifecycle` delta, deliberately**: that spec's cycle requirements govern the entry
  decision, the direction gate, and the destructive-verb prohibitions — none of which change; it
  does not enumerate the Run path's phase list, and the backfill's seat, gating, and failure
  posture are specified normatively in `sync-ledger`'s new requirement (which names the cycle as
  the seat). The Skip/NotJoined "touch nothing" outcomes hold structurally (the sweep sits after
  both returns). **No `sync-engine` delta either**: the engine's decision table and
  write-after-act semantics are unchanged — its contract speaks in states, not record payloads;
  what a record *carries* is specified in `sync-ledger`, where the carriage contract now sits.
- Code: `:domain` (model/ports/feature-upload/compose), `:adapter:generic` (schema + store),
  `:adapter:fake`, `:test:world` (contract), test fixtures.
- Device: update-in-place over a joined install migrates in whichever process opens first; one
  cycle backfills; **zero new upload jobs** (Session C part 2 verifies on hardware).
