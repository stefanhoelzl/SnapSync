## MODIFIED Requirements

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
