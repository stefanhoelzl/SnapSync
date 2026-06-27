## Why

The ledger's `updatedAt` column exists for exactly one purpose: to render the status screen's
"last … ago" line (`finishedAgo`). That line is **redundant** while uploading (the count and the
in-progress indicator already say "working"), **noise** when complete ("47 images synced · 3 d ago"
reads as stale when everything is in fact safe), and the only alternative reading — a "last checked"
liveness stamp — would actively *mislead*, since a sweep that uploads nothing would reset the clock
over a genuine stall. The screen's useful job is **completeness** (`n of N`) and **live activity**
(`{n} in progress`); neither needs a timestamp. Removing `updatedAt` deletes the field **and the last
wall-clock reads left in the Kotlin domain** — engine, status, presentation, and rejoin all become
clock-free and fully deterministic.

**Depends on `drop-resource-versioning`** (the in-flight change in the `date` workspace), which must
land first. This change is written against that change's post-merge baseline: `Resource.version` and
the `ledgerRow.version` column are already gone, the SQLDelight dialect floor is already raised to
SQLite 3.35 (for `ALTER TABLE … DROP COLUMN`), and the backend listing's `lastModified` field is
already removed end-to-end. So this proposal scopes **only** the `updatedAt`/timestamp/clock removal —
it does not re-do the `version` or `lastModified` work.

## What Changes

- **The status screen stops reporting time.** The `finishedAgo` detail line is removed from both
  `InProgress` and `Completed`. `InProgress` keeps only `{n} in progress` (omitted at 0); `Completed`
  becomes the headline + green dot with **no** detail line. `NothingToSync` is unchanged.
- **`updatedAt` is removed from the ledger.** The `ledgerRow.updatedAt` column, the
  `EpochMillisAdapter`, `LedgerEntry.updatedAt`, the `MAX(updatedAt)` aggregate, and
  `LedgerAggregates`/`LedgerSnapshot.newestCompletionAt` all go. `LedgerWriter` loses its injected
  `Clock` (its only use was stamping). After this, the ledger row is `key, assetId, state, attempt`.
- **The status projection drops the timestamp.** `SyncProgress.lastFinishedAt` and
  `Overlaid.newestCompletionAt` are removed. Classification is **unaffected** — it is already a pure
  function of counts (`total == 0 → NothingToSync; synced >= total → Complete; else InProgress`).
- **Presentation becomes clock-free.** With `finishedAgo` gone, the minute ticker, the
  `relativeTime` formatter, and the entire `Clock`/`now` plumbing through the reduction are removed;
  the five-source `combine` no longer needs the ticker. `UiState.InProgress`/`Completed` drop their
  `finishedAgo` parameter.
- **Rejoin stops stamping seeds.** `drop-resource-versioning` already made the seam filename-only and
  set the seed's `updatedAt` to the join time; this change removes that — `JoinEvent` drops its
  `Clock`/`joinTime`, and seeded `COMPLETED` rows carry no timestamp.
- **Schema migration `3.sqm` (v3→v4).** A single row-preserving `ALTER TABLE ledgerRow DROP COLUMN
  updatedAt;` (the dropped column is neither the primary key nor indexed), so an app update keeps the
  ledger and forces no re-enumeration. The 3.35 dialect floor it needs is already in place from
  `drop-resource-versioning`'s `2.sqm`.
- **BREAKING (display):** the "last backed up N ago" text is removed from the status screen.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `sync-ledger`: remove `updatedAt` from the ledger row, the `put`/`get`/`aggregates` queries, and
  `newestCompletionAt` from the aggregates/snapshot; `LedgerWriter` no longer takes or reads a clock;
  add the `3.sqm` row-preserving column-drop migration.
- `sync-status`: remove `lastFinishedAt` from `SyncProgress` and `newestCompletionAt` from the
  overlay; the minted snapshot no longer carries a completion timestamp. Classification (counts-only)
  is unchanged.
- `sync-status-screen`: the `InProgress` detail line is `{n} in progress` only (no `finishedAgo`);
  `Completed` shows no detail line; the presentation layer no longer formats relative time or owns a
  clock, and no longer re-emits on a minute tick.
- `desktop-test-harness`: the forged sync presets drop `lastFinishedAt`; the control panel no longer
  manipulates time, and no preset exercises relative-time rendering.
- `event-rejoin-reconciliation`: seeds record `COMPLETED` without any timestamp; the join no longer
  reads a clock.

## Impact

- **`:domain:engine`**: `Ledger.sq` (column + `aggregates` + `put`/`get`), new `3.sqm`,
  `SqlDelightLedgerBackend` (`EpochMillisAdapter` removed), `Ledger.kt` (`LedgerEntry`,
  `LedgerWriter` clock, `LedgerAggregates`, `LedgerSnapshot`); engine test fakes and
  `LedgerBackendContract`.
- **`:domain:status`**: `SyncProgress`, `ObservedCompletionOverlay` (`Overlaid`),
  `LedgerSyncStatusSource.mint`; tests.
- **`:domain:presentation`**: `StatusContainerHost` (clock/now/minuteTicker/relativeTime removed),
  `UiState`; tests (the relative-time/tick tests are deleted).
- **`:domain:ui`**: `StatusScreen` (`inProgressCaption`, `Completed` hero); tests.
- **`:capability:rejoin`**: `JoinEvent` (drop `Clock`/`joinTime`, seed without timestamp); tests.
- **`:app:desktop`**: `PanelController` presets (clock removed).
- **Docs**: `docs/design.md` §2.4 (the stale `lastFinishedAt`/`newestCompletionAt` notes and the
  `LedgerAggregates` signature).
- **Out of scope** (owned by `drop-resource-versioning`): the `version` removal, the `lastModified`
  removal, the backend `app.ts`/`README`, the `bunny-list-endpoint` spec, and the 3.35 dialect bump.
- **Out of scope**: any new sync-history/“backed up on” feature (a future need would source a photo
  capture date fresh, not reuse this record-operation timestamp).
