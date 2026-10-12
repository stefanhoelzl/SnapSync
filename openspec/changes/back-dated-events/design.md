# Design

## Context

See proposal.md for the motivation. The pieces that exist today and that this change reshapes:

- **Backend lifecycle** (`api/src/lifecycle.ts`, `api/src/db.ts`, migrations 0004/0008): an event row carries
  `starts_at`, `ends_at`, `created_at`, `lifetime_seconds`, `closed_at`, `completed_at`, `last_landed_at`. A
  membership is `sharing | settled | done | left` with `joined_at` and `manifest_version`. A v2 manifest publish
  with `final` after the end makes a membership `settled`, and the publish (or a leave) that leaves no `sharing`
  member stamps `closed_at` and wakes everyone with a `close` push. The join, the publish and the rename refuse a
  closed event. The nightly sweep's `sweepVerdict` drops at `max(created, starts) + lifetime` and *completes*
  (deletes memberships, assets, bytes; keeps the row) a joined event when every member departed or at
  `max(ends_at, last_landed_at) + 3 days`.
- **The union log** (0006, `changes/incremental-union`) already gives every event a monotonically growing position:
  a delta read answers "everything gained after position P" and returns the position it covers.
- **App** (`domain/feature/.../membership/`): `EventCompletion.finish()` ends every wake. After the end it
  publishes final, reads the event, and leaves (`LeaveEvent.leave()`) once the event is `CLOSED` and this device
  has everything. `MembershipRefresh` folds a `completed`/absent answer into a leave. `SwitchDecision` is a pure
  `current vs next` choice and always asks on a different event. `EventDirectory` maps `closedAt != null` to
  `closed` for every shipped build (`EventDirectory.kt:65`).
- **Create** (`CreateEvent`, `StatusContainerHost.onCreateEvent`, `ScreenModel.creatableEnd`): the range picker
  already admits past ranges; the create request carries `name`, `startsAt`, `endsAt`, `keyId`, `zone`.

## Goals / Non-Goals

**Goals:**
- One lifecycle formula for every event, parameterised by an open-until date fixed at creation.
- Shipped builds keep working unchanged, and never leave an event early because of the new rules.
- The "everyone is done" condition is computed in one place (the backend) and read by the sweep, the status
  line and the switch decision alike.

**Non-Goals:**
- Extending or editing an event's open-until date after creation.
- Pushing member-count or "everyone has shared" changes (foreground reads only).
- Concurrent multi-event membership (unchanged: switch = leave-then-join).

## Decisions

### D1 — The open-until date is a stored column, `open_until`, fixed at creation

