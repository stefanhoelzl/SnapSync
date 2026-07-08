## MODIFIED Requirements

### Requirement: Full-fidelity per-asset import into the camera roll

When **every** resource of a foreign asset is staged, the client SHALL import the asset with a single
`PHAssetCreationRequest` adding all of its resources, mapping each resource by `role`: `live` →
`.pairedVideo`; `primary` → `.photo`/`.video`/`.audio` selected by its `contentType`. An unrecognized
`contentType` SHALL be logged and skipped, not force-imported. The asset SHALL be imported into the
photo library (camera roll). The import SHALL reuse the existing full-library-access grant and add no new
permission state.

When the membership opted into an event album (`EventConfig.saveToAlbum`, capability `event-album`) and
that album already exists, the importer SHALL, **in the same `PHPhotoLibrary.performChanges` commit** as
the creation, add the newly-created asset to the event album (via the album's
`PHAssetCollectionChangeRequest` and the creation request's placeholder), so a received photo is
atomically already-in-the-album and never briefly loose. The album identifier SHALL be sourced from the
shared `eventId → albumLocalId` map via an injected lookup; when no album identifier is available (album
not yet created, or `saveToAlbum` is false), the importer SHALL import into the camera roll only and add
to no album. The album add SHALL be best-effort — it SHALL never fail or defer the import.

#### Scenario: A Live Photo round-trips

- **WHEN** a foreign Live Photo's `primary` (image) and `live` (paired video) are both staged
- **THEN** one `PHAssetCreationRequest` recreates a working Live Photo in the library

#### Scenario: Import waits for the complete resource set

- **WHEN** only some of an asset's resources are staged
- **THEN** the asset is not imported until every resource is staged

#### Scenario: Imported asset lands in the camera roll

- **WHEN** an asset import succeeds for a membership that did not opt into an album
- **THEN** the asset is present in the photo library and is added to no SnapSync album

#### Scenario: An album-opted import lands atomically in the album

- **WHEN** an asset import succeeds for a `saveToAlbum` membership whose event album exists
- **THEN** the asset is created and added to the event album in a single commit, never appearing outside it

#### Scenario: Import proceeds when the album is not yet created

- **WHEN** an import runs for a `saveToAlbum` membership before the event album has been created
- **THEN** the asset is imported into the camera roll and no album add is attempted for it
