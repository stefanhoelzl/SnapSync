# Design — status in-flight from a read-only ledger peek

## Problem

`SyncProgress.pending` (→ the UI's "{n} in progress" caption) is computed as `max(0, total −
completed)`. That equals `total − synced` (the hero's inverse) and conflates two very different
groups: photos the OS is **transferring now** and photos the extension **has not discovered yet**.
The app wants the first — the real in-flight count. It is not derivable from storage truth (the
per-device file listing): a 1-resource photo has no observable mid-state, so "partially present" is
~0 in practice.

## Approach

The extension's ledger already tracks exactly this. `LedgerState` is `REQUESTED → COMPLETED/FAILED`,
and `aggregates().pending` is **asset-counted** ("photos with any non-`COMPLETED` resource") — i.e.
photos with a job created (or retrying) but not yet done. The app reads that one number,
**read-only**, from the shared App-Group ledger.

```
                 ┌──────────────── extension process (sole WRITER) ────────────────┐
                 │  reconcile → discover → createJob → ack   (put / resetTo / clear) │
                 └───────────────────────────────┬─────────────────────────────────┘
                                                  │ writes
                       App-Group  group.app.snapsync/ledger.db   (SQLite, WAL)
                                                  │ reads (read-only: aggregates() only)
   ┌──────────────────────────── app process (READER) ──────────────────────────────┐
   │  InFlightSource.refresh()  →  backend.aggregates().pending  →  inFlight: Int      │
   │  ListingSyncStatusSource.combine(completed, permission, gallery, inFlight)        │
   │  SyncProgress.pending = min(inFlight, max(0, total − completed))   (display-only) │
   └──────────────────────────────────────────────────────────────────────────────────┘
```

- **Cross-process read is safe.** `iosLedgerBackend()` opens the DB in **WAL** mode — "one
  cross-process writer plus concurrent readers" (its own contract). The reader needs only
  `aggregates()` (a single snapshot-consistent query); it never writes.
- **Single-writer invariant preserved.** The app constructs a `LedgerBackend` but uses it **only**
  through the `InFlightSource` seam, which exposes a count and nothing else. No `LedgerWriter` is
  constructed in `:app:ios` (the hard rule); the seam type makes an accidental write unrepresentable
  in app code.
- **Display-only.** `pending` continues to NOT drive `SyncProgress.state` — classification stays
  `synced` vs `total` (storage truth), so a stale/over-counting ledger can never change the rendered
  sync state, only the caption number.

## Decisions

| Decision | Choice | Why |
| --- | --- | --- |
| In-flight source | Ledger `aggregates().pending` (read-only) | Already asset-counted; the only place the true in-flight set exists; storage truth can't see it. |
| Clamp | `min(inFlight, max(0, total − completed))` | The ledger over-counts a finished-but-unacked job (`REQUESTED` until the next ack cycle); clamping keeps the caption ≤ remaining and consistent. |
| Liveness | Refresh on **foreground entry** | No cross-process change notification exists (the `changes` flow is in-process); a foreground snapshot is simple and matches the completed-source trigger. |
| Read failure | `inFlight = 0` → caption hidden | Graceful; the ledger may not exist yet (extension never ran) or be momentarily unreadable. |
| Classification | Unchanged (storage truth) | Keep the `ledger-free-status` guarantee that an extension-internal job state can't flip the rendered state; the peek is for the number only. |
| Wording | Keep `"{n} in progress"` | Only the number was wrong; the caption already auto-hides at 0. |

## Reversal of `ledger-free-status`

`ledger-free-status` removed the app's `LedgerWatcher` so status derived purely from storage truth.
This re-introduces a ledger read, but a strictly narrower one: **read-only**, the **in-flight count
only**, **display-only**, **foreground-triggered**. The motivating reasons for `ledger-free-status`
(no cross-process write contention; the rendered sync state must reflect what is actually stored, not
the extension's hopeful job rows) are all preserved — classification and the synced count remain
storage-truth; only the supplementary caption peeks at the ledger.

## Out of scope

- Live (polled) updates while foregrounded — a snapshot on foreground is enough for v1.
- Reconciling the in-flight set against storage truth per-asset (subtracting already-stored-but-unacked
  rows) — the arithmetic clamp covers the visible inconsistency without a second join.
