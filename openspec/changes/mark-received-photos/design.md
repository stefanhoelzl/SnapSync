## Context

See proposal.md for the motivation. The facts below shape the approach.

- **The download store does five jobs,** all in `downloads.db` (App Group, deleted with the app):
  - dedup by source ref `(sourceDeviceId, sourceAssetId)`;
  - echo suppression: an imported row's `createdLocalId` feeds the `NotEcho` selection rule;
  - crash-safe import: the marker is written inside the change block, then adjudicated;
  - in-flight staging: urls, staged paths, enqueued;
  - the per-event received count.
- **The import name today** is `importFilename(originalFilename, resourceKey)`, the sender's name or else the
  storage key. iOS passes it through `PHAssetResourceCreationOptions.originalFilename`, and Android as
  `DISPLAY_NAME` in `DCIM/Camera`.
- **Reading a name back:**
  - iOS: a resource read (`GalleryReader.resources(ids)`). Measured on an SE2: about 4.5 ms per request plus
    about 3.45 ms per photo, batched.
  - Android: `AndroidGalleryReader` already reports `DISPLAY_NAME` as the primary resource's
    `originalFilename`. MediaStore may rename on collision (`name (1).ext`).
- **`SelectionPolicy` law:** every rule decides on `AssetFacts` alone, never on resources.
- **Join order:** `Provision` → `MembershipEntry`, which runs stop previous uploads → leave → `ShareSetLoad` →
  save config → start uploads. The config save is what makes the membership visible to both uploaders.
- **The event's date range is immutable** after creation; only the name can be renamed.
- **The device id survives a reinstall** (Keychain), and own-device refs are never planned for download.

## Goals / Non-Goals

**Goals:**
- A received photo's filename identifies its source ref, readable by any later install of the app.
- At every join, photos still in the library that were received for this event's union are settled as
  imported before any upload or download can start.

**Non-Goals:**
- Replacing `downloads.db`. It keeps in-flight staging, the memory of deleted photos, and the counts.
- A filename-based selection rule.
- Renaming photos imported before this change.
- Adoption outside a join: not on each reconcile, and not when receiving is switched on later.
- Asking a limited-access member to widen their selection.

## Decisions

### D1. The mark is a second record; the database stays authoritative

A filename cannot hold in-flight staging. It also cannot remember a photo the user deleted, and
`receiving-photos` promises that such a photo never comes back. So the mark only rebuilds settled rows.

- *Rejected: filename as the only record.* It breaks the deleted-photo promise and leaves staging with nowhere
  to live.
- *Rejected: filename-only settled rows plus a tombstone table.* That is two stores for one fact, with no gain
  over rebuilding.

### D2. The token is a hash of the source ref, not a bare flag and not raw ids

`token = base32(fnv1a64("<sourceDeviceId>/<sourceAssetId>") truncated to 50 bits)`: 10 lowercase characters
from `[a-z2-7]`.

- A bare `.snapsync.` flag would rebuild suppression only. Dedup would then need fuzzy
  `(name, capture date)` matching, which is ambiguous for bursts and same-named photos.
- Raw ids would put two UUIDs in a visible name, against the UUID-scrub posture.
- FNV-1a is used because `commonMain` has no hash library. Collision resistance against an adversary is not
  needed: a collision among one event's union refs only means one photo is adopted for the wrong ref, and at
  50 bits that has negligible probability.
- The hash and its byte order are pinned by a test, because changing them silently disables adoption of every
  photo marked before the change.

### D3. Filename shape: `<stem>.snapsync-<token>.<ext>`

- **Stem:** the sender's filename minus its extension, with any existing `.snapsync-<token>` removed first,
  so a name never carries two marks.
- **Empty sender name:** `snapsync-<token>.<ext>`, where `<ext>` comes from the resource key.
- **Live Photos:** on iOS both resources get the same token; the primary resource's name is authoritative
  when reading.
- **Parsing:** `\.snapsync-([a-z2-7]{10})` matched anywhere in the stem, rightmost match, case-insensitive.
  This tolerates MediaStore's ` (1)` suffix and a user appending text.
- All of this is one pure `model/` rule, `ReceivedPhotoName`: `mark(original, resourceKey, ref)` and
  `tokenOf(filename)`. It replaces `importFilename` at both import adapters and is unit-tested in
  `commonTest`.

### D4. Adoption runs at the join and again when the photo grant becomes usable, for every direction

Adoption sits beside `ShareSetLoad`:

1. Fetch the event's union (`EventUnionSource`).
2. Take the foreign refs the download store has not settled.
3. List gallery candidates inside the event's capture window, `[startsAt, endsAt]`.
4. Drop candidates the download store already knows as a `createdLocalId`.
5. Read the remaining candidates' names and parse their tokens.
6. Record each token that matches an open ref as `IMPORTED` with `createdLocalId` set to that candidate,
   tagged with the event (D7).

It runs at two moments:
- **In `MembershipEntry`, before the config save.** No uploader can see the membership while its received
  photos are still unsuppressed, for the same reason `ShareSetLoad` runs before the save. This is enough when
  the grant is already usable at the join.
- **When the photo grant becomes usable while joined**, in the composition's permission subscription, before
  `uploadTransitions.onPermissionChanged()` arms the uploads and its tail drains the staged downloads.

