## ADDED Requirements

### Requirement: The v2 manifest publish is ordered by its version

The v2 manifest publish body SHALL accept an optional `version`, a non-negative safe integer (capability
`device-manifest`, "A publish carries the manifest version"). A `version` that is present but not a
non-negative safe integer SHALL yield `400` and write nothing.

- A publish carrying a `version` **at least** the membership's stored manifest version, or when none is
  stored, SHALL be applied as today and SHALL record that version.
- A publish carrying a `version` **strictly older** than the stored one SHALL change nothing — neither the
  asset set nor the stored version — and SHALL respond `200`, exactly as an applied publish does. It SHALL
  wake nobody: the fan-out's post-commit decision is taken only for a publish that was applied.
- A publish carrying **no** `version` — the v2 builds that predate it — SHALL be applied unconditionally and
  SHALL clear the stored version, so the next versioned publish from an upgraded build always wins.

An equal version is accepted rather than refused: two publishes carrying the same version carry the same
snapshot (capability `sync-ledger`), so re-applying it is harmless, and a retried identical publish succeeds.
A refused publish answers `200` rather than an error status because it is an ordinary outcome of two
processes publishing at once, and the device must treat it as published. The v1 route SHALL NOT read, write,
or compare a version.

The ordering is decided inside the publish's one transaction (capability `database`, "A manifest publish is
one atomic transaction"), not by a read before it. Decision record: `changes/manifest-versions`.

#### Scenario: A newer publish replaces the asset set

- **WHEN** the membership stores manifest version 7 and a v2 publish carries version 9
- **THEN** the asset set is replaced, the stored version becomes 9, and the response is `200`

#### Scenario: An equal publish is accepted

- **WHEN** the membership stores manifest version 7 and a v2 publish carries version 7
- **THEN** the asset set is replaced and the response is `200`

#### Scenario: An older publish is refused silently

- **WHEN** the membership stores manifest version 9 and a v2 publish carries version 7 that newly declares a
  fully-recorded asset
- **THEN** the asset set and the stored version are unchanged, the response is `200`, and no member is woken

#### Scenario: A versionless publish is applied and clears the version

- **WHEN** the membership stores manifest version 9 and a v2 publish carries no `version`
- **THEN** the asset set is replaced, the stored version is cleared, and a later publish carrying version 1 is
  accepted

#### Scenario: An invalid version is rejected

- **WHEN** a v2 publish carries `version: -1`, `version: 1.5`, or `version: "3"`
- **THEN** the response is `400` and nothing is written

#### Scenario: The v1 route ignores versions

- **WHEN** a v1 manifest publish carries a `version` field
- **THEN** it is handled exactly as before, and the stored manifest version is neither read nor written

## MODIFIED Requirements

### Requirement: The device manifest write is one atomic database transaction

A manifest publish route SHALL accept the device manifest document as its request body (wire format:
capability `device-manifest`) and record it in the database as **one atomic transaction** (capability
`database`) that **replaces** that membership's asset set with exactly the assets the body lists — a
full-state replace, so an asset the body omits is removed. On the v2 route that replace is conditional on
the body's manifest version, decided inside the same transaction (see "The v2 manifest publish is ordered by
its version"); a refused publish writes nothing. The routes are
`PUT /api/v1/events/<eventId>/devices/<deviceId>` and
`PUT /api/v2/events/<eventId>/devices/<deviceId>/manifest`.

A short document is a **retraction, not an omission**: a manifest that no longer names an asset removes it
from the event. The document is the device's complete statement of what it contributes, and a device that
cannot establish that complete set SHALL publish nothing rather than publish a partial one.

After the commit, the v2 route SHALL wake the event's other active members **only when the publish made an
asset newly fetchable** — that is, when it declares an asset whose every declared role already has a
recorded resource and which the previous declaration did not name (capability
`upload-completion-notify`). A publish that only declares resources whose bytes have not arrived, or that
only retracts, SHALL wake nobody. The wake SHALL be best-effort and bounded, never changing the response.

**The v1 route additionally writes two things the v2 route does not**, and both are preserved rather than
carried forward: v1 is legacy, spoken by builds that cannot be updated, and its behaviour is frozen.

First, the v1 route enrolls the writing device and sets its membership `active`, because in v1 the
manifest write **is** the enrollment. The v2 route SHALL NOT: it requires an existing membership, created
by the explicit join route, and SHALL NOT create or reactivate one.

Second, the v1 route upserts a row for each resource the body lists, which is what **repairs** a byte
route's lost best-effort record: an entry that does not say otherwise means the bytes are stored, so the
row is created when missing. An entry that explicitly says the bytes are *not* stored SHALL NOT remove an
existing row — the record is monotone, and a later publish cannot un-say an upload an earlier one
recorded. The v2 route SHALL write no resource row at all; under v2 that table has a single writer, the
byte upload.

