## MODIFIED Requirements

### Requirement: Opt-in album mirroring per membership
The system SHALL mirror an event's synced photos into a single PhotoKit album on the device — titled
after the event's (non-null) `name` — when that membership's persisted `EventConfig.saveToAlbum` is
`true` (capability `event-link`). The set placed into the album SHALL be **every** photo the membership
syncs in its participation direction: the **foreign photos it downloads** and/or the **own photos it
enqueues for upload** (capability `join-event` direction). When `saveToAlbum` is `false` (the default) the
system SHALL create no album and place no photos. The choice SHALL be a **forward-only runtime toggle**,
changeable in place after join via `reconfigure-membership`: turning it **on** SHALL ensure the album and
mirror, from that point onward, the foreign photos imported and the own photos whose upload is first
enqueued — photos already imported or already enqueued SHALL NOT be retroactively gathered; turning it
**off** SHALL stop further placement but SHALL NOT delete the album or clear its
identity map (see *Album identity is remembered per event and survives leave*), so a later on reuses the
same album. Album placement SHALL be **best-effort**: a failure to create the album or to add a given
photo SHALL be logged and SHALL NOT fail, block, or retry the underlying sync (upload or import).

#### Scenario: Album-on mirrors both directions
- **WHEN** a membership has `saveToAlbum = true` and direction `Both`, and the device both downloads a foreign photo and enqueues the upload of its own photo
- **THEN** both photos are present in the event's album

#### Scenario: Album-off creates nothing
- **WHEN** a membership has `saveToAlbum = false`
- **THEN** no album is created and no photo is placed, for either direction

#### Scenario: Turning the album on adds only photos synced thereafter
- **WHEN** a membership with `saveToAlbum = false` and already-uploaded photos is reconfigured to `saveToAlbum = true`
- **THEN** the album is ensured and own photos whose upload is first enqueued from that point onward are added, while the already-uploaded photos are not retroactively gathered

#### Scenario: Turning the album off stops placement without deleting
- **WHEN** a membership with `saveToAlbum = true` is reconfigured to `saveToAlbum = false`
- **THEN** no further photos are placed, and the album and its `eventId → albumLocalId` map entry are left intact

#### Scenario: A placement failure never breaks sync
- **WHEN** adding a photo to the album fails (e.g. the asset was deleted, or the album no longer resolves)
- **THEN** the failure is logged and the upload/import it rode on still proceeds

## ADDED Requirements

### Requirement: Own photos are added when their upload is first enqueued, in the running process

For a `saveToAlbum` membership, the upload cycle SHALL add a member's **own** photo to the event album when
its upload is **first enqueued**, in **whichever process runs the cycle** — the upload extension on iOS ≥26.1
and the app on iOS 18–26.0. Placement depends on no upload outcome: a photo belongs to the event from the
moment this device commits to sharing it, which is also what the device manifest already declares (capability
`device-manifest`).

In each cycle's enqueue stage, after the rows needing a job have been admitted by the membership's **current**
policy, bounded to the slice the platform will accept, and resolved to live resources — and **before** any job
for that slice is created — the cycle SHALL place, in a single best-effort add, the assets of the slice's rows
whose state is still `DISCOVERED` and whose resource resolved. Placing before creating the jobs is required,
not incidental: a row whose job is then created is recorded `REQUESTED` durably, so a process death after that
write and before a later placement would leave a photo no pass ever places; placed first, a creation that
fails, hits the platform's limit, or is interrupted leaves the row `DISCOVERED`, and the next cycle's placement
repeats harmlessly (adding an asset already in the collection is a no-op — measured, simulator, iOS 26.5).

The placement SHALL NOT be repeated for a `FAILED` row being re-created — its first attempt was placed when it
was still `DISCOVERED` — and SHALL NOT be attempted for a row the current policy excludes, for a row whose
resource no longer resolves, or on a cycle that creates no upload work (an unreadable or absent membership, a
deferred re-join reconciliation, or a direction that excludes upload). Rows settled by the ledger migration
that retired `UPLOADED` SHALL NOT be placed (capability `sync-ledger`).

Because a returned ledger row carries only its normalized `assetId`, the system SHALL recover each asset's raw
`localIdentifier` by **reversing the `normalizeAssetId` `/`→`_` mapping** and re-fetch the `PHAsset`. The add
SHALL be best-effort: if the fetch returns no asset (deleted, or a non-standard identifier) or no album exists
yet, the photo SHALL be skipped without error, and job creation SHALL proceed regardless. The extension is
permitted to add to a `PHAssetCollection` (verified on device); if it ever cannot, own-photo placement remains
the only affected path and MUST degrade to a best-effort skip, never a cycle failure.

#### Scenario: A photo is placed before its upload job is created
- **WHEN** a cycle for a `saveToAlbum` membership whose album exists enqueues a `DISCOVERED` row
- **THEN** the asset is added to the album before `createJob` is called for it, and its placement does not
  wait for the upload to complete

#### Scenario: An offline photo reaches the album
- **WHEN** a new own photo is admitted and enqueued while the device has no network
- **THEN** it is added to the event album in that cycle, although its upload has not completed

#### Scenario: A photo the current policy excludes is not placed
- **WHEN** a `DISCOVERED` row's asset is no longer admitted by the membership's policy when the cycle enqueues
- **THEN** that asset is not added to the album and no job is created for it

#### Scenario: A re-created failure is not placed again
- **WHEN** a `FAILED` row is re-created by a cycle
- **THEN** no album add is made for it

#### Scenario: A job limit leaves the slice placed
- **WHEN** job creation reports the platform's limit partway through a placed slice
- **THEN** the rows without a job stay `DISCOVERED`, and the next cycle's placement of them adds nothing new
  and fails nothing

#### Scenario: A missing asset is skipped
- **WHEN** the recovered localIdentifier fetches no `PHAsset` (the photo was deleted)
- **THEN** the album add is skipped and the cycle still creates jobs and reports its outcome

#### Scenario: A declined cycle places nothing
- **WHEN** the membership's direction excludes upload
- **THEN** no own photo is added to the album on that cycle

## REMOVED Requirements

### Requirement: Uploaded photos are added at upload-cycle completion in the running process
**Reason**: Placement keyed off a completed upload needed a state between "bytes stored" and "settled" to
find its work, and that state (`UPLOADED`) is retired (capability `sync-ledger`). Placing a member's own photo
in a local album needs no uploaded bytes.
**Migration**: Own photos are added when their upload is first enqueued — see "Own photos are added when
their upload is first enqueued, in the running process".
