## MODIFIED Requirements

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
