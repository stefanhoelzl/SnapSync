# Proposal: sync-engine-ledger

## Why

Research into the iOS platform invalidated the stateless-engine premise: PhotoKit's change-token
history expires routinely (retention undocumented, illustrated in days), and Apple's prescribed
remedy is full library re-enumeration — which, with no memory of what is already backed up, would
re-upload the entire library (~50k assets) on every expiry. Apple's own guidance for the upload-job
API prescribes write-then-acknowledge bookkeeping with per-key idempotent tracking; every durable
per-key store the old design scattered across platforms (status fold, discovery ledger, inbox)
collapses into one engine-owned ledger. Decided in design exploration 2026-06-12; this change
amends the sync-engine-core slice (shipped 2026-06-12) before any platform code builds on it.

## What Changes

- **BREAKING — the engine becomes stateful**: `SyncEngine(provider, ledger)` owns a SQL-backed
  ledger (the only writer, ever). "Stateless" is replaced by "re-handling is safe via idempotent
  per-key upserts".
- **BREAKING — `handle(event)` returns `SyncDecision`, not `UploadJob`**: sealed
  `Upload` / `ReUpload` / `Retry` (all implementing `Work`, carrying the job) /
  `AlreadyUploaded` (no work; the ledger proves the content is already backed up).
- **BREAKING — concurrency contract weakened**: one `handle()` call at a time per engine
  (sequential); concurrency is the caller's responsibility. All known drivers are sequential loops.
- **New event `UploadCompleted(job)`**: platforms report observed completions at the
  acknowledge edge (write-then-ack); the engine records and answers `AlreadyUploaded`.
- **`Resource` gains `version: String`**: platform-supplied content-identity proof (iOS: asset
  `modificationDate`); the engine compares equality only — same version ⇒ `AlreadyUploaded`,
  different ⇒ `ReUpload`. Accepted cost: metadata-only changes leave stale upload headers
  remotely (replaces the old "re-upload all bytes on metadata change" accepted cost).
- **New ledger classes** in `:domain:sync`: `LedgerBackend` (storage seam: `get`/`put` single-row
  upserts), concrete shared `LedgerReader` (`entry(key)`) and `LedgerWriter : LedgerReader`
  (`recordRequested`/`recordCompleted`/`recordFailed`). SQLDelight backend (Kotlin Multiplatform) plus an
  in-memory test backend. No transactions, no timestamps — schema is
  `key PRIMARY KEY, state, attempt, version`.
- **Decision rules** (the heart): ledger absent/requested/failed → `Upload`; completed + same
  version → `AlreadyUploaded`; completed + different version → `ReUpload`; `UploadFailed` →
  `Retry` (attempt + 1, retry-forever unchanged). "Requested" rows are hopes, never trusted for
  skipping (skipping on them risks silent loss).
- **docs/design.md** updated: §2.2 rewritten (ledgered engine), §2.4/§3.2/§3.3 amended (inbox and
  platform discovery ledger superseded; platform bookkeeping shrinks to token/residue/deferred),
  §7 (+SQLDelight), §8 (new verification items).

**Explicitly out of scope** (the next proposals): status as ledger queries (`ReadonlySyncLedger`
aggregates, `active` derivation, `:domain:status` module split, timestamps/`lastFinishedAt`),
JVM engine console, all platform adapters.

## Capabilities

### New Capabilities

- `sync-ledger`: the engine's durable per-key memory — backend storage seam, reader/writer
  capability split (single-writer codified by construction), record semantics, idempotence.

### Modified Capabilities

- `sync-engine`: decision vocabulary replaces one-job-per-event (`SyncDecision` with four arms);
  statefulness via the ledger; skip/re-upload rules keyed on `Resource.version`; new
  `UploadCompleted` event; concurrency contract weakened to sequential.

## Impact

- **Code**: `:domain:sync` commonMain — `SyncEngine` rewritten, `SyncDecision` + ledger classes
  added, `Resource`/`SyncEvent` amended; commonTest — engine decision tests rewritten around the
  ledger, in-memory backend, upsert-order/idempotence tests.
- **Dependencies**: SQLDelight (runtime + drivers: sqlite/JVM for tests and desktop later; native
  driver deferred to the iOS slice).
- **Compatibility**: no external consumers of the engine exist yet (verified: only status-side
  types are imported outside `:domain:sync`), so the breaking changes are free today.
- **Docs**: docs/design.md §2.2/§2.4/§3.2/§3.3/§7/§8.
