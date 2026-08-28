# device-manifest Specification

## Purpose

The per-event device manifest: one mutable JSON document per (event, device), published to
`PUT /api/v2/events/<eventId>/devices/<deviceId>/manifest`, that projects all of a device's uploaded,
not-deleted resources — original-only, each typed by a generic `role` — into a single full-state
snapshot. The publish is **contribution only**: it replaces the membership's asset set and enrols nobody,
which is what the manifest sub-resource makes structural rather than conventional (capability
`join-event`). The upload extension is its sole writer, publishing synchronously in-cycle as a per-event
projection of the upload ledger's `COMPLETED` rows (capability `sync-ledger`), admitted by the
membership's one selection policy. No in-app consumer reads it; it is read by the event-wide union, and
the publish is also what wakes the other members (capability `upload-completion-notify`).
## Requirements

### Requirement: Per-event device manifest document

For each (event, device) pair it backs up, the producer SHALL publish exactly one device manifest **as the
request body** of `PUT /api/v2/events/<eventId>/devices/<deviceId>/manifest` (capability `api-endpoints`).
The manifest is a **wire format**, not a stored object: the backend records it relationally (capability
`database`) and writes no manifest object to storage.

The publish is **contribution only**. It replaces the membership's asset set and does nothing else — it
enrolls nobody (the join request owns that, capability `join-event`) and records no upload (the byte route
owns that). A publish from a device that holds no membership SHALL be refused rather than silently creating
one.

