-- ═══════════════════════════════════════════════════════════════════════════════════════════════════
-- 0011 — THE EVENT'S KEY ID (the encrypted file format, `docs/architecture.md`)
--
-- ⚠️ FROZEN ONCE APPLIED, like every migration here: the runner records a checksum of these bytes, so
-- editing this file makes every later apply refuse as `modified` history. A correction is a NEW file.
--
-- WHY: an ENCRYPTED event's photos are stored encrypted under a key that travels only inside the invite
-- link's fragment and never reaches this backend. What the backend holds is the key's ID — the first 8
-- bytes of HKDF-SHA256(key, no salt, "snapsync/key-id/v1"), 16 lowercase hex characters — so the byte
-- routes can refuse a file that names another key (or none), and a device can tell a link's key from the
-- wrong one, without the backend being able to open a single photo. NULL means a PLAIN event.
--
-- ADDITIVE AND DERIVES NOTHING. Every existing row lands NULL, which is the truth: no event was encrypted
-- before. Write-once, by `POST /events`, from the client's optional `keyId`; inert under the previous
-- bundle, which never names it.
ALTER TABLE events ADD COLUMN key_id TEXT
  CHECK (key_id IS NULL OR (length(key_id) = 16 AND key_id NOT GLOB '*[^0-9a-f]*'));
