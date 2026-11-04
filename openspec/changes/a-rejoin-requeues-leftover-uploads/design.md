# Design

## Context

See proposal.md — Why. At a join into a new membership (a first join or a switch, never a re-provision of the joined
event), the join-time load (`ShareSetLoad`, phase 4.4b) asks the backend which of this device's resources it stores in
the event:

- **Listed** — the event's rows become exactly the stored resources, settled, in one transaction
  (`UploadRowsReset.seed`: purge every other event, delete the event's rows, insert the listed ones). No leftover row
  survives.
- **Not listed** (unreachable, refused, timed out, unreadable) — every other event's rows are purged and the joined
  event's rows are kept as they are (`UploadRowsReset.purgeExcept`), so a rejoin does not re-upload what this device last
  knew was stored.

A kept `REQUESTED` row is a stranded upload. A leave or switch ended every upload of the event it left (`StopUploads`:
the extension deregistered — wiping iOS's in-flight jobs — the wakes and the app's transfers cancelled), so no job sends
the row's bytes. The top-up plans only rows needing a job (`DISCOVERED`); the foreground's stored-upload settle can only
promote an in-flight row the listing shows stored, never demote one; the walk keeps a row it finds again whatever its
state. Nothing ever sends the photo again.

## Goals / Non-Goals

**Goals:**
- After a join that keeps the joined event's rows, none of them is in flight: what was on its way waits needing a job, so
  the next pass uploads it.
- What the device knew was stored stays settled: a `COMPLETED` row is kept as it is.

**Non-Goals:**
- The listed path (it already replaces the event's rows).
- A re-provision of the joined event (it runs no load, and its uploads were never stopped).
- Leftovers of other events (purged as today).

## Decisions

### D1 — The kept path returns the joined event's in-flight rows to needing a job

`UploadRowsReset.purgeExcept(event)` becomes the join's *kept* reset: in one transaction it purges every other event's
rows and returns `event`'s `REQUESTED` rows to `DISCOVERED`, their destination cleared — a new SQL statement over
`ledgerRow` scoped to the event. The manifest version does not move (the update trigger fires only on what the manifest
declares; the state is not one of its columns), and the rows keep their detail, so the manifest declares the same set.

*Alternative rejected:* re-create the jobs in the join itself. The join may not create uploads (its gate and the grant are
the pass's), and the next pass creates them anyway.

*Alternative rejected:* demote at the leave. A leave whose clear succeeds but whose stop does not finish, or a device that
updated from a build without this fix, would still strand rows; the join is the one place every rejoin passes.

### D2 — Why demotion is safe at a join

Only an upload of the joined event could still be live, and a join into a new membership follows a leave or switch that
stopped every upload of it (`StopUploads` runs only with the evidence a membership's uploads ended). If an OS job did
survive, the worst case is the same photo uploaded twice — which the spec allows, and no other member sees as a second
copy (requirement "A photo is never lost on the way").

### D3 — The listed path is unchanged

`seed` already deletes the event's rows before inserting the listed ones; a stored resource becomes `COMPLETED`, and one
the listing does not name is rediscovered by the next walk.

## Risks / Trade-offs

- [A photo whose bytes actually landed after the leave, before the rejoin, is uploaded again] → Only on the kept path,
  where the spec already allows re-uploads ("When the device cannot learn at join what the service already holds … it MAY
  upload such photos again"); never seen as a second copy.

## Migration Plan

None: no schema change (one new statement). Rollback is reverting the commit; stranded rows simply stay stranded as
before.
