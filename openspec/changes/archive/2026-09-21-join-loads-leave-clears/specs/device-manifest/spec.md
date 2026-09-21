## MODIFIED Requirements

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
