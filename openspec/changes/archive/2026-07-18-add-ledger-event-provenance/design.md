# Design — add-ledger-event-provenance

## Context

Migration step 11b: the ledger gains an `eventId` column as provenance for future multi-event
work, on a live TestFlight channel where every merge ships, both the app and the upload extension
open the same WAL `ledger.db` in the App-Group container, and fix-forward is the only realistic
revert. The PLAN's rebuilt Step 11b section names the constraints; this records how each is met
and what was deliberately not done.

## Goals / Non-Goals

**Goals**

- Every ledger row records the event it was written under; pre-existing rows converge to the
  live event via a backfill that runs in the single writer only.
- COMPLETED rows survive the migration byte-for-byte in every field they had (the `2.sqm` house
  invariant) — an update-in-place must create **zero** new upload jobs.
- A behavior revert of this step remains shippable after devices have migrated.

**Non-Goals**

- Event-scoped reads. No query filters by `eventId`; aggregates, pending, and dedup are
  unchanged. Multi-event is a future change.
- The composite PK `(eventId, key)`. A PK change forces a table rebuild (SQLite cannot alter a
  PK in place); it buys nothing until reads are event-scoped, so it is deferred to the change
  that pays for it.
- Any UI or backend surface. Provenance is invisible outside the ledger.

## Decisions

### D1 — `ADD COLUMN … NOT NULL DEFAULT ''`, PK unchanged (the PLAN's recommended shape)

`4.sqm` is a single catalog-only statement: `ALTER TABLE ledgerRow ADD COLUMN eventId TEXT NOT
NULL DEFAULT '';`. Row-preserving by construction — SQLite's ADD COLUMN rewrites the schema
record, not row data — so the migration cannot lose a COMPLETED row even in principle.
Alternatives rejected:

- **Drop-and-recreate (the `1.sqm` pattern)**: loses every COMPLETED row → the next cycle
  re-uploads the member's whole post-cutoff library. That is the failure this project exists to
  prevent; `1.sqm` could afford it only because the pre-assetId ledger was rebuildable.
- **NOT NULL without DEFAULT**: SQLite rejects the ALTER outright on a non-empty table, and even
  on the fresh-create side it bricks the staged revert's 4-column INSERT (D4).
- **Nullable column**: two "unknown" states (NULL and '') with no benefit; the sentinel must be
  a single, equality-matchable value for the backfill's WHERE clause and the honest fakes.

The `.sq` CREATE carries the **identical** `DEFAULT ''` so the SQLDelight verify task
(`verifyCommonMainLedgerDatabaseMigration`, part of `build`) proves migrated ≡ created.

### D2 — The backfill's seat: the shared cycle, after the reconcile settles

The sentinel's true value lives in config, which arrives with the cycle gate — so the sweep is
`LedgerStore.backfillEventId(eventId)`, invoked by `UploadCycle.run()` once per cycle, **after**
`reconcile(eventId)` returns true. Why exactly there:

- **Single-writer law**: the shared `UploadCycle` is the one seat that runs on *both* tiers'
  cycles (PhotoKit extension on ≥26.1, app-driven on 18–26.0) and never in a reader. A root-wired
  backfill would repeat the exact mistake the reconcile requirement documents (the app tier
  shipped without one).
- **After the reconcile gate**: a settled reconcile means the marker agrees with the configured
  event — on a switch, the reconciler's authoritative `resetTo` has already re-baselined (its
  seeds carry the new event), so the sweep can never label another event's rows mid-switch. A
  deferred reconcile (failed listing) skips the sweep too; the sentinel is durable and waits.
- **Idempotent and cheap**: the WHERE clause matches only the sentinel, so from the second cycle
  on it is a no-op UPDATE. Failure is `runCatching`-contained (provenance hygiene must never fail
  a cycle) and retried next cycle by construction.

The engine's own writes thread the eventId per cycle: `SyncEngine` gains an `eventId`
constructor parameter, supplied by `engineFor(config)` — the engine was already minted per cycle
from that cycle's config precisely so config-scoped facts (the host) arrive this way. The
`LedgerWriter` stays process-lifetime (it is constructed before any config exists), so its record
operations take the eventId per call: `recordX(key, assetId, attempt, eventId)`.

### D3 — `''` is a sentinel the writer sweeps, never a value anything trusts