The manifest SHALL be a UTF-8 JSON object carrying `deviceId` (the stable per-install device id) and
`assets` (an array). Each `assets` element SHALL carry `assetId` (the device-local asset identity),
`creationDate` (the asset's capture timestamp as an ISO-8601 string), and `resources` (a non-empty array).
Each `resources` element SHALL carry `role`, `contentType` (the resource's MIME type), `key` (the
resource's object name — the byte-store key under its device partition, the fetch handle), and `filename`
(the resource's human filename as captured). The field names `key` and `filename` are shared verbatim with
the event-wide union read, so the union projects them unchanged.

The document shape is **unchanged by the move to a database**. That is deliberate: it is what lets the
backend flip its storage without a client change, and be rolled back against a shipped app.

It is **unchanged by the move to v2** as well, and for a second reason worth stating separately: it is what
makes this a transport move rather than a format change, and what lets the manifest later declare intent
rather than completion without any wire change at all. Two storage moves and a version move have now left
this document alone; that is the property, not a coincidence.

#### Scenario: One manifest per event and device

- **WHEN** a device backs up assets for event `E`
- **THEN** it publishes exactly one manifest for `(E, deviceId)` via the manifest sub-resource, carrying
  `deviceId` and an `assets` array, and no manifest object is written to storage

#### Scenario: The publish enrolls nobody

- **WHEN** a device that holds no membership for `E` publishes a manifest for it
- **THEN** the publish is refused, and no membership is created as a side effect

#### Scenario: Fields present on each entry

- **WHEN** the manifest lists an asset entry
- **THEN** that entry carries `assetId`, `creationDate`, and a non-empty `resources` array
- **AND** each resource entry carries `role`, `contentType`, `key` (the storage object name), and
  `filename` (the human capture name)

### Requirement: Generic resource roles

Resources SHALL be typed by a generic, platform-neutral `role`, never a platform resource-type name.
This change defines two roles: `primary` — the single original primary medium of the asset (a still
image or a video) — and `live` — the original paired video of a Live Photo. An asset SHALL have
exactly one `primary` resource and at most one `live` resource. Whether the primary is an image or a
video SHALL be carried by `contentType`, not by the role. The manifest SHALL list only the asset's
**original** resources and SHALL NOT list edit artifacts. (The role formerly named `live` is renamed
to `live`; this is a clean cutover — the producer rewrites each device.json as a full-state snapshot
next cycle, so older `live` manifests from un-updated builds age out and are not migrated.)

#### Scenario: A plain photo has one primary

- **WHEN** an asset is a single still image
- **THEN** its entry lists exactly one resource with role `primary` and no `live`

#### Scenario: A Live Photo has primary plus live

- **WHEN** an asset is a Live Photo (original still plus original paired video)
- **THEN** its entry lists a `primary` (the still) and a `live` (the paired video), and
  image-versus-video is distinguished by `contentType`

### Requirement: Mutable full-state projection

The device manifest SHALL be projected from the upload **ledger** (capability `sync-ledger`). For a given
event the manifest SHALL list exactly the ledger's **COMPLETED** rows whose asset falls within the current
membership's admitted capture-date range (capability `photo-selection-policy`) — a full-state document
listing only genuinely-uploaded resources. The ledger SHALL be the **only** durable record the projection
reads: no second structure holding the same asset set exists, so none can disagree with it, and
deletion-awareness comes from the ledger's **absence mark** (a departed asset's rows are marked, not
dropped).

The projection SHALL additionally exclude rows marked **absent** — assets that have left the device's
library (capability `sync-ledger`). Absence is a fact the row carries, so the projection can apply it; the
row itself is retained, because its bytes are still on the backend.

Applying the **current** policy at projection time is the **intended** mechanism by which a membership's
scope change reaches the other members, not an incidental filter. The manifest answers *what does this
member share now?*; the ledger answers *which bytes has this member uploaded?*. These are different
questions with different lifetimes, and only the first depends on the policy. Consequently a narrowing of
scope SHALL shrink the projection and a widening SHALL restore it, in both cases **without** any change to
the ledger and therefore without re-uploading a byte (capability `reconfigure-membership`).

An **empty** projection SHALL be a valid manifest and SHALL be published. A membership that currently shares
nothing — because its direction excludes upload, or because its range admits none of its uploaded assets —
publishes an empty document rather than leaving a stale one in place.

Because the manifest lists only `COMPLETED` resources, the event union's completeness check (capability
`api-endpoints`) is not the mechanism that hides not-yet-uploaded assets; it is defense-in-depth against a
`COMPLETED`-but-unrecorded resource. Under the relational store that check reads the resource row's
`uploaded` flag rather than a storage listing, so the two witnesses are now two writes rather than two
systems; the guarantee is unchanged, because the sweep still protects a referenced byte from collection
(capability `scheduled-cleanup`).

#### Scenario: The manifest lists completed rows in the event window

- **WHEN** the manifest is produced for an event
- **THEN** it lists exactly the device's COMPLETED ledger resources whose asset is within the membership's
  admitted range — no discovered-but-unuploaded asset, and nothing outside the range

#### Scenario: A deleted asset drops from the manifest

- **WHEN** an asset is deleted locally and its ledger rows are marked absent
- **THEN** it no longer appears in the projected manifest, and its rows are still readable so re-upload
  stays suppressed if the asset is restored

#### Scenario: Narrowing the scope shrinks the projection without touching the ledger

- **WHEN** the membership's admitted range narrows so that a previously-listed COMPLETED asset falls outside
  it, and the manifest is produced again
- **THEN** the manifest no longer lists that asset, and its ledger row is unchanged

#### Scenario: Widening the scope restores the projection without re-uploading

- **WHEN** the membership's admitted range widens again to include that asset, and the manifest is produced
- **THEN** the manifest lists it once more and no byte is re-uploaded, because the ledger row was retained

#### Scenario: An empty projection is published

- **WHEN** the membership currently admits none of its uploaded assets
- **THEN** an empty manifest is published for that event, rather than the previous manifest being left in
  place

### Requirement: Device-global ledger with per-event projection

The manifest's entries SHALL derive from the device-global upload ledger — the durable, event-independently
keyed record of every resource this device has uploaded and not deleted, each row carrying the manifest's
presentation detail (capability `sync-ledger`). Each event's manifest SHALL be the **admitted** projection of
that ledger's `COMPLETED` rows: the assets the membership's selection policy admits by **capture date**
(capability `photo-selection-policy`) — at or after the device's configured start for that event (its
per-membership capture-date cutoff) and at or before the event's capture-date ceiling. A membership's cutoff is
**required, never absent** (no scope admits the whole library), so every projection SHALL be date-bounded —
there is no whole-library projection. The projection SHALL apply that one policy rather than a date comparison
of its own, so a bound added to the policy reaches the manifest by construction. The ledger SHALL remain
device-global under a cutoff — it holds every uploaded resource, including ones the current projection
excludes by **date** — so that a differing cutoff (a future edit, or a concurrent membership in another
event) can be projected without re-walking the library.

