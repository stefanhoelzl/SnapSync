# Design

## Context

See proposal.md for the motivation. The state this design starts from:

- **Key custody.** An encrypted event's key travels only in the invite's `#k=` fragment. The backend holds
  `events.key_id`, an HKDF tag of the key ("The encrypted file format", `docs/architecture.md`). Photo bytes are Tink
  AES-GCM-HKDF streaming files bound to `snapsync/v1/<eventId>/<deviceId>/<assetId>/<role>`.
- **The name today.** It is `events.name TEXT NOT NULL`, trimmed, non-empty and at most 100 characters, validated in
  `POST /events` and in rename. `renameEvent` writes it as the only mutable column. It is read by:
  - the server-rendered event page: `<title>`, `og:title`, the heading (`routes/event-page.ts`);
  - every event body the app reads;
  - the app's ledger (`Membership.sq` keeps `name` and refreshes it on every read);
  - the zip name in the page script.
- **The id is minted by the backend.** `POST /events` returns `eventId`, so the creator does not know it while
  building the request.
- **Encrypted creation is rig-only today.** Shipped 0.5/0.6 can join encrypted events (`PENDING_CLEANUPS.md`,
  "Re-enable encrypted creation").
- **The link parser rejects a query.** `InviteLinkText.pathForm` hands everything before `#` to `EventId.parse`, so a
  shipped build reads `/join/<id>?n=…#k=…` as **no invite**: it shows the damaged-invite message.
- **The request log keeps the full URL.** `request_log.url` holds the request URL as received, and backend failure
  reports carry the request as received (`privacy-security`).

## Goals / Non-Goals

**Goals:**
- The service's database holds no readable name for an encrypted event created from now on.
- One sealed record, extensible without a new mechanism (JSON, additive).
- Link previews keep naming the event.
- No app build in use after the rollout meets an encrypted event it cannot name or a link it cannot read.

**Non-Goals:**
- Per-photo metadata: `resources.filename` and `event_assets.creation_date` stay readable. This is a follow-up
  change, which can reuse this envelope and key derivation with its own HKDF label.
- Dates, open-until, zone, members and status stay readable, because the service enforces lifecycle on them.
- Protecting the name from whoever holds the link, or from the messenger that renders its preview. The link is the
  key; the preview name is deliberately readable to them.
- Rollback protection: a hostile service could serve an older sealed record (an earlier name). This is accepted,
  since the service could equally withhold the event.

## Decisions

### D1 — One sealed JSON record, not a column per field

`events.sealed_meta` holds one envelope whose plaintext is a UTF-8 JSON object, today `{"name":"…"}`.

- Readers ignore unknown keys, and every new key is optional.
- A rename decrypts the record, sets `name`, **keeps every key it did not understand**, and re-seals it. A newer
  build's field therefore survives a rename by an older build.
- There is no version key inside the JSON. Format changes are versioned by the envelope's first byte (D3), so an
  inner version could only duplicate it or invite needless refusals.
- *Rejected:*
  - **A sealed column per field.** Every future field would cost a migration, a wire field and a rename path.
  - **A per-field patch on rename.** Several ciphertexts, and the "one struct" idea is lost.
- *Naming:* this is an encrypted record, **not** AEAD associated data, which would be authenticated but readable. The
  word "associated" is avoided in code to keep the two apart.

### D2 — Key: HKDF subkey of the event key; the binding lives in the derivation, not in AAD

- `metaKey = HKDF-SHA256(event key, salt = none, info = "snapsync/meta/v1")`, 32 bytes. This separates it from the
  file segment keys (`snapsync/v1/…`) and from the key id (`snapsync/key-id/v1`).
- AES-256-GCM, a fresh random 12-byte nonce on every seal, **no associated data**. This is how the encrypted file
  format binds a file: its binding is the HKDF info, and its GCM carries no associated data.
- *Rejected:* `AAD = "snapsync/meta/v1" ‖ keyId`, the first draft. The `Crypto` port's AES-GCM takes no associated
  data, and adding it would mean a port change on three platforms and new contract vectors. It also adds nothing: the
  key id is itself derived from the event key, so a record that opens under a key is already bound to that key's id,
  and the label already sits in the derivation.
