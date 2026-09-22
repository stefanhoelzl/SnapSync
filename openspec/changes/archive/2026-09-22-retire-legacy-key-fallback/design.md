## Context

`30489610` (2026-08-28) moved byte uploads to v2: `/api/v2/files/devices/<deviceId>/<assetId>/<role>`. Under v2
the last path segment is the resource's role, not its ledger key. The same commit added the nullable
`destinationPath` column (`7.sqm`), recorded with every `REQUESTED` write, and made the recorded path the
primary way back from a returned `PHAssetResourceUploadJob` to its row. As a fallback, it kept the v1 recovery
"key = last path segment", for jobs created by the outgoing build. `selection-is-the-walk` (PR #285) made the
fallback apply only when a row exists for the recovered key. Its design record left open whether the fallback
still serves any job.

What the fallback does today, for a job with a v1 destination whose row exists (a row from before `7.sqm`, with
no `destinationPath`):

| job | with the fallback | without it (pruned) |
|---|---|---|
| `.acknowledge`, `Succeeded` | row `COMPLETED` | nothing written; `StoredUploadSettle` marks it `COMPLETED` on the next app foreground (the backend lists the object name, which v2 left unchanged) |
| `.acknowledge`, retry-spent failure | row `DISCOVERED`, re-created | nothing written; row stays `REQUESTED` |
| `.retry`, first failure | retried to the v2 address | acknowledged; row stays `REQUESTED` |

A `REQUESTED` row is never offered for a new job (`NEEDS_JOB_STATES`), and `2f1bdd6a` removed the repair that
reset stuck rows (`demoteRequested`). So the two failure rows are the fallback's whole remaining value: without
it, such a photo never uploads and the status stays below 100%.

How many such jobs can exist. A v1-shaped job means an upload before 2026-08-28. The capture window is clamped
to the event start, so that event started on or before 2026-08-28. Its lifetime is 30 days from
`max(createdAt, startsAt)`, so it is swept by about 2026-09-27. A leave clears the ledger (`LeaveEvent`), after
which any leftover job has no row. So the population needs all of these at once: a device still joined to an
event from before v2, a v1 job that **failed**, still unanswered by the OS, and an extension that has not run
under a full grant since the update (any run would have answered it through the fallback). And the window
ends in days.

## Goals / Non-Goals

**Goals:**
- One route from a returned job to its row: the recorded destination path.
- No code path left that knows the v1 byte shape.
- If the accepted risk does happen, it is **reported**, not silent.

**Non-Goals:**
- No ledger migration, and no backfill of `destinationPath` for old rows (nothing could supply one: the job,
  not the row, carries the path).
- No change to `StoredUploadSettle`, to the backend's `/api/v1` routes (retiring v1 on the backend is its own
  change), or to the manifest-version triggers.
- No repair for a stuck `REQUESTED` row. Adding one would bring back what `2f1bdd6a` deliberately removed.

## Decisions

**D1 — Delete now, on reasoning, not after a measurement.** A device measurement would need a v1-shaped job,
and no current build can create one. So the evidence has to be the bound in Context. It is honest to call
this *accepted risk* rather than *proven dead*: the argument shows the population is tiny and ends in days,
not that it is empty today. *Alternative:* wait until after about 2026-09-28, when the event-lifetime bound
makes the deletion provably safe. The operator chose not to wait. *Alternative:* delete, and treat an
unresolvable v1 job as a failure that returns a row to `DISCOVERED`. That needs the key, which only the
last-segment parse provides, so it keeps the fallback under another name.

**D2 — A v1-shaped destination becomes unmappable (`Error`), not pruned (`Info`).** Pruned means "the walk
removed this row, which is expected", and it writes nothing. For a v1 job, removing the fallback makes that
reading false: its row may still exist and be stuck at `REQUESTED`. The `module-architecture` law "absence is
never silent" asks for the one signal that reaches the operator. So `isByteRoute` recognises only the v2 shape,
and a v1 job takes the unmappable branch: acknowledged (so error 50008 stays impossible), counted, and raised
at `Error`, which Bugsink receives. In practice this event should never fire. If it does, it is the evidence
the deletion lacked, and names the device. *Alternative:* keep v1 in `isByteRoute` so such jobs prune quietly.
That hides the one failure this change accepts.

**D3 — The `.retry` lookup keeps using the drain's resolver.** `retryJobMatching` is generic over the
resolver and already takes `rowFor`'s answer. Only the resolver narrows, so the rule "a retry and a drain
never disagree about which row a job belongs to" holds without further change.

**D4 — Leave `7.sqm`'s header as it is.** A committed migration is a historical record of what it did and why.
Its comment says the fallback is "defined by the tier that reads them", and the spec now says there is none.
Rewriting a shipped migration's text would make the file say something it did not say when it ran.

**D5 — `get(key)` leaves `TransferRecord`.** `selection-is-the-walk` put the per-key read on the transport
surface for one reason: the v1 fallback had to check that a row existed for the key it recovered. The v2 path
never needed it, because `entryForDestination` returning null already means "pruned". With the fallback gone, no
transport calls `get`, and a read with no transport caller on the transport surface only widens what a
transport can reach. So it moves back to `LedgerStore`, where it was before. Every implementation of
`TransferRecord` is a `LedgerStore`, so nothing else changes. *Alternative:* leave it on `TransferRecord` and
only fix its KDoc. That keeps a surface whose stated reason is gone.

## Risks / Trade-offs

- [A device holds a failed v1-shaped job for a row that still exists] → That row stays `REQUESTED` and the photo
  never uploads. **Mitigation:** only reporting — the `Error` from D2 names the count. The ways out are a leave
  and rejoin, or the event's sweep, which ends the whole population by about 2026-09-27.
- [A succeeded v1-shaped job] → Also unmappable, so an `Error` fires for an upload that actually landed. The
  foreground settle still records `COMPLETED`. Accepted: a false alarm here costs one event, and a missed stuck
  row costs a photo.
- [The bound leans on the event lifetime and on leave clearing the ledger] → Both are specified (lifetime in
  the mission, the ledger clear in `LeaveEvent`). A device that never notices its event is gone keeps the
  ledger longer; the risk above then applies to it for longer, and is still reported.
