# api-endpoints Specification

## Purpose

**The whole `/api/v1` surface, in one place.** Every route's method, path, parameters, request body,
response shape and status codes — and nothing else. This capability owns *what a request looks like and
what it gets back*; it owns no rule that has a reason behind it.

That division is deliberate and load-bearing. Before this capability the surface was spread across six
endpoint specs, and every rule with a decision behind it was stated twice — once where it was decided and
once where it was enforced. The `endsAt` validation rules lived in full in both `event-limits` and
`event-creation`; the "requires a device token" rule was written **seven** times. Nothing contradicted, but
nothing prevented it from starting to, and `openspec validate --specs --strict` never compares two specs to
each other.

So: **this spec cites, it does not restate.** Where a rule is decided elsewhere it names the capability and
the status code a violation earns, and stops. A reader who wants to know *why* a window is capped at 30
days is sent to `event-limits`; a reader who wants to know what a client gets for exceeding it reads `400`
here.

Decision record: `changes/archive/2026-08-25-record-uploads-in-database`.

## Requirements
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

### Requirement: Path parameters are validated before any upstream request

`eventId` and `deviceId` SHALL each match a canonical UUID pattern. `filename` SHALL be a single non-empty
path segment containing no path separator — `/`, its encoded form `%2F`, or a literal — and no `..`.

A matched route whose UUID parameter is not a UUID, or whose `filename` is unsafe, SHALL yield `400` and
SHALL make no upstream request. Validation SHALL happen before authorization is consulted only where the
route is ungated; on a gated route the token check comes first.

#### Scenario: A non-UUID parameter is rejected

- **WHEN** a matched route's `eventId` or `deviceId` segment is not a canonical UUID
- **THEN** the application responds `400` and makes no storage or database request

#### Scenario: An unsafe filename is rejected

- **WHEN** the byte route's `filename` segment contains `..` or a separator (`/` or `%2F`)
- **THEN** the application responds `400` and makes no storage or database request

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

#### Scenario: A missing filename is refused

- **WHEN** a v2 byte upload omits the `filename` parameter or supplies an empty one
- **THEN** the application responds `400`

#### Scenario: Both versions address one object

- **WHEN** the same asset and role are uploaded under v1 and under v2
- **THEN** both compose the same stored object name and resolve to the same resource

#### Scenario: A changed filename does not create a second resource

- **WHEN** the same asset and role are re-uploaded with a different filename
- **THEN** the existing resource's metadata is updated and its object overwritten

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

### Requirement: OPTIONS preflight falls back to plain PUT

The application SHALL answer an `OPTIONS` request on any path without requiring a token, so that a
cross-origin preflight the pull zone does not answer itself cannot break the plain-`PUT` upload the iOS
uploader depends on.

#### Scenario: Preflight is answered ungated

- **WHEN** an `OPTIONS` request arrives on any path, with or without an `Authorization` header
- **THEN** the application answers it and does not respond `401`

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

### Requirement: Presigned S3 download URL

Every `url` this capability emits SHALL be a presigned S3 `GET` URL for the named object, minted by one
shared authority so the per-device listing and the union agree by construction. Fetching a `url` SHALL
return the very object its entry describes.

#### Scenario: A listed url fetches its object

- **WHEN** any `url` from a per-device listing or a union entry is fetched
- **THEN** bunny's S3 endpoint returns the object that entry describes

### Requirement: Event creation

`POST /api/v1/events` SHALL accept a JSON body carrying `name`, `startsAt`, and an optional `endsAt`, and
on success SHALL respond `201` with the created event.

The route SHALL mint the `eventId` itself as a canonical UUID and SHALL ignore any client-supplied id.

The route SHALL validate `name`: trim surrounding whitespace, require the trimmed value to be non-empty,
and require its length to be at most 100 characters. The trimmed value SHALL be the name stored and
returned. This bound is surface — no other capability reads it.

`startsAt` and `endsAt` SHALL be validated against the rules `event-limits` decides, and a body violating
any of them SHALL yield `400` and write nothing. This spec SHALL NOT restate those rules.

A body that is not valid JSON SHALL yield `400` and write nothing.

#### Scenario: A valid create mints an event

- **WHEN** a valid `POST /api/v1/events` is processed
- **THEN** the application responds `201`, the `eventId` is a server-minted canonical UUID, and the event
  row exists

#### Scenario: A client-supplied id is ignored

- **WHEN** the body carries an `eventId` or `id` field alongside `name`
- **THEN** the application ignores it and returns a freshly minted `eventId`

