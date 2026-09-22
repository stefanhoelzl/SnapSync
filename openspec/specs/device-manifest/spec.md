# device-manifest Specification

## Purpose

The per-event device manifest: one mutable JSON document per (event, device), published to
`PUT /api/v2/events/<eventId>/devices/<deviceId>/manifest`, that projects all of the resources a device
**intends to provide** and has not deleted — original-only, each typed by a generic `role` — into a single
full-state snapshot. The publish is **contribution only**: it replaces the membership's asset set and enrols
nobody, which is what the manifest sub-resource makes structural rather than conventional (capability
`join-event`). The upload cycle is its writer — in whichever process runs it, the app's uploader or the upload
extension (last write wins; `changes/archive/2026-09-22-both-uploaders-active`) — publishing synchronously in-cycle as a per-event
projection of the upload ledger's rows in **every** upload state (capability `sync-ledger`), admitted by the
membership's one selection policy. No in-app consumer reads it; it is read by the event-wide union, whose
completeness check is what distinguishes a declared asset from a fetchable one, and a publish wakes the
other members only when it makes an asset newly fetchable (capability `upload-completion-notify`).
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
event the manifest SHALL list exactly the resources the device **intends to provide** — every ledger row
whose asset falls within the current membership's admitted capture-date range (capability
`photo-selection-policy`), **whatever its upload state**. A full-state document declaring what this member
contributes, not a record of what has already landed. The ledger SHALL be the **only** durable record the
projection reads: no second structure holding the same asset set exists, so none can disagree with it, and
deletion-awareness comes from the ledger itself: a departed asset's rows are **deleted** once an
authoritative walk shows it gone (see "Deletion-aware manifest").

Upload state SHALL NOT be an input to the projection. In particular a resource whose upload **failed**
SHALL still be listed: a failure returns its row to `DISCOVERED` (capability `sync-ledger`) and the engine
retries forever with no attempt budget, so a failure means "still owed" rather than "abandoned", and excluding
it would make the declared role set oscillate as a resource fails and retries — each flip a manifest write and
a member wake.

The projection SHALL apply **no row filter of its own** beyond the policy: the ledger carries no absence mark,
because a departed asset's rows are deleted rather than marked (see "Deletion-aware manifest").

The projection SHALL apply **no capture-date predicate of its own**, including no exclusion of rows whose
capture date is unknown. The membership's policy already excludes an undated row — its lower bound sorts
an empty capture date before any real cutoff, and a contributing policy always carries that bound
(capability `photo-selection-policy`) — so restating the rule here would be a second copy of an admission
decision that this system deliberately keeps in one place.

Applying the **current** policy at projection time is the **intended** mechanism by which a membership's
scope change reaches the other members, not an incidental filter. The manifest answers *what does this
member share now?*; the ledger answers *which of those resources have landed?*. These are different
questions with different lifetimes, and only the first depends on the policy. Consequently a narrowing of
scope SHALL shrink the projection and a widening SHALL restore it, in both cases **without** any change to
the ledger and therefore without re-uploading a byte (capability `reconfigure-membership`).

An **empty** projection SHALL be a valid manifest and SHALL be published. A membership that currently shares
nothing — because its direction excludes upload, or because its range admits none of its assets — publishes
an empty document rather than leaving a stale one in place.

Because the manifest declares resources whose bytes may not have arrived, the event union's completeness
check (capability `api-endpoints`) is the **primary** mechanism that hides a not-yet-complete asset, not
defense-in-depth. The manifest supplies the expectation and the backend's resource rows supply the reality;
their comparison is what distinguishes a downloadable asset from a declared one. The sweep continues to
protect a referenced byte from collection (capability `scheduled-cleanup`).

#### Scenario: The manifest lists intended resources in the event window

- **WHEN** the manifest is produced for an event
- **THEN** it lists exactly the device's ledger resources whose asset is within the membership's admitted
  range, regardless of upload state — and nothing outside the range

#### Scenario: A declared resource whose bytes have not landed is listed and hidden

- **WHEN** an asset's `primary` has uploaded and its `live` has not
- **THEN** the manifest declares both roles, and the event union excludes that asset until the second
  resource's bytes are recorded — rather than serving it as a complete one-resource asset

#### Scenario: A failed resource stays declared

- **WHEN** a resource's upload fails and the engine returns its row to `DISCOVERED` alongside a retry
- **THEN** the manifest still declares that role, so the declared role set does not change and no member
  is woken by the failure

#### Scenario: A deleted asset drops from the manifest

- **WHEN** an asset is deleted locally and the next authoritative walk deletes its ledger rows
- **THEN** it no longer appears in the projected manifest

