## MODIFIED Requirements

### Requirement: The route table is closed

The application SHALL serve exactly the routes below and no others. A request whose method and path match
no entry SHALL yield `404` and SHALL make no upstream request — to storage or to the database. Each
**version** has its own closed table; a path present in one version's table and absent from another's is
`404` under the version that does not carry it.

**`/api/v1` — frozen. This table SHALL NOT change while v1 is served.**

| method | path | purpose | gated |
|---|---|---|---|
| `PUT` | `/api/v1/files/devices/<deviceId>/<filename>` | upload one resource's bytes | yes |
| `GET` | `/api/v1/files/devices/<deviceId>` | list this device's stored resources | yes |
| `PUT` | `/api/v1/events/<eventId>/devices/<deviceId>` | publish this device's manifest for the event | yes |
| `DELETE` | `/api/v1/events/<eventId>/devices/<deviceId>` | leave the event | yes |
| `POST` | `/api/v1/events` | create an event | yes |
| `GET`/`HEAD` | `/api/v1/events/<eventId>` | event metadata and existence | **no** |
| `PATCH` | `/api/v1/events/<eventId>` | rename the event | yes |
| `GET`/`HEAD` | `/api/v1/events/<eventId>/files` | the event-wide photo union | **no** |
| `POST` | `/api/v1/events/<eventId>/notify` | silent-push fan-out to members | yes |
| `PUT` | `/api/v1/devices/<deviceId>` | write this device's config document | yes |
| `GET` | `/api/v1/attest/challenge` | attestation challenge | **no** |
| `POST` | `/api/v1/attest/token`, `/api/v1/attest/renew` | mint / renew a device token | **no** |

**`/api/v2`**

| method | path | purpose | gated |
|---|---|---|---|
| `PUT` | `/api/v2/files/devices/<deviceId>/<assetId>/<role>` | upload one resource's bytes | yes |
| `GET` | `/api/v2/files/devices/<deviceId>` | list this device's stored resources | yes |
| `PUT` | `/api/v2/events/<eventId>/devices/<deviceId>` | **join** this event | yes |
| `DELETE` | `/api/v2/events/<eventId>/devices/<deviceId>` | leave the event | yes |
| `PUT` | `/api/v2/events/<eventId>/devices/<deviceId>/manifest` | publish this device's contribution | yes |
| `POST` | `/api/v2/events` | create an event | yes |
| `GET`/`HEAD` | `/api/v2/events/<eventId>` | event metadata and existence | **no** |
| `PATCH` | `/api/v2/events/<eventId>` | rename the event | yes |
| `GET`/`HEAD` | `/api/v2/events/<eventId>/files` | the event-wide photo union | **no** |
| `PUT` | `/api/v2/devices/<deviceId>` | write this device's config document | yes |
| `GET` | `/api/v2/attest/challenge` | attestation challenge | **no** |
| `POST` | `/api/v2/attest/token`, `/api/v2/attest/renew` | mint / renew a device token | **no** |

**Served at the root, under no version:**

| method | path | purpose | gated |
|---|---|---|---|
| `OPTIONS` | any path | CORS preflight | **no** |
| `GET`/`HEAD` | `/`, `/join`, `/.well-known/apple-app-site-association` | static pages and the AASA document | **no** |
| `GET` | `/health` | deployment boot probe | **no** |

v2 carries **no notify route**; its fan-out is an effect of the manifest publish. v1 keeps its notify
route unchanged.

The **gated** column is a summary for the reader; the authority for which routes are ungated, and why each
exception is safe, is `device-attestation`'s closed list. This spec SHALL NOT restate the token rule, and
a disagreement between these tables and that list SHALL be resolved in favour of `device-attestation`.

#### Scenario: An unmatched path is rejected without an upstream request

- **WHEN** a request arrives whose path matches no entry in its version's table (a missing label, the
  wrong depth, or no final segment — for example a path ending in `/files/`)
- **THEN** the application responds `404` and makes no storage or database request

#### Scenario: A wrong method on a matched path is rejected

- **WHEN** a request uses a method not paired with that path in its version's table (for example `POST` on
  `/api/v1/events/<eventId>`)
- **THEN** the application responds `404`

#### Scenario: A route belonging to another version is not served

- **WHEN** a request targets `/api/v2/events/<eventId>/notify`, which exists only in v1's table
- **THEN** the application responds `404` and dispatches nothing

### Requirement: Byte upload streams to storage and records that the bytes arrived

A byte upload route SHALL stream the request body to bunny native Storage at a bare key under the device's
partition — the key composed by the backend, each segment percent-encoded so it stays a single flat path —
authorized by the `AccessKey` header from configuration. The route SHALL NOT buffer the body.

On a successful store the route SHALL record the resource in the database (capability `database`), where
**the row's existence is the record** that those bytes arrived.

