## 0. Rebase prerequisite

- [x] 0.1 Confirm `drop-resource-versioning` has landed on `main` and branch from it: `Resource`/ledger
      `version` gone, the `lastModified` removal in place (rejoin seam + backend + `bunny-list-endpoint`),
      and the SQLite 3.35 dialect floor already set in `domain/engine/build.gradle.kts`. (If it has not
      landed, stop — the migration number and rejoin/backend scope below assume its baseline.)

## 1. Ledger schema & backend (`:domain:engine`)

- [x] 1.1 `Ledger.sq`: drop the `updatedAt INTEGER AS Instant NOT NULL` column from the `CREATE TABLE`;
      remove it from the `get` SELECT and the `put` INSERT column/value lists. (Row is now
      `key, assetId, state, attempt`.)
- [x] 1.2 `Ledger.sq` `aggregates`: drop `MAX(updatedAt) AS lastTouch` from the `perAsset` CTE and the
      outer `MAX(CASE WHEN notDone = 0 THEN lastTouch END) AS newestCompletionAt` column — the query
      returns only `pending` and `completed`.
- [x] 1.3 Add `3.sqm` (v3→v4): `ALTER TABLE ledgerRow DROP COLUMN updatedAt;` — raw SQL, no type
      annotations, row-preserving. Mirror the `2.sqm` header-comment style. (No `build.gradle.kts`
      change — the 3.35 dialect floor is already present.)
- [x] 1.4 `SqlDelightLedgerBackend`: remove `EpochMillisAdapter` and the `updatedAt` adapter wiring;
      update the `get` and `aggregates` row mappers to the narrower column sets (keep the
      `AS LedgerState` enum adapter).
- [x] 1.5 `Ledger.kt`: drop `updatedAt` from `LedgerEntry`; remove the `clock`/`Clock` constructor
      param from `LedgerWriter` and the `clock.now()` in `record(...)`; drop `newestCompletionAt` from
      `LedgerAggregates` and `LedgerSnapshot`.
- [x] 1.6 Update engine test doubles and the contract: `InMemoryLedgerBackend` (engine commonTest),
      `LedgerBackendContract`, `SqlDelightLedgerBackendTest`, `LedgerWatcherTest`, `SyncEngineTest` —
      drop `updatedAt`/`newestCompletionAt`, the stamp-the-time scenario, and any injected-clock
      plumbing; the photokit-extension `InMemoryLedgerBackend` too.
- [x] 1.7 Add a migration test exercising v3→v4 on the JVM sqlite driver: an existing row survives the
      `DROP COLUMN` with `key/assetId/state/attempt` intact and no `updatedAt` column remains.

## 2. Status projection (`:domain:status`)

- [x] 2.1 `SyncProgress`: remove the `lastFinishedAt: Instant?` field and its doc paragraph;
      classification is already counts-only and unchanged.
- [x] 2.2 `ObservedCompletionOverlay`: drop `newestCompletionAt` from `Overlaid` and from the
      `overlay(...)` construction.
- [x] 2.3 `LedgerSyncStatusSource.mint`: stop setting `lastFinishedAt`.
- [x] 2.4 Update status tests (`SyncProgressTest`, `ObservedCompletionOverlayTest`,
      `LedgerSyncStatusSourceTest`) to drop the timestamp field and the "lastFinishedAt equals the
      completion's timestamp" assertions.

## 3. Presentation (`:domain:presentation`)

- [x] 3.1 `StatusContainerHost`: remove the `clock`/`Clock` constructor param, the `clock.now()` seeds,
      `minuteTicker()`, `relativeTime(...)`, and `SyncProgress.finishedAgo(...)`; collapse the
      `combine(inputs, minuteTicker())` to collect `inputs` directly; drop `now: Instant` from
      `reduceFrom`/`toUiState`.
- [x] 3.2 `UiState`: remove the `finishedAgo` parameter from `InProgress` and `Completed`.
- [x] 3.3 Update `StatusContainerHostTest`: delete the relative-time / minute-tick re-emission tests
      and drop `finishedAgo` from reduction assertions.

## 4. UI (`:domain:ui`)

- [x] 4.1 `StatusScreen`: `inProgressCaption` keeps only the `{n} in progress` label (no
      `finishedAgo`), returning null at `inProgress = 0`; the `Completed` hero renders no detail line.
- [x] 4.2 Update `StatusScreen` tests to the new captions (InProgress: "{n} in progress" or none;
      Completed: no detail line).

## 5. Rejoin (`:capability:rejoin`)

- [x] 5.1 `JoinEvent`: remove the `clock`/`joinTime` (left over from `drop-resource-versioning`'s
      seed-at-join-time); seeds construct `LedgerEntry` with no `updatedAt`.
- [x] 5.2 Update rejoin tests (`JoinEventTest`, `JoinThenEngineTest`) to the timestamp-free seed.

## 6. Desktop harness (`:app:desktop`)

- [x] 6.1 `PanelController`: drop `lastFinishedAt` from the `progress(...)` helper and all presets;
      remove the `clock`/`Clock` constructor param and the `now() - 5.minutes` time forging.

## 7. Docs

- [x] 7.1 `docs/design.md` §2.4: drop the stale `lastFinishedAt`/`newestCompletionAt` notes and update
      the `LedgerAggregates(pending, completed, newestCompletionAt …)` signature to drop the timestamp.

## 8. Verify

- [x] 8.1 `./gradlew build` green (compiles all targets + JVM tests, incl. the new migration test and
      the offscreen UI tests).
- [x] 8.2 `./gradlew compileIosMainKotlinMetadata` green (iOS source sets compile clock-free).
- [x] 8.3 Confirm no production `Clock`/`.now()` remains: `grep -rn "Clock\|\.now()" domain capability app
      --include=*.kt | grep -v Test` returns nothing.
- [ ] 8.4 Run the desktop harness (`./gradlew :app:desktop:run`) and confirm Completed shows no detail
      line and InProgress shows only "{n} in progress".
