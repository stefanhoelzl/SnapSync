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

Decision record: `changes/record-uploads-in-database`.

## ADDED Requirements

### Requirement: The route table is closed

The application SHALL serve exactly the routes below and no others. A request whose method and path match
no entry SHALL yield `404` and SHALL make no upstream request — to storage or to the database.

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
| `OPTIONS` | any path | CORS preflight | **no** |
| `GET`/`HEAD` | `/`, `/join`, `/.well-known/apple-app-site-association` | static pages and the AASA document | **no** |
| `GET` | `/health` | deployment boot probe | **no** |

The **gated** column is a summary for the reader; the authority for which routes are ungated, and why each
exception is safe, is `device-attestation`'s closed list. This spec SHALL NOT restate the token rule, and
a disagreement between this table and that list SHALL be resolved in favour of `device-attestation`.

#### Scenario: An unmatched path is rejected without an upstream request

- **WHEN** a request arrives whose path matches no entry in the table (a missing label, the wrong depth, or
  no final segment — for example a path ending in `/files/`)
- **THEN** the application responds `404` and makes no storage or database request

#### Scenario: A wrong method on a matched path is rejected

- **WHEN** a request uses a method not paired with that path in the table (for example `POST` on
  `/api/v1/events/<eventId>`)
- **THEN** the application responds `404`

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

### Requirement: Byte upload streams to storage and records the upload best-effort

`PUT /api/v1/files/devices/<deviceId>/<filename>` SHALL stream the request body to bunny native Storage at
the bare key `files/devices/<deviceId>/<filename>` — the URL's label ordering preserved in the key, each
segment percent-encoded so the key stays a single flat path — authorized by the `AccessKey` header from
configuration. The route SHALL NOT buffer the body.

On a successful store the route SHALL record the upload in the database by setting the resource row's
`uploaded` to `1`, keyed by `(deviceId, key)` (capability `database`). **That write is best-effort:** a
failure to reach or write the database SHALL NOT change the response, which SHALL remain the storage
outcome.

The collapse is safe because the record is repaired, not lost: the device manifest write (below) is a
full-state document listing only uploaded resources, it upserts each resource's `uploaded` as true when the
entry does not say otherwise, and it is published in the same upload cycle that produced these bytes.
The repair path further depends on `device-manifest`'s rule that an unchanged manifest may be skipped only
when the **last write succeeded** — a failed manifest write forces a retry next cycle rather than a skip.
**These two requirements SHALL NOT be edited independently**; removing that word would strand a lost
`uploaded` at `0` with the device believing it had published.

`uploaded` SHALL be monotone: it moves `0 → 1` and no route lowers it.

Object writes SHALL be last-write-wins: re-uploading the same key overwrites it, and the response SHALL NOT
distinguish a create from an overwrite.

#### Scenario: Bytes land and the upload is recorded

- **WHEN** a valid byte `PUT` is stored successfully and the database write succeeds
- **THEN** the response is the storage outcome and the resource row's `uploaded` is `1`

#### Scenario: A database failure does not fail the upload

- **WHEN** the bytes are stored successfully but the database write fails or times out
- **THEN** the response is still the storage success outcome, and the record is repaired by the next
  device manifest write

#### Scenario: Re-uploading the same key overwrites

- **WHEN** a byte `PUT` targets a key that already holds an object
- **THEN** the object is replaced and the response does not distinguish this from a first write

### Requirement: OPTIONS preflight falls back to plain PUT

The application SHALL answer an `OPTIONS` request on any path without requiring a token, so that a
cross-origin preflight the pull zone does not answer itself cannot break the plain-`PUT` upload the iOS
uploader depends on.

#### Scenario: Preflight is answered ungated

- **WHEN** an `OPTIONS` request arrives on any path, with or without an `Authorization` header
- **THEN** the application answers it and does not respond `401`

### Requirement: The device manifest write is one atomic database transaction

`PUT /api/v1/events/<eventId>/devices/<deviceId>` SHALL accept the device manifest document as its request
body (wire format: capability `device-manifest`) and record it in the database as **one atomic
transaction** (capability `database`) that:

1. upserts the membership row for `(eventId, deviceId)` with state `active`;
2. **replaces** that membership's asset set with exactly the assets the body lists — a full-state replace,
   so an asset the body omits is removed;
