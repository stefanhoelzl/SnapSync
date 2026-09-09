## Context

The selection policy has five production consumers. Four ask `EventPhotoSet`:

| consumer | input | asks? |
|---|---|---|
| what gets recorded (`UploadCycle.decide`) | walked candidates | yes |
| what gets declared (`projectDeviceManifest`) | ledger rows | yes |
| the status total `N` (`OwnDeviceGalleryStatusSource`) | gallery assets | yes |
| the join/reconfigure preview (`ShareableCount`) | gallery assets | yes |
| **the byte upload (`UploadCycle.enqueue`)** | **ledger rows** | **no** |

`enqueue` reads `rowsNeedingJob(limit)`, resolves those keys through the platform, and creates jobs. The
only admission in that path happened when the row was written, under whatever policy was current then.

A ledger row is what `photo-selection-policy` calls an **upstream-filtered structure**: it records that
the policy admitted the asset at walk time, and the requirement is explicit that such a pre-filter
"enforces only a subset of the rules, so the consumer still asks the policy."

The policy changes under a live membership through `ReconfigureEvent` — a member raising their cutoff, or
lowering their ceiling. A raise does **not** clear the discovery cursor (only a *lowered* cutoff does, so
newly-in-scope older photos get re-enumerated), so the rows recorded under the wider policy simply remain,
and drain at `enqueueBatchSize = 16` per cycle.

## Goals / Non-Goals

**Goals:**

- The byte upload derives from the admitted set, like every other consumer — so a narrowing takes effect
  on what is uploaded, not only on what is declared.
- The fix cannot starve: excluded rows must not block admitted ones.
- The narrowing case is pinned by scenarios, so it cannot silently regress.

**Non-Goals:**

- No change to how the policy is derived, or to any rule in it.
- No change to the reconcile, to `resetTo`, or to any ledger state transition. This change removes the
  *reason* `resetTo`'s clear is load-bearing; it does not touch the clear.
- No pruning of rows the policy stopped admitting. `sync-ledger`'s *The ledger is never pruned by the
  selection policy* stands unchanged — a narrowing must stay reversible, and the rows are what suppress
  re-upload when the member widens again.
- No backend change, no wire-format change, no migration.

## Decisions

### D1 — The uploader asks, at the point of query, over the rows it is about to resolve

`photo-selection-policy` requires admission "at the point of **query** — when a consumer asks for the set
or the count". `enqueue` is a query for work, so it admits there.

It uses the construction the manifest projection already uses: `candidatesFromFacts` over the row facts,
then `EventPhotoSet(policy).assets()`. The reduced fact set is correct here for the same reason it is
correct there — the capture-date bounds and the two id-set exclusions can speak from a row; origin
defaults to admit-on-doubt, because an origin-excluded asset never earned a row in the first place (the
cycle drops those before recording).

**Alternative considered — compute one admitted set per cycle and hand it to both the manifest hook and
enqueue.** Rejected: they are not the same input. The manifest projects from `manifestRows()` (every
non-absent row, any state); enqueue works from `rowsNeedingJob` (`DISCOVERED` + `FAILED`, non-absent).
Sharing one computation would mean widening one consumer's input to serve the other, which is a larger
change than the bug warrants and would blur two reads that answer different questions.

### D2 — The batch bounds what is RESOLVED, not what is READ

Admitting *after* a bounded read starves. `rowsNeedingJob` returns rows in a stable key order, so an
excluded backlog at the front is re-read and re-discarded every cycle while admitted work further down is
never reached — permanent, silent, and strictly worse than the bug being fixed.

So the needs-job rows are read without the batch bound, admitted, and the bound applied to the admitted
result. This is what `enqueue`'s own contract already claims ("The batch bounds only what is RESOLVED.
What is created is bounded by the platform"); only the implementation disagreed. The costly things — the
platform round-trip and, on the app-driven tier, the staged temp file — stay bounded exactly as before.
The read is a local indexed scan.

**Alternative considered — push the capture-date bounds into the SQL.** Rejected, and the spec rejects it
by name: *A consumer cannot re-enumerate the policy* requires that the capture-date comparison "appears
only inside the single `SelectionPolicy` admission, not at any consumer". Phase 2 removed a
`creationDate != ''` predicate from the manifest query for this reason; re-adding one here would undo
that decision in a second place.

**Alternative considered — page the read in chunks until enough admitted rows are found.** Kept in
reserve. It is strictly more code for a benefit only a very large needs-job set would show, and that set
shrinks as work completes. If measurement ever shows the scan matters, this is the change to make.

### D3 — A row the policy cannot judge is excluded, and that is already safe

A row resting with an empty `creationDate` sorts before every real cutoff, so the policy excludes it —
`CaptureAfter` is "the one place a missing fact excludes rather than admits". Such a row therefore stops
being uploaded by this change.

That is not a new invisibility: a bare row is **already** excluded from the manifest projection, so its
bytes were being uploaded and never declared. And a bare row cannot hide behind a settled cursor, because
`backfillManifestDetail` is a **precondition of the cursor advance** — the walk fills every bare row it
covers before `saveToken`. So the row is enriched by the next walk that reaches it and re-enters the work
set with a real date.

### D4 — Excluded rows stay in the ledger, and cannot peg the screen

Rows the policy stopped admitting are retained (`sync-ledger`), stay `DISCOVERED`, and therefore keep
counting toward the ledger's pending aggregate. That looks like the failure `CLAUDE.md` names — "the
screen pegs below 100% forever" — and it is already defused: `LedgerBackedSyncStatusSource` mints
`pending = min(ledgerPending, total − completed)`, and `total` is the **policy-scoped** own-device gallery
size. Once every admitted asset completes, `total − completed` is zero and the clamp drives `pending` to
zero regardless of how many excluded rows remain.

The clamp exists for a neighbouring reason ("a deleted-but-not-yet-pruned photo can never read `pending`
above the shown remainder"), and it covers this case for free. `sync-status` therefore needs no change.

## Risks / Trade-offs

- **Starvation from a naive fix** → D2. This is the one way to make things worse than they are now, and
  it must be a test rather than a review note.
- **A member widening again expects their photos back** → they come back: the rows were never pruned, the
  policy re-admits them, and they are still in `NEEDS_JOB_STATES`, so the next cycle enqueues them with no
  re-walk. Covered by `sync-ledger`'s existing *Narrow then widen re-lists without re-uploading*.
- **`completed` is not policy-scoped while `total` is** → pre-existing, untouched here, and visible only
  as an oddity when a device holds completed rows for assets outside the current membership (a prior
  event). Worth a look, but it is not this change's to fix and the clamp bounds its display effect.
- **Two admissions run per cycle over overlapping rows** (manifest and enqueue) → accepted. They are
  different reads answering different questions, the admission is pure and in-memory, and D1's alternative
  costs more than it saves.

## Open Questions

- Should `candidatesFromFacts` move out of `DeviceManifest.kt` to sit beside the admission, now that it
  has two callers? A placement question for the implementation, not a behavioural one.
