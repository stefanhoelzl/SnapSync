# Proposal

## Why

An encrypted event's photos and, since `seal-event-name`, its name are stored so that only the invite opens them. The
**name each photo had on its sender's phone** is still stored readable. The service keeps it per photo, serves it in
the event's photo list and the event page's list, and logs it in every upload's request line. A file name says less
than an event name, but it is still a member's own data: a renamed file ("Lena_birthday_cake.jpg"), a screenshot's
date, or the app it came from. The service never needs it. It only passes it on to the other members and to the event
page, and both of those hold the key. That design's non-goals named per-photo metadata as the follow-up, and it ships
in the same release, before shipped builds create encrypted events again (`PENDING_CLEANUPS.md`, "Re-enable encrypted
creation"). So no shipped encrypted event ever has a readable file name.

## What Changes

- In an **encrypted event**, a photo's file name is sealed on the sending phone under a key derived from the event's
  key, bound to that one photo (event, device, asset, role). It reaches the service, its store, its logs and its
  failure reports only sealed. It travels in the same place the readable name does today, so in an encrypted event a
  photo's name is always sealed. A **plain event** is unchanged.
- The service refuses an upload into an encrypted event whose name is not a sealed record. It cannot open the record,
  so it checks only its shape.
- A **receiving phone** opens each name with the event's key before it plans the download, and keeps the sender's name
  as it does today. A photo whose name does not open under the key is never received. A phone that cannot read the key
  right now reads the list again later.
- The **event page**, opened with the whole invite, opens each name in the browser. A photo whose name does not open is
  left out of the zip, a Live Photo's two files together. The page's fallback names for a photo without a name are
  removed: every upload names its file, so they never apply.
- A phone now builds each photo's key (`<assetId>-<role>.<ext>`) itself, from the readable name it holds. The uploader
  already does. The receiver now builds it from the name it opened. The service stops needing an extension. Its photo
  list keeps the key it derives only for builds older than this one, and removing that is a `PENDING_CLEANUPS.md`
  entry. Nothing a phone stores changes.
- The device manifest stops carrying each resource's key and file name, which the service never read. The schema keeps
  both, optional, for older builds (`PENDING_CLEANUPS.md`).
- Encrypted events that already exist were created only by test builds, and production never created one. They get no
  migration: their readable names do not open, so their photos are not received and are left out of the zip until the
  sweep deletes the events.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `invite-link`: a new requirement, "The service keeps an encrypted event's photo names only encrypted". It sits beside
  the event-name one. A photo whose name does not open reaches no member's library.
- `event-site`: "An encrypted event's photos open only with the whole invite". The zip names each photo by the name
  opened in the browser, and leaves out a photo whose name does not open.

## Impact

- **Formats**: `SealedMetaFormat` (Kotlin) and `api/src/sealed-meta.ts` gain the file-name record. It uses the same
  envelope and the same `{"name": …}` record, with a key derived under its own label and bound to the photo.
  `test/vectors/sealed-meta.json` and `scripts/sealed-meta-vectors.py` gain file-name vectors.
- **api**:
  - Both byte routes check that an encrypted event's `filename` is shaped as a sealed record.
  - `openapi.yaml` makes the manifest resource's `key` and `filename` optional, documents the sealed `filename`, and
    marks the union's `key` as kept for older builds.
  - `docs/architecture.md` describes the new behaviour.
- **App**:
  - `UploadRequests` builds the request from the upload's seal, so a sealed upload carries a sealed name, and both
    upload tiers ask for the seal first.
  - `ManifestPublication` drops `key` and `filename`.
  - `EventUnion` opens names and derives the resource key.
  - `FileNameCipher` seals and opens file names, over the envelope it shares with `MetaCipher` (`SealedRecords`).
  - The wiring is in `:composition:app`.
- **Mock backend and contracts**: they mirror the shape check and stop reading the manifest's name, and the contract
  clauses cover both.
- **Site**: `event-key.ts` opens a list's names; `zip-names.ts` loses its fallback names.
- **Docs**: `PENDING_CLEANUPS.md` gets the union `key` and manifest `key`/`filename` entry, and the re-enable entry's
  policy paragraph names photo names.
