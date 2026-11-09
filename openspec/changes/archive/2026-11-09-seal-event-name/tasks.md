# Tasks

## 1. The sealed record's format (D1, D2, D3)

- [x] 1.1 Extend `scripts/encrypted-file-vectors.py` with sealed-metadata vectors: the key → metaKey HKDF
  (`snapsync/meta/v1`), GCM with no associated data (D2), a fixed-nonce envelope `0x01 ‖ nonce ‖ ct ‖ tag` for
  `{"name":"Ana's 30th"}`, one record with an unknown extra key, one emoji name of 100 characters, and failure
  cases: a wrong key, a record sealed under another label, an unknown version byte, and truncated input. Regenerate
  `test/vectors/encrypted-file.json`, or a sibling `sealed-meta.json`. Verify: the script reruns byte-identically.
- [x] 1.2 Add `SealedMetaFormat` to `:domain:format`, covering the envelope layout, base64url text, the version byte
  and the 1 KiB cap, plus the metadata cipher in `data/crypto`, sealing and opening over the `Crypto` port. Opening
  keeps unknown JSON keys, so a re-seal preserves them. Verify: a Kotlin vectors test is green for every vector in
  1.1, including the unknown-key round trip; `./gradlew compileIosMainKotlinMetadata` passes.
- [x] 1.3 Add the TypeScript twin in `api/src/encrypted-file.ts`, or a sibling, used by the site and by the server's
  shape check. The server only decodes and checks the version and length; the site seals and opens. Verify: the
  `api/test/` vectors test is green for the same vectors.
- [x] 1.4 Document the record in `docs/architecture.md`, "The encrypted file format": its key derivation, envelope, JSON
  evolution rules, and "not AEAD associated data". Verify: the section names the vectors file, and
  `./gradlew build`'s doc gates pass.

## 2. The backend stores and serves the sealed record (D3, D4)

- [x] 2.1 Add migration `0017_sealed_event_meta.sql`: rebuild `events` with `name` nullable, `sealed_meta TEXT`, and
  the three-shape CHECK. Run `deno task schema`. Verify: a migration test shows that existing plain and legacy
  encrypted rows survive unchanged, that a sealed row with a NULL name inserts, and that each illegal shape is
  refused; `api/schema.sql` is regenerated.
- [x] 2.2 Update `EventRow`, `insertEvent`, `readEvent` and `renameEvent` in `db.ts`, and add `sealedMeta` to the wire
  schema (`:domain:wire` and its generated TS), with `name` optional.
  - `POST /events`: `keyId` requires `sealedMeta` and forbids `name`; no `keyId` requires `name` and forbids
    `sealedMeta`; a malformed or oversized record is a `400`.
  - Rename takes `sealedMeta` for an encrypted event and `name` for a plain one.
  - Every event body returns the field the row holds.
  Verify: `api/test/` cases cover each accepted and refused combination, rename of each shape, and the legacy row's
  body still carrying `name`.
- [x] 2.3 Update the HTTP API table in `docs/architecture.md` (`POST /events`, rename, `GET /events/<id>`). Verify:
  the table matches the routes' tests.

## 3. The preview name on the service side (D5)

- [x] 3.1 Update `routes/event-page.ts`. For an encrypted event, `<title>`, `og:title` and the heading come from the
  request's `n`: escaped, cut at 100 characters, with a generic title when it is absent. For a plain event the stored
  name is used and `n` is ignored. A missing or expired event names nothing, whatever `n` says. Verify:
  `api/test/event-page.test.ts` covers an encrypted event with and without `n`, HTML in `n`, a plain event with a
  foreign `n`, and an expired event with `n`.
- [x] 3.2 Strip `n` from what the service keeps: the `request_log.url` write in `request-log.ts`, and the URL the
  failure-report capture attaches. Verify: a test makes an `n`-carrying page request, then a forced failure on one,
  and asserts that neither the `request_log` row nor the captured report contains the name.

## 4. The app seals, opens and shows the name (D1, D2, D6)

- [x] 4.1 Domain: the event's name, as read, is a value that is either readable (plain or legacy) or sealed, with
  verdicts:
  - opened → the name;
  - the record does not open with the held key → a failed load, never an unnamed event;
  - no key → the ledger's last name.
  The name rules (trim, 1–100 characters) stay in the domain and are now the only check for a sealed name. Verify:
  domain tests for each verdict.
