## Context

The nightly sweep (capability `scheduled-cleanup`) deletes objects and only objects: every delete site
filters `!IsDirectory` before acting. Bunny keeps a directory after its last object is removed, so a swept
event leaves `events/<eventId>/` behind, holding an empty `devices/`. The next run enumerates that
directory, reads a `404` marker, and `marker === null` classifies it stale — so it is "deleted" again, and
counted again, every night.

Nothing about this is client-visible: no device-facing route enumerates `events/`, so the husks are
invisible to the API. The damage is confined to the sweep's own summary, which is the sole operator-facing
output of a job that deletes real users' photos.

### Forcing proofs

Measured 2026-07-26 against the live `snap-sync-dev` zone (read-only GETs, storage-zone `AccessKey`):

| observation | result |
| --- | --- |
| `GET events/<swept-id>/devices/` (emptied by the sweep) | `200` `[]` |
| `GET events/<never-existed>/devices/` | `200` `[]` |
| `GET events/<swept-id>/metadata.json` (a **file**) | `404` |
| `GET events/<swept-id>/` | `200`, one child: `devices`, `IsDirectory=true`, `Length=0` |
| `GET events/` | 50 directories, 0 files |

From the five scheduled runs of 2026-07-22 … 2026-07-26: **40 event ids appear in `deleted stale event` on
all five nights**. The one id absent from a single night (`0d553167`) was skipped by a transient bunny `500`
on its manifest LIST, recorded as that run's `errors 1` — not by having been reclaimed. Reported `events
deleted` read 41, 41, 42, 45, 46 while `kept` read 1, 4, 5, 4, 4; three of the five nights reclaimed **zero
bytes** while reporting 42–46 events deleted.

Two facts follow that the code contradicted:

1. **A directory listing never `404`s** — neither empty nor absent. `listDir`'s `if (res.status === 404)
   return null` is unreachable for directory paths, and its doc comment ("when the directory has nothing /
   does not exist (bunny `404`)") is wrong on both counts. A **file** GET does `404`, which is why
   conflating the two in one comment was a trap: `readMarker`'s null branch is live and load-bearing.
2. **A husk is two nested directories**, and holds nothing else — confirming that one recursive delete on
   the parent suffices.

Bunny documents `DELETE` on a directory as recursive: "in case the object is a directory all the data in it
will be recursively deleted as well"
(<https://docs.bunny.net/api-reference/storage/manage-files/delete-file>). **Expiry trigger:** re-verify
these five observations and the recursion contract if bunny changes the Edge Storage listing or delete
semantics, or if any code ever needs to distinguish an absent directory from an empty one.

## Goals / Non-Goals

**Goals:**

- Reclaim the directory a swept event leaves behind, so the zone converges on live events only.
- Make `events deleted` mean "events deleted", so an operator can read the summary as a report of work.
- Change nothing about how a real stale event's objects are deleted.
- Record the measured bunny behavior in place of the incorrect comment.

**Non-Goals:**

- Reclaiming device byte partitions (`files/devices/<deviceId>/`) — explicitly rejected below.
- Any device-facing behavior change. No route, response, or credential moves.
- Making emptiness reclamation guaranteed. It stays opportunistic, exactly as today.
- A whole-zone or bulk delete tool of any kind. The single zone holds real users' photos; the prohibition
  in CLAUDE.md stands and this change does not weaken it.

## Decisions

### D1. A tombstone requires no marker **and** no manifest objects

`marker === null` currently means one thing to the code and two things in reality: a **tombstone** (nothing
left to delete) and an **incomplete/corrupt** event (a marker that was never written, or lost, while
manifests remain). The spec's stale-event requirement already names the second. Splitting them is what lets
the tombstone take a cheaper path without ever pointing a recursive delete at a directory holding data.

*Alternative — treat every marker-less directory as a tombstone.* Rejected: it would apply a recursive
delete to a directory whose contents we have not established are absent, which is precisely the case that
must keep the careful path.

### D2. One recursive `DELETE events/<eventId>/`, confined to tombstones

For a tombstone the recursion has nothing to recurse over — the directory is empty by definition — so this
is one call to remove a directory entry, and it clears the nested `devices/` husk without needing to know
whether that husk exists (which, per the forcing proofs, is unobservable through `listDir`).

Confining it to tombstones matters for two reasons:

- **The marker-LAST ordering survives.** Real stale events keep manifests-first, marker-last, which the
  existing code comments as "retryable if interrupted": the marker is what makes an event exist, so an
  interrupted run leaves a still-existing event the next run re-reaps cleanly. A recursive delete hands the
  ordering to bunny, which documents no atomicity.
- **The precondition is assertable.** The call site has just read a `404` marker and an empty listing.
  Applied to every stale event, the same call would destroy live manifests in one opaque operation with no
  per-object log line — a meaningful loss on a destructive job.

The prune is **race-free**, and for a reason specific to event directories: the manifest write and leave
routes both gate on event existence, so a marker-`404` directory authorizes no write at all. Nothing can
appear inside it between the LIST and the DELETE.

*Alternative — recursion for every stale event.* Rejected: it saves single-digit calls per night (~41 of
~45 nightly candidates are tombstones, where both approaches cost exactly one call) in exchange for the
retry property and the per-manifest log lines.

*Alternative — leaf-first, no recursion.* Rejected as unnecessary: it costs an extra delete and forces a
distinction the forcing proofs show is unobservable, while the recursive call on a proven-empty directory
carries no additional risk.

### D3. Device byte partitions are **not** pruned

The byte upload is **ungated** by design — no marker read, because bytes are device-partitioned and
event-independent. Any token-holding device may `PUT` into `files/devices/<deviceId>/<filename>` at any
moment. A recursive directory delete therefore has a window a per-object delete does not: the sweep LISTs,
decides every byte is collectable, then deletes the *directory* — destroying a byte that landed in between
and was never listed. Per-object deletes can only ever remove names they saw.

For a device that stays orphaned this is harmless (its bytes are collected every run regardless). The
unacceptable case is a device that joins mid-sweep: the event phase has already run, so the new membership
is invisible to the asset phase, the device still looks orphaned, and its first live byte for the new event
sits in the window. Losing it is **silent and unrecoverable** — the device ledger keys on the bare filename
and already reads `COMPLETED`, so the photo is never re-uploaded, which is the exact failure mode CLAUDE.md
calls out as having "no error, no failed request, no log line."

Against that: the husk costs **one wasted LIST per night per orphaned device** and distorts no count (its
config and attestation records are already gone, so the device tier never sees it). The trade is not close.

*Alternative — prune when orphaned and zero bytes kept.* Rejected: "zero kept" does not imply orphaned (a
device that just joined a new event, with all pre-cutoff bytes collected, reaches it with a live
membership), and the orphan test itself is stale by the time the asset phase runs.

*Alternative — prune only a directory observed already empty.* Rejected: it narrows the window without
closing it, and selects for exactly the devices most likely to be uploading their first byte.

### D4. Tombstones are counted in neither `deleted` nor `kept`, and get no tier of their own

The events tier counts events; a tombstone is not one. Adding a `tombstones` tier was considered and
rejected as unnecessary instrumentation: the reclamation is self-extinguishing, so the honest count is its
own oracle — `events deleted` falls from ~46 to the number of genuinely stale events on the first real run
and stays there. A tier would report a nonzero number once and zero forever after.

The consequence is that `deleted + kept` no longer covers every directory enumerated. That is stated
explicitly in the amended summary requirement rather than left to be rediscovered, since the tiers reading
as a partition is what let this ship.

### D5. Dry-run logs each prune

A prune is a delete, and the dry-run contract is that the mode lists everything a real run would delete.
Dry-run is also the tool used to validate this change before anything is removed, so a mode that showed 46
fewer candidates than the run performs deletes for would undermine its own purpose. Successful prunes stay
silent in a **real** run; failures log through the existing per-event catch, as every other delete failure
does.

### D6. `listDir` returns `BunnyEntry[]`, with a `404` yielding `[]` — never a throw

The `null` was never distinguished from `[]` anywhere: all eleven call sites either apply `?? []` or hand
the value to `resolveMembership`, which does the same. Narrowing the type removes that ceremony from
`resolveMembership` and `eventIsStale` as well.

A `404` must map to `[]` rather than throw, because `bunny-list-endpoint` **requires** the tolerance in
terms: an absent or empty directory "SHALL be treated as 'no contributors' → `200 []`", and a partition
`404` "is not treated as a failure". Returning `[]` satisfies those requirements exactly as `null` did, so
this is behavior-preserving and needs no delta there. Making a directory `404` fail loudly would have
contradicted four existing scenarios — an appealing-sounding change that the spec had already ruled out.

### D7. `eventDir(eventId)` builder; `deleteObject` documents its recursion

Every storage key in this backend comes from a named builder in `storage.ts`; the pruned path is no
exception, and building it there keeps the `encodeURIComponent` treatment identical to its siblings.
`deleteObject` already accepts any key and has always been able to delete a directory recursively — that is
pre-existing, not introduced here — but its doc said nothing about it. It does now, with the citation.

## Risks / Trade-offs

- **A live event whose marker read spuriously `404`s is misclassified** → Unchanged from today, and now
  strictly narrower: the tombstone path additionally requires an empty manifest listing, so a live event
  with members can never reach the recursive delete. It falls to the existing careful path exactly as
  before.
- **Recursive delete has no documented atomicity** → It is only ever applied to a directory proven to hold
  no objects, so there is nothing to partially delete. A failure increments `errors`, logs through the
  existing catch, and the next run retries; the prune is idempotent and `404`-tolerant.
- **A transient bunny `5xx` skips one prune for a night** → Observed 2026-07-25 on a manifest LIST.
  Accepted: reclamation is eventually consistent by design, and the retry is automatic.
- **Bunny may one day prune empty directories itself** → The prune degrades to a `404`-tolerant no-op;
  `deleteObject` already treats `404` as success.
- **Device byte husks accumulate indefinitely** → Accepted per D3. Cost is one LIST per night per orphaned
  device and no count distortion. Revisit only if a safe (non-recursive, or gated) reclamation route
  appears, or if the orphaned-device population grows enough for the LIST cost to matter.
- **The first real run deletes ~46 directories at once** → They are proven empty by the classification, and
  the dry-run step below lists them all for inspection before anything is removed.

## Migration Plan

1. Merge. No deployment step: the sweep runs from the repo on a GitHub Actions runner, and
   `nightly-cleanup.yml` is unchanged.
2. `gh workflow run nightly-cleanup.yml -f dry_run=true`. Confirm the prune lines name the known husk ids
   and that no id belonging to a live event appears.
3. Let the 03:17 UTC schedule perform the first real run.
4. **Verify:** `events deleted` drops from ~46 to the count of genuinely stale events, and `GET events/`
   returns only live event directories (~4 at time of writing).

**Rollback:** revert the commit; the next run reverts to the prior behavior. The prune itself is not
reversible, but it removes only directories established to hold no objects, so there is no data to restore.

## Open Questions

None blocking. One deferred: whether device byte husks ever warrant reclamation (D3) — revisit only if a
route exists that cannot destroy an unlisted in-flight upload.