3. upserts each listed resource row keyed by `(deviceId, key)`, setting `uploaded` to the entry's value
   when it carries one and to true when it does not.

The transaction SHALL be all-or-nothing: a partial replace SHALL NOT be observable by the union read.
Where the number of bound parameters would exceed the platform limit the write SHALL be chunked **within**
the same transaction, never across transactions.

The route SHALL be gated on event existence: an event that does not exist SHALL yield `404` and SHALL write
nothing. A failure to complete the transaction SHALL yield `502` and SHALL write nothing.

The route SHALL NOT write the manifest to storage.

#### Scenario: A manifest write replaces the event's asset set for that device

- **WHEN** a device publishes a manifest listing assets A and B for an event where it previously listed
  A and C
- **THEN** the membership's asset set becomes exactly {A, B}, C is removed, and the change is atomic

#### Scenario: A manifest write repairs a lost upload record

- **WHEN** a manifest lists a resource whose row carries `uploaded = 0` because the byte route's
  best-effort write was lost, and the entry does not state otherwise
- **THEN** the upsert sets that resource's `uploaded` to true

#### Scenario: A manifest for a missing event is refused

- **WHEN** a manifest write names an `eventId` with no event
- **THEN** the application responds `404` and writes nothing

#### Scenario: A failed transaction writes nothing

- **WHEN** the database transaction cannot complete
- **THEN** the application responds `502`, and neither the membership, the asset set, nor any resource row
  is changed

### Requirement: Per-device file listing

`GET /api/v1/files/devices/<deviceId>` SHALL return the device's stored resources as a JSON array. Each
element SHALL carry exactly `filename` and `url`: `filename` the uploaded name, and `url` a presigned S3
download URL for that object. The field set is closed.

The listing SHALL be served from the database — the device's resource rows with `uploaded = 1` — and SHALL
NOT enumerate storage.

#### Scenario: The listing carries the closed two-field shape

- **WHEN** a per-device listing is served
- **THEN** each element is `{ filename, url }` and carries no other field

#### Scenario: A not-yet-uploaded resource is not listed

- **WHEN** a resource row exists with `uploaded = 0`
- **THEN** the listing omits it

### Requirement: The event union is one query over active and departed memberships

`GET|HEAD /api/v1/events/<eventId>/files` SHALL return every contributing device's complete assets for the
event as a JSON array, assembled by a single database query over the event's memberships — **both** `active`
and `departed`, so a member who has left keeps contributing the photos it already shared.

Each element SHALL be an asset object carrying exactly `deviceId`, `assetId`, `creationDate` and
`resources`. Each resource element SHALL carry exactly `role`, `contentType`, `key`, `filename` and `url`.
Both field sets are closed.

An asset SHALL be included only when every resource it names carries `uploaded = 1`. This is
defense-in-depth rather than the primary completeness mechanism: the manifest lists only uploaded
resources, so a listed resource is uploaded by construction, and the sweep protects a referenced byte from
collection (capability `scheduled-cleanup`). The check SHALL NOT be relied on to filter
merely-discovered assets.

The route SHALL be gated on event existence: an event that does not exist SHALL yield `404`.

#### Scenario: The union spans a departed member's contributions

- **WHEN** a device has left an event that still exists
- **THEN** the union still lists the assets that device published before leaving

#### Scenario: An incompletely-uploaded asset is excluded

- **WHEN** an asset names a resource whose row carries `uploaded = 0`
- **THEN** the union excludes that asset entirely

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

### Requirement: Device config write

`PUT /api/v1/devices/<deviceId>` SHALL accept the device's config document as its JSON body and record it
against that device (capability `database`). Writes SHALL be last-write-wins.

The document SHALL carry the device's push token (capability `push-registration`). The config is not a
member of the device's byte partition and SHALL NOT appear in the per-device file listing or the event
union.

#### Scenario: A config write is recorded

- **WHEN** a valid `PUT /api/v1/devices/<uuid>` arrives with a JSON body
- **THEN** the device's record carries that document

#### Scenario: Repeated writes are last-write-wins

- **WHEN** two config writes arrive for the same device
- **THEN** the later one is the one retained

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
