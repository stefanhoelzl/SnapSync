# Design

## Context

See proposal.md for the motivation. This design starts from the following state:

- **Where the name travels.** A photo's capture name reaches the service twice.
  - In the device manifest (`resources[].filename`): it is validated but never stored, and the manifest's `key` is not
    stored either.
  - In the byte upload's required `?filename=` query: it is stored in `resources.filename`, logged in
    `request_log.url`, carried by failure reports, and served unchanged in the union and the event page's list.
- **The key.** The union's `key` (`<assetId>-<role>.<ext>`) is derived on the server from `asset_id`, `role` and the
  stored filename (`objectNameFor`), and mirrors the client's `UploadKey.of` exactly.
  - The extension is **load-bearing on the phone**. A received resource is staged as `…/<device>/<key>`, and the iOS
    importer hands PhotoKit that file with no stated type.
  - The upload ledger, the download store and every transfer tag hold keys in this shape.
- **The sealed metadata format** (`changes/seal-event-name`, D1–D3) consists of:
  - an envelope `0x01 ‖ nonce(12) ‖ AES-256-GCM ct+tag`, unpadded base64url, at most 1024 characters;
  - a key HKDF-SHA256(event key, no salt, `"snapsync/meta/v1"`);
  - a plaintext JSON object `{"name": …}` that grows additively.

  Kotlin `SealedMetaFormat` + `MetaCipher`; TypeScript `api/src/sealed-meta.ts`; vectors `test/vectors/sealed-meta.json`
  from `scripts/sealed-meta-vectors.py`.
- **The encrypted file format** binds a file by key derivation, not by GCM associated data: the segment key is
  HKDF(event key, salt, `snapsync/v1/<event>/<device>/<asset>/<role>`). The `Crypto` port's AES-GCM takes no AD.
- **Who uploads.** The app's session tier sends a sealed file, and the OS queue tier (PhotoKit) has the edge seal it.
  Both build their request in `UploadRequests` before asking `UploadSealing` how to seal.
- **Rollout.** `seal-event-name` is not merged yet (it is on this branch). This change ships in the same release, whose
  version becomes the floor that "Re-enable encrypted creation" raises `MIN_APP_VERSION` to.

## Goals / Non-Goals

**Goals:**
- The service never receives, stores or logs a readable photo name for an encrypted event.
- The sealed name cannot be moved onto another photo by the service.
- Nothing a phone stores changes shape: no on-device migration and no shim for transfers in flight.

**Non-Goals:**
- Capture dates, content types, roles, asset ids and sizes stay readable. The record format leaves room for a later
  field.
- The edge seal: an iOS PhotoKit upload's plaintext passing through the edge is untouched.
- Encrypted events created before this change (all test-rig) are not migrated.

## Decisions

### D1 — The same sealed record, its key derived per photo

A photo's name is sealed as the sealed metadata format's envelope and record (`{"name": "IMG_4471.HEIC"}`), under
`HKDF-SHA256(event key, no salt, "snapsync/file-name/v1/<event>/<device>/<asset>/<role>")`.

- **Binding by derivation**, as the encrypted file format binds a file. A record moved to another photo, device, event
  or role opens under no key there. The service can therefore not swap names between photos. That is cheap here
  because, unlike an event's name at creation, every id is known when a name is sealed.
- **Its own label prefix** (`file-name/v1`), so a file-name key never equals the event record's key or a file's key.
- **The same envelope and record type**, so one decoder and one shape check serve both, and a later per-photo field
  (a capture date) joins the record without a new format.
- *Rejected:*
  - **GCM associated data.** It needs an AD parameter on the `Crypto` port on both platforms, and gives the same
    binding.
  - **A bare UTF-8 name.** It allows no later fields.
  - **The event record's key, unbound.** It lets the service move names between photos.

### D2 — The sealed name rides in `filename`, the one field it always used

In an encrypted event the `?filename=` query, `resources.filename`, the union's and the web list's `filename` hold the
envelope text. The rule is **encrypted event ⇔ `filename` is a sealed record**. A reader knows which it holds from the
event's encryption, which every reader already has to know to open the photo.

- Ciphertext in `request_log.url` and failure reports is harmless, so neither needs a redaction.
- *Rejected:*
  - **A separate `sealedFilename` field.** It means two exclusive fields for one fact, and the user chose the one
    field.
  - **Carrying the name in the manifest.** It moves where the name is stored for no gain.

### D3 — The service checks the shape only

Both byte routes refuse an upload into an encrypted event whose `filename` is not an envelope of the sealed metadata
format (`envelopeShape`) with `400 invalid filename`, before a byte is stored. A plain event's filename is unchanged.
The check lives with the encrypted-body decision (`routes/encrypted-upload.ts`), where the event's key id is already
read. The mock backend mirrors it, and a contract clause pins it on both.

### D4 — The phone builds the key; the service's `key` stays only for older builds

Each phone derives `UploadKey.of(asset, role, name)` from the readable name it holds.

- The uploader does this from its own file name, as today.
- `EventUnion` does it from the name it opened, and stops reading the union's `key`.
- The key's shape, the staged file's extension, the ledger, the download store and transfer tags therefore stay
  exactly as they are.