#### Scenario: A v1 manifest repairs a lost upload record

- **WHEN** a v1 manifest lists a resource whose row is missing because the byte route's best-effort write
  was lost, and the entry does not state otherwise
- **THEN** the row is created, and the resource is listed and unioned again

#### Scenario: A v1 manifest cannot un-say a recorded upload

- **WHEN** a v1 manifest lists a resource as not uploaded whose row already exists
- **THEN** the row remains, because the record is monotone

#### Scenario: A v2 manifest records no upload

- **WHEN** a v2 manifest lists resources, whether or not their bytes have arrived
- **THEN** it writes the membership's asset set and no resource row

The transaction SHALL be all-or-nothing: a partial replace SHALL NOT be observable by the union read.
Where the number of bound parameters would exceed the platform limit the write SHALL be chunked **within**
the same transaction, never across transactions.

Both routes SHALL be gated on event existence: an event that does not exist SHALL yield `404` and SHALL
write nothing. A failure to complete the transaction SHALL yield `502` and SHALL write nothing.

Neither route SHALL write the manifest to storage.

#### Scenario: A manifest write replaces the event's asset set for that device

- **WHEN** a device publishes a manifest listing assets A and B for an event where it previously listed
  A and C
- **THEN** the membership's asset set becomes exactly {A, B}, C is removed, and the change is atomic

#### Scenario: An omitted asset is retracted

- **WHEN** a manifest omits an asset it previously listed, whose bytes are still stored
- **THEN** that asset leaves the event, and its bytes remain for any other event that still names them

#### Scenario: A publish that only declares intent wakes nobody

- **WHEN** a v2 manifest publish declares assets whose roles have no recorded resources
- **THEN** the asset set is replaced and no member is woken

#### Scenario: A widening publish that re-admits stored assets wakes members

- **WHEN** a v2 manifest publish newly declares an asset whose every declared role already has a recorded
  resource
- **THEN** the event's other active members are woken

#### Scenario: A manifest for a missing event is refused

- **WHEN** a manifest write names an `eventId` with no event
- **THEN** the application responds `404` and writes nothing

#### Scenario: A v2 manifest from a non-member is refused

- **WHEN** a v2 manifest write names a `(eventId, deviceId)` pair with no membership
- **THEN** the application refuses it and creates no membership

#### Scenario: A failed transaction writes nothing

- **WHEN** the database transaction cannot complete
- **THEN** the application responds `502`, and neither the membership nor the asset set is changed


### Requirement: Joining is an explicit route

`PUT /api/v2/events/<eventId>/devices/<deviceId>` SHALL enroll that device in that event, and SHALL be the
only route that creates or reactivates a membership.

It SHALL be **idempotent**: enrolling a device already enrolled succeeds and changes nothing but the
membership's state to `active` and its stored manifest version to absent.

Every join SHALL clear the membership's stored manifest version (capability `database`), in the same
statement that enrolls or reactivates it. A join starts a new sequence of manifests: the device's version
counter lives in its local ledger database while its identity outlives that database, so a device that
re-joins after its counter restarted would otherwise have every publish refused as older, forever. The
first publish after a join therefore always wins.

It SHALL carry the capacity decision (capability `event-limits`): an event at capacity SHALL yield `409`
and an event that does not exist SHALL yield `404`, told apart deliberately rather than collapsed
(capability `database`).

Separating this from the manifest write is what gives `memberships` a single writer. In v1 the manifest
publish *is* the enrollment, which means a document describing what a device shares also decides whether
it is a member — so a device could rejoin an event it had left simply by publishing, and the capacity
decision lived on a route whose purpose was something else entirely.

#### Scenario: A join enrolls the device

- **WHEN** a device joins an event below capacity
- **THEN** its membership exists with state `active`

#### Scenario: Joining twice is harmless

- **WHEN** a device joins an event it is already enrolled in
- **THEN** the request succeeds, the membership count does not increase, and its state is `active`

#### Scenario: A full event refuses a new device

- **WHEN** a device not previously enrolled joins an event already at capacity
- **THEN** the application responds `409`

#### Scenario: A departed device rejoins into its own slot

- **WHEN** a device that previously left rejoins an event at capacity
- **THEN** it is admitted, reusing its membership row, and the device count does not increase

#### Scenario: A re-join accepts a restarted manifest version

- **WHEN** a device whose membership holds manifest version 500 re-joins the event, and then publishes a
  manifest carrying version 3
- **THEN** the join clears the stored version, and the publish is accepted and replaces the asset set

#### Scenario: Joining a missing event is refused

- **WHEN** a join names an `eventId` with no event
- **THEN** the application responds `404` and writes nothing

