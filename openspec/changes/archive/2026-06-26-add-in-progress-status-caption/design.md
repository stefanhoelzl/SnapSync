## Context

The ledger-backed `LedgerSyncStatusSource` already mints `SyncProgress.pending` — the asset-counted
photos with any not-yet-`COMPLETED` resource — but it is documented as "does not drive
classification" and is never shown. The InProgress screen state renders only `synced`/`total` and an
optional relative time. Because iOS performs background-upload PUTs out-of-process and only
reconciles completions into the ledger when it re-wakes the extension (sparsely, under its own
throttling), the synced count can sit unchanged for long stretches while uploads are in fact landing
— making a working backup look stalled.

## Goals / Non-Goals

**Goals:**
- Surface a glanceable "work underway" signal next to "n of N synced".
- Reuse the existing `pending` aggregate — no new projection or ledger work.
- Keep the design-system contract intact (no `App*` signature change).

**Non-Goals:**
- Changing the three-state classification (`pending` still does not classify; a snapshot with
  `synced < total` is IN_PROGRESS regardless of `pending`).
- Estimating time remaining or per-photo progress.
- Adding a distinct visual element — the caption is text on the existing detail line.

## Decisions

- **Count = `pending` (actively uploading), not `total - synced` (remaining).** `pending` is the
  honest "discovered and mid-upload" number. It can read lower than `total - synced` right after a
  large import (some photos not yet discovered) and may briefly include a deleted-not-yet-pruned
  photo — both acceptable for an informational caption, and neither can mislead the primary
  `n of N` line, which stays driven by `synced`/`total`.
- **Carry the count as an int in `UiState.InProgress.inProgress`; compose the string in the screen.**
  This mirrors how `synced`/`total` are already carried as ints and interpolated by `StatusScreen`,
  keeping the rendered text assertable in `:domain:ui` jvmTest. The pre-formatted `finishedAgo`
  string stays owned by presentation.
- **Merge onto the one existing detail line** as `"{inProgress} in progress · {finishedAgo}"`,
  collapsing to `"{inProgress} in progress"` when `finishedAgo` is null. This keeps `StatusHero` at
  exactly one detail slot, so no `App*` component (and no Material 3 surface) changes.

## Risks / Trade-offs

- **"0 in progress" can appear** when `synced < total` but nothing is currently discovered/pending
  (all remaining photos are undiscovered). This is honest ("nothing uploading right now") but may
  briefly read oddly; accepted over inventing a synthetic count.
- **`inProgress` and `synced` need not sum to `total`** (undiscovered photos belong to neither).
  This is the intended meaning of "actively uploading" and is documented on the field.