- The record is sealed by the creating phone and sent inside the one `POST /events`. No eventId binding is needed,
  because event keys are per-event: a record moved to another event fails to open under that event's key.
- *Rejected:*
  - **Bind eventId.** It needs create-then-seal: two requests, and an event with no name in between. Or it needs a
    client-minted id, which changes the trust in id minting.
  - **Reuse the Tink streaming file format.** About 50 bytes of framing for a value of under 100 bytes, and a
    `FileBinding` (device/asset/role) that does not fit an event record.
- **Random nonces** are safe at this volume: a record is sealed once per create or rename, nowhere near the GCM bound.

### D3 — Envelope `0x01 ‖ nonce(12) ‖ ciphertext ‖ tag(16)`, base64url TEXT

- The same unpadded base64url text appears on the wire (`sealedMeta`) and in the column. No conversion at the
  boundary, and the STRICT tables keep it TEXT.
- **Server validation** — all it can check:
  - it decodes;
  - the version byte is known;
  - the length is between 29 bytes and a cap of 1 KiB of text.
  - An invalid record is a `400`, never a plain event. This mirrors `keyId`.
- **Name rules move to the client domain.** Trimming, non-empty and at most 100 characters are checked where the
  name is still readable. The domain already enforces them in the dialog (`manage-membership`).
- *Rejected:* a BLOB column (a conversion at the boundary for a few bytes); a JSON envelope (more parsing on the
  server, and more verbose).

### D4 — `events.name` becomes nullable; a CHECK states the three legal shapes

A table-rebuild migration, because SQLite cannot drop NOT NULL in place. It recreates `events` with `name TEXT`
nullable and `sealed_meta TEXT`, plus:

```
CHECK (
  (key_id IS NULL     AND name IS NOT NULL AND sealed_meta IS NULL)      -- plain
  OR (key_id IS NOT NULL AND name IS NULL     AND sealed_meta IS NOT NULL)  -- sealed
  OR (key_id IS NOT NULL AND name IS NOT NULL AND sealed_meta IS NULL)      -- legacy encrypted (pre-change)
)
```

- The legacy shape only exists for rig-created encrypted events that predate this change. `POST /events` refuses to
  create it: an encrypted create without `sealedMeta` is a `400`.
- A `PENDING_CLEANUPS.md` entry drops the third arm once no such row is left. Its unlock condition is 30 days plus
  the lifetime after deployment, checked by a count query.
- **Readers:** a body carries `name` or `sealedMeta`. The app shows `name` when present and the opened record
  otherwise. This fallback is the shim the entry retires.
- *Rejected:* an empty string or placeholder in `name`. It is easy to mistake for a real name, and a user-visible
  string would live in the DB.

### D5 — The preview name rides the invite as `?n=`, read by the server only to render

- **Form.** `https://snapsync.stho.net/join/<id>?n=<percent-encoded UTF-8 name>#k=<key>`. A crawler never receives
  the fragment, so the preview name has to sit before `#`. A query keeps every character; a path slug would lose
  emoji and accents.
- **The server's use of `n`** is to fill `<title>`, `og:title` and the no-script heading for an **encrypted** event:
  HTML-escaped as today, cut at 100 characters, and ignored for a plain event, whose stored name wins. For a missing
  or expired event the page names nothing (`event-site`).
- **Never kept.** `request-log.ts` writes the URL with `n` removed. The failure-report capture drops it from the URL
  it attaches. Nothing writes it to the DB. A test asserts all three for an `n`-carrying page request.
- **Preview-only.** The app's parser accepts and discards `n`. The joined screen, join screen, switch question,
  album title and share-sheet title all use the opened record. The page script replaces the heading, `<title>` and
  zip name with the opened record once `#k=` verifies.
