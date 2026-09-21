# event-album Specification

## Purpose

A per-membership **album** in the system Photos library, named after the event, into which both the
photos this device contributes and the photos it receives are mirrored. The join surface offers it
**on by default** and declinable in one tap: the album is the only on-device statement that a set of
photos belongs to this event, so a member who decides nothing still gets the grouping.

Without it an event's photos scatter into the camera roll: your own contributions blend into your timeline
and the photos you receive land loose beside them, with nothing on the device saying *"this is event X"*. The
album is the local mirror of the shared event — and it is the reason the app still needs no gallery of its
own (`photo-download`): the grouping lives where the user already looks at photos.

The **app is the sole album creator**, eagerly on the photo-permission grant, because album creation needs a
grant the extension may not have. Album identity is remembered per event and **survives leave**, so a rejoin
reuses the same album rather than spawning a duplicate. A member's own photos are added when their upload is
first enqueued, in whichever process runs the cycle — placement waits for no upload outcome; downloaded photos
are added atomically inside the importer's existing commit, so a photo is never visible in the library but
missing from the album. What the device **already holds** is gathered by the app on each opt-in act: photos
shared or received before the member opted in, and photos contributed during an earlier event that this
event's window admits. So the album mirrors the event as the device holds it, not only what arrived after
the toggle.

Decision record: `changes/archive/2026-07-08-add-event-album`. Own-photo placement moved from upload
completion to first enqueue in `changes/archive/2026-09-15-retire-uploaded-state`. The forward-only toggle
was replaced by the gather in `changes/archive/2026-09-21-album-gathers-retroactively`.
## Requirements
### Requirement: Opt-in album mirroring per membership
The system SHALL mirror an event's synced photos into a single PhotoKit album on the device — titled
after the event's (non-null) `name` — when that membership's persisted `EventConfig.saveToAlbum` is
`true` (capability `event-link`). The album SHALL mirror **the event as this device holds it**: every photo
of this event that this device holds, however it arrived — the **foreign photos it has imported** from this
event and the **own photos it contributes** to this event (capability `join-event` direction). It is not
bounded to what the membership moved after opting in: photos the device already held when the album was
turned on belong to the event exactly as much as the ones synced afterwards. When `saveToAlbum` is `false` the
system SHALL create no album and place no photos. `false` is no longer what a join defaults to — the
join surface seeds the choice **on** (see *The album opt-in is a direction-independent join-surface
affordance*) — but it remains the value a config persisted before the field existed reads as
(capability `event-link`), and the value a member who declines commits. The choice SHALL be a **runtime toggle**,
changeable in place after join via `reconfigure-membership`: turning it **on** SHALL ensure the album,
place the foreign photos imported and the own photos first enqueued from then on, **and gather** the photos
the device already holds for the event (see *Ensuring the album gathers what the device already holds*);
turning it **off** SHALL stop further placement but SHALL NOT delete the album or clear its
identity map (see *Album identity is remembered per event and survives leave*), so a later on reuses the
same album. Album placement SHALL be **best-effort**: a failure to create the album or to add a given
photo SHALL be logged and SHALL NOT fail, block, or retry the underlying sync (upload or import).

#### Scenario: Album-on mirrors both directions
- **WHEN** a membership has `saveToAlbum = true` and direction `Both`, and the device both downloads a foreign photo and enqueues the upload of its own photo
- **THEN** both photos are present in the event's album

#### Scenario: Album-off creates nothing
- **WHEN** a membership has `saveToAlbum = false`
- **THEN** no album is created and no photo is placed, for either direction

#### Scenario: Turning the album on gathers what was already shared and received
- **WHEN** a membership with `saveToAlbum = false`, already-uploaded own photos and already-imported foreign photos is reconfigured to `saveToAlbum = true`
- **THEN** the album is ensured, the already-uploaded own photos and the already-imported foreign photos are added to it, and own photos first enqueued from then on are added as well

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
importer SHALL still import the asset and simply not add it; the next gather places it (see *Ensuring the
album gathers what the device already holds*). The download
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

