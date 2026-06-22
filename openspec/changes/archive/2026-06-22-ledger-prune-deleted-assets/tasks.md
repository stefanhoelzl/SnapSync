## 1. Ledger seam — prune operations (`:domain:engine`)

- [x] 1.1 Add `deleteByKeyPrefix(prefix: String)` and `retainKeys(keep: Set<String>)` to the
  `LedgerBackend` interface (both `suspend`, both ding `changes`); document them as verbatim
  key-string removals (asset-blind).
- [x] 1.2 Expose `deleteByKeyPrefix` and `retainKeys` on `LedgerWriter` (delegating to the
  backend); leave `LedgerReader` unchanged so reader-typed access cannot prune.
- [x] 1.3 Add `deleteByKeyPrefix` (prefix match on the `TEXT PRIMARY KEY`) and `retainKeys` to
  `Ledger.sq` as additive queries — **no schema change**. Implement `retainKeys` without an
  unbounded `IN`/`NOT IN` bind list (e.g. read current keys + delete the complement, or a
  temp/bounded approach) so large libraries stay within the driver's bind-variable limit.
- [x] 1.4 Implement both ops in `SqlDelightLedgerBackend`, signalling `changes` after each.
- [x] 1.5 Implement both ops in the iOS backends (`IosLedgerBackend` and the
  `DarwinCrossProcessLedgerBackend` wrapper), feeding `changes`/the Darwin notification as `put`
  does.
- [x] 1.6 Implement both ops in the two in-memory fakes (`:domain:engine` commonTest and
  `:app:ios:photokit-extension` commonTest `InMemoryLedgerBackend`).

## 2. Ledger seam — tests

- [x] 2.1 Extend `LedgerBackendContract` with the prefix-delete and retain-keys scenarios
  (matching rows removed, complement kept, empty-set empties, `changes` signalled) so every
  backend impl is covered.
- [x] 2.2 Add a `retainKeys` test with a keep-set larger than the sqlite bind-variable limit
  (asserts no bind error) against the SQLDelight backend.
- [x] 2.3 Add `LedgerWriter`/`LedgerReader` tests: writer prunes by prefix and by retain-set;
  confirm prune ops are absent from the `LedgerReader` surface (compile-time — assert via the
  reader-typed handle in the existing capability-split test).

## 3. Extension discovery — incremental prune (`:app:ios:photokit-extension`)

- [x] 3.1 In `IosUploadJobPlatform`, collect each change record's `deletedLocalIdentifiers()`
  alongside the existing inserted/updated handling, and surface them from `discoverResources`
  (extend the `Discovery` result with the removed-asset id set).
- [x] 3.2 In the upload cycle, for each removed `localIdentifier` `L` apply the resource-key
  normalisation (`/`→`_`) and call `writer.deleteByKeyPrefix("<L>-")`.
- [x] 3.3 Add commonTest coverage (fake platform + in-memory backend) proving: a removed asset's
  rows are pruned; a mid-upload deletion drops `pending` to zero so the cycle can report
  `completed` instead of `processing`.

## 4. Extension discovery — reconcile backstop

- [x] 4.1 During a full enumeration, accumulate every built resource key into a live key-set.
- [x] 4.2 On a full enumeration that completed with **no** `limitExceeded`, call
  `writer.retainKeys(liveKeySet)` — gated on the same condition that advances the change token;
  do not call it on incremental cycles or cap-truncated full enumerations.
- [x] 4.3 Add commonTest coverage: full-enum reconcile prunes an absent asset's rows; reconcile is
  skipped on a cap-truncated cycle (un-enumerated tail survives).

## 5. Decision regression + spec sync

- [x] 5.1 Add a `decide()`/discovery test confirming a re-added (previously pruned) asset is
  treated as fresh `Upload` (no ledger entry → `Upload`), proving no `DELETED` state or
  decision change is needed.
- [x] 5.2 Run `./gradlew build` (JVM + headless UI tests) and
  `./gradlew compileIosMainKotlinMetadata` (iOS proxy) green; confirm no status-projection or
  `SyncProgress` change was required.
- [x] 5.3 `npx openspec validate "ledger-prune-deleted-assets" --strict` passes; archive after
  merge so `sync-ledger` and `ios-background-upload` canonical specs absorb the deltas.