- [x] 4.2 Data:
  - `EventCreation` seals `{"name"}` with the new key and sends `sealedMeta` + `keyId`.
  - `EventDirectory` opens it on every read and refreshes the ledger's `name`.
  - `EventRename` re-seals the opened record with `name` replaced, keeping unknown keys.
  - A plain event keeps sending `name`.
  Verify: `:test:feature` tests for create, read, rename-preserving-unknown-keys and legacy fallback.
- [x] 4.3 Join screen: a whole invite whose record does not open is shown as a failed load with Retry; an incomplete
  invite's screen names no event. Verify: presentation tests, and an integration test with a backend mock serving a
  corrupted record.
- [x] 4.4 Joined screen: rename is withheld while the key cannot be read, on the same key read that hides the invite
  (`EventKeyQuery`), and returns after a restore. Verify: presentation tests (lost key, locked, restored); the
  lost-key integration test asserts no rename action while the key is lost and its return after the restore.

## 5. The invite carries the preview name (D5)

- [x] 5.1 `InviteLinkText`: `pathForm` accepts and discards a `?…` query, so `/join/<id>?n=…#k=…` parses to the same
  invite as without it, and a malformed query never turns an invite into "not an invite". The builder emits
  `?n=<percent-encoded opened name>` for the keyed path form's shared link only. The QR code's link and the install
  referrer stay without `n`. Verify: `InviteLinkTextTest` covers with and without `n`, `n` with `#`, `&`, `%` and
  emoji, a stale `n`, the QR link without `n`, and a referrer round trip; `InviteFormsIntegrationTest` covers the
  keyed form with `n`.
- [x] 5.2 Confirm on device that both platforms' link claims route a `?n=` link to the app. Check the AASA `/join/*`
  pattern and the Android intent filter, and fix them if either drops a query. Verify: rig builds on an iOS simulator
  (`ios-simulator`) and an Android emulator (`snapsync-android`) open a `?n=` invite on the join screen.
- [x] 5.3 The joined screen's QR code encodes the invite without `n`, while Share hands the system share sheet the
  link with `n`. Verify: a presentation test that the QR text has no query and the shared text carries the current
  name, both before and after a rename.

## 6. The event page opens the name in the browser (D5)

- [x] 6.1 `site/src/lib/event-key.ts` and the page island: once `#k=` verifies, open `sealedMeta` and replace the
  heading, `<title>` and zip name with it. A record that does not open leaves the server-rendered text and offers no
  download. Verify: `site/scripts` tests cover a stale `n` replaced by the opened name, no `n`, a corrupted record,
  and a page without a key that keeps `n`'s name.
- [x] 6.2 Drive the encrypted event page through the local backend (`local-backend`) in a browser with scripts off
  and on, both with and without `n`. Verify: screenshots show the generic/`n` heading without scripts and the opened
  name with them.

## 7. Rollout bookkeeping (D4, D7)

- [x] 7.1 Add a `PENDING_CLEANUPS.md` entry, "Legacy readable names of encrypted events". It covers the CHECK's third
  arm and the reader's `name` fallback for an encrypted event, with file anchors. Unlock: no `events` row with
  `key_id IS NOT NULL AND name IS NOT NULL`, by a count query, and no earlier than 30 days plus the lifetime after
  deployment. Removal: a migration dropping the arm, and deleting the fallback. Verify: the entry names both sites and
  the query.
- [x] 7.2 Update the "Re-enable encrypted creation" entry: its floor is the app version shipping this change, not
  0.5, because 0.5/0.6 read `?n=` as a damaged invite and cannot name a sealed event. Verify: the entry's unlock
  condition and order name this change.
- [x] 7.3 Update the Privacy Policy draft that returns with the re-enable (`site/src/pages/index.astro`): an
  encrypted event's name is stored encrypted, and its invite carries the name readable for link previews, which the
  service shows but does not keep. Verify: the text is staged in the re-enable entry's checklist, not published
  before that release.

## 8. Integration

- [x] 8.1 Extend `EncryptedEventIntegrationTest` end to end: a rig host creates "Ana's 3oth", and a second member
  joins via a `?n=` invite and sees the name. The host renames it to "Ana's 30th", and the guest sees the new name.
  The backend's stored row holds no readable name, and the page request log holds no `n`. Verify:
  `./gradlew :test:integration:test` is green.
- [x] 8.2 Run the full gate. Verify: `./gradlew build` is green with `deno` on PATH, and `./gradlew architectureDiagrams`
  leaves no diff.
