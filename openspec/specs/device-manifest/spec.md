# device-manifest Specification

## Purpose

The per-event device manifest: one mutable JSON object per (event, device) at
`/events/<eventId>/devices/<deviceId>.json` that projects all of a device's uploaded, not-deleted
resources — original-only, each typed by a generic `role` — into a single full-state snapshot. It
supersedes the per-asset manifest: the upload extension is its sole writer, PUTting it synchronously
in-cycle as a per-event projection of the upload ledger's `COMPLETED` rows (capability `sync-ledger`),
admitted by the membership's one selection policy. Write-only in v1 (no in-app consumer reads it), it
exists as forward-preparation for restore and event-wide union.
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

Because the manifest lists only `COMPLETED` resources, the event union's byte-presence check (capability
`bunny-list-endpoint`) is not the mechanism that hides not-yet-uploaded assets; it is defense-in-depth
against a `COMPLETED`-but-absent byte.

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

The upload extension SHALL be the **sole** writer of the *projected* device manifest. It SHALL PUT the
manifest **synchronously within the upload cycle** — no background `URLSession` and no app involvement.
The extension MAY skip the PUT when the projected snapshot is unchanged since the last successful write.
A kill mid-PUT SHALL be tolerated: the partial write is lost and recomputed on the next cycle (benign,
because the manifest is write-only in v1 and converges).

Enrollment (capability `join-event`) writes a **register-only empty** manifest to the same resource, so
the skip-if-unchanged record is a belief about the server that a second writer can falsify. Any
successful register-only write SHALL therefore **invalidate** that record, so the next cycle re-PUTs the
projection rather than skipping it. A **failed** register-only write SHALL leave the record intact — the
server was not changed, so the belief is still true.

#### Scenario: Synchronous PUT with skip-if-unchanged

- **WHEN** the upload cycle has produced the projected snapshot
- **THEN** the extension PUTs the manifest synchronously in-cycle, or skips the PUT when the snapshot is
  unchanged since the last successful write

#### Scenario: Re-joining an event does not empty this device's manifest

- **WHEN** the device re-enrolls in an event it has already contributed to — after a leave, a durable
  state reset, or a reinstall — and the projected snapshot is unchanged from before
- **THEN** the enrollment's empty manifest is overwritten by the projection on the next cycle, so the
  event union still lists this device's uploaded photos

#### Scenario: A failed enrollment does not force a redundant PUT

- **WHEN** a register-only enrollment write is not confirmed by the edge
- **THEN** the skip-if-unchanged record is unchanged, and an unchanged projection still skips its PUT

#### Scenario: Kill mid-PUT is caught next cycle

- **WHEN** the extension is killed during a manifest PUT
- **THEN** the partial write is discarded and the manifest is recomputed and rewritten on the next cycle

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

### Requirement: Departed manifest and last-write-wins membership

A device's per-event membership SHALL be represented by two possible sibling objects under
`events/<eventId>/devices/`: the **active** manifest `<deviceId>.json` and the **departed** manifest
`<deviceId>.left.json`. Leaving renames active → departed (see `event-leave-endpoint`); rejoining writes
a fresh active `<deviceId>.json`. Both carry the same manifest document shape; the departed sibling is a
snapshot of the device's contributions at leave time, retained so the event union can still serve them.
Membership state SHALL be resolved **last-write-wins** by the two siblings' last-modified times:

- `active(device)` = `<deviceId>.json` present AND (`<deviceId>.left.json` absent OR
  `<deviceId>.json` is newer than `<deviceId>.left.json`).
- `departed(device)` = `<deviceId>.left.json` present AND NOT `active(device)`.

An exact-tie of last-modified times (not producible in practice — the two writes are on different keys
separated by a human gesture) SHALL resolve to `active`. Consumers that enumerate membership
(`bunny-list-endpoint` union, `event-notify-endpoint` fan-out, the `event-leave-endpoint` reap) SHALL
apply this rule over the last-modified time already present in the directory `LIST`, requiring no
per-object follow-up read. A device SHALL NOT be counted twice when both siblings are present.

#### Scenario: Newer departed sibling wins after a leave

- **WHEN** both `<deviceId>.json` and `<deviceId>.left.json` exist and the `.left.json` is newer
- **THEN** the device resolves to `departed` (its photos stay in the union; it is not an active member)

#### Scenario: Newer active sibling wins after a rejoin

- **WHEN** both siblings exist and the `<deviceId>.json` is newer (a rejoin superseding a prior leave)
- **THEN** the device resolves to `active` (it is an active member and is notified)

#### Scenario: Membership resolved from the directory listing alone

- **WHEN** a consumer enumerates `events/<eventId>/devices/`
- **THEN** it resolves each device's active/departed state from the two siblings' last-modified times in that one listing, with no extra per-object read

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
