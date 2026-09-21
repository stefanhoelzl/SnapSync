## MODIFIED Requirements

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
