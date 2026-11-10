-- ═══════════════════════════════════════════════════════════════════════════════════════════════════
-- 0018 — THE OPERATIONAL TABLES KEEP ONLY WHAT OPERATIONS READ (decision record `changes/delivery-record`, D9/D10)
--
-- ⚠️ FROZEN ONCE APPLIED, like every migration here: the runner records a checksum of these bytes, so
-- editing this file makes every later apply refuse as `modified` history. A correction is a NEW file.
--
-- WHY: everything kept only for diagnostics moves into the request log, the delivery record (`docs/architecture.md`,
-- "The delivery record"); nothing operational read these:
--
--   request_log.version        the declared app version — now `fields.app_version`, copied over first so no reader
--                              needs a fallback for the rows this migration finds
--   union_log fetch / removed  who read the union and what a publish withdrew — the request log's `reader=` and
--                              `withdrawn=`; with them go the columns only they used (trigger, cursor_from,
--                              cursor_to, served) and every row's `at`
--   devices.app_version        the version a device last declared — the request log's `app_version`
--   devices.attested_at        when a device last attested — the request log's record of the attestation
--   devices.push_updated_at    when a device last registered its push token — the request log's record of it
--
-- ⚠️ NOT ROLLBACK-SAFE, by decision (D10): the bundle before this one writes `union_log.at` with every landing,
-- `attested_at` with every attestation and `push_updated_at` with every push registration, so an automatic rollback
-- of THIS deploy fails those until it is rolled forward (`docs/deployment.md`, "Rollback").
UPDATE request_log SET fields = json_set(fields, '$.app_version', version) WHERE version IS NOT NULL;
ALTER TABLE request_log DROP COLUMN version;
DELETE FROM union_log WHERE kind IN ('fetch', 'removed');
ALTER TABLE union_log DROP COLUMN trigger;
ALTER TABLE union_log DROP COLUMN cursor_from;
ALTER TABLE union_log DROP COLUMN cursor_to;
ALTER TABLE union_log DROP COLUMN served;
ALTER TABLE union_log DROP COLUMN at;
ALTER TABLE devices DROP COLUMN app_version;
ALTER TABLE devices DROP COLUMN attested_at;
ALTER TABLE devices DROP COLUMN push_updated_at;