The same holds for a row an earlier upload **failed**: a failure returns its row to `DISCOVERED` (capability
`sync-ledger`), so when the enqueue stage re-creates its job the placement covers it again, and the repeat is a
no-op. The ledger records no first-attempt/retry distinction for this rule to lean on, and none is needed —
placement is idempotent, as the gather below already relies on. A failure the cycle re-creates from the
platform's returned jobs does not pass through the enqueue stage and is not placed there.

The placement SHALL NOT be attempted for a row the current policy excludes, for a row whose
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

#### Scenario: A failure re-enqueued from the ledger is placed again, harmlessly
- **WHEN** a photo's upload failed, returning its row to `DISCOVERED`, and a later cycle's enqueue stage
  re-creates its job
- **THEN** the asset is included in that cycle's placement, the add is a no-op because the album already holds
  it, and the album holds the photo once

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

### Requirement: Ensuring the album gathers what the device already holds

The **app process** SHALL **gather** into the event album the photos of the event that this device already
holds. Placement at first enqueue and at import covers what is synced **after** an album exists. The gather
covers what was already there: photos shared or received before the member turned the album on, and photos
this device contributed during an earlier event that this event's window also admits. Those photos are
listed in this event's device manifest, but they are never enqueued again, so no enqueue ever places them.

**What it places.** A gather SHALL place two sets, and only these:

- **own photos**: the assets of the device's **manifest projection** for the membership. These are the
  ledger's rows, admitted by the membership's **current** selection policy (capabilities
  `device-manifest`, `photo-selection-policy`), with each normalized `assetId` reversed to its raw
  `localIdentifier`. A photo the current policy excludes SHALL NOT be gathered;
- **foreign photos**: the assets in the **event union** (capability `photo-download`) owned by another
  device whose download-store row is `IMPORTED`, by their created local identifiers (capability
  `download-store`, *Imported local identifiers are readable by source ref*). A photo this device imported
  for a **different** event, and that is not in this event's union, SHALL NOT be gathered.

**When it runs.** A gather SHALL start on each **opt-in act**:

- a **provision** of the membership: a join, a re-join, or a switch (capability `join-event`);
- a **`reconfigure-membership` Save** (capability `reconfigure-membership`);
- photo access becoming **usable** (`grantsPhotoAccess`) while the app runs, after the process first observed
  it as not usable.

It SHALL NOT run on a process start that first observes access as already usable. It SHALL NOT run in the
upload extension, whose process has a hard runtime ceiling that a library write must not spend. A gather
SHALL place nothing when the membership has opted out, when access is not usable, when the current config
no longer names the event it was started for, or when no album exists. A gather SHALL never create the
album: it runs after the album has been ensured.

**How it runs.**

- The command that triggered a gather SHALL NOT wait for it. It is started detached on the app's lifetime
  scope, so a join or a Save returns when the membership is committed.
- A gather SHALL add in **batches** of at most a fixed size, each its own library change. A batch that fails
  SHALL NOT stop the remaining batches.
- At most **one** gather SHALL run at a time in the process. A gather started while another is running SHALL
  run after it, against the config current when it runs.
- A gather SHALL keep **no record** of what it placed. It relies on adding an asset already in the collection
  being a no-op (measured, simulator, iOS 26.5), so a repeated gather places nothing twice.

**Best-effort.** A gather SHALL NOT fail, block, or retry any sync. A failed union read SHALL skip the foreign
set, and the own set SHALL still be gathered. A missing asset SHALL be skipped. Each failure SHALL be
logged. A failed gather SHALL NOT be retried on its own: the next opt-in act runs another.

#### Scenario: A carried-over photo is gathered at join
- **WHEN** a device joins an event with `saveToAlbum = true`, and its ledger holds a `COMPLETED` row from an earlier event whose photo the new membership's policy admits
- **THEN** that photo is added to the new event's album, although no upload job is created for it