- **The builder** adds `n` from the opened name at share time, to the **shared link only**, so a re-shared invite
  carries the current name. The **QR code encodes the link without `n`**: a QR is scanned, never previewed, so `n`
  would buy nothing there. It also keeps the QR as dense as today and unchanged by a rename.
- **The Play install referrer does not carry `n`.** The app ignores it, and Play then learns less.
- *Accepted cost:*
  - The service sees the name on each page fetch. It is not stored, but it is no longer secret from the service
    process.
  - The messenger's preview servers see it too.
- *Rejected:* a generic preview (the user chose previews that name the event); a sealed `n` (a crawler cannot open
  it).

### D6 — Rename re-seals on the phone; withheld without the key

- **Rename** sends a new `sealedMeta`, built from the opened record with `name` replaced (D1). The route swaps the
  column; the last write wins, as today.
- **Without a readable key** (lost, or locked since boot) rename is withheld. The joined screen hides it on the same
  key read that already hides the invite (`EventKeyQuery`), so both appear and disappear together.
- **The ledger keeps the last opened name** (`Membership.sq name`). A phone that loses the key, or is restored from
  a backup that did not carry it, still shows the event's name from the ledger. It cannot refresh that name until
  the invite restores the key.
- **A record that does not open** — whole invite, wrong ciphertext, so the service sent a bad record — is a failed
  load. The join screen says the event could not be loaded and offers Retry (`join-event`: "an incomplete answer such
  as an event without a name"). Never an unnamed join.

### D7 — Rollout: ride the re-enable, and raise the floor to this version

- A shipped 0.5/0.6 reads `?n=` as a damaged invite (Context), and cannot name a sealed event.
- So no shipped build may **share** a `?n=` link or **create** a sealed event before every build in use can read
  both. Both happen only in the release that turns encrypted creation back on.
- That release's `MIN_APP_VERSION` is **the version that ships this change**, not 0.5. The "Re-enable encrypted
  creation" entry in `PENDING_CLEANUPS.md` is updated to say so.
- Until then, rig builds create sealed events and share `?n=` links. A shipped build that joins one sees a damaged
  invite or no name, which is accepted for rig events.
- **Spec sequencing.** This change's `invite-link` delta keeps the sentence "every event this version creates SHALL
  be created without encryption". The re-enable change, archived after this one, modifies that same requirement, and
  its delta must be rebuilt from the main spec as this change leaves it.

## Risks / Trade-offs

- [The server sees the name in page requests] → It is never stored or logged (D5) and is tested. This is stated in
  the Privacy Policy update.
- [A forged `n` can show a misleading preview for a real event] → Previews only. The app and the opened page always
  show the members' name. The id must still name an existing event.
- [A stale `n` in a link shared before a rename] → Only that link's preview shows the old name; everything a member
  sees is current. A QR code carries no `n`, so a printed one never goes stale.
- [A lost key shows a frozen name] → The ledger's last name is shown, and rename and invite are withheld until the
  key returns (D6).
- [The table rebuild on the deployed bunny Database] → A rebuild is a create-copy-drop-rename inside the migration
  runner's transaction. Rehearse it against a copy of production data via `deno task dev:local` before deploying. The
  backend's minimum-version gate is untouched, so a rollback is redeploying the previous bundle: nullable `name` rows
  only exist for sealed events, which only rig builds create before the re-enable.
- [The name rules are no longer enforced by the server] → A modified client could store a 1 KiB name. The cap bounds
  it, and every reader cuts the display at 100 characters.

## Migration Plan

1. Land the api first:
   - the migration;
   - `sealedMeta` accepted and returned;
   - `n` rendering and stripping.
   Old bundles' rows stay valid: plain, or legacy encrypted.
2. Land the app and site in the same release:
   - sealing at create and rename;
   - opening on read;
   - `?n=` in shared links;
   - the parser accepting `?n=`;
   - the page script's swap.
   Rig builds exercise it from the merge.
3. Ship the re-enable release with `MIN_APP_VERSION` set to this version (D7).
4. After 30 days plus the lifetime, retire the legacy CHECK arm and the `name` fallback (`PENDING_CLEANUPS.md`).