**The two versions differ in what a failure to record means, and the difference is deliberate.**

Under **v1** that write SHALL remain **best-effort**: a failure to reach or write the database SHALL NOT
change the response, which SHALL remain the storage outcome. The collapse stays safe for exactly the
reason it always was — the record is repaired, not lost, because v1's manifest publish is a full-state
document listing only uploaded resources and it re-creates a missing row on the next cycle. That repair
and this collapse SHALL NOT be edited independently; removing the repair while leaving the write
best-effort would strand a stored byte the backend does not know about, with the device believing it had
published.

Under **v2** that write SHALL NOT be best-effort. v2's manifest publish writes no resource rows at all
(the table has a single writer there), so no repair exists to make a swallowed failure safe: the bytes
would be stored, the backend would not know, the device would be told it succeeded, and the resource would
be absent from every union forever. A failure to record SHALL therefore fail the request, so the device
retries — a visible cost of one re-upload, in place of an invisible cost of one photo.

Object writes SHALL be last-write-wins under both versions: re-uploading the same resource overwrites it,
and the response SHALL NOT distinguish a create from an overwrite.

#### Scenario: Bytes land and the resource is recorded

- **WHEN** a valid byte `PUT` is stored successfully and the database write succeeds
- **THEN** the response is the storage outcome and the resource's row exists

#### Scenario: A database failure does not fail a v1 upload

- **WHEN** the bytes are stored successfully by a v1 upload but the database write fails or times out
- **THEN** the response is still the storage success outcome, and the record is repaired by the next
  device manifest write

#### Scenario: A database failure fails a v2 upload

- **WHEN** the bytes are stored successfully by a v2 upload but the database write fails or times out
- **THEN** the request fails, so the device retries, rather than reporting a success the backend cannot
  vouch for

#### Scenario: Re-uploading the same resource overwrites

- **WHEN** a byte `PUT` targets a resource that already holds an object
- **THEN** the object is replaced and the response does not distinguish this from a first write

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

#### Scenario: A manifest for a missing event is refused

- **WHEN** a manifest write names an `eventId` with no event
- **THEN** the application responds `404` and writes nothing

#### Scenario: A v2 manifest from a non-member is refused

- **WHEN** a v2 manifest write names a `(eventId, deviceId)` pair with no membership
- **THEN** the application refuses it and creates no membership

#### Scenario: A failed transaction writes nothing

- **WHEN** the database transaction cannot complete
- **THEN** the application responds `502`, and neither the membership nor the asset set is changed

### Requirement: Per-device file listing

A per-device listing route SHALL return the device's stored resources as a JSON array, served from the
database — never by enumerating storage. Each element's field set is **closed**.

`GET /api/v1/files/devices/<deviceId>` SHALL carry exactly `filename` and `url`: `filename` the stored
object's name, and `url` a presigned S3 download URL for that object.

`GET /api/v2/files/devices/<deviceId>` SHALL carry the resource's **identity** — its `assetId`, its `role`,
and the capture `filename` — and SHALL NOT carry `url`. The v2 listing answers *"what does the backend
hold for me?"*, a question no URL is needed to answer; minting one costs a per-row signature on a route
whose consumer does not fetch bytes.

Both listings return exactly the resources whose bytes the backend has recorded as arrived. Under the
schema this change introduces, that is every row the table holds for the device (capability `database`).

#### Scenario: The v1 listing carries the closed two-field shape

- **WHEN** a v1 per-device listing is served
- **THEN** each element is `{ filename, url }` and carries no other field

#### Scenario: The v2 listing carries identity and no url

- **WHEN** a v2 per-device listing is served
- **THEN** each element carries `assetId`, `role` and `filename`, and no `url`

#### Scenario: A resource whose bytes never arrived is not listed

- **WHEN** a device's manifest names a resource whose bytes have not been recorded
- **THEN** neither listing includes it

### Requirement: The event union is one query over active and departed memberships

`GET|HEAD /api/vN/events/<eventId>/files` SHALL return every contributing device's complete assets for the
event as a JSON array, assembled by a single database query over the event's memberships — **both** `active`
and `departed`, so a member who has left keeps contributing the photos it already shared.

Each element SHALL be an asset object carrying exactly `deviceId`, `assetId`, `creationDate` and
`resources`. Each resource element SHALL carry exactly `role`, `contentType`, `key`, `filename` and `url`.
Both field sets are closed.

An asset SHALL be included only when **every role its manifest declares** has a recorded resource — a
set comparison, not a count, because the declared roles are event-scoped while the recorded resources are
device-scoped, and a device may hold a role that a given event's manifest does not declare. Counting would
mark such an asset incomplete and drop it silently from that event.

