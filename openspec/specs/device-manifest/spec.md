# device-manifest Specification

## Purpose

The per-event device manifest: one mutable JSON object per (event, device) at
`/events/<eventId>/device/<deviceId>.json` that projects all of a device's not-deleted assets — with
their original-only resources, each typed by a generic `role` — into a single full-state snapshot. It
supersedes the per-asset manifest: the upload extension is its sole writer, PUTting it synchronously
in-cycle as a date-filtered projection of a device-global accumulator. Write-only in v1 (no in-app
consumer reads it), it exists as forward-preparation for restore and event-wide union.
## Requirements
### Requirement: Per-event device manifest document

For each (event, device) pair it backs up, the producer SHALL maintain exactly one device manifest
object at the key `/events/<eventId>/devices/<deviceId>.json` with `Content-Type: application/json`. The
manifest SHALL be a UTF-8 JSON object carrying `deviceId` (the stable per-install device id) and
`assets` (an array). Each `assets` element SHALL carry `assetId` (the device-local asset identity),
`creationDate` (the asset's capture timestamp as an ISO-8601 string), and `resources` (a non-empty
array). Each `resources` element SHALL carry `role`, `contentType` (the resource's MIME type), `key`
(the resource's object name — the byte-store key under its device partition, capability
`bunny-upload-endpoint`, the fetch handle), and `filename` (the resource's human filename as captured). The field names `key` and
`filename` are shared verbatim with the event-wide union read (`bunny-list-endpoint`), so the union is
a straight projection of the manifest.

#### Scenario: One manifest per event and device

- **WHEN** a device backs up assets for event `E`
- **THEN** exactly one object `/events/E/devices/<deviceId>.json` exists for that device, with
  `Content-Type: application/json`, carrying `deviceId` and an `assets` array

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

The device manifest SHALL be a **mutable** full-state snapshot, not an immutable write-once object. It
SHALL be rewritten each cycle as a complete, self-contained snapshot of the projected asset set, so
there is no read-modify-write step and no lost update under last-write-wins. Each write SHALL fully
replace the prior object, and any transient staleness SHALL self-heal on the next cycle.

#### Scenario: Each write is a complete snapshot

- **WHEN** the producer writes the manifest for an event
- **THEN** the written object is a complete self-contained snapshot of the projected assets, computed
  without reading the prior object

#### Scenario: Last-write-wins is harmless

- **WHEN** two manifest writes for the same (event, device) race or repeat
- **THEN** the result is the last write, with no lost update and no corruption, and the snapshot
  converges on the next cycle

### Requirement: Device-global accumulator with per-event projection

The manifest's entries SHALL derive from a device-global accumulator holding every discovered,
not-deleted asset with its manifest detail. Each event's manifest SHALL be the date-filtered
projection of that accumulator — the assets whose capture date is at or after the event's start. Under
the whole-library scope the projection SHALL be the identity over the accumulator. An accumulator entry
SHALL be written on **every** discovery of an asset, including an already-uploaded one, so the
accumulator is a rebuildable cache rather than a source of truth; after an App-Group wipe it SHALL
rebuild gradually as discovery re-encounters each present asset.

#### Scenario: Projection equals the accumulator under whole-library scope

- **WHEN** the event scope is the whole library
- **THEN** the event's manifest lists exactly the accumulator's not-deleted assets, with no date
  exclusion

#### Scenario: Date-filtered projection per event

- **WHEN** an event has a start date and an accumulator asset's capture date precedes it
- **THEN** that asset is excluded from that event's manifest while remaining in the accumulator

#### Scenario: Entry written on every discovery, accumulator rebuilds gradually

- **WHEN** an asset is discovered even though it is already uploaded
- **THEN** its accumulator entry is written
- **AND** after an App-Group wipe the accumulator rebuilds gradually as discovery re-encounters each
  present asset

### Requirement: Sole writer, synchronous in-cycle upload

The upload extension SHALL be the **sole** writer of the device manifest. It SHALL PUT the manifest
**synchronously within the upload cycle** — no background `URLSession` and no app involvement. The
extension MAY skip the PUT when the projected snapshot is unchanged since the last successful write. A
kill mid-PUT SHALL be tolerated: the partial write is lost and recomputed on the next cycle (benign,
because the manifest is write-only in v1 and converges).

#### Scenario: Synchronous PUT with skip-if-unchanged

- **WHEN** the upload cycle has produced the projected snapshot
- **THEN** the extension PUTs the manifest synchronously in-cycle, or skips the PUT when the snapshot is
  unchanged since the last successful write

#### Scenario: Kill mid-PUT is caught next cycle

- **WHEN** the extension is killed during a manifest PUT
- **THEN** the partial write is discarded and the manifest is recomputed and rewritten on the next cycle

### Requirement: Deletion-aware manifest

When an asset is deleted from the library, its accumulator entry SHALL be pruned, so the manifest stops
listing that asset on the next projection. This pruning is the basis for future deletion-correct
restore.

#### Scenario: Deletion prunes the entry

- **WHEN** an asset previously listed in the manifest is deleted from the library
- **THEN** its accumulator entry is pruned and the next manifest projection no longer lists it

### Requirement: Write-only in v1

The device manifest SHALL be write-only in v1: no in-app consumer SHALL read it. Status and
completeness SHALL be computed elsewhere (from the gallery enumeration seam and the per-device file
listing), and the manifest SHALL exist solely as forward-preparation for restore and event-wide union.

#### Scenario: No in-app consumer reads the manifest

- **WHEN** the app computes sync status
- **THEN** it reads the gallery enumeration seam and the per-device file listing, and never reads the
  device manifest

