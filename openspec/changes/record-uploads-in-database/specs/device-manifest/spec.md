## MODIFIED Requirements

### Requirement: Per-event device manifest document

For each (event, device) pair it backs up, the producer SHALL publish exactly one device manifest **as the
request body** of `PUT /api/v1/events/<eventId>/devices/<deviceId>` (capability `api-endpoints`). The
manifest is a **wire format**, not a stored object: the backend records it relationally (capability
`database`) and writes no manifest object to storage.

The manifest SHALL be a UTF-8 JSON object carrying `deviceId` (the stable per-install device id) and
`assets` (an array). Each `assets` element SHALL carry `assetId` (the device-local asset identity),
`creationDate` (the asset's capture timestamp as an ISO-8601 string), and `resources` (a non-empty array).
Each `resources` element SHALL carry `role`, `contentType` (the resource's MIME type), `key` (the
resource's object name — the byte-store key under its device partition, the fetch handle), and `filename`
(the resource's human filename as captured). The field names `key` and `filename` are shared verbatim with
the event-wide union read, so the union projects them unchanged.

The document shape is **unchanged by the move to a database**. That is deliberate: it is what lets the
backend flip its storage without a client change, and be rolled back against a shipped app.

#### Scenario: One manifest per event and device

- **WHEN** a device backs up assets for event `E`
- **THEN** it publishes exactly one manifest for `(E, deviceId)` via the manifest route, carrying
  `deviceId` and an `assets` array, and no manifest object is written to storage

#### Scenario: Fields present on each entry

- **WHEN** the manifest lists an asset entry
- **THEN** that entry carries `assetId`, `creationDate`, and a non-empty `resources` array
- **AND** each resource entry carries `role`, `contentType`, `key` (the storage object name), and
  `filename` (the human capture name)

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

### Requirement: Sole writer, synchronous in-cycle upload

The upload extension SHALL be the **sole** writer of the *projected* device manifest. It SHALL publish the
manifest **synchronously within the upload cycle** — no background `URLSession` and no app involvement.
The extension MAY skip the publish when the projected snapshot is unchanged since the last **successful**
write. A kill mid-publish SHALL be tolerated: the write is atomic at the backend (capability `database`),
so a killed cycle leaves the previous state intact and the projection is recomputed next cycle.

**The word "successful" is load-bearing beyond this capability.** The byte upload route records
`uploaded = 1` best-effort and relies on the next manifest publish to repair a lost record (capability
`api-endpoints`). If an unchanged projection could be skipped after a *failed* publish, a doubly-failed
write would strand `uploaded` at `0` while the device believed it had published — an uploaded photo
invisible to every other member, with no error anywhere. These two requirements SHALL NOT be edited
independently.

Enrollment (capability `join-event`) writes a **register-only empty** manifest, so the skip-if-unchanged
record is a belief about the server that a second writer can falsify. Any successful register-only write
SHALL therefore **invalidate** that record, so the next cycle re-publishes the projection rather than
skipping it. A **failed** register-only write SHALL leave the record intact — the server was not changed,
so the belief is still true.

#### Scenario: Synchronous publish with skip-if-unchanged

- **WHEN** the upload cycle has produced the projected snapshot
- **THEN** the extension publishes the manifest synchronously in-cycle, or skips it when the snapshot is
  unchanged since the last successful write

#### Scenario: A failed publish is retried rather than skipped

- **WHEN** a manifest publish fails and the next cycle's projection is unchanged
- **THEN** the next cycle publishes again rather than skipping, because the last write was not successful

#### Scenario: Re-joining an event does not empty this device's manifest

- **WHEN** the device re-enrolls in an event it has already contributed to — after a leave, a durable
  state reset, or a reinstall — and the projected snapshot is unchanged from before
- **THEN** the enrollment's empty manifest is overwritten by the projection on the next cycle, so the
  event union still lists this device's uploaded photos

#### Scenario: A failed enrollment does not force a redundant publish

- **WHEN** a register-only enrollment write is not confirmed by the backend
- **THEN** the skip-if-unchanged record is unchanged, and an unchanged projection still skips its publish

#### Scenario: Kill mid-publish leaves the previous state intact

- **WHEN** the extension is killed during a manifest publish
- **THEN** the backend applies none of that publish, and the manifest is recomputed and re-published on the
  next cycle

## REMOVED Requirements

### Requirement: Departed manifest and last-write-wins membership
**Reason**: Pure artifact of the object store. A membership is one row whose `state` column is `active` or
`departed` (capability `database`), so the `<deviceId>.left.json` sibling, the last-write-wins resolution
over two objects' last-modified times, the exact-tie rule the requirement itself called *"not producible in
practice"*, and the "SHALL NOT be counted twice when both siblings are present" rule all become
unstateable. Three consumers that each re-implemented the resolution — the union, the notify fan-out, and
the leave reap — collapse to reading the column.
**Migration**: `database` → *Membership state is a column with exactly two values*, which preserves the
semantics: a departed membership retains its assets, so a leaver's photos stay in the event union.
