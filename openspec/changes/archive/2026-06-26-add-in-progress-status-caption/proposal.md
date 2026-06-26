## Why

While iOS drips a large library out over many throttled background-upload windows, the status
screen shows a flat "n of N images synced" — during the long gap between a transfer finishing on
the wire and the extension being re-woken to acknowledge it, the count sits still and the backup
reads as stuck even though it is flowing. The screen has no way to say "work is underway right now."

## What Changes

- Add a second caption to the InProgress status state showing how many photos are **actively being
  uploaded** — the asset-counted `pending` already carried in `SyncProgress` but never surfaced.
- Reduce that count into `UiState.InProgress` as a new `inProgress` field.
- Render it merged onto the existing detail line as `"{inProgress} in progress · {finishedAgo}"`,
  collapsing to just `"{inProgress} in progress"` at a virgin "0 of N" where nothing has completed
  yet (no relative time to show).
- No change to the snapshot/projection contract: `SyncProgress.pending` already exists and is
  already minted by the ledger-backed source; this change only consumes it.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `sync-status-screen`: the InProgress reduction additionally carries an `inProgress` count, and the
  screen renders it as a second caption on the detail line.

## Impact

- `:domain:presentation` — `UiState.InProgress` gains `inProgress: Int`; the reducer maps it from
  `SyncProgress.pending`.
- `:domain:ui` — `StatusScreen` composes the merged detail line; `StatusHero` (App\*) is unchanged
  (still one detail line).
- `:app:desktop` — the harness "In progress" preset forges a non-zero `pending` so the caption is
  reviewable off-device.
- No change to `:domain:status`, `:domain:engine`, or the `sync-status` contract.
