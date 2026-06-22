## MODIFIED Requirements

### Requirement: Discovery prunes ledger rows for deleted assets

The extension SHALL prune ledger rows for assets removed from the library, via two paths driven
from its discovery cycle (both as `LedgerWriter` writes, preserving the single-writer invariant).
This keeps the ledger honest about what still exists on device and, critically, removes a row
left non-`COMPLETED` by an asset deleted mid-upload — which would otherwise keep `pending > 0`
forever and hold the extension in the perpetual `processing` re-invocation loop (see
"Cap-aware creation and tri-state processing result"). No S3 object is deleted; the one-way model
is unchanged.

- **Incremental (every cycle):** when deriving the changed set from
  `fetchPersistentChanges(since:)`, the extension SHALL also collect each change record's
  `deletedLocalIdentifiers()` and, for each removed `localIdentifier` `L` (normalized `/`→`_` to
  match the stored `assetId`), call `deleteByAssetId(L)` so all of that asset's resource rows are
  removed.
- **Reconcile (backstop):** on a full enumeration that completes with no `PHPhotosErrorLimitExceeded`,
  the extension SHALL call `retainAssets(liveAssetIds)`, where `liveAssetIds` is the set of
  `assetId`s of the resources it built during enumeration — pruning rows for assets no longer
  present, closing the gap for deletions that occurred while the persistent-change token was expired.

A re-added asset (e.g. recovered from "Recently Deleted") whose rows were pruned SHALL be treated
as new work: discovery finds no ledger entry, so the engine returns `Upload` and a fresh
(idempotent) job is created. No `DELETED` state is introduced and the upload decision is unchanged.

#### Scenario: Removed asset's rows are pruned incrementally
- **WHEN** `fetchPersistentChanges(since:)` reports `deletedLocalIdentifiers` containing asset `L`,
  and the ledger holds rows for `L`'s resources
- **THEN** the extension calls `deleteByAssetId(L)` and those rows are removed, so `L` no longer
  contributes to `pending`/`completed`

#### Scenario: Mid-upload deletion lets the extension rest
- **WHEN** an asset deleted before its upload completed leaves a non-`COMPLETED` ledger row, and a
  later cycle's change feed reports that asset as removed
- **THEN** the extension prunes the row, the ledger reaches no pending rows, and `process()` can
  return `completed` instead of looping on `processing`

#### Scenario: Full enumeration reconciles against the live library
- **WHEN** a full enumeration completes with no `limitExceeded` and the ledger holds rows for an
  asset that is no longer present in the library
- **THEN** the extension calls `retainAssets(liveAssetIds)` and the absent asset's rows are removed

#### Scenario: Reconcile is skipped on a cap-truncated cycle
- **WHEN** a full enumeration stops early because job creation raised `limitExceeded`
- **THEN** the extension SHALL NOT call `retainAssets`, so the un-enumerated tail's rows are not
  wrongly pruned (reconcile runs only when enumeration completed fully — the same gate that
  advances the change token)

#### Scenario: Re-added asset re-uploads after pruning
- **WHEN** an asset whose rows were pruned reappears in the library (e.g. recovered from
  "Recently Deleted")
- **THEN** discovery finds no ledger entry for its resources, the engine returns `Upload`, and a
  fresh job is created (the idempotent PUT targets the unchanged key)
