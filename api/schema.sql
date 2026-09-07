-- ═══════════════════════════════════════════════════════════════════════════════════════════════════
-- THE SCHEMA, AS THE MIGRATIONS BUILD IT (capability `database`) — GENERATED, DO NOT EDIT.
--
-- Regenerate with:  deno task schema
-- CI fails when this file is not what replaying `api/migrations/*.sql` produces.
--
-- ⚠️ NOT a migration. It is `sqlite_master` verbatim, ordered by object NAME so that a migration
-- produces the smallest possible diff — which means a table may appear before the table it references.
-- Nothing applies this file; to build a store, replay the migrations.
--
-- Do NOT confuse it with `api/migrations/0001_baseline.sql`, which is a FROZEN INPUT: that file is
-- checksummed and never changes, this one moves with every migration. On the day they were introduced
-- the two were near-identical text, which is why both say which they are.
--
-- ⚠️ A DELETION IN THIS FILE'S DIFF IS THE POINT. A rebuild drops the table's indexes and its table
-- options with it, and a migration that forgets to restore them says nothing about it — the absence IS
-- the bug, and it shows up here as removed lines and nowhere else. Read the deletions.
-- ═══════════════════════════════════════════════════════════════════════════════════════════════════

CREATE TABLE devices (
  device_id               TEXT PRIMARY KEY NOT NULL,
  created_at              TEXT NOT NULL,
  attest_key              TEXT NOT NULL,
  attest_env              TEXT NOT NULL,
  attested_at             TEXT NOT NULL,
  attest_token_expires_at TEXT NOT NULL,
  push_kind               TEXT,
  push_token              TEXT,
  push_env                TEXT,
  push_updated_at         TEXT
) STRICT;

CREATE TABLE event_assets (
  event_id      TEXT NOT NULL,
  device_id     TEXT NOT NULL,
  asset_id      TEXT NOT NULL,
  creation_date TEXT NOT NULL,
  -- The roles this asset DECLARES for this event, as a JSON array (e.g. '["primary","live"]').
  -- The manifest is the only party that knows an asset has two resources, so the expectation has to
  -- be recorded here: `resources` holds only what ARRIVED, and one row proves nothing about whether
  -- a second is still owed. The union compares the two as SETS (`json_each` + NOT EXISTS), never as
  -- counts — `resources` is device-scoped while this is event-scoped, so a device may hold a role
  -- this event does not declare, and counting would read that asset as incomplete and drop it.
  -- NOT NULL so no read ever needs a fallback branch for a row written before the column existed.
  roles         TEXT NOT NULL,
  PRIMARY KEY (event_id, device_id, asset_id),
  FOREIGN KEY (event_id, device_id)
    REFERENCES memberships(event_id, device_id) ON DELETE CASCADE
) STRICT;

CREATE TABLE events (
  id               TEXT PRIMARY KEY NOT NULL,
  name             TEXT NOT NULL,
  created_at       TEXT NOT NULL,
  starts_at        TEXT NOT NULL,
  ends_at          TEXT NOT NULL,
  capacity         INTEGER NOT NULL,
  lifetime_seconds INTEGER NOT NULL
) STRICT;

CREATE TABLE memberships (
  event_id  TEXT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
  device_id TEXT NOT NULL,
  state     TEXT NOT NULL,
  joined_at TEXT NOT NULL,
  PRIMARY KEY (event_id, device_id)
) STRICT;

CREATE TABLE resources (
  device_id    TEXT NOT NULL,
  -- IDENTITY: which asset this resource belongs to, and which role it plays within it. An asset
  -- carries AT MOST ONE resource per role — an invariant the client upholds and this backend CANNOT
  -- verify, because a second same-role upload is indistinguishable from a legitimate re-upload of the
  -- same resource. Keying on it bounds a violation to an overwrite: no orphan object, and no row that
  -- disagrees with the bytes it names.
  asset_id     TEXT NOT NULL,
  role         TEXT NOT NULL,
  -- ADDRESS, not identity: the bare stored object name under the device's byte partition
  -- (<assetId>-<role>.<ext>). Composed by the BACKEND, and byte-identical across API versions, so a
  -- device that moves between versions finds its bytes where it left them rather than re-uploading
  -- its whole library. Kept as a column so the storage layout can change without changing what a
  -- resource IS, and so two versions can address one row while spelling the name differently.
  key          TEXT NOT NULL,
  content_type TEXT NOT NULL,
  filename     TEXT NOT NULL,
  PRIMARY KEY (device_id, asset_id, role),
  -- One stored object, one row. The key encodes identity, so this can never contradict the primary
  -- key — it earns its place by indexing the sweep's lookup, which addresses a row by object name.
  UNIQUE (device_id, key)
) STRICT;
