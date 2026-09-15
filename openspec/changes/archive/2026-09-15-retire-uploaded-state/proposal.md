## Why

`UPLOADED` was introduced by `changes/archive/2026-08-26-fix-lost-upload-acks` (D1) to mean *"the bytes are
stored, and the work a completion triggers has not run"* — the completion notify and the event-album
placement. Both halves of that work are gone from the completion path: on the versioned device API the
device-manifest write **is** the announcement and the backend fans out from it (capability
`upload-completion-notify`), and placing a member's **own** photo in a **local** album needs no uploaded
bytes. What is left is a state that only delays `COMPLETED`: a promotion pass, two store verbs, a third arm
in every exhaustive classification, a status screen that reads "uploading" over photos already stored, and a
latent re-upload — a failure handed back for a key already `UPLOADED` passes the cycle's done-state skip and
is written `FAILED` over the stored upload.

This is an independent simplification of the ledger's state machine (phase M9 of the tierless-upload
programme): useful on today's single-backend app, and depended on by no other phase.

## What Changes

- **A successful upload is recorded `COMPLETED` directly**, by whichever party the platform tells: the
  background-`URLSession` delegate and the PhotoKit terminal-job drain (and the world's fake queue).
- **`UPLOADED` is removed** from `LedgerState`, together with the cycle's promotion pass and the
  `uploadedRows` / `promoteUploaded` store operations.
- **Own-photo album placement moves from "after upload" to "when its upload is first enqueued"**: the
  cycle's enqueue stage adds the photos it is about to create jobs for — still `DISCOVERED`, admitted by the
  membership's current policy, and still resolvable — once per cycle, best-effort, before creating the jobs.
- **A data-only migration (`8.sqm`)** rewrites any existing `UPLOADED` row to `COMPLETED`. It places nothing.
- **The dead engine completion path is deleted**: `SyncEvent.UploadCompleted`, `SyncEngine`'s completion
  arm, and `LedgerWriter.recordCompleted` have no production caller.
- **BREAKING (internal port)**: `LedgerStore.markTerminal(key, state)` becomes
  `markTerminal(key, outcome: TerminalOutcome)` with `TerminalOutcome { COMPLETED, FAILED }`, so no other
  state can be recorded through the one verb platform callbacks use.

Visible to users:

- A photo counts as completed the moment its upload finishes, rather than on the next cycle.
- A member's own photos appear in the event album when their upload is enqueued — including while offline —
  and a photo whose upload keeps failing stays in the album while it retries.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `sync-ledger`: the state set loses `UPLOADED`; the guarded promotion and uploaded-row read are removed;
  `markTerminal` takes a terminal outcome; `recordCompleted` is removed; the aggregate, pending-read,
  record-operation and classification requirements drop `UPLOADED`; the schema migration gains the `8.sqm`
  rewrite and its downgrade stance.
- `sync-engine`: the resource-changed decision drops `UPLOADED`; completion recording is removed.
- `ios-photokit-upload`: a succeeded terminal job is recorded `COMPLETED`.
- `ios-url-session-upload`: the delegate records `COMPLETED` on success; the stranded-pass scenario follows.
- `event-album`: own photos are added when their upload is first enqueued, not at upload completion; the
  forward-only toggle is restated in those terms.
- `upload-lifecycle`: the cycle's publications no longer include a promotion or an album placement.

Not modified, checked: `upload-completion-notify` (only its Purpose's history names the promotion pass, as
history); `upload-state-reconciliation` (speaks of rows "recorded as landed", not of a state);
`device-manifest` (its projection is not state-scoped); `reconfigure-membership` (its "photos synced from now
on" helper text stays true).

## Impact

- `:domain` — `model/` (`LedgerState`, the three classifications, `TerminalOutcome`, `SyncEvent`),
  `ports/` (`LedgerStore`, `BackgroundTransfer` KDoc), `feature/upload` (`UploadCycle`, `SyncEngine`,
  `LedgerWriter`), `feature/album` (`AlbumCoordinator` KDoc).
- `:adapter:generic:app` — `Ledger.sq`, new `8.sqm`, `SqlDelightLedgerStore`.
- `:adapter:generic:fake` — `InMemoryLedgerStore`.
- `:adapter:ios:ext-safe` — `PhotoKitJobMapping`, `IosPhotoKitUploadPlatform`, the simulator job queue.
- `:adapter:ios:app-only` — `IosUrlSessionUploadPlatform`.
- `:test:world` — `UploadFakes`, `LedgerStoreContract`, a test-only completed-row seed helper;
  `:test:integration` — the lost-upload and reconfigure tests; `:test:rig` — a KDoc.
- `architecture/ports.md` regenerates (`SyncEvent` loses a subtype).
- No backend, wire-format or UI change.
- Changelog label: `enhancement` (status completes sooner; the album fills at enqueue).
- Related programme phases: M1 (ledger `put` never overwrites a settled row) touches the `LedgerWriter`
  record operations this change thins; M7 (transport-only backend seam) will reshape the port `markTerminal`
  lives on. Neither blocks this change or is blocked by it.