The ledger is a durable record rather than a cache of the library: a row exists because bytes landed, and the
re-join reconciliation re-seeds it from the authoritative per-device file listing (capability
`event-rejoin-reconciliation`). Such a seeded row is **bare** — a filename listing carries no capture date —
and a bare row SHALL NOT be listed in any projection until a full enumeration backfills its manifest detail.
This is fail-closed on purpose: a row whose capture date is unknown cannot be shown to fall inside an event's
range.

The two kinds of exclusion land on **opposite sides** of the ledger, and this asymmetry is deliberate. The
**capture-date bounds are per-membership**, so they SHALL be applied in the per-event *projection* — the
ledger must retain an out-of-range row because another event's range may admit it. The **origin exclusions
are event-independent** — a screenshot is a screenshot in every event, and no membership will ever admit
one — so they SHALL be applied **before** the upload, by the cycle's resource selection, and an
origin-excluded asset therefore never earns a `COMPLETED` row at all. Excluding by origin up front
therefore costs the projection no per-event flexibility, while excluding by date would.

An origin-excluded asset that reached the manifest would enter the event union and be offered to every other
member as bytes that were **never uploaded** — because the upload cycle drops it before the engine. Projecting
only from `COMPLETED` rows is what forecloses that.

#### Scenario: Date-bounded projection per the device's configured cutoff
- **WHEN** the membership has a cutoff and a `COMPLETED` ledger row's capture date precedes it
- **THEN** that asset is excluded from that event's manifest while its row remains in the device-global ledger

#### Scenario: An origin-excluded asset never reaches the ledger
- **WHEN** discovery surfaces a screenshot captured after the membership's cutoff
- **THEN** the cycle drops it before the engine, so it earns no `COMPLETED` row, appears in **no** event's
  manifest, and never enters the event union

#### Scenario: A bare reconciled row is not listed until it is backfilled
- **WHEN** the re-join reconciliation seeds a `COMPLETED` row from the per-device file listing, so the row
  carries no capture date
- **THEN** the projection omits that resource until a full enumeration backfills its capture date, after which
  the next projection lists it if the membership admits it

#### Scenario: The manifest never lists an asset whose bytes were not uploaded
- **WHEN** the selection policy excludes an asset from byte upload
- **THEN** that asset appears in no device manifest, so no other member can attempt to download bytes that
  were never uploaded

### Requirement: Sole writer, synchronous in-cycle upload

The upload extension SHALL be the **sole** writer of the *projected* device manifest. It SHALL publish the
manifest **synchronously within the upload cycle** — no background `URLSession` and no app involvement.
The extension MAY skip the publish when the projected snapshot is unchanged since the last **successful**
write. A kill mid-publish SHALL be tolerated: the write is atomic at the backend (capability `database`),
so a killed cycle leaves the previous state intact and the projection is recomputed next cycle.

**The word "successful" remains load-bearing**, for a reason that survives the move to v2 even though its
original justification does not. Under v1 it protected the byte route's best-effort upload record, which
the next manifest publish repaired. v2's byte route records the resource itself and is **not** best-effort,
and the manifest writes no resource row at all — so there is nothing left to repair. What remains is the
union: skipping a publish after a *failed* one would leave the backend holding an older asset set while the
device believed it had published, so photos already uploaded would stay absent from the event union with no
error anywhere. The rule is unchanged; only its reason is.

