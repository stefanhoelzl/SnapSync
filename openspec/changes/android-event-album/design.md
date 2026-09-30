## Context

See proposal.md for the motivation and the specs for the outcomes. The current state that shapes the approach:

- **The core already threads an album through the import.** `ImportRequest.album` is resolved by the caller
  before the import (`DownloadController` → `eventAlbum()` → `AlbumCoordinator.albumIdFor`), and iOS files the
  asset into it inside the import's own commit. On Android `MediaStoreImport` hard-codes
  `RELATIVE_PATH = DCIM/Camera/`.
- **The album is gated by one Boolean.** `GalleryReader.supportsAlbumWrites` is `false` on Android; the host
  hands it to presentation as `albumOffered`, which hides the choice and saves `saveToAlbum = false`
  (`android-receiving` D2). So every existing Android membership is stored with the album off.
- **The core places own photos too.** `AlbumCoordinator.place` runs at enqueue time (`UploadCore.placeInAlbum`)
  and `AlbumGather` gathers an own set (the manifest projection) and a foreign set (imported union assets).
- **Every Android read is scoped to `DefaultGallery`** (`relative_path LIKE 'DCIM/%'`): the candidate reads
  (`assets`, the selection snapshot, the album listing) and the by-id reads (`assetsById`, `resources`,
  `albumMembers`) alike. Echo suppression (`NotEcho`) matches imported `_ID`s.
- **`ensureAlbum` runs on non-opt-in paths too.** The grant subscription (`launchAlbumGrantSubscription`) calls
  it on every emission, including the replay at each foreground launch; Provision (join, rescan, switch) and a
  reconfigure Save are the opt-in paths. On iOS a dangling album id is recreated there.
- minSdk is 30, so scoped storage applies everywhere: the app may update and move the MediaStore rows it owns
  (`OWNER_PACKAGE_NAME`) with no prompt, and must ask for any other row.

## Goals / Non-Goals

**Goals:**
- One album mechanism in the core with a platform fact deciding what it may hold, not an Android branch in each
  feature.
- The adapter stays thin: it creates, resolves, moves and imports. Whether an album is "deleted" and whether own
  photos are placed is decided in `feature/album`.
- No iOS behaviour change.

**Non-Goals:**
- Placing, copying or moving own photos on Android.
- Following a renamed folder (the specs treat it as deleted).
- Moving photos back to the camera folder when the album is turned off.
- Fixing iOS's recreation of a deleted album on a launch's grant replay (see Open Questions).

## Decisions

### D1. The gallery states its album kind: `AlbumKind.COLLECTION` or `AlbumKind.FOLDER`

`GalleryReader.supportsAlbumWrites: Boolean` becomes `val albumKind: AlbumKind` (the enum in `model/`, so
presentation can take it):
- `COLLECTION` (iOS): an asset may be in any number of albums, and adding one moves nothing.
- `FOLDER` (Android): an album is the folder a file lives in. Filing moves the file, and only files this app
  created may be filed.

The host hands presentation the kind in place of `albumOffered`: the choice is offered for both, and the note
depends on it (D7). The composition hands the kind to `AlbumCoordinator` and `AlbumGather`, where it decides
own-photo placement (D5) and the deleted rule (D4). `PhotoLibraryMock`'s album-writes switch becomes a kind
switch, still defaulting to `COLLECTION`.

- **Rejected: keeping the Boolean `true` on Android.** The core would then place own photos, and on Android
  `addToAlbum` would try to move camera files it does not own. The Boolean says whether an album is possible,
  not what it may hold, and that difference is the whole of this change.
- **Rejected: an "own photos placeable" Boolean beside `supportsAlbumWrites`.** Two flags that must agree
  (placeable implies writes), and neither names the underlying fact that an album is a folder.

### D2. An Android album id is the folder's relative path

`createAlbum(title)` answers `DCIM/SnapSync/<name>/` as the `AlbumId`. `<name>` is the title with `/`, control
characters and characters FAT/exFAT refuse removed, trimmed, and capped in length; a blank result becomes
`Event`. A path is taken when a MediaStore item already has that `RELATIVE_PATH` or the directory exists on
disk; `createAlbum` then tries ` (2)`, ` (3)` and so on. It creates the directory (`mkdirs`), so a second
event with the same name finds the path taken even while the first event's folder is still empty.

