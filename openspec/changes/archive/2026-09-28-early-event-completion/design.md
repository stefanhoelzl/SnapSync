## Context

An event's fate is decided today by two sweep rules (`api/src/lifecycle.ts` `eventIsStale`): DEADLINE
(`max(createdAt, startsAt) + lifetimeSeconds`, the guarantee) and EMPTY (every enrolled membership
departed, opportunistic because `LeaveEvent` sends its `DELETE` fire-and-forget, never retried). The sweep
is a GitHub Actions cron (Bunny's edge has no scheduler); deleting an event row cascades memberships and
`event_assets`, and the asset phase then collects bytes no surviving event references.

Measured facts this design builds on:

- `events` has no state column (id, name, created_at, starts_at, ends_at, capacity, lifetime_seconds); no
  "last byte landed" time exists anywhere — `resources` has no time column.
- The v2 manifest publish (`PUT /api/v2/events/:id/devices/:d/manifest`) is a full-state replace with the
  device as its one writer, ordered by `version`; it wakes members only when it adds a fetchable asset.
  The byte route wakes members for every event a landing completes (`eventsCompletedBy`).
- `GET /events/:id` answers 404 for a missing event; `EventDirectory` maps only 404 to `NotFound`, every
  other refusal to `Failed` → the device stays joined. `MembershipRefresh` leaves on `NotFound` only past
  the local `deletesAt`, and runs from the `Foreground` flow only.
- `DELETE /events/:id/devices/:d` is idempotent (200 for any existing event), 404 for a missing one.
- A refused manifest publish returns `false`; the dedupe marker is not saved, so the next upload cycle
  retries. There is no retry loop beyond that.
- The heartbeat BG task is re-armed only while work remains; background wakes are otherwise the silent
  push, finished transfers, and the extension.
- The 426 gate (`MIN_APP_VERSION = 0.4`) covers v2 only.

Decision record of the interview this change came from: the user's own walk-through (early completion by
markers → reduced to "manifest final", closing, done = leave, one clock).

## Goals / Non-Goals

**Goals:**
- An ended event whose members all hold everything is deleted within about a day, without anyone acting.
- Every member's app leaves on its own once the event is finished for it, background included.
- A member who went silent cannot keep the photos on the server longer than ~3 days past the last
  arrival.
- No new notification permission; no nightly pushes; no server timer at the range end.

**Non-Goals:**
- The join screen's layout and retention wording (reworked separately; this change only adds the closed
  refusal).
- A farewell screen after an automatic leave (decided: nothing is shown).
- Waking a silent device (decided: the clock handles it).
- Concurrent multi-event membership (named future; nothing here deepens the single-membership assumption).

## Decisions

### D1 — "Manifest final" is a flag on the device's own manifest publish

The v2 manifest body gains `final: boolean`; the membership row stores it (`memberships.final`,
nullable/false by default, cleared by a (re)join exactly like `manifest_version`). The device sets it on
the first manifest it produces after `endsAt` whose projection came from a discovery that ran after
`endsAt` — i.e. the policy has looked at the library since the end, so every in-range photo (iCloud-only
originals included, since they are listed before their bytes exist) is declared. Bytes may still be
uploading.

Only the device ever changes its own flag, so it needs no generation stamp and no server-side revocation
(an earlier "stamp against union version + member count" idea died once the second marker became a
leave). Until the close, a device that discovers more in-range photos simply re-publishes a still-final
manifest.

*Alternative rejected:* a separate progress route — a second writer on the membership and a second
request per cycle, for a bit the manifest already carries.

*Trigger:* `DeviceManifestProducer`'s dedupe compares the serialised body, so adding `final` makes the
first post-end cycle publish once more with no new trigger. No server timer: the device notices the end
at its next foreground, silent push, BG task or extension run. A device that never runs again is handled
by the clock (D4).

### D2 — Closing is stamped, not derived

`events.closed_at` (nullable). The manifest publish that leaves every **active** membership `final`
stamps it inside the same batch, and — because all bytes may already have landed, so no byte-route wake
would fire — calls `notifyMembers` once. The sweep stamps it too when the clock (D4) passes, in the same
transaction that completes the event (D5).

Stamped rather than derived because closing is **final** (a later join, or a device's flag going stale,
must never reopen it), because it must fire exactly one wake, and because every refusal below reads one
column.

A closed event refuses, server-side:
- **join** (v2 `PUT …/devices/:d`, v1 publish-enroll) → `410 {error:"closed"}`. Old apps map 410 to the
  generic failure (not "full", which a 409 would have read as).
- **rename** → `410 {error:"closed"}`; the app's rename is hidden anyway.
- **manifest publish whose asset set differs** from the stored one → `409 {error:"closed"}`. An identical
  set (a version bump, a flag-only change) is accepted as a no-op 200, so a device whose last publish
  raced the close is not stuck retrying.

The UI locks (QR, share, settings, rename hidden) are the client half; the server half is authoritative.

### D3 — Done is a leave, and a leave is delivered durably

A device leaves on its own when, after a tail or a foreground refresh: the event is closed (learned from
`closedAt`, D6), every ledger row of its own is uploaded, and the download store holds every foreign
union asset settled (imported, or deleted by the member) with nothing pending or staged. A
persistently-failing import is simply "not settled" — it keeps the member in the event until the clock.
The leave is `LeaveEvent` unchanged in effect (stop, clear ledger, clear config), with no dialog and no
screen.

