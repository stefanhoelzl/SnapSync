## Why

Change 1 (`immutable-asset-manifests`) made storage self-describing — a per-asset manifest declares the
expected resource set, and the list endpoint answers **completeness at read time**. The app's status,
though, still reads the cross-process SQL ledger and papers over its lag with the
`observed-completion-overlay` (which promotes photos the platform reports succeeded-but-not-yet-ledgered
so the screen feels live). Once completeness is answerable from storage, that whole stack is redundant:
the app can derive status from the completeness listing + on-disk manifest files + the PhotoKit total,
the ledger can go **extension-private**, and the overlay can be **deleted**. This removes the app↔extension
shared-ledger coupling and the drift the overlay existed to mask.

This is **Change 2 of the storage redesign** and depends on Change 1 being applied (the completeness
listing and the on-disk manifest files it consumes are introduced there).

## What Changes

- The app SHALL derive `SyncStatus` from three observable sources, with **no ledger read**:
  `completed` ← the completeness listing (`GET /event/<id>/files`), in-progress (`pending`) ← on-disk
  manifest files in the App Group, `total` ← the PhotoKit gallery count.
- **Liveness:** the app re-LISTs on foreground entry and on each manifest `URLSession` completion.
- The app SHALL prune on-disk manifest files for assets the listing reports complete (a backstop to the
  extension's own prune-on-success from Change 1).
- **BREAKING (removal):** the `observed-completion-overlay` capability is deleted; the app no longer reads
  succeeded upload jobs; the extension no longer posts the cross-process ledger ding.
- The app **stops reading the ledger for status:** the status-facing `LedgerWatcher` and the cross-process
  change notification are removed, and `:domain:status` drops its `:domain:engine` dependency. (The app
  still seeds via `resetTo` on rejoin; **fully** privatising the ledger — relocating the seed into the
  extension — is the follow-on change `reconcile-in-extension`.)
- `gallery-status` is unchanged — the PhotoKit total is reused verbatim.

## Capabilities

### New Capabilities
<!-- none — the two new status seams are added to the existing sync-status capability -->

### Modified Capabilities
- `sync-status`: the status source becomes listing-/manifests-/gallery-backed (was `LedgerSyncStatusSource`
  over a `LedgerWatcher` + `ObservedCompletionsSource`); adds a `CompletedAssetsSource` (completeness
  listing, with foreground + manifest-completion liveness) and a `PendingManifestsSource` (on-disk manifest
  reader + complete-asset prune); `SyncProgress.completed`/`pending` are re-sourced; `:domain:status` no
  longer depends on `:domain:engine`.
- `sync-ledger`: the status-facing `LedgerWatcher` and the cross-process change notification are removed;
  the app no longer watches the ledger (reader/writer/aggregates/record/reset retained; full
  privatisation is `reconcile-in-extension`).
- `ios-background-upload`: the "app reads succeeded upload jobs" observation and the "extension posts the
  cross-process ledger ding" requirement are removed.
- `observed-completion-overlay`: **removed in its entirety** — completeness is now read from storage.

## Impact

- **Code:** `:domain:status` (new `CompletedAssetsSource`/`PendingManifestsSource`, a listing-backed
  `SyncStatusSource`, drop the `LedgerWatcher`/`ObservedCompletionsSource` inputs and the `:domain:engine`
  dependency); `:app:ios` (delete the `ObservedCompletionsSource` job-reading impl; add the iOS listing
  client + App-Group manifest reader; wire manifest `URLSession` completion → re-LIST + prune; stop
  constructing `LedgerReader`/`LedgerWatcher`); the extension (stop posting the cross-process ding).
- **Removed:** the `observed-completion-overlay` capability and its tests; the cross-process Darwin
  notification path.
- **Unchanged:** `gallery-status`; the `SyncStatus`/`SyncProgress` contract and three-state classification
  (only the *sources* of `completed`/`pending` change); the extension's private ledger mechanics.
- **Known trade-off:** if the app is backgrounded when an asset's *last* resource lands after its manifest,
  the completed count goes stale until the next foreground re-LIST (no polling timer). See design Open
  Questions.
