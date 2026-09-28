-- ═══════════════════════════════════════════════════════════════════════════════════════════════════
-- 0004 — EARLY EVENT COMPLETION (capability `event-lifetime`; decision record
-- `changes/early-event-completion`, D1–D5)
--
-- ⚠️ FROZEN ONCE APPLIED, like every migration here: the runner records a checksum of these bytes, so
-- editing this file makes every later apply refuse as `modified` history. A correction is a NEW file.
--
-- WHY: an event no longer lives its full lifetime once it is finished. Each device declares its manifest
-- FINAL after the event's range has ended; the event CLOSES once every active membership is final (or at
-- the clock), and is COMPLETED — its memberships, assets and so bytes deleted, its row kept until the
-- lifetime deadline so the API can answer "completed" rather than "not found" — once every member has
-- left, or at the clock. The clock is `max(ends_at, last_landed_at) + 3 days`.
--
-- ADDITIVE AND DERIVES NOTHING. Every existing row lands NULL, which is correct for each column: no event
-- has closed or completed, no landing time is known (the clock then anchors on `ends_at` alone), and no
-- membership has declared itself final.
--
-- Writers:
--   events.closed_at       the v2 manifest publish that leaves every active membership final; the sweep
--                          when it completes an event that had not closed
--   events.completed_at    the sweep, once
--   events.last_landed_at  the v2 byte route, for every event a landing completes an asset of
--   memberships.final      the v2 manifest publish (the device's own declaration); the join clears it,
--                          beside `manifest_version`
ALTER TABLE events ADD COLUMN closed_at TEXT;
ALTER TABLE events ADD COLUMN completed_at TEXT;
ALTER TABLE events ADD COLUMN last_landed_at TEXT;
ALTER TABLE memberships ADD COLUMN final INTEGER;
