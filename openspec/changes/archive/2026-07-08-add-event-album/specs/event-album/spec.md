## ADDED Requirements

### Requirement: Opt-in album mirroring per membership

The system SHALL mirror an event's synced photos into a single PhotoKit album on the device — titled
after the event's (non-null) `name` — when that membership's persisted `EventConfig.saveToAlbum` is
`true` (capability `deeplink-config`). The set placed into the album SHALL be **every** photo the membership
syncs in its participation direction: the **foreign photos it downloads** and/or the **own photos it
uploads** (capability `join-event` direction). When `saveToAlbum` is `false` (the default) the system
SHALL create no album and place no photos. The choice is **fixed for the membership** — there is no
runtime toggle; a change is a leave-and-rejoin. Album placement SHALL be **best-effort**: a failure to
create the album or to add a given photo SHALL be logged and SHALL NOT fail, block, or retry the
underlying sync (upload or import).

#### Scenario: Album-on mirrors both directions
- **WHEN** a membership has `saveToAlbum = true` and direction `Both`, and the device both downloads a foreign photo and completes an upload of its own photo
- **THEN** both photos are present in the event's album

#### Scenario: Album-off creates nothing
- **WHEN** a membership has `saveToAlbum = false`
- **THEN** no album is created and no photo is placed, for either direction

#### Scenario: A placement failure never breaks sync
- **WHEN** adding a photo to the album fails (e.g. the asset was deleted, or the album no longer resolves)
- **THEN** the failure is logged and the upload/import it rode on still succeeds

### Requirement: The app is the sole album creator, created eagerly on the permission grant

The **app process** SHALL be the only creator of the album. It SHALL create the album eagerly when
photo-library permission transitions to `GRANTED` (or immediately at provision if already granted) for a
membership whose `saveToAlbum` is `true` and whose album does not yet exist, reusing the app's existing
grant observation. Neither the app's download path nor the upload extension SHALL ever **create** the
album — they SHALL only **add** to an already-created album. Because syncing requires the same
full-library permission, creating on the grant guarantees the album exists before the first synced photo
is produced, so no two processes race to create it. A membership that never syncs a photo MAY therefore
have an empty album; this is acceptable.

#### Scenario: Album created on the grant transition
- **WHEN** a device is joined with `saveToAlbum = true` and photo permission transitions from not-determined to granted
- **THEN** the app creates the event's album and records its identifier before any upload or download runs

#### Scenario: Album created at provision when already granted
- **WHEN** a join with `saveToAlbum = true` is confirmed while photo permission is already granted
- **THEN** the app creates the album as part of provisioning

#### Scenario: The extension never creates the album
- **WHEN** the upload extension runs a cycle for a `saveToAlbum` membership whose album has not yet been created
- **THEN** the extension adds nothing and does not create an album; creation is left to the app

### Requirement: Album identity is remembered per event and survives leave

The system SHALL persist the created album's PhotoKit `localIdentifier` in a per-event
`eventId → albumLocalId` map held in a **shared store** readable and writable by both the app and the
upload extension. That store SHALL **survive `LeaveEvent.leave()`** — leaving an event SHALL NOT clear
the album map (unlike the event config). On a **re-join** of the same event with `saveToAlbum = true`,
the system SHALL resolve the stored `albumLocalId`: if the album still exists it SHALL be **reused** (so
the prior membership's photos remain gathered); if it no longer resolves (the user deleted it) the
system SHALL create a fresh album and overwrite the map. Within a single membership, a user-deleted album
SHALL NOT be recreated — recreation happens only on a re-join with the box checked. Photos SHALL be added
by resolving the stored `albumLocalId`, never by matching the album title, so a user rename of the album
and two events sharing a name do not misroute placement.

#### Scenario: Re-join reuses the same album
- **WHEN** a device leaves an event whose album exists, then re-joins the same event with `saveToAlbum = true`
- **THEN** the stored `albumLocalId` is reused and newly synced photos are added to the existing album (its earlier photos are retained)

#### Scenario: A deleted album is recreated on re-join
- **WHEN** the user deletes the album, then re-joins the event with `saveToAlbum = true` and the stored id no longer resolves
- **THEN** a fresh album is created and the map is overwritten with its new identifier

#### Scenario: The album map is not cleared on leave
- **WHEN** `LeaveEvent.leave()` clears the event config
- **THEN** the `eventId → albumLocalId` map entry is preserved for a future re-join

### Requirement: Downloaded photos are added atomically at import

For a `saveToAlbum` membership, the iOS importer SHALL add each imported foreign asset to the event
album **in the same `PHPhotoLibrary.performChanges` commit** that creates the asset, so a received photo
is never briefly present outside the album. The importer SHALL source the album identifier from the
shared map via an injected lookup; when no album identifier is available (album not yet created), the
importer SHALL still import the asset and simply not add it (a later placement covers it). The download
orchestration (`DownloadController`) SHALL remain album-agnostic and pure.

#### Scenario: A downloaded photo lands already in the album
- **WHEN** a foreign asset is imported for a `saveToAlbum` membership whose album exists
- **THEN** the asset is created and added to the album in one commit, never appearing outside it

#### Scenario: Import proceeds when the album id is absent
- **WHEN** an import runs before the album identifier is available
- **THEN** the asset is still imported into the library and no album add is attempted for it

### Requirement: Uploaded photos are added at upload-cycle completion in the running process

For a `saveToAlbum` membership, the system SHALL add each successfully-uploaded own photo to the event
album at `UploadCycle` completion, in **whichever process ran the cycle** — the upload extension on iOS
≥26.1 and the app on iOS 18–26.0. Because a completed job carries only its URL-derived key (the resource
is released), the system SHALL recover the asset's raw `localIdentifier` from the normalized `assetId` by
**reversing the `normalizeAssetId` `/`→`_` mapping** and re-fetch the `PHAsset`. The add SHALL be
best-effort: if the fetch returns no asset (deleted, or a non-standard identifier), the photo SHALL be
skipped without error. The extension is permitted to add to a `PHAssetCollection` (verified on device);
if it ever cannot, uploaded-photo placement remains the only affected path and MUST degrade to a
best-effort skip, never a cycle failure.

#### Scenario: A completed upload is added to the album
- **WHEN** an upload of an own photo completes for a `saveToAlbum` membership whose album exists
- **THEN** the asset's raw localIdentifier is recovered by reversing `_`→`/`, the `PHAsset` is fetched, and it is added to the album

#### Scenario: A missing asset is skipped
- **WHEN** the recovered localIdentifier fetches no `PHAsset` (the photo was deleted)
- **THEN** the album add is skipped and the upload cycle still reports success

### Requirement: Album orchestration is a tested commonMain coordinator over platform seams

The decision logic — resolve-or-create the album, reuse-on-rejoin, dispatch-or-skip an add — SHALL live
in a pure `commonMain` coordinator, depending on two seams: an `AlbumManager` (the iOS `PHAssetCollection`
create / exists / add operations, `iosMain`, wiring-only and untested) and an `AlbumMapStore` (the shared
leave-surviving `eventId → albumLocalId` map). The coordinator and seams SHALL be fakeable so that
`:test:world` integration tests can assert which asset identifiers were placed into which album, and the
reuse-on-rejoin behavior, without invoking PhotoKit. No album decision logic SHALL live in the untested
app or extension shells.

#### Scenario: Placement is asserted without PhotoKit
- **WHEN** an integration test runs the real download and upload flows over `:test:world` with a fake `AlbumManager`
- **THEN** it can assert the exact set of asset identifiers added to the event album and that a rejoin reused the same album identifier
