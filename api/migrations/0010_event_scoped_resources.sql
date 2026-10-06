-- ═══════════════════════════════════════════════════════════════════════════════════════════════════
-- 0010 — EVERY EVENT HOLDS ITS OWN BYTES (capability `photo-sharing`; change `per-event-storage-layout`)
--
-- ⚠️ FROZEN ONCE APPLIED, like every migration here: the runner records a checksum of these bytes, so
-- editing this file makes every later apply refuse as `modified` history. A correction is a NEW file.
--
-- WHY: a resource was DEVICE-scoped — `(device_id, asset_id, role)`, stored once under
-- `files/devices/<deviceId>/` and served to every event that declared it. No event owned its bytes, so none
-- could be deleted as a unit or, later, encrypted under its own key. A resource is now EVENT-scoped:
-- `(event_id, device_id, asset_id, role)`, child of the membership, and new bytes land under
-- `files/<eventId>/`.
--
-- `path` REPLACES `key`. The row records WHERE its bytes are — the full storage path, unencoded, one `/`
-- between segments (no segment can hold one: the routes refuse it) — so no reader composes a layout and the
-- two layouts that now coexist need no either-or logic anywhere. The wire's `key` (the union's, v1's
-- listing) is DERIVED from `asset_id`/`role`/`filename`, the value the v2 byte route stored, so it needs no
-- column; `UNIQUE (device_id, key)` only indexed the per-byte sweep this change retires.
--
-- A REBUILD, because SQLite cannot change a primary key. Each old row becomes one row per event it belongs
-- to, keeping its OLD path — no byte moves here:
--
--   every event whose membership (any state) DECLARES the asset   → its photos stay in that union
--   the device's PRESENT membership (`sharing`/`settled`)         → a byte that landed before its manifest
--
-- A row in neither set belonged to no event and is dropped; its byte is collected with the rest of
-- `files/devices/` later. Foreign-key enforcement is off for the migration, so dropping `resources`
-- cascades nothing; every row inserted below has its membership by construction.
CREATE TABLE _resources_0010 (
  event_id     TEXT NOT NULL,
  device_id    TEXT NOT NULL,
  asset_id     TEXT NOT NULL,
  role         TEXT NOT NULL,
  path         TEXT NOT NULL,
  content_type TEXT NOT NULL,
  filename     TEXT NOT NULL,
  PRIMARY KEY (event_id, device_id, asset_id, role)
);

INSERT OR IGNORE INTO _resources_0010
  (event_id, device_id, asset_id, role, path, content_type, filename)
SELECT ea.event_id, r.device_id, r.asset_id, r.role,
       'files/devices/' || r.device_id || '/' || r.key, r.content_type, r.filename
  FROM resources r
  JOIN event_assets ea ON ea.device_id = r.device_id AND ea.asset_id = r.asset_id;

INSERT OR IGNORE INTO _resources_0010
  (event_id, device_id, asset_id, role, path, content_type, filename)
SELECT m.event_id, r.device_id, r.asset_id, r.role,
       'files/devices/' || r.device_id || '/' || r.key, r.content_type, r.filename
  FROM resources r
  JOIN memberships m ON m.device_id = r.device_id AND m.state IN ('sharing', 'settled');

DROP TABLE resources;

CREATE TABLE resources (
  event_id     TEXT NOT NULL,
  device_id    TEXT NOT NULL,
  -- IDENTITY: which asset this resource belongs to, and which role it plays within it, in this event. An
  -- asset carries AT MOST ONE resource per role — an invariant the client upholds and this backend CANNOT
  -- verify, because a second same-role upload is indistinguishable from a legitimate re-upload. Keying on
  -- it bounds a violation to an overwrite.
  asset_id     TEXT NOT NULL,
  role         TEXT NOT NULL,
  -- ADDRESS, not identity: the full storage path of the bytes, unencoded — `files/<eventId>/<sha256>` for
  -- every byte written since this migration, `files/devices/<deviceId>/<key>` for one written before it.
  path         TEXT NOT NULL,
  content_type TEXT NOT NULL,
  filename     TEXT NOT NULL,
  PRIMARY KEY (event_id, device_id, asset_id, role),
  -- A resource lives as long as its membership: completion deletes memberships, a dropped event cascades
  -- into them. NOT `event_assets`: a publish deletes and re-inserts those, and a byte may land first.
  FOREIGN KEY (event_id, device_id)
    REFERENCES memberships(event_id, device_id) ON DELETE CASCADE
) STRICT;

INSERT INTO resources (event_id, device_id, asset_id, role, path, content_type, filename)
SELECT event_id, device_id, asset_id, role, path, content_type, filename
  FROM _resources_0010;

DROP TABLE _resources_0010;