#### Scenario: An empty or over-long name is rejected

- **WHEN** the body's `name` is absent, empty after trimming, or longer than 100 characters
- **THEN** the application responds `400` and writes nothing

#### Scenario: A body violating a window rule is rejected

- **WHEN** the body's `startsAt` or `endsAt` violates any rule `event-limits` states
- **THEN** the application responds `400` and writes nothing

### Requirement: Event metadata and existence

`GET|HEAD /api/v1/events/<eventId>` SHALL respond `200` with the event's stored fields when the event
exists, and `404` when it does not. A `404` from this route SHALL be a **sealed** answer — a real absence,
never a transient miss — because no route deletes an event on touch (capability `event-limits`) and
`leave-event`'s teardown rule depends on it.

A failure to read that is not an absence SHALL yield `502`, never `404`.

#### Scenario: An existing event is described

- **WHEN** the metadata route names an event that exists
- **THEN** the application responds `200` with that event's stored fields

#### Scenario: A missing event is a sealed 404

- **WHEN** the metadata route names an event that does not exist
- **THEN** the application responds `404`

#### Scenario: A read failure is not an absence

- **WHEN** the metadata read fails for any reason other than the event being absent
- **THEN** the application responds `502` and never `404`

### Requirement: Event rename

`PATCH /api/v1/events/<eventId>` SHALL accept a JSON body containing a `name` and on success SHALL respond
`200` with the same body shape the metadata route serves, carrying the stored (trimmed) name.

The route SHALL validate `name` with the same rule the create route applies. It SHALL resolve the event
through the same existence gate the metadata route uses — absent yields `404` and writes nothing; a
non-absence read failure yields `502`.

The route SHALL update **only** the event's `name`. Every other field is immutable after creation
(capability `event-limits`), and this route SHALL NOT be extended to write any of them.

Concurrent renames SHALL resolve last-write-wins.

#### Scenario: A rename updates only the name

- **WHEN** a valid `PATCH` renames an existing event
- **THEN** the application responds `200`, the event's `name` is the trimmed value, and no other field of
  the event has changed

#### Scenario: A rename of a missing event is refused

- **WHEN** a `PATCH` names an event that does not exist
- **THEN** the application responds `404` and writes nothing

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

### Requirement: Leave

`DELETE /api/v1/events/<eventId>/devices/<deviceId>` SHALL mark that membership `departed` and SHALL
respond successfully whether or not the membership was already departed or absent — the route is
**idempotent**.

The membership's assets and resources SHALL be retained, so the event union keeps serving what the device
shared before leaving.

Leaving SHALL NOT free an enrollment slot (capability `event-limits`).

#### Scenario: Leaving marks the membership departed

- **WHEN** an active member leaves
- **THEN** its membership state becomes `departed` and its assets remain in the event union

#### Scenario: Leaving twice is harmless

- **WHEN** a leave is repeated for a membership that is already departed, or names a membership that does
  not exist
- **THEN** the application responds successfully and changes nothing

### Requirement: Notify

`POST /api/v1/events/<eventId>/notify` SHALL enumerate the event's **active** memberships, read each
device's stored push token, and dispatch a silent push to each.

The fan-out SHALL be **best-effort**: the route SHALL respond `202` when it has attempted every member,
including when some or all dispatches failed or a member had no token. It SHALL NOT report per-member
failure to the caller.

The route SHALL be gated on event existence: an event that does not exist SHALL yield `404` and dispatch
nothing.

#### Scenario: Every active member is attempted

- **WHEN** a notify is processed for an event with active and departed members
- **THEN** a push is attempted for each active member and none for a departed one, and the response is `202`

#### Scenario: A failed dispatch does not fail the request

- **WHEN** some members' pushes fail or a member has no stored token
- **THEN** the application still responds `202`

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

### Requirement: Device config write

`PUT /api/v1/devices/<deviceId>` SHALL accept the device's config document as its JSON body and record it
against that device (capability `database`). Writes SHALL be last-write-wins.

The document SHALL carry the device's push token (capability `push-registration`). The config is not a
member of the device's byte partition and SHALL NOT appear in the per-device file listing or the event
union.

The route SHALL **update** an existing device record and SHALL NOT create one. A device record exists only
where a device has attested (capability `device-attestation`), and this route cannot attest on the device's
behalf.

When the write affects **no row**, the route SHALL respond `401`. It SHALL NOT collapse that outcome into
success: a `201` for a registration the backend did not record would leave the device believing it is
reachable while no push can ever reach it, and the device PUTs once per OS-delivered token, so nothing
would retry. The `401` is what the shipped client already recovers from — it attests afresh, which creates
the record, and re-sends the registration.