The manifest producer is now the **only** writer of this document. Enrollment no longer writes a
register-only empty manifest (capability `join-event`), so the skip-if-unchanged record has no second
writer that can falsify it, and joining SHALL NOT invalidate it. A rejoin therefore leaves the membership's
existing asset set intact and correctly skips a republish of an unchanged projection — the union continues
to list this device's photos with no blank window between the join and the next cycle.

#### Scenario: Synchronous publish with skip-if-unchanged

- **WHEN** the upload cycle has produced the projected snapshot
- **THEN** the extension publishes the manifest synchronously in-cycle, or skips it when the snapshot is
  unchanged since the last successful write

#### Scenario: A failed publish is retried rather than skipped

- **WHEN** a manifest publish fails and the next cycle's projection is unchanged
- **THEN** the next cycle publishes again rather than skipping, because the last write was not successful

#### Scenario: Re-joining an event never empties this device's manifest

- **WHEN** the device re-enrolls in an event it has already contributed to — after a leave, a durable
  state reset, or a reinstall — and the projected snapshot is unchanged from before
- **THEN** the join writes no manifest, the membership's asset set is untouched, the unchanged projection
  is correctly skipped, and the event union lists this device's uploaded photos throughout

#### Scenario: The producer is the only writer

- **WHEN** any join, re-join, provision or reconfigure occurs
- **THEN** no manifest is written by it, and the skip-if-unchanged record continues to describe the last
  projection this producer successfully published

#### Scenario: Kill mid-publish leaves the previous state intact

- **WHEN** the extension is killed during a manifest publish
- **THEN** the backend applies none of that publish, and the manifest is recomputed and re-published on the
  next cycle

### Requirement: Deletion-aware manifest

When an asset is deleted from the library, its **ledger rows** SHALL be **marked absent** — from the
change feed's precise removal signal — so the next projection stops listing that asset. The rows
themselves SHALL be retained: their bytes are still on the backend, so the record that suppresses
re-upload stays true and a restored asset does not re-upload.

There SHALL be **no** full-enumeration retain-live reconcile. The change feed's removal signal is the only
deletion input. A deletion the feed missed — because the change token expired — leaves the asset listed for
the event's remaining life; its bytes are still present, so a member downloads it successfully and the photo
simply stays in the event, exactly as it does when a member leaves. Deletion-tracking is therefore not
exhaustive, and does not need to be.

This supersedes the prior requirement that pruning be driven "incrementally from the change feed, **and** by
the full enumeration's retain-live reconcile". That reconcile was fed the policy-admitted set, which
conflated "gone from the library" with "outside the current capture window" and discarded upload-suppression
state a scope change has no business touching.

#### Scenario: Deletion marks the rows

- **WHEN** an asset previously listed in the manifest is reported deleted by the change feed
- **THEN** its ledger rows are marked absent and the next manifest projection no longer lists it

#### Scenario: A deleted asset's rows survive

- **WHEN** an asset's rows have been marked absent
- **THEN** those rows are still readable and still `COMPLETED`, so restoring the asset re-uploads nothing

#### Scenario: A missed deletion leaves the asset listed

- **WHEN** an asset is deleted while the change token is expired, so no removal signal is ever received
- **THEN** the asset remains listed and remains downloadable from its still-present bytes — no full
  enumeration retracts it

### Requirement: Write-only in v1

The device manifest SHALL be write-only in v1: no in-app consumer SHALL read it. Status and
completeness SHALL be computed elsewhere (from the gallery enumeration seam and the per-device file
listing), and the manifest SHALL exist solely as forward-preparation for restore and event-wide union.

#### Scenario: No in-app consumer reads the manifest

- **WHEN** the app computes sync status
- **THEN** it reads the gallery enumeration seam and the per-device file listing, and never reads the
  device manifest

### Requirement: The manifest is published only from a ledger believed complete