#### Scenario: A photo received in another event is not gathered
- **WHEN** the download store holds an `IMPORTED` row from an earlier event whose asset is not in this event's union
- **THEN** a gather for this event does not add that photo

#### Scenario: A photo the current policy excludes is not gathered
- **WHEN** a gather runs after the member raised the cutoff past an own photo's capture date
- **THEN** that photo is not added to the album

#### Scenario: A failed union read still gathers own photos
- **WHEN** a gather runs and the union read fails
- **THEN** the own photos are still added, no foreign photo is added, and the failure is logged

#### Scenario: A grant while running gathers
- **WHEN** photo access goes from not determined to granted while the app is running, for a membership with `saveToAlbum = true`
- **THEN** the album is ensured and a gather runs

#### Scenario: A cold launch already granted does not gather
- **WHEN** the app starts with photo access already granted
- **THEN** no gather runs because of that start

#### Scenario: The trigger does not wait for the gather
- **WHEN** a join or a reconfigure Save starts a gather
- **THEN** the command returns without waiting for the gather to finish

#### Scenario: A large set is added in batches
- **WHEN** a gather has more assets to place than the batch size
- **THEN** it adds them in several changes, none larger than the batch size, and a failed change does not stop the rest

#### Scenario: A repeated gather adds nothing twice
- **WHEN** two gathers run in sequence over an unchanged event
- **THEN** the second adds no asset that the first had not already placed, and the album holds each photo once

#### Scenario: An opted-out membership gathers nothing
- **WHEN** an opt-in act runs for a membership with `saveToAlbum = false`
- **THEN** no album is created and no photo is added

#### Scenario: The extension never gathers
- **WHEN** the upload extension runs a cycle
- **THEN** it runs no gather, and only the enqueue-time placement adds photos to the album

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
both switches — it is a preference, not a consent decision — and SHALL **default on**: the album is the
only on-device statement that a set of photos belongs to this event, so a member who decides nothing
SHALL get the grouping the capability exists to provide. The default SHALL remain **declinable in one
tap** on the surface itself, and changeable afterwards through `reconfigure-membership` — the default decides what an untouched gate commits, never what a
membership is stuck with.

The **headless `autoJoin` path deliberately does NOT share this default**: it commits
`saveToAlbum = false` unless the event link carries an explicit override (capability `join-event`,
*The auto-confirmed join*). Its sibling defaults — the loaded `startsAt` as the cutoff, and direction
`Both` — do mirror this surface's seeds, so the divergence is stated here rather than inferred: a
headless dev/test launch does the minimal, side-effect-free thing, and the link's explicit
`saveToAlbum` override already exercises album placement without a tap.

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

The off-state note SHALL state that no album is created; because the affordance starts on, that note
SHALL be reachable only after a deliberate uncheck.

The chosen value SHALL cross to `JoinEvent` as `saveToAlbum` for **all** direction combinations (the
album is populated by whichever direction(s) sync).

#### Scenario: The album opt-in is standalone, not nested under a switch
- **WHEN** the join surface renders its loaded phase
- **THEN** the "Create an album" opt-in appears as its own row beneath both switches, nested under neither, and defaults on

#### Scenario: An untouched gate commits the album
- **WHEN** the user taps Join without touching the album row
- **THEN** `saveToAlbum = true` crosses to `JoinEvent`

#### Scenario: The default is declined in one tap
- **WHEN** the user unchecks the album row and taps Join
- **THEN** `saveToAlbum = false` crosses to `JoinEvent`, and the row states that no album is created

#### Scenario: The headless path does not inherit the surface default
- **WHEN** an `autoJoin` event link carrying no `saveToAlbum` override is auto-confirmed
- **THEN** the membership is committed with `saveToAlbum = false`, although the interactive surface would have defaulted it on

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

#### Scenario: An existing membership keeps its stored choice
- **WHEN** a membership persisted with `saveToAlbum = false` before this default changed is loaded
- **THEN** it stays `saveToAlbum = false` — no migration flips it — and the reconfigure surface seeds from the stored value