`events.open_until` (wire `openUntil`) is set by `POST /events`: absent → `endsAt` (every shipped build), present →
validated `endsAt <= openUntil <= max(endsAt, now) + eventWindowMaxSeconds` (an `x-snapsync-*` rule like the
existing ones; the 1/7/14/30 menu is the app's, the server bounds only the span). Never written afterwards (the
row's write-once rule; rename still writes only `name`). The app computes `now + N days` as an instant, like
`CutoffFormatter.latestEnd`, so a DST change cannot push it past the server's bound.

*Alternatives:* deriving it from `created_at` when `created_at > ends_at` (no host choice, rejected in the
interview: the host decides how long data is kept); reusing the `closed_at` column for the planned date (every
build reads `closedAt != null` as closed — D6).

### D2 — There is no close; deletion is the only end

The close stamp, its refusals (join, v2 publish, rename) and the `close` push are removed for events created after
rollout. `sweepVerdict` becomes:

| verdict | when |
|---|---|
| DROP (row + anything left) | `now > max(created_at, open_until) + lifetime_seconds` |
| COMPLETE (photos go, row kept until DROP) | joined at least once, and any of: **finished**; `now > max(open_until, max(joined_at), last_landed_at) + 3 d`; every membership departed |
| KEEP | otherwise, and an event never joined |

`deletesAt` (served, and what the app's "premature gone is disbelieved" rule compares against) is the DROP instant.
Legacy rows get `open_until = ends_at` in the migration, so their cap moves from `max(created, starts)+30d` to
`max(created, ends)+30d` — later, never earlier.

*Alternative:* a separate join window followed by a close (rejected: with deletion no earlier than the window's end,
the close only exists for the day before the sweep and adds a refusal path for nothing).

### D3 — "Done" and "finished" are computed, never stamped

A present membership is **done** when it is `settled`, every role it declares has landed, and its
`received_position >= ` the event's union head. An event is **finished** when `now > open_until` and every present
membership is done. One SQL predicate in `db.ts` serves the sweep, the event read's member counts and the derived
`closedAt`. Nothing flips back: a new own photo is declared-but-not-landed, a new gain advances the union head past
the others' positions, a late join adds a `sharing` member — each makes the predicate false by itself, which is
the reversibility the spec asks for. `settled` itself stays one-way (it records that the device looked at its
library after the end).

### D4 — A device reports the union position it has fully received

New `memberships.received_position` and `PUT /v2/events/{eventId}/devices/{deviceId}/received` with
`{ position }` (gated like the other device routes; monotonic `MAX`). The app sends it when the download
controller's plan for the event is empty and every asset of the last union read (whose position it holds) is in the
library or was deleted there — the same answer `everythingReceived` gives today, plus the position. A member that
does not receive reports the head it read without importing. A departed member is not present and never blocks.
Old builds never report; they still leave on their own when closed (D6), which takes them out of "present".

*Alternatives:* a `received` flag on the manifest publish (rejected: receiving is not part of what a device
shares, and a flag needs an un-set path a position does not); counting a leave's `received=true` only (too late:
members no longer leave).

### D5 — `closedAt` becomes a compatibility answer for shipped builds

The event read serves `closedAt` = the stored stamp for legacy closed rows, else `open_until` once it has passed
and every present member has **shared** everything (`members.waiting == 0`), else `null`. Not "finished": shipped
builds never report a received position (D4), so a `closedAt` that waited for those reports would never appear for
them — they would never leave, and the fast path would wait for the clock. The sweep alone uses "finished". A shipped build that sees it does what it does today — leaves once it has everything —
which is safe exactly then. `completedAt` keeps its meaning. A legacy row with `closed_at` stored keeps every old
refusal (join, publish, rename) until it is dropped. Both are `PENDING_CLEANUPS.md` entries: the legacy rows retire
by date (30 days after rollout plus the old cap), the derived `closedAt` when `MIN_APP_VERSION` passes this release.

### D6 — The event read carries what the screens need

`GET /events/{id}` adds `openUntil` and extends `members` to `{ active, waiting }` (`waiting` = present members not
yet done *sharing*: not settled or with a declared role not landed). The status line's waiting text uses `waiting`,
"everyone has shared" is `waiting == 0` after the range end, and the member count is `active`. The join screen
reads the same route through `JoinDetails`.

### D7 — The app never leaves on its own

`EventCompletion` keeps steps 1–3 (deliver pending leaves; nothing before the end; settle once) and adds the D4
report; it drops step 5 (the leave). `MembershipRefresh` turns a confirmed deletion (`completedAt`, or a 404 past
`deletesAt`) into a **deleted** flag on the local membership instead of a leave; uploads and downloads for it stop
as on a leave, the membership row stays, and the joined screen renders the deleted state. Leave on a deleted
membership skips the confirm. The safeguards (offline, 5xx, premature 404) are unchanged.

### D8 — The switch decision reads the server at link open

`switchDecision` stays pure but takes the current membership's answer: on `LeavePrevious` the host reads
`GET /events/{current}` once. Silent switch iff the local membership is deleted, or (the range has ended, the read
succeeded, `members.waiting == 0`, and this device's received check — D4's — is true). Any failure → the existing
switch dialog. The silent path runs the same leave-then-join as a confirmed switch.

### D9 — The back-dated sheet lives in the create flow, not in the range picker

`onCreateEvent` checks `endsAt < now` at tap time; if so, the container shows a sheet (`UiState` overlay) instead
of creating. Each of its four choices dispatches create with `openUntil = now + N days`; Cancel closes the sheet
with the draft intact. The picker, `RangeBounds` and `creatableEnd` are unchanged. Strings go through
`composeResources` (EN + DE), copy per `metadata/messaging.md`; the sheet must not promise deletion on day N.

### D10 — No new pushes

The `close` push is removed. Joins and "finished" are not pushed; member count and the waiting line refresh on the
foreground event read that already runs. Gains keep their push, which already wakes receivers.

## Risks / Trade-offs

- [A shipped build leaves once the event is finished; a late guest joining afterwards un-finishes it, and that old
  build never receives the guest's photos] → accepted: the same as today's close for that user; new builds stay.
- [A silent member (phone off, app deleted without leaving) keeps the event unfinished] → the 3-day clock still
  completes it; the switch question is asked rather than skipped (safe direction).
- [A chosen 30-day open-until date plus the 30-day cap keeps a back-dated event's row up to ~60 days] → only the
  row past completion; photos follow the finished/clock rules. Accepted; the cap is the retention promise.
- [Legacy rows' cap moves later by the range length] → only delays DROP of rows whose photos are usually already
  completed; no promise shortens.
- [The received report is a new write per device per catch-up] → bounded by gains; one `MAX` update.

## Migration Plan

1. Migration `0014`: `events.open_until` (backfill `ends_at`), `memberships.received_position` (NULL). Additive;
   rehearsed by the `migration-rehearsal` gate.
2. Deploy the api first: shipped builds keep working (no `openUntil` → `endsAt`; derived `closedAt`).
3. Ship the app. A new build against a not-yet-deployed api never happens in order; if it did, the create would
   be refused as an unknown field → `A failed create says so and changes nothing`.
4. Rollback: the api can be rolled back; rows created with `open_until > ends_at` then fall back to the old rules
   (close at settle), which is today's behaviour.
