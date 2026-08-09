## Why

A photo the device downloaded is being re-uploaded into the event, so every member receives it a second
time (Bugsink `SNAPSYNC-9`, iPhone11,2 / iOS 18.7.9, build 573: **19** occurrences, each **9–44 ms** after
the asset was successfully created).

The mechanism is a gap between two rules that are each correct alone. `download-store` already forbids the
importer from clearing a created-asset marker when an import's wait is abandoned on its deadline — "that
transaction may still commit, and clearing it is what orphans the created asset". But `photo-download`'s
adjudication then instructs the guard to clear that same marker moments later, on an **absent** verdict,
because it treats absent under a full grant as authoritative. It is not: **the photo library answers
honestly that an asset does not exist while the transaction creating it is still open.** The marker is the
only record that a downloaded photo must not be uploaded, so clearing a live one drops it from the
suppression set and the device uploads someone else's photo back into their event.

Two measurements make it routine rather than rare. The 5 s import deadline fires on **healthy** imports —
measured on device, a single import takes 1.0 s at 49 MB and 5.2 s at 197 MB, and the reporting device runs
~2× slower — so abandoned waits are common. And iOS suspends the process for arbitrary spans between a
change block and its completion (**116 s** and **254 s** observed in the same reports), so an expiry can
fire against a transaction that is alive: in the field the 5 s bound expired 3–43 ms *after* the process
resumed, having been due 116 s earlier.

## What Changes

- **An "absent" verdict is trusted only for a ref whose import outcome has been reported.** When a wait is
  abandoned (`ImportResult.TimedOut`) the ref is recorded as **unreported**; while it is, absent means
  "cannot tell" and the marker is kept. The gate is the reported/unreported **fact**, never an
  elapsed-time estimate of it, because no wall-clock bound bounds work in a process the OS can freeze.
- **The record is in memory, and that is load-bearing.** Process death erases it, which is correct: a
  transaction cannot outlive the process that opened it, so after a relaunch every absent verdict is
  trustworthy again and the guard does its original job.
- **The "present" verdict gains the staleness re-check the absent path already has.** Verdicts are computed
  outside the controller's lock and applied under it; recording an import against a marker the row no
  longer holds overwrites a live suppression handle with a stale one.
- **The photo library's completion writes the row's terminal state itself**, rather than leaving it to
  whoever was waiting. It is the party that learns the outcome, and it still runs when the requester is
  gone — so an abandoned wait settles itself instead of waiting for a later adjudication pass, and stops
  being unreported.
- **The import deadline rises from 5 s to 30 s.** The measured legitimate worst case is ~10 s; 5 s
  abandons real work, which is what manufactures the unconfirmed rows this defect then mis-adjudicates.
  **The cost is stated, not hidden:** the deadline bounds the controller's *lock*, so a stalled library can
  now block reconcile, leave and switch for 30 s rather than 5 s.

Explicitly **not** in this change, each a separate one: removing the lock from around the platform call
(the `SNAPSYNC-6` hang), holding OS wake handlers until in-process imports finish, and reclaiming the
staged bytes of settled rows.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `photo-download`: adjudication no longer acts on *absent* for a ref whose import outcome is unreported,
  and *present* is re-checked against the row's current marker before it is applied. The bounded-import
  requirement gains the obligation to record an abandoned wait, and its deadline is re-set from
  measurement.
- `download-store`: the completion callback records the row's terminal state against the marker it already
  holds, and that write is guarded on the marker so a late completion cannot settle a row that has moved
  on. The store gains a read that answers whether a ref is still unconfirmed **with a given marker**,
  which is what a verdict is re-checked against.
- `harness-world-model`: the world gains the two abandonment shapes as distinct levers (after the commit
  landed, and before it landed — only the second reaches the reported defect), a late-completion lever so
  the recovery path is reachable, and the requirement that a repeat import mints a **different** created
  identifier, without which no test can tell a duplicate from the original.

## Impact

- `:domain` `feature/download` — a new in-memory record of unreported imports, one reader; `DownloadController`
  records on `TimedOut` and gates the absent branch on it.
- `:domain` `ports/` — `DownloadStore` gains the guarded confirming write and the marker-scoped read.
- `:adapter:generic:app` / `:adapter:generic:fake` — both store implementations, held to the shared contract.
- `:adapter:ios:app-only` — `IosPhotoLibraryImporter`'s completion writes the terminal state and clears the
  unreported record; `IMPORT_DEADLINE` is re-set.
- No schema migration: the confirming write and the marker-scoped read use existing columns.
- Verification requires an on-device run, because the failure needs a real abandoned wait; a throwaway,
  never-committed probe that swallows completions produces it.
