-- ═══════════════════════════════════════════════════════════════════════════════════════════════════
-- 0015 — A RESOURCE'S UPLOAD CAN RESUME (`docs/architecture.md`, "Resumable uploads"; Bugsink SNAPSYNC-54)
--
-- ⚠️ FROZEN ONCE APPLIED, like every migration here: the runner records a checksum of these bytes, so
-- editing this file makes every later apply refuse as `modified` history. A correction is a NEW file.
--
-- WHY: on a slow link the pull zone ends a request after 60 s without progress, and every retry started
-- again from byte 0 — a video retried seven times, 40 MB sent, none of it kept. The byte route now stores a
-- large body as an S3 multipart upload and keeps what arrived when the client drops, so a retry continues
-- where the last attempt stopped. What it keeps between requests lives on the resource's row: one row per
-- resource for its whole life, uploading and landed.
--
--   landed_at      when the row's bytes were stored and became servable — what "a resource row exists"
--                  meant until now. NULL for a resource whose FIRST upload is still under way: such a row
--                  is in no union, listing or completeness check (`landed()` in `db.ts`). Every existing
--                  row has landed; it is stamped with this migration's time, its real one unknown.
--   sha256         the SHA-256 of the landed bytes, when the device declared one; a later status read for
--                  the same bytes answers "already landed"
--   upload_*       the upload under way, all NULL when none: its S3 multipart id (NULL until the first
--                  part filled), the SHA-256 and total size the device declared, and the part size it is
--                  cut into (fixed for the upload, so every request cuts the same boundaries)
--   tail_*         what a dropped request received past its last stored part, kept as one storage object:
--                  its key, where it starts, and its length. It counts only while it starts exactly where
--                  the stored parts end, so a stale tail never overstates the offset
--   claim          the request writing the upload now. A newer request for the same resource takes it, and
--                  every later write of the older one is conditional on it, so the older one stops
--
-- An uploading row is a child of its membership like every other: a leave or completion cascades it away,
-- and the sweep aborts the S3 upload behind it with the event's folder (`scripts/sweep.ts`).
--
-- ⚠️ A CODE ROLLBACK past this migration needs a REPAIR once the newer bundle is back. The previous bundle's
-- insert names no `landed_at`, so a photo it records reads as still uploading — out of every union, while its
-- device holds a 201 and never sends it again. The bundle this migration ships never leaves `landed_at` NULL
-- without an upload under way, so those rows are exactly the ones with neither (`docs/deployment.md`):
--   UPDATE resources SET landed_at = <now> WHERE landed_at IS NULL AND upload_sha256 IS NULL;
--
-- Additive otherwise: new nullable columns.
ALTER TABLE resources ADD COLUMN landed_at TEXT;
UPDATE resources SET landed_at = strftime('%Y-%m-%dT%H:%M:%SZ', 'now');
ALTER TABLE resources ADD COLUMN sha256 TEXT;
ALTER TABLE resources ADD COLUMN upload_id TEXT;
ALTER TABLE resources ADD COLUMN upload_sha256 TEXT;
ALTER TABLE resources ADD COLUMN upload_size INTEGER;
ALTER TABLE resources ADD COLUMN upload_part_size INTEGER;
ALTER TABLE resources ADD COLUMN tail_key TEXT;
ALTER TABLE resources ADD COLUMN tail_start INTEGER;
ALTER TABLE resources ADD COLUMN tail_bytes INTEGER;
ALTER TABLE resources ADD COLUMN claim TEXT;
