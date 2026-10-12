-- ═══════════════════════════════════════════════════════════════════════════════════════════════════
-- 0014 — AN EVENT IS OPEN UNTIL A DATE, AND A DEVICE SAYS WHAT IT HAS RECEIVED (capability `event-lifetime`;
-- decision record `changes/back-dated-events`, D1–D4)
--
-- ⚠️ FROZEN ONCE APPLIED, like every migration here: the runner records a checksum of these bytes, so
-- editing this file makes every later apply refuse as `modified` history. A correction is a NEW file.
--
-- WHY: an event no longer CLOSES. Deletion is the only end of its life, and every lifecycle rule counts from
-- the event's OPEN-UNTIL date: the range's end for an ordinary event, the date the host chose for one created
-- after its end (so a back-dated event lives long enough for its guests to scan the invite). Whether an event
-- is FINISHED — every member still in it has shared and received everything — is computed on every read,
-- never stamped, so a late guest or a new photo makes it unfinished again by itself.
--
--   events.open_until               write-once, by `POST /events` (absent → `endsAt`). Nullable only because
--                                   SQLite cannot add a NOT NULL column without a default; every row is
--                                   backfilled below and every reader falls back to `ends_at`.
--   memberships.received_position   the union-log position (`union_log.seq` of a `gained` row) up to which
--                                   the device holds every photo of the others; NULL until it first says so.
--                                   Monotonic: only a higher position is ever written.
--
-- ADDITIVE. The backfill gives every existing event `open_until = ends_at`, which is what it always was:
-- its range end. A row closed before this change keeps its `closed_at` and the old rules with it.
ALTER TABLE events ADD COLUMN open_until TEXT;
UPDATE events SET open_until = ends_at WHERE open_until IS NULL;
ALTER TABLE memberships ADD COLUMN received_position INTEGER;