The service keeps serving the `key` it derives so that 0.5/0.6 keep working on plain events. For an encrypted event
the derived key is meaningless (`.bin`), and no older build reaches one once "Re-enable encrypted creation" raises the
floor. The `PENDING_CLEANUPS.md` entry removes the field at that floor.

- *Rejected (after the interview):* **dropping the extension from the key for every event.** It needs:
  - a ledger and download-store migration;
  - renaming files already staged;
  - a shim for every in-flight transfer tag (sessions, downloads, PhotoKit jobs), each with an older-install layer;
  - an importer that states the type explicitly, measured on a device.

  All of that buys nothing once the phone derives the key.

### D5 — The manifest stops sending what the service never read

`ManifestPublication` no longer sends a resource's `key` or `filename`. The service never stored either, and sending a
readable name would undo D2. `openapi.yaml` makes both optional, so older builds still validate. Removing them from
the schema is part of the same `PENDING_CLEANUPS.md` entry.

### D6 — Every reader of a served name opens it, by the photo, for the membership it reads for

Two readers key a resource by its served name, and both now read it through `ServedNames`:

- `EventUnion`, the photo list that receiving plans, recognises and judges holdings from;
- `DeviceListing`, this device's own listing that the join seeds the ledger from and the foreground settles in-flight
  rows with.

A sealed name read as text would key every item `.bin`, and the device would upload everything again.

`ServedNames` is asked for the **membership the read is for** (`KeyPresence.of(membership, keys)`), never for the stored
joined one. A join lists its share set and recognises received photos *before* it enters its membership, with the
event's key already kept.

- **plain event** → names parse as today (`SenderFilename.parse`); an empty one still makes the union page unusable
  (`a-received-photo-always-has-a-sender-filename`) and still keys a listing item as before.
- **encrypted, key held** → each resource's name is opened (`FileNameCipher.openFileName`, bound to
  event/device/asset/role).
  - **Union:** a photo with any name that does not open, or opens empty, is **dropped from the page and logged at
    Warn**, since at Error every whole read would report it again. The page's position still advances past it, so it
    is never received, which is the outcome the `invite-link` requirement states.
  - **Listing:** an item whose name does not open is no evidence and is left out, so its upload is sent again,
    overwriting it.
- **encrypted, key not readable now or not kept** → the read is `Unavailable`. Nothing advances, and the next trigger
  reads it again.
  - The **holding check** (whether this member holds every photo of the others, which the event's deletion waits for)
    reads no name: it counts the union's photos by ref and its position (`EventUnion.held`, `HeldUnion`). A member who
    received everything and then lost the key still says so (`event-lifetime`). A photo left out for a name that does
    not open counts as one it lacks, so such a member waits for the sweep's later deadline, the safe side.

Dropping by photo rather than failing the page is what keeps one bad record (or a legacy readable name) from stalling
every other photo of the event.

- *Rejected:* **opening at import time.** It would store sealed names in the download store and change its meaning,
  and the key may be unreadable at import (a locked phone) in ways the import queue does not model.

### D7 — The event page opens names before naming the zip

The page's list read opens every resource's name with the invite's key and the resource's binding (`event-key.ts`)
before `zipEntries`. An asset with any name that does not open is left out whole, so a Live Photo's pair is never
split. `zip-names.ts` loses its `"photo"` / `"video.MOV"` fallbacks: every listed resource names its file (`minLength:
1`, `NOT NULL`, and the list serves only landed resources), so the fallback is dead code. A resource without a name is
skipped like one that does not open.

### D8 — No migration for existing encrypted events

Production never created an encrypted event (`changes/archive/2026-10-12-defer-encrypt-by-default`). The ones that
exist were created by the test rig, and they hold readable names. Those names do not open (D6, D7), so their photos are
not received and are left out of the zip until the nightly sweep deletes the events. A migration would have to delete
their stored objects outside the sweep, a targeted bunny cleanup bought for test data.

## Risks / Trade-offs

- [The service can no longer check a name for an encrypted event] → It only ever checked non-emptiness, and the
  envelope's minimum length guarantees a non-empty record text. A record that opens to an empty name is dropped by the
  receiver (D6).
- [An older build (0.5/0.6) joins an encrypted event by QR before the floor rises] → Its uploads are refused
  (`400 invalid filename`) and it reads ciphertext as names. That is only possible on rig-created events until "Re-enable
  encrypted creation", whose floor is this version. Accepted.
- [Per-photo key derivation costs an HKDF per name] → It is one HMAC pair per resource per upload or union read, which
  is negligible against the file's own derivation.
- [A record text near 1024 characters in a query] → A 255-character name seals to roughly 400 characters, well under
  URL limits and the format's cap.

## Migration Plan

1. Merge this with `seal-event-name` on this branch. The api deploys with the app's merge, as always.
2. The api accepts both manifest shapes and serves the union `key` as before, so 0.5/0.6 keep working on plain events.
3. "Re-enable encrypted creation" raises `MIN_APP_VERSION` to the version that ships this. After that, the
   `PENDING_CLEANUPS.md` entry removes the union `key` and the manifest's `key`/`filename`.

Rollback: reverting the app restores readable names on new uploads. The api's shape check would refuse them for
encrypted events, so revert the api together with the app. Plain events are unaffected either way.
