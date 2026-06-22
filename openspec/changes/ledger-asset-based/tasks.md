## 1. Engine value types (`:domain:engine`)

- [x] 1.1 Add `assetId: String` to `Resource` (opaque, like `filename`); update its KDoc — the
  engine carries but never interprets it. Update the `Resource` doc that says assets live "in a
  later layer above it".
- [x] 1.2 Add `assetId: String` to `LedgerEntry` (field, `equals`/`hashCode`/`toString`).
- [x] 1.3 `LedgerWriter.record*` become `recordX(key, assetId, attempt, version)` (positional);
  `SyncEngine.started/complete/retry` pass `resource.assetId`. `decide()` unchanged.

## 2. Ledger seam — asset-keyed ops (`:domain:engine`)

- [x] 2.1 `LedgerBackend`: replace `deleteByKeyPrefix`/`retainKeys` with `deleteByAssetId(assetId)`
  and `retainAssets(keep: Set<String>)` (both ding `changes`); document `assetId` as a second
  opaque grouping field.
- [x] 2.2 `LedgerWriter`: expose `deleteByAssetId`/`retainAssets` (writer-only); remove the old
  prefix/retainKeys wrappers.
- [x] 2.3 `Ledger.sq`: add `assetId TEXT NOT NULL` column + `CREATE INDEX` on `assetId`; `put`
  writes `assetId`; replace the prune queries with `deleteByAssetId` (`WHERE assetId = :assetId`)
  and the pieces for `retainAssets` (select distinct assetIds + delete-by-assetId); rework
  `aggregates` to the `assetId`-grouped CTE.
- [x] 2.4 `SqlDelightLedgerBackend`: map the `assetId` column in `get`/`put`; implement
  `deleteByAssetId`; implement `retainAssets` (read present assetIds, diff in Kotlin, delete the
  complement per straggler — no unbounded `IN`); rework `aggregates`.
- [x] 2.5 Implement the new ops + `assetId` in `DarwinCrossProcessLedgerBackend` (delegate +
  notify) and the two in-memory fakes (`:domain:engine` & `:app:ios:photokit-extension`); update
  the status module's `RowStore` fake.

## 3. Schema migration (`:domain:engine`)

- [x] 3.1 Bump the SQLDelight schema to v2 and add `migrations/1.sqm`: `DROP TABLE ledgerRow;` +
  recreate with `assetId` and the index (destructive — no data kept).
- [x] 3.2 Confirm the native/iOS driver picks up the migration (schema version wired in
  `iosLedgerBackend`); the App-Group `ledger.db` migrates on first open of the asset-based build.

## 4. Aggregates projection (`:domain:status`)

- [x] 4.1 Keep `LedgerAggregates` field names (`pending`/`completed`); update KDoc to say they
  count PHOTOS (assets), not resource rows; `newestCompletionAt` = newest fully-completed photo.
- [x] 4.2 Update `SyncProgress` doc to match (state classification unchanged — `pending>0`
  equivalence holds). No `LedgerSyncStatusSource` logic change.

## 5. Extension discovery (`:app:ios:photokit-extension`)

- [x] 5.1 `IosUploadJobPlatform.resourcesFor`: set `Resource.assetId` to the normalized
  `localIdentifier` (the value already used for the key prefix).
- [x] 5.2 `UploadCycle`: prune via `ledger.deleteByAssetId(L)` per `discovery.removedAssetIds`;
  reconcile via `ledger.retainAssets(discovery.resources.map { it.assetId }.toSet())` on a
  fully-drained full enumeration. Remove the `"<L>-"` prefix construction.
- [x] 5.3 `UploadCycle.reconstruct`: read `assetId` from the ledger entry when rebuilding a
  `Resource` for a returned job.

## 6. Tests

- [x] 6.1 `LedgerBackendContract`: replace the prefix/retain-keys scenarios with delete-by-assetId
  and retain-assets (incl. the `assetId`-grouped aggregate scenarios: photo complete only when all
  rows complete, count-by-asset, newest-fully-completed-photo); thread `assetId` into the entry
  helper and writer-record assertions.
- [x] 6.2 `SqlDelightLedgerBackendTest`: rename the bind-limit test to a large retain-assets keep-set.
- [x] 6.3 Add a JVM migration test: create a pre-`assetId` `ledgerRow` with rows, open under v2,
  assert the `assetId` column exists and the table is empty.
- [x] 6.4 `SyncEngineTest`: add `assetId` to the `resource()` helper (defaulted); add a test that
  `assetId` is carried into the recorded entry and does not change the decision.
- [x] 6.5 `UploadCycleTest`: thread `assetId` into the `resource()` helper; convert the prune tests
  to `deleteByAssetId`/`retainAssets`; keep the mid-upload-deletion-rests and reconcile-gating cases.
- [x] 6.6 `S3PresignGoldenTest`: add the defaulted `assetId` arg (presigner ignores it).

## 7. Build, docs, spec sync

- [x] 7.1 `./gradlew build` (JVM + headless UI) and `./gradlew compileIosMainKotlinMetadata` (iOS
  proxy) green.
- [x] 7.2 Update `docs/design.md` §2.2/§2.4 (engine carries an opaque `assetId`; ledger is
  asset-grouped but still a dumb store).
- [x] 7.3 `npx openspec validate "ledger-asset-based" --strict` passes; sync deltas into canonical
  (`sync-ledger`, `sync-engine`, `ios-background-upload`) — incl. the sync-engine Purpose reword —
  and archive after merge.
