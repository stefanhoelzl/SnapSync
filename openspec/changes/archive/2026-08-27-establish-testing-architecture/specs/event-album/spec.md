## MODIFIED Requirements

### Requirement: Album orchestration is a tested commonMain coordinator over platform seams

The decision logic SHALL live in a pure `commonMain` coordinator — the membership opt-in gate,
resolve-or-create the album, reuse-on-rejoin, dispatch-or-skip an add, and the import-time album
lookup (`albumIdFor(eventId, saveToAlbum)`, the opt-in-gated map read the download importer
borrows) — depending on two seams: an `AlbumManager` (the iOS `PHAssetCollection`
create / exists / add operations, an adapter in `:adapter:ios:ext-safe`, placed by linkage and
covered by that module's own `iosTest` suite) and an `AlbumMapStore` (the shared
leave-surviving `eventId → albumLocalId` map). The coordinator and seams SHALL be fakeable so that
`:test:world` integration tests can assert which asset identifiers were placed into which album, and the
reuse-on-rejoin behavior, without invoking PhotoKit. No album decision logic SHALL live in the untested
app or extension shells.

#### Scenario: Placement is asserted without PhotoKit
- **WHEN** an integration test runs the real download and upload flows over `:test:world` with a fake `AlbumManager`
- **THEN** it can assert the exact set of asset identifiers added to the event album and that a rejoin reused the same album identifier
