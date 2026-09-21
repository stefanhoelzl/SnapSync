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

### Requirement: Deletion-aware manifest

When an asset is deleted from the library, its **ledger rows** SHALL be **deleted** by the first
authoritative walk that does not return it (capability `sync-ledger`, "Deletion is a presence diff over an
authoritative walk"), so the projection that same cycle publishes stops listing that asset. The deletion
happens before the manifest is projected, so a cycle never publishes an asset its own walk found gone.

Presence is a fact about the library, not about scope. A row is judged gone only when its asset is inside
the membership's capture window and absent from a walk that read the library itself, never because the
selection policy stopped admitting it. The projection applies the policy on its own, and a row the policy
excludes is simply not listed. A walk that is not authoritative (a partial grant's selection snapshot, or an
unreadable library) retracts nothing.

Deletion-tracking is **exhaustive for a full grant**: there is no change token to expire, so a deletion is
observed by the next authoritative walk whenever it runs. Under a partial grant a settled row is never
retracted by the walk, because deselection is not withdrawal (capability `limited-photo-access`).

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

#### Scenario: A partial grant retracts nothing

- **WHEN** under a partial grant the member de-selects a listed photo
- **THEN** the photo stays listed, because a selection snapshot is not evidence of absence

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

- **WHEN** a cycle's walk covers rows seeded bare by a re-join reconciliation, and the cycle stops
  creating jobs partway through
- **THEN** every bare row the walk covered is backfilled with its capture date, including those after
  the point where job creation stopped

#### Scenario: A re-joined device's photos return to the union on its first cycles

- **WHEN** a device re-joins an event it has already contributed to, and its first cycles stop creating
  jobs early
- **THEN** its seeded rows learn their capture dates on those cycles, so its manifest lists them and
  the event union offers its photos again
