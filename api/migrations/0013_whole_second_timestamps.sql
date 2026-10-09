-- ═══════════════════════════════════════════════════════════════════════════════════════════════════
-- 0013 — ONE TIME SHAPE ON THE WIRE (`api/openapi.yaml`, `Timestamp`)
--
-- ⚠️ FROZEN ONCE APPLIED, like every migration here: the runner records a checksum of these bytes, so
-- editing this file makes every later apply refuse as `modified` history. A correction is a NEW file.
--
-- WHY: every instant the backend serves is the canonical `yyyy-MM-ddTHH:mm:ssZ` — whole seconds, UTC — so
-- that a timestamp compares as TEXT the way it compares as time, on the backend and on the device alike.
-- `startsAt`, `endsAt` and the derived `deletesAt` always were. `createdAt`, `closedAt` and `completedAt`
-- were minted with `toISOString()` and so carried milliseconds (`…T19:24:17.182Z`); they are now minted at
-- whole seconds, and this brings the rows already stored to the same shape.
--
-- TRUNCATES, never rounds: the fraction is dropped, so each instant moves at most 999 ms EARLIER. Nothing
-- reads these columns finer than a second — `deletesAt` is derived from `created_at` and itself rounded
-- down to the second, and `closed_at` / `completed_at` are read for presence — so no outcome changes.
--
-- IDEMPOTENT AND SHAPE-GUARDED: only a value of exactly the `toISOString()` shape is rewritten, so a row
-- already whole-second, NULL, or of any shape this file does not recognise is left exactly as it was.
-- The previous bundle parses either shape (`Date.parse`), so a code rollback needs no schema rollback.
UPDATE events SET created_at = substr(created_at, 1, 19) || 'Z'
  WHERE created_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9][0-9][0-9]Z';

UPDATE events SET closed_at = substr(closed_at, 1, 19) || 'Z'
  WHERE closed_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9][0-9][0-9]Z';

UPDATE events SET completed_at = substr(completed_at, 1, 19) || 'Z'
  WHERE completed_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9][0-9][0-9]Z';