`LeaveEvent`'s fire-and-forget `DELETE` becomes a **pending-leave record** (`PendingLeaves`, a SHARED-area
file, one entry per event id) written before the local teardown and cleared on 200 or 404 (a completed
event answers 200; a dropped one 404 — nothing left to leave either way).
Every wake the app already gets (foreground, silent push, BG task) retries outstanding entries. For the
member a leave stays instant and offline-capable; manual leaves get the same durability, which is what
turns EMPTY from opportunistic into dependable.

*Alternative rejected:* a "downloaded everything" flag — an extra marker with a staleness rule (union
growth, new join) the leave makes unnecessary: a departed membership IS "done", and EMPTY already exists.

### D4 — One clock, derived from one new column

`events.last_landed_at`, written by the byte route for every event a landing completes (the same
`eventsCompletedBy` set that drives the wake). Clock = `max(ends_at, last_landed_at) + 3 d`. It applies
only to an event with at least one membership (a never-joined event keeps living to its deadline, as
today). At the clock the sweep closes (if not yet) **and** completes the event in one step: members still
active are left behind — accepted.

*Why one clock rather than "3 d to close, 3 d to delete":* the last arrival already woke every receiver;
3 days after it, a member that has not finished is silent, and a second grace would only prolong
retention for it.

### D5 — Completing deletes the data and keeps the row until the deadline

`events.completed_at`. The sweep completes an event when (a) it has memberships and every one is
departed (EMPTY — now also covering a finished, closed event) or (b) the clock passed. Completing
deletes every membership (cascade: `event_assets`), sets `closed_at` if null, keeps the row with its
name and range; the asset phase then collects the bytes as it does for any deleted event (a completed
event contributes no references and no floor). At the DEADLINE the row is dropped as today.

The row lives on so the API can say **"completed"** instead of "not found": `GET /events/:id` answers
200 with `completedAt` set (old apps read it as Found and stay joined until their own deadline rule — no
regression). The app leaves on `completedAt` in the foreground **and** in the background — the evidence
is positive, so the "disbelieve an early 404" rule (still in force for a bare 404) does not apply.
`DELETE …/devices/:d` answers 200 for a completed event so a pending leave clears. The web event page
and the union listing treat a completed event as expired.

*Alternative rejected:* delete the row and answer 404 — a device left behind by the clock would then
disbelieve it until the 30-day date, stuck in an event that no longer exists.

### D6 — The app learns the event state at the end of every wake it already has

`GET /events/:id` gains `closedAt`, `completedAt` and `members {active, final}` (the status line's counts).
Every wake funnels into the tail through `WakeHold.thenTail` (the heartbeat through its own hand-over);
after a **full-scope** tail — never inside it, since a leave must not run in the tail it would stop — the
end-of-wake step `EventCompletion.finish` runs once: deliver outstanding leaves; stop there before the
range end (no request is spent before anything can close); re-publish once if the last published
manifest is not yet final (under a partial grant the tail publishes nothing on its own); read the details
through `MembershipRefresh`; leave when closed and everything is here. One details read per full wake
after the end is the whole cost — no polling, and no change to the union route.

*Alternative rejected:* carrying `closedAt` on the union read. It would have saved that one request but
changed a route the web page and every app version share, for a field only new apps read.

### D7 — Status: "waiting for N of M"

While the range has ended, the event is not closed, and the status is "In sync", the ended line reads
"Event ended · waiting for N of M members", N = active − final, M = active, from D6's counts (refreshed
with the details). After the close a member in sync has everything and leaves, so no post-close waiting
line is needed. The joined screen of a closed event drops QR, share, settings and rename.

## Risks / Trade-offs

- [A device with nothing to do after `endsAt` may not run until the member opens the app] → the clock
  closes without it; its in-range photos found later are lost to the event. Accepted in the interview
  (silent devices are ignored).
- [Old app versions never send `final`] → an event with an old-app member closes only on the clock;
  after the close their manifest publishes get 409 and are retried each cycle (harmless, bounded by the
  deadline). No `MIN_APP_VERSION` bump: a 426 would stop them syncing at all.
- [A member deletes a shared photo after the close; the server keeps serving it] → the new promise
  (`photo-sharing`: fixed once closed). The copy stays at most until completion.
- [`last_landed_at` is the only new write on the byte route] → one extra `UPDATE` per landing in the same
  batch; the byte route already resolves `eventsCompletedBy`.
- [The close push is a second kind of wake] → still content-available with the event id; the app's
  `SilentPush` flow runs its normal tail, which now ends with the auto-leave check. `privacy-security`
  names it.
- [Clock-completion can cut an upload in flight] → only 3 days after the last landing; that upload's
  device was silent for 3 days.
- [Spec purposes still carry the old promises] → the delta format cannot edit `## Purpose`; the sync step
  rewrites the Purpose paragraphs of `event-lifetime`, `manage-membership`, `join-event`, `sync-status`
  and `photo-sharing` by hand (task 8).

## Migration Plan

1. api migration: `events.closed_at`, `events.completed_at`, `events.last_landed_at`,
   `memberships.final` — all nullable, no backfill (existing events have no clock anchor beyond
   `ends_at`, which is correct).
2. Deploy the api first: it accepts `final` absent (old apps), serves the new fields additively, and the
   sweep's new rules act only on events that meet them.
3. Ship the app. Rollback: the app ignores unknown fields; reverting the api leaves the columns unused.

## Open Questions

- None blocking. The exact status-line copy is the UI's (not the spec's).