The album map (`AlbumMapService`, `eventId → albumId`) stores the path as it stores iOS's `localIdentifier`,
so a rejoin reuses it and an event rename never touches it.

- **Rejected: MediaStore's `BUCKET_ID`.** It is a hash of the path, so it cannot be imported into, and it
  exists only once the folder holds a file.
- **Rejected: an event-id suffix in the folder name.** Collision-free, but every gallery app would show the id.

### D3. `albumsById` on Android: a folder album resolves while it holds an item

An Android album resolves when at least one MediaStore item has its `RELATIVE_PATH`. Any item counts,
including one the member put there. That is the folder as a gallery app shows it: a folder with no media is no
album.

### D4. "Deleted" on a folder album: once filled, now empty (`feature/album`)

Because an empty folder is not an album (D3), "does not resolve" is ambiguous: *never filled yet* or *emptied
by the member*, whether they deleted it, emptied it or renamed it. The album map therefore records per event
whether its album has held a photo (`filled`). `AlbumCoordinator` sets it when an import into the album settles
`Imported` and when a gather move succeeds.

Under `FOLDER`, the import-time lookup (`albumIdFor`, which becomes `suspend`) answers:

| recorded | filled | resolves | answer |
|---|---|---|---|
| no | — | — | `null` (camera folder) |
| yes | no | — | the path (a fresh album fills) |
| yes | yes | yes | the path |
| yes | yes | no | `null`: **deleted**, so the camera folder |

`ensureAlbum` gains `optIn: Boolean`. A deleted `FOLDER` album is recreated only when `optIn` is true
(Provision and a reconfigure Save). The grant subscription passes `false`, so a launch's replay never brings
one back. Recreating reuses the same path when it is free, or a numbered one when it isn't, and clears `filled`.
Under `COLLECTION` nothing changes: `optIn` is ignored and the behaviour is today's.

A rename reads as a delete (spec: "A renamed album keeps receiving" is iPhone-only): the recorded folder is
empty, whatever became of its photos.

- **Rejected: telling a rename from a delete by whether the imported ids still exist.** It is possible (a move
  keeps `_ID`), and it would allow following a rename. The member chose to treat a rename as a delete; the
  simpler rule needs no presence read at import time.
- **Rejected: the adapter deciding "deleted".** It would have to persist `filled`, and the adapter decides nothing.

### D5. Under `FOLDER`, own photos are never placed

`AlbumCoordinator.place` is a no-op for the enqueue-time call under `FOLDER`, and `AlbumGather` skips its own
set. Only the foreign set is gathered, and a gather under `FOLDER` *moves* those rows through `addToAlbum`
(D6). The rule is in the coordinator, so the extension and the app cannot disagree (Android has no extension,
but the core is shared).

### D6. Android `addToAlbum` moves the app's own rows; the import writes into the album's folder

- **Import:** `MediaStoreImport` takes the target from `ImportRequest.album`: the album's path, or
  `DCIM/Camera/` when `null`. The pending → publish sequence, the orphan sweep (now over the camera folder
  *and* every folder under `DCIM/SnapSync/` the app owns rows in) and the naming (`(1)` suffixes in the target
  folder) are unchanged. The folder is created by MediaStore on insert, so an import never depends on D2's
  `mkdirs`.
- **Move:** `addToAlbum(album, ids)` updates `RELATIVE_PATH` for each id the app owns
  (`OWNER_PACKAGE_NAME` = this package). It skips an id that is gone, already there, or owned by another
  package (after a reinstall ownership is cleared), and answers `Ok` when every movable row moved. It never
  asks for consent. This relies on a move keeping `_ID`, which both echo suppression and the download store's
  import record depend on. **Measured 2026-09-30 on the emulator at API 30 and API 36:** an app-owned row moved from
  `DCIM/Camera/` to `DCIM/SnapSync/<name>/` by a `RELATIVE_PATH` update answers `1`, keeps its `_ID`, keeps its
  owner, and its file is at the new path. `FolderAlbumContract.A_MOVED_PHOTO_KEEPS_ITS_ID_AND_IS_NO_CANDIDATE`
  pins it on `ANDROID_EMU`.

### D7. The note says "received"

`ParticipationSections` offers the album for both kinds. The notes (`joinAlbumNote`, `reconfigureAlbumNote`)
take the kind. Under `FOLDER` they name received photos only and add that the member's own photos stay in the
camera folder; with receiving off they say the album collects nothing. The reconfigure on-note says "already
received". Presentation stops forcing `saveToAlbum = false` (the `offering()` helper goes away). A fresh
Android form starts with the album on, and an existing Android membership keeps its stored `false`.

