# Proposal

## Why

An encrypted event's photos are stored so that only the invite opens them, but the event's **name**, often the most
personal thing about it ("Ana's 30th", "Grandma's funeral"), is still stored readable on SnapSync's service. The
service holds it in its event record and puts it into every page and link preview it renders. "Only the whole invite
opens an event" is therefore true of the photos and not of what the event is called. The name is the one piece of
an event's own description that the service does not need to run the event: dates, status and membership drive
uploads and deletion, and the name drives nothing. Sealing it now, before shipped builds create encrypted events
again (`PENDING_CLEANUPS.md`, "Re-enable encrypted creation"), means no shipped encrypted event ever has a readable
name.

## What Changes

- An encrypted event's descriptive metadata, today only its **name**, is stored on the service **only encrypted**,
  under a key derived from the event's own key, as one sealed record. The record is a JSON object, so later
  descriptive fields join it without a new mechanism. Dates, open-until, time zone, members and status stay readable:
  the service enforces the event's lifecycle on them.
- A plain event is unchanged: its name stays readable.
- The **keyed path invite** gains an optional, readable name for link previews, in the shared link only. The QR code
  is scanned, never previewed, so it does not carry it:
  `https://snapsync.stho.net/join/<eventId>?n=<name>#k=<key>`. A messenger's link preview never sees the `#k=`
  fragment, so this is the only way a preview can still name the event. The service uses that name only to render
  the page and the preview it is asked for, and keeps it in no record, log or failure report.
- The link's name is **for previews only**. The app never reads it. The event page, once the whole invite opens the
  event in the browser, shows the name the members gave the event in its place. A stale name (after a rename) or an
  edited one therefore never reaches a member's screen or the downloaded zip's name.
- **Rename** of an encrypted event re-seals the record on the renaming phone, keeping any field it does not know.
  The service can no longer check the name, so the app's name rules are the only ones applied to it. A phone that
  cannot read the event's key offers no rename, just as it already offers no invite.
- Encrypted events created before this change keep their readable name until the sweep deletes them. Every app
  build that creates encrypted events after this change seals the name.
- **Rollout**: this ships in the same release that turns encrypted creation back on for shipped builds. That
  release's minimum app version is the version that can open a sealed name, not 0.5, so no app still in use meets
  an encrypted event it cannot name.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `invite-link`: the keyed path form may carry a readable name for link previews, which nothing relies on. The
  service holds an encrypted event's name only encrypted. "The invite carries the event identifier only" becomes
  "the identifier, the key, and at most a preview name".
- `event-site`: an encrypted event's page and preview name the event from the invite's preview name, and, once the
  whole invite opens the event in the browser, from its sealed name. Without either, the page and preview name no
  event.
- `privacy-security`: the service's own failure reports never carry an encrypted event's name.
- `manage-membership`: renaming an encrypted event is not offered while the phone cannot read the event's key.
- `sync-status`: the joined screen's rename is present "as `manage-membership` offers it", no longer
  unconditionally, in the same way its invite already defers to `invite-link`.

## Impact

- **api** (`api/src/db.ts`, `api/migrations/`, `routes/event-page.ts`, `routes/v2.ts`, `routes/shared.ts`,
  `request-log.ts`, `site.ts`):
  - A table-rebuild migration makes `events.name` nullable and adds `sealed_meta`, with a CHECK that a new encrypted
    event holds one and a plain event the other.
  - `POST /events` and the rename route accept `sealedMeta`, and every event body returns it.
  - The event page renders from `?n=` for an encrypted event.
  - The request log and failure reports drop `n`.
- **Wire / domain / data**:
  - `:domain:wire`: `name` becomes optional and `sealedMeta` is added.
  - Domain: the event's name becomes a value that is either readable or sealed.
  - `data/crypto`: a metadata cipher, with the HKDF label `snapsync/meta/v1`, AES-GCM, and the envelope
    `0x01 ‖ nonce ‖ ct+tag`.
  - Event creation, rename and the event read go through the cipher.
- **Invite**: the link builder adds `?n=` to the keyed path form; the link readers on every platform ignore it.
- **Site** (`site/src/lib/event-key.ts`): opens the sealed record, and swaps the decrypted name into the heading,
  the title and the zip name.
- **Test vectors**: `test/vectors/` gains sealed-metadata vectors, checked by both the Kotlin and the TypeScript
  halves.
- **Docs**: `docs/architecture.md` ("The encrypted file format", the HTTP API table) and `PENDING_CLEANUPS.md`. The
  legacy readable-name rows get an entry to tighten the CHECK, and the re-enable entry raises its floor to this
  version. The Privacy Policy states that an encrypted event's name is stored encrypted.
