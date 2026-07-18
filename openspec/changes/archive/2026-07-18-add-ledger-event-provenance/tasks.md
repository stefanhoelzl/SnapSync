# Tasks — add-ledger-event-provenance

## 1. Schema (`:adapter:generic`)

- [x] 1.1 `4.sqm` (v4 → v5): `ALTER TABLE ledgerRow ADD COLUMN eventId TEXT NOT NULL DEFAULT '';`
      — row-preserving, sentinel-defaulted, PK unchanged
- [x] 1.2 `Ledger.sq`: CREATE TABLE gains `eventId TEXT NOT NULL DEFAULT ''` (identical DEFAULT so
      `verifyCommonMainLedgerDatabaseMigration` proves migrated ≡ created); `get`/`put` carry the
      column; new `backfillEventId` sentinel-only UPDATE
- [x] 1.3 `SqlDelightLedgerStore`: map/bind `eventId` in `get`/`put`/`resetTo`; implement
      `backfillEventId` (one UPDATE + one ding)

## 2. Model + port + threading (`:domain`)

- [x] 2.1 `LedgerEntry` gains `eventId` (equality + toString; `""` documented as the
      pre-provenance sentinel)
- [x] 2.2 `LedgerStore.backfillEventId(eventId)` port member (sentinel-only, idempotent, one ding)
- [x] 2.3 `LedgerWriter`: `recordX(key, assetId, attempt, eventId)`; `backfillEventId` exposed
      writer-family
- [x] 2.4 `SyncEngine` gains the per-cycle `eventId` (no default); all three lifecycle records
      write under it
- [x] 2.5 `uploadCore.engineFor` supplies `config.eventId`; `UploadCycle.run()` sweeps once per
      settled cycle (after the reconcile gate, `runCatching`-contained)
- [x] 2.6 `ExtensionReconciler` seeds carry the reconciled event as provenance

## 3. Fakes + contract

- [x] 3.1 `:adapter:fake` `InMemoryLedgerStore` + the two `:domain` commonTest doubles implement
      the sentinel-only rewrite (port members only — honesty gate)
- [x] 3.2 `LedgerStoreContract`: verbatim `eventId` round-trip (incl. the sentinel), sentinel-only
      backfill, idempotence, ding, writer-family exposure, records carry the id

## 4. Tests

- [x] 4.1 Migration test: v4 schema + COMPLETED row → migrate 4→5 → row survives with sentinel →
      backfill sweeps → fresh put round-trips
- [x] 4.2 Staged-revert test: a v4-shaped column-explicit INSERT works on the v5 schema (DEFAULT
      fills the sentinel)
- [x] 4.3 `SyncEngineTest`: every record operation carries the engine's eventId
- [x] 4.4 `UploadCycleTest`: the cycle sweeps sentinel rows to the live event (provenance only,
      states untouched, provenanced rows never rewritten); new records carry the live id; a
      deferred reconcile defers the sweep
- [x] 4.5 `ReconcilerTest`: seeds carry the reconciled event

## 5. Verification

- [x] 5.1 `./gradlew build` (includes `verifyCommonMainLedgerDatabaseMigration` +
      `verifySqlDelightMigration`)
- [x] 5.2 `./gradlew compileIosMainKotlinMetadata`
- [x] 5.3 `./gradlew architectureDiagrams` (regenerate; commit if changed)
- [x] 5.4 `:test:architecture:test` (FakeHonestyTest over the changed fake; RuntimeIdentityTest
      untouched — `ledger.db` unchanged)
- [ ] 5.5 Session C part 2 (device): update-in-place over a joined install → one cycle → zero new
      upload jobs; both-process WAL open post-update; positive backfill evidence — grep both
      processes' `debug.log` for `eventId backfill: swept N row(s)` (logged by
      `SqlDelightLedgerStore` only when N > 0, so the line appearing exactly once proves the
      sweep ran and the steady state is silent)
