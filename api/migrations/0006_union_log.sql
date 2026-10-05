-- ═══════════════════════════════════════════════════════════════════════════════════════════════════
-- 0006 — THE EVENT'S UNION LOG (capability `privacy-security`; decision record
-- `changes/incremental-union`, D4–D5)
--
-- ⚠️ FROZEN ONCE APPLIED, like every migration here: the runner records a checksum of these bytes, so
-- editing this file makes every later apply refuse as `modified` history. A correction is a NEW file.
--
-- WHY: a read of the event union used to return every asset, so every push made every member read and
-- sign the whole event again. A client now reads from a POSITION, and this table is what positions
-- point into. It records, per event and in one global order:
--   gained    an asset became servable (its last declared role landed, or a publish declared one whose
--             bytes were all stored already) — what a delta read serves after a cursor;
--   removed   a publish stopped declaring an asset that was servable — a log entry only, never served;
--   fetch     a read of the union: by which device (only when its token verified, else NULL), why
--             (`trigger`, as the client says), from which position (`cursor_from`, NULL for a full
--             read) to which (`cursor_to`), and how many assets it was given (`served`).
--
-- `seq` is AUTOINCREMENT, not a bare rowid: a cursor must never be handed out twice, and a rowid reuses
-- the largest value once its row is deleted — which completing an event does.
--
-- PURELY ADDITIVE. A new table derives nothing from existing rows; the previous bundle never names it, so
-- a code rollback needs no schema rollback. It starts empty: every client's first read is a full one.
--
-- Writers:
--   gained   the v2 byte route (the events `eventsCompletedBy` answered) and the v2 manifest publish
--            (inside its batch, under the publish's own gates)
--   removed  the v2 manifest publish (inside its batch)
--   fetch    the shared union read, best-effort, after it assembled its answer
--   (delete) completing an event; deleting one cascades
CREATE TABLE union_log (
  seq         INTEGER PRIMARY KEY AUTOINCREMENT,
  event_id    TEXT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
  kind        TEXT NOT NULL,
  device_id   TEXT,
  asset_id    TEXT,
  trigger     TEXT,
  cursor_from INTEGER,
  cursor_to   INTEGER,
  served      INTEGER,
  at          TEXT NOT NULL
) STRICT;

-- A delta read scans one event's rows past a position; so does the completion's delete.
CREATE INDEX union_log_by_event_seq ON union_log (event_id, seq);
