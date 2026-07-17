# event-album — delta for create-flow-zone-and-drain-shell

## MODIFIED Requirements

### Requirement: The app is the sole album creator, created eagerly on the permission grant

The **app process** SHALL be the only creator of the album. It SHALL create the album eagerly when
photo-library permission transitions to `GRANTED` (or immediately at provision if already granted) for a
membership whose `saveToAlbum` is `true` and whose album does not yet exist. The membership's opt-in
gate SHALL be the coordinator's **own leading guard** — `AlbumCoordinator.ensureAlbum(eventId, name,
saveToAlbum)` is a no-op returning `null` for an opted-out or nameless membership — so its callers (the
`compose/`-installed permission-grant subscription and the `flow/Provision` trigger) call it
unconditionally with the membership's facts and no caller can forget the rule. Neither the app's download path nor the upload extension SHALL ever **create** the
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

### Requirement: Album orchestration is a tested commonMain coordinator over platform seams

The decision logic SHALL live in a pure `commonMain` coordinator — the membership opt-in gate,
resolve-or-create the album, reuse-on-rejoin, dispatch-or-skip an add, and the import-time album
lookup (`albumIdFor(eventId, saveToAlbum)`, the opt-in-gated map read the download importer
borrows) — depending on two seams: an `AlbumManager` (the iOS `PHAssetCollection`
create / exists / add operations, `iosMain`, wiring-only and untested) and an `AlbumMapStore` (the shared
leave-surviving `eventId → albumLocalId` map). The coordinator and seams SHALL be fakeable so that
`:test:world` integration tests can assert which asset identifiers were placed into which album, and the
reuse-on-rejoin behavior, without invoking PhotoKit. No album decision logic SHALL live in the untested
app or extension shells.

#### Scenario: Placement is asserted without PhotoKit
- **WHEN** an integration test runs the real download and upload flows over `:test:world` with a fake `AlbumManager`
- **THEN** it can assert the exact set of asset identifiers added to the event album and that a rejoin reused the same album identifier
