## Context

The ledger stores one row per `(asset, resource)`, keyed `"<localId>-<kind>.<ext>"`. It is
deliberately asset-blind: `LedgerEntry` carries no asset notion, and the just-archived
`ledger-prune-deleted-assets` change went out of its way (its decision D2) to *avoid* an `assetId`
column — pruning a deleted asset used a `deleteByKeyPrefix("<localId>-")` plus a half-open key
range (`key >= lo AND key < hi`) to dodge `LIKE`'s `_` wildcard. That change is archived but never
merged to main.

We now reverse that stance: add an opaque `assetId` to every row. The motivation is cleaner
modelling (asset-keyed deletes replace the prefix hack) plus forward-looking photo-level status
(aggregates count photos, not resource rows). This change supersedes the prune mechanism on the
same branch, so the prefix-ops never reach main.

## Goals / Non-Goals

**Goals:**
- `assetId` on every ledger row, threaded `Resource → engine → writer → backend`.
- Asset-keyed deletion: `deleteByAssetId` / `retainAssets` replace the key-prefix ops.
- Photo-grouped aggregates: a photo is complete only when all its resources are.
- Keep the ledger a "dumb row store" — `assetId` is opaque (stored/grouped/matched, never
  interpreted), so platform-neutrality holds.

**Non-Goals:**
- No data migration (the ledger is rebuildable) — but the *schema* change is handled.
- No two-table (asset/resource) model — still one row per resource, plus a column.
- No `DELETED` state, no `decide()` change, no S3/upload change.
- No UI count, no sync-state-machine change, no harness change.

## Decisions

### D1: `assetId` is a second *opaque* column — "dumb row store" survives
The backend stores, groups (`GROUP BY assetId`), and matches (`WHERE assetId = ?`) `assetId` but
never interprets it — exactly like `key`. The JVM/console may use any string; iOS uses the
normalized `localIdentifier`. So the ledger stays platform-neutral; only the prior claim that
asset grouping is "the caller's convention, unknown to the seam" is recast to "an opaque grouping
field the seam stores but does not interpret."

*This reverses D2 of `ledger-prune-deleted-assets`.* We previously avoided the column to spare
`LedgerEntry` the ripple; the cleaner-model + photo-status goals now justify it.

### D2: `Resource.assetId`, opaque pass-through — engine carries, never reasons
`Resource` gains `assetId: String`. `decide()` is unchanged (per-resource, by `key`/`version`);
the engine only *carries* `assetId` into the recorded entry via the writer. Record ops become
`recordX(key, assetId, attempt, version)` (primitives, keeping the writer decoupled from the
engine's `Resource` type). The sync-engine Purpose ("knows only resources — asset handling lives
in a later layer above") is reworded: the engine carries an opaque asset grouping but still does
not interpret it.

### D3: Key unchanged; `assetId` redundant with the key prefix
The PK / S3 object key stays `"<localId>-<kind>.<ext>"` (changing it would re-upload everything /
orphan S3 objects). So `assetId` duplicates the key's prefix. This is unavoidable — the key's
dashes make the `localId` unparseable from it (the exact reason a column is needed) — and
harmless: `key` is the opaque destination identity, `assetId` the explicit grouping.

### D4: Asset-keyed prune — drop the range trick, keep the bind-safe one
- `deleteByAssetId(assetId)` = `DELETE … WHERE assetId = ?`, backed by an `assetId` index. The
  half-open-range trick dies (equality needs no wildcard dance).
- `retainAssets(keep)` = delete the complement: read the present `assetId`s, diff against `keep` in
  Kotlin, delete each straggler — never binding `keep` into one statement (the bind-variable-limit
  technique carries straight over from `retainKeys`).
- Both stay writer-only (not on `LedgerReader`). The "neither stamps `updatedAt` nor depends on a
  prior read" phrasing is rescoped to the *writer layer* (the backend's `retainAssets` does read
  first — an implementation detail, not the seam contract).

### D5: Photo-grouped aggregates via a CTE
```sql
WITH perAsset AS (
  SELECT assetId,
         SUM(CASE WHEN state != 'COMPLETED' THEN 1 ELSE 0 END) AS notDone,
         MAX(updatedAt) AS lastTouch
  FROM ledgerRow GROUP BY assetId
)
SELECT COUNT(CASE WHEN notDone > 0 THEN 1 END) AS pending,
       COUNT(CASE WHEN notDone = 0 THEN 1 END) AS completed,
       MAX(CASE WHEN notDone = 0 THEN lastTouch END) AS newestCompletionAt
FROM perAsset;
```
One round-trip. `LedgerAggregates` field names stay `pending`/`completed` (now *photos*) with a
sharp KDoc to avoid the row-vs-photo trap. `newestCompletionAt` is the newest *fully-completed*
photo's time — behaviorally identical to the row-level `MAX` for the state machine (which reads
`lastFinishedAt` only when `pending == 0`, where the two definitions converge), just cleaner for a
future "last photo backed up" display.

### D6: Destructive schema migration (no data kept)
The on-device `ledger.db` lives in the App-Group container and survives reinstall, so the v1→v2
schema change must be handled — "no data to migrate" ≠ "nothing to do". Ship a one-statement
`1.sqm`: `DROP TABLE ledgerRow; CREATE TABLE ledgerRow (… assetId …);` bumping the schema to v2.
SQLDelight runs it 1→2 on existing DBs; fresh installs create v2 directly. Re-enumeration rebuilds
the ledger with proper `assetId`s.

### D7: Discovery prunes by asset id; reconcile from the live asset set
Incremental: for each deleted (normalized `/`→`_`) `localIdentifier` `L`, `deleteByAssetId(L)`.
Reconcile (on a fully-drained full enumeration, same gate as before): `retainAssets(liveAssetIds)`
where `liveAssetIds = discovery.resources.map { it.assetId }.toSet()` — naturally deduped, and the
platform has asset ids more directly than resource keys (no new `Discovery` field needed).

## Risks / Trade-offs

- **Reversing the just-shipped asset-blind design within the same branch** → honest as a journey;
  the prefix-ops never reach main, so only the asset-keyed form ships. Two archived changes ride in
  one PR (the 2nd supersedes the 1st's prune mechanism).
- **`LedgerAggregates` names kept but meaning changed (rows→photos)** → semantic trap for future
  readers → mitigate with a sharp KDoc; the state machine is unaffected (`pending>0` equivalence).
- **Destructive migration wipes the on-device ledger** → intended; the ledger is rebuildable and
  re-enumeration repopulates. Stated in the migration requirement.
- **`assetId` redundant with the key** → unavoidable and harmless (D3).
- **Migration testability is thin on Linux** → the native/on-device migration can't run on Linux;
  the JVM path is testable (open a v1 DB with rows, migrate, assert the v2 schema + empty table).
  Lean on that JVM test + CI's `ios-test` for the native driver.

## Migration Plan

One SQLDelight migration (`1.sqm`, destructive). No data migration. On-device: the migration runs
automatically when the asset-based build opens the existing v1 `ledger.db`; the next extension
cycle re-enumerates and repopulates with `assetId`s. Rollback is moot pre-1.0 (a downgrade would
re-run from v1 schema on its own DB copy; the App-Group `ledger.db` is disposable either way).
