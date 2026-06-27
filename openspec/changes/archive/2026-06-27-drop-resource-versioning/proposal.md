## Why

`Resource.version` is the platform's proof of content identity; on iOS it is the asset's
`modificationDate` (`PhotoLibraryResourceEnumerator` → `assetVersion`). The engine compares it for
equality: a `COMPLETED`/`REQUESTED` key whose incoming `version` differs is re-uploaded (`ReUpload`).

That version is **dangerous**. It is the **asset's** modification date but is stamped onto **every
resource** of that asset, and `PHAsset.modificationDate` moves for **non-content** reasons —
favorite, caption, location/album edits, Photos re-analysis, iCloud metadata sync. Unchanged bytes
therefore look "changed" and re-upload spuriously. The date cannot distinguish a real edit from a
metadata bump, and the one place a "did it change since upload?" signal seems available — comparing
the local `modificationDate` against the backend list's storage timestamp — is a **cross-clock**
comparison (device clock vs storage-server clock) that is unreliable: skew yields false re-uploads or
missed edits. The honest options are "re-upload too much" or "ignore edits."

This change chooses **ignore edits**: treat each uploaded resource as **immutable**. Once a key is
`COMPLETED`, it is never re-uploaded. New keys still upload — a brand-new photo, the *new resource
kinds* a first edit produces (`ios.fullSizePhoto`, `ios.adjustmentData`), and a late-arriving iCloud
full-res — so genuinely new content is still captured; only re-writing an existing key is foreclosed.
Edits are assumed rare; re-editing an already-uploaded resource is the accepted blind spot.

## What Changes

- **BREAKING (internal seam)** `Resource` drops `version`; `SyncDecision.ReUpload` is removed. The
  engine's `ResourceChanged` decision becomes state-only: `COMPLETED`/`REQUESTED` → `AlreadyUploaded`;
  `FAILED`/absent → `Upload`. Only new keys upload.
- The ledger drops the `version` column and the `version` argument on
  `recordRequested`/`recordCompleted`/`recordFailed`; `LedgerEntry` no longer carries it.
- **Migration (row-preserving):** a new `2.sqm` drops the `version` column while **keeping existing
  rows**, so `COMPLETED` resources survive the update and are not re-uploaded. `ALTER TABLE … DROP
  COLUMN` is a SQLite 3.35 feature, so `:domain:engine` adds
  `dialect("app.cash.sqldelight:sqlite-3-35-dialect")` (the build currently defaults to the 3.18
  grammar). This is a compile-time grammar floor only — both drivers already run SQLite ≫ 3.35.
- Discovery is **unchanged** — the producer still processes inserts, updates, and deletes. With no
  version, an updated asset re-enumerates to all-`AlreadyUploaded` (a no-op — this is the fix for the
  metadata-bump re-upload), while genuinely new keys still upload.
- Re-join reconciliation seeds `COMPLETED` matched by **`filename` only**; the seeded row's
  `updatedAt` is the **join time**.
- **BREAKING (API)** `bunny-list-endpoint` drops `lastModified` from each entry —
  `{ filename, size, lastModified, url }` → `{ filename, size, url }`. It was a storage-clock
  timestamp that only ever fed the seed's cosmetic `updatedAt`; the backend stops reading bunny's
  `LastChanged`/`DateLastModified`.

## Capabilities

### Modified Capabilities
- `sync-engine`: `Resource` loses `version`; `ReUpload` removed; the `ResourceChanged` decision is
  state-only; the "Resource version" requirement is removed; completion/started recording and the
  asset-identity decision note drop `version`.
- `sync-ledger`: `LedgerEntry` and the `record*` operations lose `version`; the SQLDelight schema
  drops the column; the schema migration becomes **row-preserving** (drop column, keep rows); the
  atomic-reset entry contract drops `version`.
- `event-rejoin-reconciliation`: the `EventFilesSource` seam carries only `filename`; the seed matches
  by filename and sets `updatedAt` to the join time; the "seeded version matches the producer"
  requirement is removed.
- `bunny-list-endpoint`: the entry shape drops `lastModified` → `{ filename, size, url }`.
- `ios-background-upload`: the `Work`-arm wording drops `ReUpload` (`Upload` only) and the skip note
  drops "at the same version".

### Unaffected (verified)
- Discovery / change-token handling, deletion pruning, and the full-enumeration reconcile
  (`retainAssets`) — no requirement changes; the producer keeps processing `updated` identifiers.
- `bunny-download-endpoint` (`url` unchanged) and `edge-upload-provider` (filename→destination is
  injective and never used `version`).
