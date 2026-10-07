-- ═══════════════════════════════════════════════════════════════════════════════════════════════════
-- 0012 — THE REQUEST LOG, KEPT (`docs/architecture.md`, "The request log")
--
-- ⚠️ FROZEN ONCE APPLIED, like every migration here: the runner records a checksum of these bytes, so
-- editing this file makes every later apply refuse as `modified` history. A correction is a NEW file.
--
-- WHY: the edge script's console log is a ring of its last 100 lines on bunny — a burst of errors pushes
-- out everything before it, and nothing older than a few hours survives a normal day. Every request's
-- record (`request-log.ts`) is therefore also kept here, one row per request, for forensics and traffic
-- trends (`deno task logs`). The nightly sweep deletes rows older than 30 days and reports the previous
-- day's 5xx rows to the error tracker.
--
-- Operator-only, like the console line it mirrors: no address, no User-Agent. Not part of any event, so
-- deleting an event deletes none of its rows — the sweep's age limit does.
--
--   fields  the request's recorded facts as ONE JSON object: a key recorded once holds its value, one
--           recorded again an array of its values; a fault's detail sits under its tag
--   errors  the fault tags, space-joined, each once — NULL for a request that recorded none, so
--           `' ' || errors || ' ' LIKE '% fanout %'` finds one tag
--
-- PURELY ADDITIVE. A new table derives nothing from existing rows; the previous bundle never names it, so
-- a code rollback needs no schema rollback.
CREATE TABLE request_log (
  id        INTEGER PRIMARY KEY,
  at        TEXT NOT NULL,
  reqid     TEXT NOT NULL,
  method    TEXT NOT NULL,
  url       TEXT NOT NULL,
  status    INTEGER NOT NULL,
  ms        INTEGER NOT NULL,
  version   TEXT,
  bytes_in  INTEGER,
  bytes_out INTEGER NOT NULL,
  fields    TEXT NOT NULL CHECK (json_valid(fields) AND json_type(fields) = 'object'),
  errors    TEXT CHECK (errors IS NULL OR errors <> '')
) STRICT;

-- Every read is a time window (the tool's --since/--until, the sweep's day and its age limit).
CREATE INDEX request_log_by_at ON request_log (at);