#### Scenario: A config write is recorded

- **WHEN** a valid `PUT /api/v1/devices/<uuid>` arrives with a JSON body for a device that has attested
- **THEN** the device's record carries that document

#### Scenario: Repeated writes are last-write-wins

- **WHEN** two config writes arrive for the same device
- **THEN** the later one is the one retained

#### Scenario: A write for a device with no attestation on file is refused

- **WHEN** a valid `PUT /api/v1/devices/<uuid>` arrives bearing a valid token, for a device the backend
  holds no attestation record for
- **THEN** the endpoint responds `401` and creates no record

#### Scenario: The refusal does not disturb the attestation columns

- **WHEN** a config write succeeds
- **THEN** only the push-registration fields are written, and the device's attestation record is unchanged

### Requirement: Faithful outcome — no partial success, no partial list

Every route SHALL propagate its true outcome. A write route SHALL NOT report success for a write that did
not land, and a read route SHALL NOT return a **partial** collection: when any part of assembling a listing
or union fails, the route SHALL fail with `502` rather than return a shorter array.

A partial list is indistinguishable, to every client, from a complete one that is genuinely short — which
is how a photo becomes invisible with no error anywhere.

#### Scenario: A partial assembly fails rather than truncates

- **WHEN** assembling a per-device listing or an event union fails part-way
- **THEN** the application responds `502` and returns no array

#### Scenario: An upstream failure is propagated, not masked

- **WHEN** an upstream storage or database call fails on a write route
- **THEN** the application responds with a failure status and does not report success

### Requirement: A maintenance window answers every device-API route with 503

The application SHALL answer **`503`** to every request under the **`/api/` prefix** while the bundle
serving it carries the maintenance flag (capability `backend-deployment`) — before any other handling, and
making no storage or database request for it.

The match SHALL be the **prefix**, not an enumeration of routes. A closed list can be omitted from — a
route added later lands ungated by nobody's decision — whereas a prefix cannot, and a future
`/api/v2` mount inherits the gate by construction.

`503` is the status HTTP defines for exactly this: a temporary inability to serve due to scheduled
maintenance. The response SHALL carry `Retry-After`, which HTTP pairs with it, and SHALL carry the
listings' no-cache directives.

**The no-cache directives are load-bearing, not decoration.** A pull zone fronts every request and caches
on the origin's directives; a cached `503` would outlive the window and turn a bounded, deliberate outage
into an unbounded accidental one. The deploy workflow cannot configure the pull zone, so the origin's
header is the only lever, and its behaviour SHALL be verified **through the pull zone** rather than at the
origin alone.

The gate SHALL run **before** the device-token gate (capability `device-attestation`), so an unauthenticated
request during the window is answered `503` rather than `401`. That is both cheaper — no token verification
— and truthful: the service is unavailable, and the caller's credentials are not what is wrong. Nothing is
disclosed by answering before authentication that the health route does not already disclose publicly.

Routes served at the **root** — the marketing page, the no-app download page, the site's fingerprinted
assets, the AASA document, and the health route — SHALL NOT be gated. They read only the public storage
`site/` prefix or nothing at all, never the relational store, so a schema migration has no bearing on them,
and the health route is how the deploy learns the window's state.

Downloads are unaffected by construction: presigned S3 URLs are fetched directly from the storage
provider's S3 endpoint and never reach this application.

#### Scenario: A device-API request during the window is refused

- **WHEN** a request under `/api/` arrives while the serving bundle carries the maintenance flag
- **THEN** it is answered `503` with `Retry-After` and no-cache directives, and no storage or database
  request is made

#### Scenario: Maintenance is answered before authentication

- **WHEN** a request under `/api/` arrives during the window carrying no valid device token
- **THEN** it is answered `503`, not `401`

#### Scenario: A future version prefix is gated without being enumerated

- **WHEN** a request under a device-API version prefix other than `/api/v1` arrives during the window
- **THEN** it is answered `503`, because the gate matches the `/api/` prefix rather than a list of routes

#### Scenario: Root routes keep serving during the window

- **WHEN** the marketing page, the download page, a fingerprinted site asset, the AASA document, or the
  health route is requested during the window
- **THEN** it is served normally

#### Scenario: The window's refusal is not cached past the window

- **WHEN** a device-API route is requested through the pull zone after the window closes
- **THEN** the response is served by the application, not from a cached `503`
