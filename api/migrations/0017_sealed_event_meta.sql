-- ═══════════════════════════════════════════════════════════════════════════════════════════════════
-- 0017 — AN ENCRYPTED EVENT'S NAME IS STORED ONLY SEALED (capability `invite-link`, "The service keeps an
-- encrypted event's name only encrypted"; decision record `changes/seal-event-name`, D4)
--
-- ⚠️ FROZEN ONCE APPLIED, like every migration here: the runner records a checksum of these bytes, so
-- editing this file makes every later apply refuse as `modified` history. A correction is a NEW file.
--
-- WHY: an encrypted event's photos open only with its invite, but its name sat readable in `events.name`.
-- It now lives in `sealed_meta`: the sealed metadata record (`docs/architecture.md`, "The encrypted file
-- format"), an envelope the service can check the shape of but never open.
--
--   events.name         now NULLABLE — NULL exactly for an encrypted event created from this change on
--   events.sealed_meta  the record's envelope text; the one column besides `name` a rename writes
--
-- The CHECK states the three legal shapes of a row:
--
--   plain               no key id, a readable name, no record
--   sealed              a key id, no readable name, a record
--   legacy encrypted    a key id, a readable name, no record — an encrypted event created before this
--                       change (rig builds only). `POST /events` never creates one; the arm is dropped once
--                       none is left (`PENDING_CLEANUPS.md`, "Legacy readable names of encrypted events")
--
-- A REBUILD, because SQLite cannot drop NOT NULL in place. WIDENING only — every existing row is plain or
-- legacy encrypted, both legal — so it needs no precondition guard. Foreign-key enforcement is off for the
-- migration (`replay.ts`), so dropping `events` cascades nothing into `memberships` or `union_log`. `events`
-- carries no index or trigger of its own, so the rebuild drops none. Named columns on both sides.
CREATE TABLE _events_0017 (
  id               TEXT NOT NULL,
  name             TEXT NOT NULL,
  created_at       TEXT NOT NULL,
  starts_at        TEXT NOT NULL,
  ends_at          TEXT NOT NULL,
  capacity         INTEGER NOT NULL,
  lifetime_seconds INTEGER NOT NULL,
  closed_at        TEXT,
  completed_at     TEXT,
  last_landed_at   TEXT,
  zone             TEXT,
  key_id           TEXT,
  open_until       TEXT
);

INSERT INTO _events_0017 (id, name, created_at, starts_at, ends_at, capacity, lifetime_seconds, closed_at,
                          completed_at, last_landed_at, zone, key_id, open_until)
SELECT id, name, created_at, starts_at, ends_at, capacity, lifetime_seconds, closed_at,
       completed_at, last_landed_at, zone, key_id, open_until
  FROM events;

DROP TABLE events;

CREATE TABLE events (
  id               TEXT PRIMARY KEY NOT NULL,
  name             TEXT,
  created_at       TEXT NOT NULL,
  starts_at        TEXT NOT NULL,
  ends_at          TEXT NOT NULL,
  capacity         INTEGER NOT NULL,
  lifetime_seconds INTEGER NOT NULL,
  closed_at        TEXT,
  completed_at     TEXT,
  last_landed_at   TEXT,
  zone             TEXT,
  key_id           TEXT
    CHECK (key_id IS NULL OR (length(key_id) = 16 AND key_id NOT GLOB '*[^0-9a-f]*')),
  open_until       TEXT,
  sealed_meta      TEXT,
  CHECK (
    (key_id IS NULL AND name IS NOT NULL AND sealed_meta IS NULL)
    OR (key_id IS NOT NULL AND name IS NULL AND sealed_meta IS NOT NULL)
    OR (key_id IS NOT NULL AND name IS NOT NULL AND sealed_meta IS NULL)
  )
) STRICT;

INSERT INTO events (id, name, created_at, starts_at, ends_at, capacity, lifetime_seconds, closed_at,
                    completed_at, last_landed_at, zone, key_id, open_until)
SELECT id, name, created_at, starts_at, ends_at, capacity, lifetime_seconds, closed_at,
       completed_at, last_landed_at, zone, key_id, open_until
  FROM _events_0017;

DROP TABLE _events_0017;
