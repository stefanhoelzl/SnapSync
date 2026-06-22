## Context

The ledger is the engine's only state: one row per `(asset, resource)`, keyed
`"<localId>-<kind>.<ext>"` (the PHAsset `localIdentifier` with `/`→`_`). It is deliberately
a "dumb row store" — the `LedgerBackend` seam is just `get`/`put`/`aggregates`/`changes`/`clear`,
and `LedgerEntry` carries only `key`/`state`/`attempt`/`version`/`updatedAt`. The engine is
platform-neutral and never reasons about "assets"; only the iOS extension does.

Today nothing ever removes a single row. The extension's change feed
(`fetchPersistentChangesSinceToken`) already enumerates inserted/updated assets but **ignores
`deletedLocalIdentifiers()`** (`IosUploadJobPlatform.kt`). So a deleted photo's rows live on.

This bites hardest for a photo deleted *mid-upload*: its row stays non-`COMPLETED`, discovery
never revisits it, `pending` stays `> 0` forever, and the `ios-background-upload` rule
"pending in-flight work requests re-invocation" makes `process()` return `processing` forever
— the OS keeps re-invoking the extension and it never rests. Deleted-after-upload rows are
milder (they only inflate `completed`), but both are the same fix: prune rows whose asset is
gone.

## Goals / Non-Goals

**Goals:**
- Let the extension reach `completed` and rest after a mid-upload deletion.
- Keep `pending`/`completed` aggregates honest about what still exists on device.
- Detect deletions both promptly (incremental) and durably (reconcile backstop for token expiry).
- Keep the ledger seam minimal and **asset-blind** — no engine knowledge of PhotoKit grouping.

**Non-Goals:**
- No remote deletes — S3 is never touched; the one-way model is unchanged.
- No `DELETED` ledger state, no audit of "was it backed up when deleted."
- No status-projection / `SyncProgress` change (pruned rows drop out of existing aggregates).
- No schema migration.

## Decisions

### D1: Hard-delete rows, don't mark them
A new `DELETED` `LedgerState` would ripple into the exhaustive `decide()` `when`, the aggregate
queries, and every state consumer, and would still need pruning logic later. Hard-delete is
strictly less: removed rows simply stop counting. A re-added asset (e.g. recovered from
"Recently Deleted") finds **no** row → `decide()` returns a fresh `Upload`; the PUT is
idempotent on the unchanged S3 key, so recovery self-heals with **no** `decide()` change.

*Alternative considered — `DELETED` state + tombstone:* preserves a "deleted N, all safe"
audit signal, but costs a central-type change and a new state across all consumers for value we
have no reader for. Rejected for v1.

### D2: Asset-blind, key-only seam — no `assetId` column
Mapping a deleted `localIdentifier` to its rows could be done with an indexed `assetId` column,
but that mutates `LedgerEntry` (the spec's central value object), forces every
`recordRequested/Completed/Failed` to supply an `assetId` (it can't be reliably parsed back out
of the key — `localIdentifier` itself contains `-`), and ripples through all ~5 `LedgerBackend`
implementations plus the contract. Instead the seam gains two **string-key** operations:

- `deleteByKeyPrefix(prefix)` — incremental. The extension passes `"<localId>-"`; the ledger
  matches it as a **half-open key range** `key >= prefix AND key < successor` (successor =
  prefix with its last char bumped), which rides the `TEXT PRIMARY KEY` (BINARY) index. A range,
  not `LIKE 'prefix%'`, on purpose: the key embeds `_` (from `/`→`_`), which `LIKE` treats as a
  single-char wildcard and would over-match (`A_1-` matching `A21-`).
- `retainKeys(keep: Set<String>)` — reconcile. Delete every row whose key ∉ `keep`.

`LedgerEntry`, `put`, `get`, and the aggregates are untouched. The engine never learns what an
"asset" is — the `"<localId>-"` prefix convention lives entirely in the extension. Prefix
collisions are effectively impossible given the fixed `localIdentifier` key format.

### D3: Both detection paths, each with a clear job
- **Incremental** runs every wake: collect `deletedLocalIdentifiers()` in the existing
  change-feed enumerate loop; call `deleteByKeyPrefix("<localId>-")` per removed asset. This is
  what breaks the perpetual-`processing` loop promptly (the extension is already being re-woken
  while pending), so it fixes the motivating bug. Nearly free — the loop already exists.
- **Reconcile** is the backstop for the one gap incremental misses: a deletion that happened
  while the persistent-change token was expired. On a full enumeration the extension already
  builds every current resource key for upload decisions; collect them into the live key-set
  and call `retainKeys(set)`.

### D4: Writes go through `LedgerWriter`; reads stay narrow
Both new ops are sync writes, so they live on `LedgerWriter` (delegating to new backend
methods) — reader-typed holders cannot prune, preserving the compile-time read/write split and
the single-writer (extension-only) invariant. `clear()` stays the one sanctioned app-side reset
on the backend.

### D5: `retainKeys` runs only on a fully-completed full enumeration
A cap-truncated (`PHPhotosErrorLimitExceeded`) cycle has only a **partial** key-set;
`retainKeys` would then wrongly delete the un-enumerated tail. Gate reconcile on the same
condition that advances the token: a full enumeration that completed with no `limitExceeded`.
On first run the ledger is empty so it is a harmless no-op; it earns its keep on token-expiry
re-enumeration.

## Risks / Trade-offs

- **Reconcile deletes the un-enumerated tail if run after a cap** → gate it on a fully-completed,
  cap-free full enumeration only (D5); never run it after incremental (which sees a subset).
- **Prefix match catching the wrong rows** → use a half-open key range, not `LIKE` (the key's
  `_` chars are `LIKE` wildcards that would over-match); the appended `-` delimiter further bounds
  the match to one asset. Pinned by a contract scenario that deletes `A_1-` while keeping `A_2-`.
- **`retainKeys` straggler-delete cost** → on reconcile the keep-set is the whole library and
  the stragglers are just the few recently-deleted assets, so the delete volume is small.
  Implement `retainKeys` inside the backend (select current keys, delete the complement) to
  avoid a SQL `NOT IN` parameter-limit blow-up on large libraries.
- **"Recently Deleted" reports removal before permanent purge (unverified)** → harmless given
  D1: a recovered asset re-uploads idempotently. The exact PHChange timing (enter-trash vs
  purge) and whether iOS wakes the upload extension for a deletion-*only* change are on-device
  unknowns; neither affects correctness (cleanup is caught on the next wake for any reason),
  only promptness. Note, don't block.

## Migration Plan

None required — no schema change. The new `.sq` statements are additive query definitions over
the existing table. Existing rows for already-deleted assets are pruned opportunistically the
next time the extension runs incremental discovery or a full-enumeration reconcile; no backfill,
no data migration, no `clear()`.