#### Scenario: Narrowing the scope shrinks the projection without touching the ledger

- **WHEN** the membership's admitted range narrows so that a previously-listed asset falls outside it, and
  the manifest is produced again
- **THEN** the manifest no longer lists that asset, and its ledger row is unchanged

#### Scenario: Widening the scope restores the projection without re-uploading

- **WHEN** the membership's admitted range widens again to include that asset, and the manifest is produced
- **THEN** the manifest lists it once more and no byte is re-uploaded, because the ledger row was retained

#### Scenario: An empty projection is published

- **WHEN** the membership currently admits none of its assets
- **THEN** an empty manifest is published for that event, rather than the previous manifest being left in
  place

### Requirement: Device-global ledger with per-event projection

The manifest's entries SHALL derive from the upload ledger — the durable, event-independently keyed record of
the **current membership's share set**: every resource the join-time load found already stored for this device,
and every resource the walk has admitted since, that has not been deleted, each row carrying the manifest's
presentation detail once known (capability `sync-ledger`). A leave or a switch clears it, and a join loads it
afresh. Each event's manifest SHALL be the **admitted** projection of
that ledger's rows: the assets the membership's selection policy admits by **capture date**
(capability `photo-selection-policy`) — at or after the device's configured start for that event (its
per-membership capture-date cutoff) and at or before the event's capture-date ceiling. A membership's cutoff is
**required, never absent** (no scope admits the whole library), so every projection SHALL be date-bounded —
there is no whole-library projection. The projection SHALL apply that one policy rather than a date comparison
of its own, so a bound added to the policy reaches the manifest by construction. Within a membership the ledger
SHALL retain rows the current projection excludes by **date** — rows the join-time load recorded for photos
outside this event's range, and rows a narrowing reconfigure stopped admitting — so that a differing cutoff
(a reconfigure) can be projected without re-walking the library or re-uploading a byte. A differing cutoff in
**another** event is not served by this ledger: it holds one membership's share set, and concurrent
multi-event membership (a named future) would need per-event ledgers or a membership column.

The ledger is a durable record rather than a cache of the library: a row exists because the walk admitted the
resource, or because the join-time load recorded it from the authoritative per-device file listing (capability
`upload-state-reconciliation`). Such a loaded row is **bare** — a filename listing carries no capture date —
and a bare row SHALL NOT be listed in any projection until a full enumeration backfills its manifest detail.
This is fail-closed on purpose: a row whose capture date is unknown cannot be shown to fall inside an event's
range. That exclusion SHALL be effected by the membership's policy rather than by a predicate in the
projection or its storage read.

The two kinds of exclusion land on **opposite sides** of the ledger, and this asymmetry is deliberate. The
**capture-date bounds are per-membership**, so they SHALL be applied in the per-event *projection* — the
ledger must retain an out-of-range row because a later reconfigure of the membership's range may admit it. The **origin exclusions
are event-independent** — a screenshot is a screenshot in every event, and no membership will ever admit
one — so they SHALL be applied **before** the resource is recorded, by the cycle's resource selection, and an
origin-excluded asset therefore never earns a ledger row at all. Excluding by origin up front
therefore costs the projection no per-event flexibility, while excluding by date would.

An origin-excluded asset that reached the manifest would enter the event union and be offered to every other
member as bytes that will **never be uploaded** — because the upload cycle drops it before the engine.
Recording no row for it at all is what forecloses that.

#### Scenario: Date-bounded projection per the device's configured cutoff
- **WHEN** the membership has a cutoff and a ledger row's capture date precedes it
- **THEN** that asset is excluded from that event's manifest while its row remains in the device-global ledger

#### Scenario: An origin-excluded asset never reaches the ledger
- **WHEN** discovery surfaces a screenshot captured after the membership's cutoff
- **THEN** the cycle drops it before the engine, so it earns no ledger row, appears in **no** event's
  manifest, and never enters the event union

#### Scenario: A bare loaded row is not listed until it is backfilled
- **WHEN** the join-time load records a row from the per-device file listing, so the row
  carries no capture date
- **THEN** the membership's policy excludes that resource until a full enumeration backfills its capture
  date, after which the next projection lists it if the membership admits it

#### Scenario: The manifest never lists an asset the policy excludes
- **WHEN** the selection policy excludes an asset from byte upload
- **THEN** that asset appears in no device manifest, so no other member can attempt to download bytes that
  will never be uploaded

### Requirement: Deletion-aware manifest