The cycle SHALL publish a manifest only on a path where it believes the ledger settled for that event, and
SHALL **suppress the write** — leaving the previously published manifest in place — whenever it does not.
Because the projection is a **full-state** document, publishing one built from an incomplete ledger silently
un-lists resources that really are uploaded.

In particular, when the re-join reconciliation (capability `event-rejoin-reconciliation`) defers because the
device's stored-file listing failed or timed out, the ledger has not been seeded and the cycle SHALL NOT
write a manifest that cycle. The same SHALL hold for any failure to read the ledger rows the projection is
built from.

Suppressing the write SHALL be distinguishable in the diagnostic log from publishing an empty manifest: the
first means "this device could not determine what it shares"; the second means "this device shares
nothing". They differ in consequence, and collapsing them would make an outage indistinguishable from a
deliberate withdrawal.

#### Scenario: A deferred reconcile suppresses the manifest write

- **WHEN** the re-join reconciliation defers because the device file listing failed or timed out
- **THEN** no manifest is written that cycle and the previously published manifest is left in place

#### Scenario: A ledger read failure suppresses the manifest write

- **WHEN** the projection cannot read the ledger's completed rows
- **THEN** no manifest is written that cycle and the previously published manifest is left in place

#### Scenario: Suppression and emptiness are distinguishable

- **WHEN** a cycle suppresses the manifest write, and another cycle publishes an empty manifest
- **THEN** the two are recorded distinctly in the diagnostic log, so "could not tell" is never read as
  "shares nothing"

### Requirement: The manifest is published on every cycle that settled its ledger

Publishing the device manifest SHALL depend only on whether this cycle believes the ledger settled for
the event (see "The manifest is published only from a ledger believed complete"). It SHALL NOT depend
on whether the cycle went on to create an upload job for every resource it discovered.

A cycle that stops creating jobs early — because the platform's job limit was reached — SHALL still
write its manifest. Its already-completed rows are in the ledger, the projection reads the ledger, and
withholding the write publishes nothing new about a device that has in fact uploaded more since the
last write. The consequence of withholding it is that a member's uploaded photos do not enter the
event union, so no other member can download them; and because a device only stops creating jobs early
when it has a backlog, the withholding lasts precisely as long as the member is contributing most.

#### Scenario: A cap-truncated cycle publishes its manifest

- **WHEN** an upload cycle settled its ledger and then stopped creating jobs because the platform's
  job limit was reached
- **THEN** it writes the device manifest for that event, projected from the ledger's completed rows as
  on any other cycle

#### Scenario: A member's photos reach the union while the member is still uploading

- **WHEN** a device has more outstanding resources than the platform will accept jobs for, and
  completes uploads across several cycles
- **THEN** each cycle whose projection changed publishes it, so other members can download those
  photos without waiting for the device to finish its backlog

### Requirement: Manifest detail is backfilled for every row the walk covered

A cycle's walk SHALL backfill the manifest detail of **every** already-recorded row it covered that is
still bare, not only those it reached before it stopped creating jobs.

A row's capture date exists only in the photo library, and the walk is the only thing that reads it. A
bare row is excluded from every projection fail-closed (see "Device-global ledger with per-event
projection"), so a bare row the discovery cursor has advanced past would stay out of the union with no
error anywhere, for as long as the cursor stands. The backfill is therefore a precondition of advancing
the cursor (capability `ios-photokit-upload`, "In-extension discovery via persistent change token"),
not an opportunistic sweep.

#### Scenario: Bare rows past the truncation point are still backfilled

- **WHEN** a cycle's walk covers rows seeded bare by a re-join reconciliation, and the cycle stops
  creating jobs partway through
- **THEN** every bare row the walk covered is backfilled with its capture date, including those after
  the point where job creation stopped

#### Scenario: A re-joined device's photos return to the union without a full re-enumeration

- **WHEN** a device re-joins an event it has already contributed to, and its first cycles stop creating
  jobs early
- **THEN** its seeded rows learn their capture dates on those cycles, so its manifest lists them and
  the event union offers its photos again