The second moment is not optional. A reinstall resets the photo grant, so its rejoin **always** provisions
with the access dialog still open: the join does not wait for the answer (`simplify-join-screen`, D3), and
the first moment reads an unreadable library. Measured on the SE2, 2026-09-30:
`adopted 0 … 0 marked in window` at 19:50:03, then the grant at 19:51:07. All four received photos were
imported again, and the two ≥ 3 MP ones were shared back.

Two rules make the second moment sufficient:
- **No import before the adoption has run under a usable grant** (`DownloadController.readyToImport`, bound
  to `ReceivedPhotoAdoption.ensureAdopted`: once per membership per process, serialized, and asked again
  while the library cannot be read). Every import drain asks it before claiming anything, whichever trigger
  reaches the drain first. A plain grant gate is not enough. Measured on the SE2, 2026-10-01: closing the
  dialog makes the app active, and its `onForeground` → reconcile → tail imported both photos while the
  grant's own pass was still reading names. Downloads the join planned and staged therefore wait. An
  unreachable union or an unread partial selection settles the pass instead of holding every import behind it.
- **Adoption settles a planned or staged row that no import has created an asset for** (D7), since by the
  grant the ref is already planned.

Running for every direction closes share-back for share-only rejoins, and makes the "receiving switched on
later" edge moot.

Failure handling:
- The union fetch is best-effort and never blocks the join or the grant, like `ShareSetLoad`. It is skipped
  offline or on failure, which leaves the accepted gap stated in the spec.
- With no usable grant there are no candidates, and adoption is a no-op.
- Under a partial grant with no selection read yet, there are no candidates either (D5); this falls under the
  limited-access gap.

Rejected alternatives:
- *Adopt on every reconcile.* It runs a snapshot and name check whenever unknown refs appear, which after the
  join means every new foreign photo, and it still misses share-only memberships.
- *Adopt only on an empty DB.* It needs a reliable "fresh DB" signal and misses a partial loss.
- *Make the join wait for the access answer.* It is simpler, but it changes when a join commits
  (`join-event`, `photo-access`), and it would still miss a grant given later in Settings.

### D5. Candidates come from the event window and the grant's own scope

- The window is the event's range, `[startsAt, endsAt]`, not the member's capture range: receiving covers the
  whole event, and the event range is immutable.
- There is no exact capture-date match. Android's EXIF-derived `DATE_TAKEN`, and a user-edited date on iOS,
  would miss.
- Under a full grant: one facts fetch narrowed to the window, then `resources(ids)` for names, skipping known
  ids.
- Under a partial grant: the candidates are the selection. The facts come from the in-memory selection
  snapshot the app already holds (the `Gallery` observer's latest emission), not a new walk, following the
  photo-access rule that a walk under a partial grant buys nothing.
- Cost is one batched name read per join: about 7 s for 2,000 in-window photos on an SE2, and free on Android.

### D6. Suppression needs no new rule

An adopted row carries `createdLocalId`, so the existing `NotEcho` rule excludes it from the upload, the
manifest, the status total `N`, and the join preview. `SelectionPolicy` stays facts-only.

A marked photo that reaches another member outside SnapSync (AirDrop, a messenger) is admitted on doubt, as
today.

### D7. Two store writes and a locked seam, no migration

`DownloadService.adoptAll(adopted, eventId)` settles, in one transaction:
- a ref with **no row**: `adoptImported`, an `INSERT OR IGNORE` of a terminal `IMPORTED` row carrying the
  adopted asset as its marker, with no resource rows;
- a ref **planned or staged, not terminal, and carrying no marker**: `adoptPending`, an `UPDATE` to
  `IMPORTED` with the adopted asset. No import has created an asset for such a ref. Its staged files are
  released by the pass that releases every imported row's bytes, and a transfer still in flight stages onto
  the settled row and is released the same way.

Every other row is left exactly as it is: a row carrying an import's marker (confirmed or not), and a
terminal row (imported, unimportable, or a photo the member deleted).

The write reaches the store only through `DownloadController.settleAdopted`. It runs under the controller's
mutex and skips any ref the controller has claimed for an import, so adoption never lands underneath an
import whose change block is about to write its own marker. `ReceivedPhotoAdoption` takes it as an injected
`record` (feature-blindness).

The schema is unchanged.

## Risks / Trade-offs

- **The name may not survive.** iCloud Photos sync, an edit, or an export could lose `originalFilename`.
  → Adoption only removes duplicates; a lost mark falls back to today's behaviour. Verify on device: sync,
  edit, and restore to a second device.
- **The limited selection resets on reinstall** (believed, not measured). → Accepted gap, stated in the spec.
  Adoption reads what the grant shows.
- **A user renames a photo and removes the mark.** → Same fallback: that photo may arrive again.
- **The name is visible to users.** → The mark is short, and the sender's stem is kept.
- **Join latency** on iOS grows with the size of the in-window library. → The read is batched and runs once
  per join; the join UI already waits for enrollment. Measure on an SE2 with a large window.
- **Token algorithm drift** would silently stop adoption. → A pinned golden test (D2).

## Migration Plan

There is no data migration. Photos imported before this ships keep their unmarked names and stay covered by
their existing DB rows until a reinstall.

Rollback: an older build ignores the marks, and its `importFilename` just stops adding them. Nothing to undo.

## Open Questions

- The exact interaction between the join UI's busy state and the adoption read on a very large library.
  Measure it; the approach does not change.
