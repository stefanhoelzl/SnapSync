## MODIFIED Requirements

### Requirement: A v2 byte upload names its resource in the path

`PUT /api/v2/files/devices/<deviceId>/<assetId>/<role>?filename=<name>` SHALL identify the resource by its
**path segments** — the owning asset and the role it plays — and SHALL carry the capture filename as a
**required query parameter**.

`role` SHALL be validated against the closed vocabulary the manifest uses; a value outside it SHALL yield
`400`. `assetId` SHALL be a single path segment. The absence of `filename`, or an empty value, SHALL yield
`400`.

The filename is a **query parameter rather than a path segment** so that no caller-supplied bytes reach the
storage key. The rule that a filename segment must contain no separator and no `..` is not relaxed but
made **unnecessary**: a value that never enters the key cannot traverse it. It also keeps arbitrary bytes
away from path normalization in the CDN that fronts this application.

The filename SHALL be treated as **metadata only**. It SHALL NOT contribute to the resource's identity, so
re-uploading the same asset and role with a different filename updates the metadata and overwrites the
object rather than creating a second resource.

The backend SHALL compose the stored object's name itself, from the identity in the path and the filename.
The composed name SHALL be **byte-identical to the name v1 composes for the same resource**, so that a
resource uploaded under either version is the same stored object. Without that, a device moving between
versions would consider none of its bytes uploaded and re-upload its entire library, and an event with a
member on each version would need two addressing schemes for one photo.

After recording the resource, the route SHALL determine whether that resource was the **last declared role**
its asset was missing, and if so SHALL wake the other active members of every event whose manifest declares
that asset (capability `upload-completion-notify`). The completeness test SHALL be the same set comparison
the union applies. This route's path names no event, so the events declaring the asset SHALL be resolved
from the device and asset identity.

The wake SHALL be **best-effort and bounded**: the response remains the outcome of the storage write and the
resource record, and is never changed by a push that failed, was skipped for a member with no token, or
timed out. A byte upload that completes no asset SHALL wake nobody.

#### Scenario: Identity comes from the path

- **WHEN** a v2 byte upload names an asset and role in its path
- **THEN** the recorded resource has that identity, with no parsing of the stored object's name

#### Scenario: An unknown role is refused

- **WHEN** a v2 byte upload names a role outside the closed vocabulary
- **THEN** the application responds `400` and makes no upstream request

#### Scenario: The byte that completes an asset wakes the event

- **WHEN** a v2 byte upload records the last declared role its asset was missing
- **THEN** the other active members of each event declaring that asset are woken, and the response is still
  the storage outcome

#### Scenario: A byte that leaves an asset incomplete wakes nobody

- **WHEN** a v2 byte upload records a resource while its asset still declares a role with no recorded
  resource
- **THEN** no member is woken

#### Scenario: A failed wake does not fail the upload

- **WHEN** the push fan-out errors or times out after the resource was recorded
- **THEN** the route still responds with the storage outcome

### Requirement: The device manifest write is one atomic database transaction

A manifest publish route SHALL accept the device manifest document as its request body (wire format:
capability `device-manifest`) and record it in the database as **one atomic transaction** (capability
`database`) that **replaces** that membership's asset set with exactly the assets the body lists — a
full-state replace, so an asset the body omits is removed. The routes are
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
existing row — the record is monotone, and a later publish cannot un-say an upload an earlier one recorded.

#### Scenario: A publish replaces the membership's asset set atomically

- **WHEN** a device publishes its manifest for an event
- **THEN** that membership's asset set is replaced with exactly the assets the body lists, in one
  transaction

#### Scenario: A publish that only declares intent wakes nobody

- **WHEN** a v2 manifest publish declares assets whose roles have no recorded resources
- **THEN** the asset set is replaced and no member is woken

#### Scenario: A widening publish that re-admits stored assets wakes members

- **WHEN** a v2 manifest publish newly declares an asset whose every declared role already has a recorded
  resource
- **THEN** the event's other active members are woken

### Requirement: The v2 manifest publish notifies the event's members

The v2 manifest publish SHALL dispatch a silent push to the event's other **active** members after its
transaction commits, replacing v1's separate notify route — but **only when the publish made an asset
newly fetchable**: when it declares an asset whose every declared role already has a recorded resource and
which the stored declaration did not already serve.

It SHALL NOT notify on every publish that the route accepts. That rule was correct while the manifest
listed what a device had already uploaded, because then every publish did enlarge the downloadable set. It
is wrong once the manifest declares **intent** (capability `device-manifest`): most publishes then name
assets whose bytes have not arrived, so notifying on all of them would wake members for photos they cannot
fetch — and spend an allowance the platform caps at two or three background notifications per hour.

The reasoning this replaces held that growth "cannot be determined from the publish alone", because bytes
arriving between two publishes enlarge the union with no change to any manifest. That observation is
correct and is now answered where it arises: the **byte upload** notifies when its resource completes an
asset (see "A v2 byte upload names its resource in the path"), which is the ordinary case the publish
could never see. What remains for the publish is the case only it can see — a membership widening its
capture-date range to re-admit assets whose bytes are already stored — and that IS determinable, because
the stored declaration is exactly the durable record of what was last announced.

The fan-out SHALL be **best-effort**, exactly as v1's notify route is: the response SHALL reflect the
transaction's outcome and SHALL NOT be changed by a push that failed, was skipped for a member with no
token, or timed out. A failure to determine whether anything became fetchable SHALL likewise not fail the
publish; it SHALL wake nobody. The fan-out SHALL be bounded so that a stalled connection cannot delay the
response past the caller's own timeout — a publish reported as failed but actually committed would
suppress the next cycle's write.

#### Scenario: A publish that makes an asset fetchable notifies the other active members

- **WHEN** a v2 manifest publish newly declares an asset whose every declared role is already recorded,
  for an event with active and departed members
- **THEN** a push is attempted for each other active member and none for a departed one

#### Scenario: A publish that declares only unlanded resources notifies nobody

- **WHEN** a v2 manifest publish declares assets whose roles have no recorded resources
- **THEN** no push is attempted, because the union serves nothing it did not serve before

#### Scenario: A failed push does not fail the publish

- **WHEN** some members' pushes fail, time out, or a member has no stored token
- **THEN** the publish still reports its transaction's outcome

#### Scenario: Notification follows the commit

- **WHEN** a v2 manifest publish notifies
- **THEN** the transaction is already committed, so a recipient reading the union observes the published
  state