### D8. The SnapSync folder is outside the Android default gallery for own-photo reads

`DefaultGallery` gets two fragments:
- **the candidate scope:** `DCIM/%` except `DCIM/SnapSync/%`. It is used by `assets(policy)`, the selection
  snapshot and the album listing the denylist reads.
- **the library scope:** `DCIM/%`. It is used by the by-id reads (`assetsById`, `resources`) and by
  `albumsById`/`albumMembers`, because presence, the import settle and the album's resolve must still see
  received photos in the album.

Every consumer of the one policy (the upload, the manifest and `N`) starts from the candidate reads, so all three
exclude the folder. That is the "one policy, one place" rule the photo-sharing spec relies on.

- **Rejected: a path rule in `SelectionPolicy`.** `AssetFacts` carries no path, and the Android default
  gallery is the adapter's definition by design (`photo-sharing`: "which the platform's gallery adapter
  defines"). A new rule would need a new platform fact on every asset for one Android folder.
- **Rejected: one scope for all reads.** Excluding the folder from by-id reads makes an imported photo read
  `ABSENT`, and the interrupted-import sweep would import it again forever.

## Risks / Trade-offs

- [A MediaStore `RELATIVE_PATH` update might mint a new `_ID` on some OEM or API level] → measured on the
  emulator at API 30 and 36, and pinned as a `GalleryImport` contract clause on `ANDROID_EMU`. If it does not
  hold, the gather re-records the new id in the download store before echo suppression can miss it. That would
  be a design revision, raised before implementation.
- [`mkdirs` under `DCIM` may be refused on some API level] → **measured 2026-09-30, API 30 and 36, under the
  full grant:** `File(DCIM, "SnapSync/<name>").mkdirs()` answers `true` and the directory exists; MediaStore
  lists nothing for an empty directory. Without a grant it was not measured: `createAlbum` runs only under usable
  access (`ensureAlbum`'s guard). If a device refuses it, `createAlbum` still answers the path, and only the
  same-name-while-empty collision is lost; D2's collision check still sees any folder holding an item.
- [An emptied folder leaves its directory] → **measured, API 30 and 36:** deleting a folder's only item through
  MediaStore leaves the directory on disk. D4 reads MediaStore items, not directories, so it still reads the album
  as deleted. D2's collision check sees the directory, so a recreation after a delete gets a numbered folder; the
  delete's leftover directory is empty and invisible in every gallery app.
- [The Android album holds half the event] → stated on the join and settings screens (D7) and in the spec.
- [Gathering moves files a backup app or another gallery indexed under `DCIM/Camera`] → those are the app's own
  received photos, and moving them is what the member opted into.

## Migration Plan

- No data migration. Existing Android memberships keep `saveToAlbum = false` and see the choice, off, in
  settings. The album map's `filled` flag defaults to `false`, which is exact for iOS (unused) and for Android
  (no album exists yet).
- iOS is unchanged: `COLLECTION` keeps every current path, and `optIn` is ignored under it.
- Rollback: revert the change. Folders already created stay in the member's gallery as ordinary folders, and
  their photos stay where they are.

## Verification on the emulator

**2026-09-30, API 36, the rig build over the REAL photo library** (backend, downloads and transfers mocked; another
member played with the `foreign-device` lever):
- The join form offered the album, on, with the received-only note.
- A received photo was saved straight into `DCIM/SnapSync/Party/` (`_ID` 541), and it was not a share candidate.
- With the album turned off, the next one went to `DCIM/Camera/` (542). Turning the album on again moved 542 into
  `DCIM/SnapSync/Party/`, keeping its `_ID`.
- After both rows were deleted through MediaStore (what a gallery app's folder delete does), the next photo (543)
  went to `DCIM/Camera/`: "emptied by the member".
- Turning the album off and on in settings recreated it as `DCIM/SnapSync/Party (2)/` and moved 543 in. The
  numbered name is D2 at work: the deleted folder's empty directory stays on disk and still holds the name.

## Open Questions

- iOS recreates a deleted album on a launch's grant replay (`ensureAlbum` from the grant subscription over a
  dangling id). That appears to contradict event-album's "A deleted album stays deleted". This change does not
  alter iOS; it is recorded here for its own change.