When an asset is deleted from the library, its **ledger rows** SHALL be **deleted** by the first
authoritative walk that does not return it (capability `sync-ledger`, "Deletion is a presence diff over an
authoritative walk"), so the projection that same cycle publishes stops listing that asset. The deletion
happens before the manifest is projected, so a cycle never publishes an asset its own walk found gone.

Presence is a fact about the library, not about scope. A row is judged gone only when its asset is inside
the membership's capture window and absent from an authoritative walk, never because the selection policy
stopped admitting it. The projection applies the policy on its own, and a row the policy excludes is simply
not listed. Under a partial grant the member's selection is the library from the app's point of view, so a
selection snapshot that has been read is an authoritative walk. **A de-selected photo is retracted**
exactly as a deleted one is (capability `limited-photo-access`). A walk that is not authoritative (an
unreadable library) retracts nothing, and a selection that has not been read yet never reaches a walk.

A row's upload state does not delay its retraction: an in-flight (`REQUESTED`) row is deleted with its
asset, so the manifest stops listing the photo in the cycle that saw it leave, even if its bytes land
afterwards. Such bytes are listed in no manifest.

Deletion-tracking is **exhaustive under both grants**: there is no change token to expire, so a deletion or
a de-selection is observed by the next authoritative walk whenever it runs. Decision record:
`changes/selection-is-the-walk` (D1, D2), which reversed "a partial grant retracts nothing".

This supersedes two earlier requirements: that deletion be recorded by **marking** rows from the change
feed's removal signal, with no reconcile backstop; and, before that, that pruning be driven "incrementally
from the change feed, **and** by the full enumeration's retain-live reconcile". That reconcile was fed the
policy-admitted set, which conflated "gone from the library" with "outside the current capture window" and
discarded upload-suppression state a scope change has no business touching. Presence-driven deletion
retracts only rows inside the window, judged against the walk's whole candidate set.

A restored asset is a new asset to the ledger: its rows are gone, so the walk records it as new work, it
re-uploads under its same keys, and the projection lists it again once it is recorded. The backend
re-stores each role idempotently.

#### Scenario: Deletion retracts the listing

- **WHEN** an asset previously listed in the manifest leaves the library, and an authoritative walk does not
  return it
- **THEN** its ledger rows are deleted and the manifest that cycle publishes no longer lists it

#### Scenario: A restored asset is listed again

- **WHEN** an asset whose rows were deleted is restored to the library and returned by a later walk
- **THEN** it is recorded as new work, and the next manifest projection lists it (when the current policy
  admits it)

#### Scenario: A deletion is not missed for want of a token

- **WHEN** an asset is deleted while no upload cycle runs for an extended period
- **THEN** the first authoritative walk afterwards retracts it; no signal needs to have been received at the
  moment of deletion

#### Scenario: De-selection retracts the listing

- **WHEN** under a partial grant the member de-selects a listed photo, and the next cycle runs over the read
  selection
- **THEN** the photo's rows are deleted and the manifest that cycle publishes no longer lists it

#### Scenario: An in-flight photo is retracted before its upload settles

- **WHEN** a photo whose row is `REQUESTED` leaves the library or the selection, and an authoritative walk
  runs
- **THEN** the manifest that cycle publishes no longer lists it, whether or not its bytes land afterwards

#### Scenario: An unread selection retracts nothing

- **WHEN** under a partial grant the app's cycle runs before the selection has been read
- **THEN** the cycle is withheld and publishes no manifest, so nothing is retracted

### Requirement: Write-only in v1

The device manifest SHALL be write-only in v1: no in-app consumer SHALL read it. Status and
completeness SHALL be computed elsewhere (from the gallery enumeration seam and the upload ledger,
capability `sync-status`), and the manifest SHALL exist solely as forward-preparation for restore and event-wide union.

#### Scenario: No in-app consumer reads the manifest

- **WHEN** the app computes sync status
- **THEN** it reads the gallery enumeration seam and the upload ledger, and never reads the
  device manifest

### Requirement: The manifest is published only from a ledger believed complete

The cycle SHALL publish a manifest only on a path where it believes the ledger settled for that event, and
SHALL **suppress the write** — leaving the previously published manifest in place — whenever it does not.
Because the projection is a **full-state** document, publishing one built from an incomplete ledger silently
un-lists resources that really are uploaded.

In particular, the cycle SHALL NOT write a manifest on any failure to read the ledger rows the projection is
built from.

A failed per-device listing is **not** such a path, and nothing defers a cycle for one. The listing is read
once, at the join (capability `upload-state-reconciliation`), and a failed fetch clears the ledger rather than
leaving it unseeded; the walk then records every admitted resource afresh, so the ledger the cycle projects is
complete for the membership. What the failure costs is the re-upload of resources the backend already held,
not an incomplete manifest.

Suppressing the write SHALL be distinguishable in the diagnostic log from publishing an empty manifest: the
first means "this device could not determine what it shares"; the second means "this device shares
nothing". They differ in consequence, and collapsing them would make an outage indistinguishable from a
deliberate withdrawal.

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
projection"), and it is also never judged gone by the walk's deletion, so a bare row the walk never filled
would stay out of the union with no error anywhere. The walk SHALL therefore always read the resources of
an asset that has a bare row, even when every other asset it returns is skipped as fully known (capability
`sync-ledger`, "A walk re-reads only the assets the ledger does not fully know"). The backfill is part of
the walk, not an opportunistic sweep.

#### Scenario: Bare rows past the truncation point are still backfilled

- **WHEN** a cycle's walk covers rows recorded bare by the join-time load, and the cycle stops
  creating jobs partway through
- **THEN** every bare row the walk covered is backfilled with its capture date, including those after
  the point where job creation stopped

#### Scenario: A re-joined device's photos return to the union on its first cycles

- **WHEN** a device re-joins an event it has already contributed to, so the join-time load records its
  stored resources as bare rows, and its first cycles stop creating jobs early
- **THEN** its loaded rows learn their capture dates on those cycles, so its manifest lists them and
  the event union offers its photos again

### Requirement: The upload cycle is the writer, synchronous in-cycle upload

The upload cycle SHALL be the **only** code that writes the *projected* device manifest, and it SHALL publish
the manifest **synchronously within the cycle** — no background `URLSession` transfer carries it. Both
processes' cycles publish: the app's on every iOS version, and the extension's on iOS ≥26.1, so on iOS ≥26.1
under a full grant both do. Each publish is the **full-state snapshot** its cycle projected from the one shared
ledger through the membership's one selection policy, and the backend applies it whole, so **the last write
wins**. A cycle MAY skip the publish when the projected snapshot is unchanged since the last **successful**
write. A kill mid-publish SHALL be tolerated: the write is atomic at the backend (capability `database`),
so a killed cycle leaves the previous state intact and the projection is recomputed next cycle.

**The word "successful" remains load-bearing**, for a reason that survives the move to v2 even though its
original justification does not. Under v1 it protected the byte route's best-effort upload record, which
the next manifest publish repaired. v2's byte route records the resource itself and is **not** best-effort,
and the manifest writes no resource row at all — so there is nothing left to repair. What remains is the
union: skipping a publish after a *failed* one would leave the backend holding an older asset set while the
device believed it had published, so photos already uploaded would stay absent from the event union with no
error anywhere. The rule is unchanged; only its reason is.

The upload cycle is the **only** producer of this document. Enrollment no longer writes a
register-only empty manifest (capability `join-event`), so joining SHALL NOT invalidate the skip-if-unchanged
record. A rejoin therefore leaves the membership's existing asset set intact and correctly skips a republish
of an unchanged projection — the union continues to list this device's photos with no blank window between the
join and the next cycle.

Two cycles publishing at once is **accepted, not prevented**. Both project the same ledger through the same
policy, so they agree whenever the ledger did not change between their projections. When it did, a **crossed
pair** — cycle A publishes snapshot S1 and cycle B snapshot S2, A's write lands last and B's skip record lands
last — can leave the backend one snapshot behind while the skip record claims it current. That staleness is
bounded and self-healing: the next ledger change produces a different projection, which no skip record
matches, so the next cycle republishes it. Decision record: `changes/both-uploaders-active`.

#### Scenario: Synchronous publish with skip-if-unchanged

- **WHEN** the upload cycle has produced the projected snapshot
- **THEN** the cycle publishes the manifest synchronously in-cycle, or skips it when the snapshot is
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
  projection an upload cycle successfully published

#### Scenario: Both processes' cycles publish the full-state snapshot

- **WHEN** on iOS ≥26.1 under a full grant the app's cycle and the extension's cycle each settle the ledger
  for the joined event
- **THEN** each publishes the full-state snapshot it projected (or skips an unchanged one), and the backend
  holds whichever write landed last

#### Scenario: A crossed pair heals at the next ledger change

- **WHEN** two cycles' publishes cross so that the backend holds the older snapshot while the skip record
  names the newer one
- **THEN** the next cycle after the ledger changes projects a snapshot the skip record does not match and
  publishes it, so the backend is current again

#### Scenario: Kill mid-publish leaves the previous state intact

- **WHEN** the process running the cycle is killed during a manifest publish
- **THEN** the backend applies none of that publish, and the manifest is recomputed and re-published on the
  next cycle

