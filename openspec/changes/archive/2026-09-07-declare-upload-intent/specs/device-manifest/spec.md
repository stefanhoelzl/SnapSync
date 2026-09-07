## MODIFIED Requirements

### Requirement: Mutable full-state projection

The device manifest SHALL be projected from the upload **ledger** (capability `sync-ledger`). For a given
event the manifest SHALL list exactly the resources the device **intends to provide** — every ledger row
whose asset falls within the current membership's admitted capture-date range (capability
`photo-selection-policy`), **whatever its upload state**. A full-state document declaring what this member
contributes, not a record of what has already landed. The ledger SHALL be the **only** durable record the
projection reads: no second structure holding the same asset set exists, so none can disagree with it, and
deletion-awareness comes from the ledger's **absence mark** (a departed asset's rows are marked, not
dropped).

Upload state SHALL NOT be an input to the projection. In particular a `FAILED` row SHALL still be listed:
the engine retries forever with no attempt budget, so `FAILED` means "attempted, still owed" rather than
"abandoned", and excluding it would make the declared role set oscillate as a resource fails and retries —
each flip a manifest write and a member wake.

The projection SHALL additionally exclude rows marked **absent** — assets that have left the device's
library (capability `sync-ledger`). Absence is a fact the row carries, so the projection can apply it; the
row itself is retained, because its bytes are still on the backend.

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

- **WHEN** a resource's upload fails and the engine records `FAILED` alongside a retry
- **THEN** the manifest still declares that role, so the declared role set does not change and no member
  is woken by the failure

#### Scenario: A deleted asset drops from the manifest

- **WHEN** an asset is deleted locally and its ledger rows are marked absent
- **THEN** it no longer appears in the projected manifest, and its rows are still readable so re-upload
  stays suppressed if the asset is restored

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

The manifest's entries SHALL derive from the device-global upload ledger — the durable, event-independently
keyed record of every resource this device has admitted and not deleted, each row carrying the manifest's
presentation detail (capability `sync-ledger`). Each event's manifest SHALL be the **admitted** projection of
that ledger's rows: the assets the membership's selection policy admits by **capture date**
(capability `photo-selection-policy`) — at or after the device's configured start for that event (its
per-membership capture-date cutoff) and at or before the event's capture-date ceiling. A membership's cutoff is
**required, never absent** (no scope admits the whole library), so every projection SHALL be date-bounded —
there is no whole-library projection. The projection SHALL apply that one policy rather than a date comparison
of its own, so a bound added to the policy reaches the manifest by construction. The ledger SHALL remain
device-global under a cutoff — it holds every admitted resource, including ones the current projection
excludes by **date** — so that a differing cutoff (a future edit, or a concurrent membership in another
event) can be projected without re-walking the library.

The ledger is a durable record rather than a cache of the library: a row exists because the walk admitted the
resource, and the re-join reconciliation re-seeds it from the authoritative per-device file listing (capability
`event-rejoin-reconciliation`). Such a seeded row is **bare** — a filename listing carries no capture date —
and a bare row SHALL NOT be listed in any projection until a full enumeration backfills its manifest detail.
This is fail-closed on purpose: a row whose capture date is unknown cannot be shown to fall inside an event's
range. That exclusion SHALL be effected by the membership's policy rather than by a predicate in the
projection or its storage read.

The two kinds of exclusion land on **opposite sides** of the ledger, and this asymmetry is deliberate. The
**capture-date bounds are per-membership**, so they SHALL be applied in the per-event *projection* — the
ledger must retain an out-of-range row because another event's range may admit it. The **origin exclusions
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

#### Scenario: A bare reconciled row is not listed until it is backfilled
- **WHEN** the re-join reconciliation seeds a row from the per-device file listing, so the row
  carries no capture date
- **THEN** the membership's policy excludes that resource until a full enumeration backfills its capture
  date, after which the next projection lists it if the membership admits it

#### Scenario: The manifest never lists an asset the policy excludes
- **WHEN** the selection policy excludes an asset from byte upload
- **THEN** that asset appears in no device manifest, so no other member can attempt to download bytes that
  will never be uploaded
