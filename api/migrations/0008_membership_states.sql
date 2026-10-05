-- ═══════════════════════════════════════════════════════════════════════════════════════════════════
-- 0008 — A MEMBERSHIP'S STATE NAMES WHERE IT STANDS (capabilities `event-lifetime`, `manage-membership`)
--
-- ⚠️ FROZEN ONCE APPLIED, like every migration here: the runner records a checksum of these bytes, so
-- editing this file makes every later apply refuse as `modified` history. A correction is a NEW file.
--
-- WHY: a membership was `active` | `departed` plus a separate `final` flag, so "where does this member
-- stand" was two columns read together, and a leave said nothing about HOW the member left. It is now ONE
-- column of four states:
--
--   sharing   joined, and has not settled what it shares
--   settled   its last publish after the event's end declared its share settled (the old `final = 1`)
--   done      left having everything: settled, every role it declared landed, and — said by the device at
--             the leave — every photo of the others received
--   left      left with anything missing
--
-- `done` and `left` are both "gone" everywhere (no push, neither holds the close, both count toward an empty
-- event, both stay in the union); the split is for observability only.
--
-- A REBUILD, because SQLite cannot add a CHECK to an existing column. The rows are copied out, the table is
-- recreated with the constraint and without `final`, and the rows are copied back — named columns on both
-- sides. Foreign-key enforcement is off for the migration, so dropping `memberships` cascades nothing into
-- `event_assets`. The backfill:
--
--   active   + final = 1                              → settled
--   active                                            → sharing
--   departed + final = 1 + every declared role landed → done
--   departed                                          → left
--
-- A past departure cannot tell whether the device had received everything; the backfill accepts that a few
-- historical `done` rows may overstate it.
CREATE TEMP TABLE _memberships_0008 (
  event_id         TEXT NOT NULL,
  device_id        TEXT NOT NULL,
  state            TEXT NOT NULL,
  joined_at        TEXT NOT NULL,
  manifest_version INTEGER
);

INSERT INTO _memberships_0008 (event_id, device_id, state, joined_at, manifest_version)
SELECT m.event_id, m.device_id,
       CASE
         WHEN m.state = 'active' AND COALESCE(m.final, 0) = 1 THEN 'settled'
         WHEN m.state = 'active' THEN 'sharing'
         WHEN COALESCE(m.final, 0) = 1 AND NOT EXISTS (
           SELECT 1 FROM event_assets ea, json_each(ea.roles) r
            WHERE ea.event_id = m.event_id AND ea.device_id = m.device_id
              AND NOT EXISTS (SELECT 1 FROM resources rs
                               WHERE rs.device_id = ea.device_id AND rs.asset_id = ea.asset_id
                                 AND rs.role = r.value)
         ) THEN 'done'
         ELSE 'left'
       END,
       m.joined_at, m.manifest_version
  FROM memberships m;

DROP TABLE memberships;

CREATE TABLE memberships (
  event_id         TEXT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
  device_id        TEXT NOT NULL,
  state            TEXT NOT NULL CHECK (state IN ('sharing', 'settled', 'done', 'left')),
  joined_at        TEXT NOT NULL,
  manifest_version INTEGER,
  PRIMARY KEY (event_id, device_id)
) STRICT;

INSERT INTO memberships (event_id, device_id, state, joined_at, manifest_version)
SELECT event_id, device_id, state, joined_at, manifest_version
  FROM _memberships_0008;

DROP TABLE _memberships_0008;