`LedgerEntry.eventId == ""` means exactly "recorded by a build that did not carry provenance"
(pre-4.sqm rows, or a staged-revert build's writes). Nothing reads it as an event; the only code
that matches it is the backfill's WHERE clause. `SyncEngine` requires the eventId with **no
default** — a defaulted `""` would silently mint sentinel rows forever, which is the standard
this codebase applies to every dangerous default (see `UploadCycle`'s port parameters).

### D4 — Downgrade stance: the schema is a one-way door; behavior reverts keep the schema

The forcing proof, from the shipped driver (SQLiter 1.3.3, `NativeDatabaseConnection.kt`,
`migrateIfNeeded`, verbatim):

```kotlin
this.withTransaction {
    val initialVersion = getVersion()
    if (initialVersion == 0) {
        create(this)
        setVersion(version)
    } else if (initialVersion != version) {
        if (initialVersion > version)
            throw IllegalStateException("Database version $initialVersion newer than config version $version")
        upgrade(this, initialVersion, version)
        setVersion(version)
    }
}
```

A binary compiled at schema v4 that opens a migrated v5 `ledger.db` **throws at connection
creation** — the DEFAULT cannot save it, because the failure precedes any SQL of ours. So the
PLAN's parenthetical ("old binaries keep working via the DEFAULT") holds at the *SQL* level but
not at the *driver* level, and the honest stance is:

- **A full schema revert is impossible** once any device has migrated. Do not ship a build whose
  `Ledger.sq` tree lacks `4.sqm`.
- **A behavior revert is a staged revert**: revert the Kotlin surface (model, port, threading,
  backfill) while keeping `4.sqm` and the `.sq` column; the store binds `''` where the entry no
  longer carries an eventId. Schema.version stays 5, the driver opens, and the old-shaped
  column-explicit `INSERT OR REPLACE (key, assetId, state, attempt)` lands sentinel rows the
  first post-re-update cycle sweeps. Note the REPLACE half: a reverted build re-recording an
  **existing** key replaces the whole row, so that row's *real* provenance also resets to the
  sentinel — not only new rows are affected — and is likewise re-labeled by the first
  post-re-update sweep. (Verified in-tree: every historical INSERT in `Ledger.sq`
  has always been column-explicit, and a jvmTest pins the v4-shaped insert against the v5
  schema.)
- **Residual risk, stated honestly**: rows written during a revert window carry the sentinel; if
  the device later joins event B, the backfill labels them B even though they uploaded under
  event A. Bounded by reality: a device holds one membership; a *switch* to B triggers the
  reconciler's `resetTo`, which re-baselines the whole ledger from the device listing with B as
  seed provenance anyway — so the mislabel washes out at exactly the moment it could matter, and
  until multi-event lands nothing consults the value at all.
- Same posture as 11a's staged truth (`migrate-config-to-app-group-file`, D5): the write-through
  analog here is the DEFAULT-compatible schema; the "flip" (event-scoped reads) waits for the
  change that owns it.

### D5 — The two-process migration race is serialized by the driver's own transaction

Both processes may open the migrated-from DB concurrently after an update (the OS can run the
extension before the app is opened). What actually happens, per the driver source: SQLiter runs
the version check and the upgrade inside **one transaction on the first connection**
(`migrateIfNeeded` above wraps `getVersion()` → `upgrade` → `setVersion(5)` in
`withTransaction`, a plain `BEGIN;`). Under WAL:

- The winner reads `user_version = 4`, runs the ALTER (acquiring the single write lock), sets
  `user_version = 5` (header writes are transactional), commits.
- A concurrent loser either (a) opens after the commit and reads 5 — `initialVersion == version`,
  no migration; or (b) read 4 under a pre-commit snapshot, in which case its write upgrade fails
  **immediately** with `SQLITE_BUSY_SNAPSHOT` — SQLite does not consult the busy handler for
  snapshot invalidation, so no `busyTimeout` wait occurs on this path (the timeout applies only
  while the winner still *holds* the write lock). Either way the transaction **rolls
  back whole** — the ALTER and the version write together, so no partial state exists — and
  SQLiter closes that connection and rethrows. The loser's next open re-runs the check, sees 5,
  and proceeds. One failed open in the worst case, never a corrupted or half-migrated schema.
- Precedent: `2.sqm` and `3.sqm` shipped through this identical two-process path (same DB, same
  driver, same WAL container) without incident; `4.sqm` is the same statement class
  (single catalog-only ALTER).

The backfill UPDATE races nothing meaningful: only the writer's cycle executes it, exactly one
tier is live per device (the tier switch is structural — `upload-lifecycle`, "Only the selected
tier's producer exists"), and the cycle itself is sequential. Both tiers' *code* carries the
call; both are gated by the same single-writer cycle law.

### D6 — `backfillEventId` dings `changes` once, like every other bulk operation

Provenance is invisible to today's watchers (aggregates ignore it), so a silent sweep would also
be defensible — but a mutation that sometimes signals and sometimes doesn't is a trap for the
multi-event future, where event-scoped reads would make the sweep watcher-visible and the missing
ding a stale-read bug. The change signal is a level trigger ("re-read the truth"), so the extra
ding is free by the spec's own conflation argument. Pinned in the shared contract.

### D7 — The port grows one member, and the fakes stay honest

`backfillEventId` is a `LedgerStore` member (the honesty gate permits port members only), exposed
through `LedgerWriter` like the prunes — writer-family, because only the single writer's cycle
may run it. All three in-memory stores (`:adapter:fake`'s contract-bound store and the two
`:domain` commonTest doubles) implement the same sentinel-only rewrite; the shared
`LedgerStoreContract` pins verbatim storage of `''`, sentinel-only rewriting, idempotence, and
the ding, and runs unchanged against SQLite (JVM + native driver) and the in-memory store.