This check is now the **primary** completeness mechanism, not defense-in-depth. It became so when the
manifest started declaring what a member contributes rather than only what it had already uploaded: the
manifest supplies the expectation and the resource rows supply the reality, and their comparison is what
distinguishes a downloadable asset from a declared one. The sweep continues to protect a referenced byte
from collection (capability `scheduled-cleanup`).

The route SHALL be gated on event existence: an event that does not exist SHALL yield `404`.

#### Scenario: The union spans a departed member's contributions

- **WHEN** a device has left an event that still exists
- **THEN** the union still lists the assets that device published before leaving

#### Scenario: An incompletely-uploaded asset is excluded

- **WHEN** an asset declares two roles and only one has a recorded resource
- **THEN** the union excludes that asset entirely

#### Scenario: An extra recorded role does not make an asset incomplete

- **WHEN** an event's manifest declares one role for an asset while the device holds recorded resources
  for two
- **THEN** the union includes that asset, because every declared role is present

#### Scenario: The union entry shapes are closed

- **WHEN** a union asset is emitted
- **THEN** it is `{ deviceId, assetId, creationDate, resources }`, each resource is
  `{ role, contentType, key, filename, url }`, and neither carries any other field

#### Scenario: A union for a missing event is refused

- **WHEN** a union read names an `eventId` with no event
- **THEN** the application responds `404`

## ADDED Requirements

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

#### Scenario: Identity comes from the path

- **WHEN** a v2 byte upload names an asset and role in its path
- **THEN** the recorded resource has that identity, with no parsing of the stored object's name

#### Scenario: An unknown role is refused

- **WHEN** a v2 byte upload names a role outside the closed vocabulary
- **THEN** the application responds `400` and makes no upstream request

#### Scenario: A missing filename is refused

- **WHEN** a v2 byte upload omits the `filename` parameter or supplies an empty one
- **THEN** the application responds `400`

#### Scenario: Both versions address one object

- **WHEN** the same asset and role are uploaded under v1 and under v2
- **THEN** both compose the same stored object name and resolve to the same resource

#### Scenario: A changed filename does not create a second resource

- **WHEN** the same asset and role are re-uploaded with a different filename
- **THEN** the existing resource's metadata is updated and its object overwritten

### Requirement: Joining is an explicit route

`PUT /api/v2/events/<eventId>/devices/<deviceId>` SHALL enroll that device in that event, and SHALL be the
only route that creates or reactivates a membership.

It SHALL be **idempotent**: enrolling a device already enrolled succeeds and changes nothing but the
membership's state to `active`.

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

#### Scenario: Joining a missing event is refused

- **WHEN** a join names an `eventId` with no event
- **THEN** the application responds `404` and writes nothing

### Requirement: The v2 manifest publish notifies the event's members

The v2 manifest publish SHALL dispatch a silent push to the event's other **active** members after its
transaction commits, replacing v1's separate notify route.

It SHALL notify on **every** publish that the route accepts, rather than attempting to notify only when
the event's downloadable set grew. Whether it grew cannot be determined from the publish alone: bytes
arriving between two publishes enlarge the union with no change to any manifest, so a before-and-after
comparison inside the transaction would miss the ordinary case, and detecting it would require durable
state recording what was last announced. The accepted cost is that a recipient may wake and find nothing
new.

The fan-out SHALL be **best-effort**, exactly as v1's notify route is: the response SHALL reflect the
transaction's outcome and SHALL NOT be changed by a push that failed, was skipped for a member with no
token, or timed out. The fan-out SHALL be bounded so that a stalled connection cannot delay the response
past the caller's own timeout — a publish reported as failed but actually committed would suppress the
next cycle's write.

#### Scenario: A publish notifies the other active members

- **WHEN** a v2 manifest publish commits for an event with active and departed members
- **THEN** a push is attempted for each other active member and none for a departed one

#### Scenario: A failed push does not fail the publish

- **WHEN** some members' pushes fail, time out, or a member has no stored token
- **THEN** the publish still reports its transaction's outcome

#### Scenario: Notification follows the commit

- **WHEN** a v2 manifest publish notifies
- **THEN** the transaction is already committed, so a recipient reading the union observes the published
  state

### Requirement: The fan-out's recipients are resolved in a single query

Any route that dispatches a push to an event's members SHALL resolve the recipient set — the event's
active memberships and each one's registered push token — in **one** database query.

It SHALL NOT enumerate the members and then read each member's record separately. That shape is a
survival of the object-store era, where one document per device was the only way to ask; under a
relational store it is one join, and the per-member form costs a round-trip per member on a path that is
now inside a request the caller times out.

A member with no registered token SHALL be excluded by that query rather than by a later filter.

#### Scenario: One query resolves the recipients

- **WHEN** a fan-out resolves an event's recipients
- **THEN** it issues a single query, whatever the member count

#### Scenario: A member without a token is excluded by the query

- **WHEN** an active member has no registered push token
- **THEN** the recipient set omits it and no separate read is made for it
