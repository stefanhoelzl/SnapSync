## Why

A photo deleted from the library *before* its upload completes leaves a non-`COMPLETED`
ledger row that discovery never revisits. That row keeps `pending > 0` forever, which —
per the `ios-background-upload` "pending in-flight work requests re-invocation" rule —
makes `process()` return `processing` forever, so the OS re-invokes the extension forever
and it never rests. Pruning rows for deleted assets lets the extension reach `completed`
and rest, and keeps the ledger (and its `pending`/`completed` aggregates) honest about
what still exists on device. This stays fully within the one-way model: nothing is deleted
from S3.

## What Changes

- Add two key-only, asset-blind removal operations to the ledger storage seam:
  - `deleteByKeyPrefix(prefix)` — delete every row whose key starts with `prefix`.
  - `retainKeys(keep)` — delete every row whose key is **not** in `keep`.
  - Both ding `changes` like `put`/`clear`; both are writes, so they are reachable only
    through `LedgerWriter` (reader-typed access cannot call them). `LedgerEntry`, the record
    operations, and the aggregate semantics are **unchanged** — no new column, no migration.
- Wire deletion detection into the extension's discovery cycle:
  - **Incremental** (every wake): collect `deletedLocalIdentifiers()` from the change feed
    and call `deleteByKeyPrefix("<localId>-")` per removed asset, so an asset's rows
    (across all its resources) are pruned promptly.
  - **Reconcile** (backstop): on a fully-completed full enumeration (no `limitExceeded`),
    call `retainKeys(currentKeySet)` to prune rows for assets missing from the library —
    closing the gap when the persistent-change token expired across a deletion.
- The extension owns the `"<localId>-"` prefix convention; the engine/ledger never learns
  what an "asset" is and stays platform-neutral.

No `DELETED` state, no change to the upload decision (`decide()`): a re-added asset finds no
row → `null` → a fresh, idempotent `Upload`.

## Capabilities

### New Capabilities
<!-- None. -->

### Modified Capabilities
- `sync-ledger`: the storage seam gains `deleteByKeyPrefix` and `retainKeys` (both ding;
  both write-only), with backend-contract scenarios every `LedgerBackend` must satisfy.
- `ios-background-upload`: discovery additionally collects removed assets and prunes their
  rows (incremental prefix-delete), and a fully-completed full enumeration reconciles the
  ledger against the live library (`retainKeys`), guarded off on a cap-truncated cycle.

## Impact

- **Specs**: `sync-ledger`, `ios-background-upload`.
- **Code**:
  - `:domain:engine` — `LedgerBackend` interface + `LedgerWriter` (new ops),
    `Ledger.sq` (new `deleteByKeyPrefix`/`retainKeys` queries, no schema change),
    `SqlDelightLedgerBackend`, `IosLedgerBackend`, `DarwinCrossProcessLedgerBackend`, the
    two in-memory fakes, and `LedgerBackendContract`.
  - `:app:ios:photokit-extension` — discovery (`IosUploadJobPlatform` collects
    `deletedLocalIdentifiers`; full-enum key-set capture) and the cycle that issues the
    prune/reconcile writes.
- **No migration** (no schema change), **no S3 behavior change**, **no status-projection
  change** (pruned rows simply drop out of the existing aggregates).
