# event-album Specification

## Purpose

An opt-in, per-membership **album** in the system Photos library, named after the event, into which both the
photos this device contributes and the photos it receives are mirrored.

Without it an event's photos scatter into the camera roll: your own contributions blend into your timeline
and the photos you receive land loose beside them, with nothing on the device saying *"this is event X"*. The
album is the local mirror of the shared event — and it is the reason the app still needs no gallery of its
own (`photo-download`): the grouping lives where the user already looks at photos.

The **app is the sole album creator**, eagerly on the photo-permission grant, because album creation needs a
grant the extension may not have. Album identity is remembered per event and **survives leave**, so a rejoin
reuses the same album rather than spawning a duplicate. A member's own photos are added when their upload is
first enqueued, in whichever process runs the cycle — placement waits for no upload outcome; downloaded photos
are added atomically inside the importer's existing commit, so a photo is never visible in the library but
missing from the album.

Decision record: `changes/archive/2026-07-08-add-event-album`. Own-photo placement moved from upload
completion to first enqueue in `changes/archive/2026-09-15-retire-uploaded-state`.
## Requirements
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

### Requirement: The app is the sole album creator, created eagerly on the permission grant

The **app process** SHALL be the only creator of the album. It SHALL create the album eagerly when
photo-library permission transitions to `GRANTED` (or immediately at provision if already granted) for a
membership whose `saveToAlbum` is `true` and whose album does not yet exist. The membership's opt-in
gate SHALL be the coordinator's **own leading guard** — `AlbumCoordinator.ensureAlbum(eventId, name,
saveToAlbum, granted)` is a no-op returning `null` for an ungranted or opted-out
membership (`granted` defaults `true` for callers that run *because* access was granted) — so its
callers (the `compose/`-installed permission-grant subscription and the `flow/Provision` trigger,
which passes the access fact) call it unconditionally with the membership's facts and no caller
can forget the rule. The guard SHALL NOT test the `name`: a membership's name is required and non-null
(capability `event-link`), so a nameless membership is not a representable state and a clause guarding
against one would be an unreachable branch inviting the reader to believe otherwise. Neither the app's
download path nor the upload extension SHALL ever **create** the
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
system SHALL create a fresh album and overwrite the map. A user-deleted album SHALL NOT be recreated by a
passive sync cycle; recreation SHALL happen only on an **explicit opt-in act** — a re-join with the box
checked, or a `reconfigure-membership` change that turns the album on. Photos SHALL be added by resolving
the stored `albumLocalId`, never by matching the album title, so a user rename of the album and two events
sharing a name do not misroute placement.

#### Scenario: Re-join reuses the same album
- **WHEN** a device leaves an event whose album exists, then re-joins the same event with `saveToAlbum = true`
- **THEN** the stored `albumLocalId` is reused and newly synced photos are added to the existing album (its earlier photos are retained)

#### Scenario: A deleted album is recreated on re-join
- **WHEN** the user deletes the album, then re-joins the event with `saveToAlbum = true` and the stored id no longer resolves
- **THEN** a fresh album is created and the map is overwritten with its new identifier

#### Scenario: Turning the album on after deletion recreates it
- **WHEN** the user deletes the album, then turns the album on via `reconfigure-membership` and the stored id no longer resolves
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

### Requirement: The album opt-in is a direction-independent join-surface affordance

The join surface (capability `join-event`) SHALL present the album opt-in as a **standalone** affordance,
nested under **neither** the Share switch nor the Receive switch. The album mirrors **both** the member's
own uploads **and** the foreign photos it downloads (see *Opt-in album mirroring per membership*), so
placing it under either switch would be a false statement about what feeds it. It SHALL rank **below**
both switches — it is a preference, not a consent decision — and SHALL **default off** (opt-in).

The affordance SHALL render as a **checkbox** — not a switch — because the choice commits with Join (a
switch's "applies immediately" contract would be untrue here). Its off state SHALL draw an empty
affordance, and when the affordance is **dimmed** (not currently applicable) it SHALL remain present in
the accessibility tree as a **disabled** checkbox rather than dropping out of it, so assistive technology
still finds a control and reports it unavailable.

The affordance SHALL carry an explanatory **note that adaptively names exactly the feeds the current
switches produce**, so it can never claim a feed the membership does not have:

- both switches on → the photos the member shares **and** the photos they receive are collected;
- Share only → the photos the member shares are collected;
- Receive only → the photos the member receives are collected;
- neither switch on → nothing is shared or received, so nothing is collected.

The chosen value SHALL cross to `JoinEvent` as `saveToAlbum` for **all** direction combinations (the
album is populated by whichever direction(s) sync).

#### Scenario: The album opt-in is standalone, not nested under a switch
- **WHEN** the join surface renders its loaded phase
- **THEN** the "Create an album" opt-in appears as its own row beneath both switches, nested under neither, and defaults off

#### Scenario: The note names exactly the produced feeds
- **WHEN** both switches are on
- **THEN** the note states that photos the member shares **and** receives are collected; **WHEN** only
  Share is on it names only shared photos; **WHEN** only Receive is on it names only received photos;
  **WHEN** neither is on it states that nothing is collected

#### Scenario: The opt-in stays a present-but-disabled checkbox when dimmed
- **WHEN** the album opt-in is rendered in a dimmed (not-applicable) state
- **THEN** it remains in the accessibility tree as a disabled checkbox, reported unavailable rather than absent

#### Scenario: The album choice crosses in every direction
- **WHEN** the user opts into the album and taps Join for any switch combination
- **THEN** `saveToAlbum = true` crosses to `JoinEvent` alongside the derived direction and the cutoff
