## Why

The ledger is a resource-keyed row store that is deliberately *asset-blind*: an asset's
resources are grouped only by a `<localId>-` key prefix convention living in the extension. Two
things now justify making the asset a first-class (if opaque) column on every row:

1. **Cleaner model.** Deletion cleanup currently leans on a stringly-typed key-prefix
   (`deleteByKeyPrefix("<localId>-")`) plus a half-open-range trick to dodge `LIKE`'s `_`
   wildcard. A real `assetId` column turns that into a plain `deleteByAssetId(assetId)`.
2. **Photo-level status.** Aggregates count resource *rows* — a photo with two resources counts
   as two. Grouping by asset lets `pending`/`completed` mean *photos*, so a future "N photos
   backed up" reads correctly. (No count is rendered today, so this is forward-looking — the
   sync-state classification is unchanged because "any resource pending" ⟺ "any photo pending".)

This supersedes the key-prefix prune mechanism added in `ledger-prune-deleted-assets` (archived,
never merged to main). It stays one-way: no S3 object is ever deleted.

## What Changes

- Add `assetId: String` to the ledger row (an **opaque** grouping field — the backend stores,
  groups, and matches it by equality but never interprets it; the "dumb row store" identity
  holds). It rides on `LedgerEntry`, the `LedgerWriter.record*` ops (`recordX(key, assetId,
  attempt, version)`), and the engine's `Resource`. The PK / S3 object key
  (`<localId>-<kind>.<ext>`) is **unchanged** — `assetId` is an additional column, not a key
  change, so `assetId` is intentionally redundant with the key prefix.
- **Replace** the key-prefix prune ops with asset-keyed ones: `deleteByKeyPrefix(prefix)` →
  `deleteByAssetId(assetId)` (an indexed `WHERE assetId = ?`; the half-open-range trick is
  dropped); `retainKeys(keep)` → `retainAssets(keep)` (keeps the bind-safe complement-delete).
- **Rework aggregates to photos**: a photo is `completed` only when *all* its resource rows are
  `COMPLETED`; `pending` counts photos with any non-completed row; `newestCompletionAt` is the
  newest *fully-completed* photo's time. `LedgerAggregates` field names stay (`pending`/`completed`)
  with a sharp KDoc that they now count photos, not rows.
- Schema: add `assetId TEXT NOT NULL` and an index on `assetId`; add a destructive `1.sqm`
  migration (`DROP TABLE ledgerRow; CREATE TABLE … with assetId`) bumping the schema to v2 — the
  ledger is rebuildable, so no data is preserved (the on-device `ledger.db` in the App-Group
  container survives reinstall, so the schema change must be handled, not just the data).
- Extension discovery: prune via `deleteByAssetId(L)` for each deleted (normalized) `localIdentifier`,
  and reconcile via `retainAssets(liveAssetIds)` derived from `discovery.resources.map { it.assetId }`.
- `Resource` becomes asset-aware (carries an opaque `assetId`); the engine carries it through to
  the ledger but `decide()` still reasons purely per-resource — `assetId` is pure pass-through.

No `DELETED` state, no `decide()` change, no S3/upload change, no status-state-machine change, no
UI change, no harness change.

## Capabilities

### New Capabilities
<!-- None. -->

### Modified Capabilities
- `sync-ledger`: storage seam (`assetId` opaque field; asset-keyed prune ops), aggregate reads
  (photo-grouped), record operations (`+assetId`), SQLDelight backend (`+`column/index, stale-line
  fix), a new schema-migration requirement, and the two prune requirements (asset-keyed).
- `sync-engine`: a new "Resource asset identity" requirement (`Resource` carries an opaque
  `assetId`) and a Purpose reword (the engine now carries — but still does not interpret — an
  asset grouping).
- `ios-background-upload`: the deletion-prune requirement becomes asset-keyed (`deleteByAssetId`,
  `retainAssets(liveAssetIds)`).

## Impact

- **Specs**: `sync-ledger`, `sync-engine`, `ios-background-upload`.
- **Code**:
  - `:domain:engine` — `LedgerEntry` (+`assetId`), `LedgerWriter.record*`/prune ops, `Resource`
    (+`assetId`), `SyncEngine` record calls, `Ledger.sq` (column/index/asset-keyed queries),
    `1.sqm` migration, `SqlDelightLedgerBackend`, `DarwinCrossProcessLedgerBackend`, the two
    in-memory fakes, `LedgerBackendContract`, the status module's `RowStore`.
  - `:domain:status` — `aggregates` CTE; `LedgerAggregates` KDoc (meaning = photos); `SyncProgress`
    doc.
  - `:app:ios:photokit-extension` — `IosUploadJobPlatform.resourcesFor` sets `assetId`;
    `UploadCycle` uses `deleteByAssetId`/`retainAssets`; `reconstruct` reads `entry.assetId`.
  - Tests — `UploadCycleTest`, `SyncEngineTest`, `S3PresignGoldenTest` get a defaulted `assetId`.
- **Discarded**: the half-open key-range prune from `ledger-prune-deleted-assets` (superseded).
- **Docs**: `Resource` KDoc, `docs/design.md` §2.2/§2.4.
- **No migration of data** (rebuildable), **no S3 / UI / harness / state-machine change**.
