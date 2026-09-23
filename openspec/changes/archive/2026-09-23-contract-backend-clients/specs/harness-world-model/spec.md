## MODIFIED Requirements

### Requirement: Backend object store with faithful read-models

The world SHALL provide an in-memory backend store holding the edge's state: deposited object keys per
device byte-partition (`files/devices/<deviceId>/<filename>`), and the **relational** state the real
backend keeps — events, per-`(eventId, deviceId)` memberships each carrying an `active`/`departed` state,
each membership's asset set, and the device-scoped resources with their `uploaded` flag. From this state it
SHALL compute the edge's read-models **faithfully in behavior** — the per-device file listing
(`GET /files/devices/<id>`), the event-wide union (`GET /events/<id>/files`), and the join-load
listing — where the join-load listing is the **same** per-device read-model the join-time share-set load
consumes (capability `upload-state-reconciliation`). Byte-level fidelity to the real Deno `api/` edge is **NOT** required, and the store SHALL NOT mint
real presigned S3 URLs (each `url` is a
synthetic in-memory handle the fake download seams resolve store-direct).

The per-device listing SHALL return one `{filename, url}` entry per resource recorded as uploaded. The
event-union SHALL span a device's memberships whether `active` or `departed`, include an asset **only when
every** resource that asset names is recorded as uploaded, tag each asset with its owning `deviceId`, and
gate on event existence (an unregistered event is absent, not empty).

Behavioural fidelity on every route a backend port contract covers (capability `port-contracts`) SHALL
NOT be a matter of accepted drift: the mini-edge, serving this store, SHALL be bound as those contracts'
`Fake`, and the real `api/` edge passing the same clauses is its reference. A clause the real edge passes
and the mini-edge fails is a defect in the mini-edge, fixed there. A state the mini-edge does not model is
declared unreachable by its binding and never simulated. Drift on routes and fields no contract covers
remains accepted, with no golden fixture.

Membership SHALL be modelled as a state on one membership record. The world SHALL NOT model the retired
active/departed sibling objects, nor resolve membership from object timestamps.

#### Scenario: Per-device listing reflects uploaded resources

- **WHEN** objects are deposited into a device's byte partition and recorded as uploaded, and the
  per-device listing is computed
- **THEN** it returns one `{filename, url}` entry per uploaded resource

#### Scenario: Union includes only complete assets, tagged by device

- **WHEN** a device's membership names an asset whose every resource is recorded as uploaded, and another
  asset with a resource that is not
- **THEN** the union includes the complete asset tagged with its `deviceId` and omits the incomplete one

#### Scenario: A departed member still contributes to the union

- **WHEN** a device's membership state is `departed` and its event still exists
- **THEN** the union still includes the assets it published before leaving

#### Scenario: Unregistered event is absent, not empty

- **WHEN** the union is computed for an event that does not exist
- **THEN** the read-model reports the event absent (a 404-equivalent that surfaces as a failed
  `union` `Result`), distinct from an existing event with no complete assets (an empty array)

#### Scenario: The join-time load reads the per-device listing

- **WHEN** a provision into a new membership loads the ledger with the photos the backend already stores for
  a device
- **THEN** it consumes the world's per-device listing read-model — the same one the backend serves, exposed once

#### Scenario: The mini-edge diverges from the real edge on a contracted route

- **WHEN** a backend contract clause passes against the real `api/` edge and fails against the mini-edge
- **THEN** the build fails naming the clause and the mini-edge binding, and the fix is made in the
  mini-edge rather than by declaring the clause's state unreachable

### Requirement: MockEngine mini-edge over the four common-Ktor seams

The world SHALL expose a Ktor `MockEngine`-backed `HttpClient` — a "mini-edge" — that answers the
app-side metadata calls by dispatching on HTTP method + request path against the backend object store,
so the **real** common-Ktor seams run unmodified against it. It SHALL answer any unmatched request `404`.
The same `HttpClient` SHALL be injected into the real `HttpDeviceFilesSource`, `HttpEventUnionSource`,
`HttpEventCreation`, and the module's common enrollment and manifest seams, mirroring the extension
composition root's single shared client.

The mini-edge SHALL serve **both** device-API versions side by side, for as long as the real backend does.
It SHALL split a leading `/api/vN` off the request path — defaulting to v1 when the path carries none —
and route on the remainder, mirroring the backend's own version split. Serving only the newer version
would be a harness that models a backend that does not exist, and would break every seam that has not yet
moved; a world in which the client and the backend can only ever move together cannot exercise the
crossing this capability exists to make testable.

Under **v1** the mini-edge SHALL keep its existing behaviour unchanged, including that a manifest publish
to `PUT /events/<id>/devices/<id>` also marks the membership active — v1 is frozen, and its publish really
does reactivate.

Under **v2** the mini-edge SHALL model the **separation of joining from contributing**, because the device
code under test depends on it: a **bodyless** `PUT /events/<id>/devices/<id>` creates or reactivates the
membership and is the only route that may refuse enrolment at capacity, while
`PUT /events/<id>/devices/<id>/manifest` replaces the membership's asset set, leaves its state untouched,
and enrolls nobody. A v2 manifest publish from a device holding no membership SHALL be refused rather than
creating one — modelling it as a create would let a device pass in the harness and fail against the real
backend, which is the one divergence this world exists to make impossible.

The v2 manifest publish SHALL also model the backend's **ordering by manifest version** (capability
`api-endpoints`, "The v2 manifest publish is ordered by its version"): a publish whose version is strictly
older than the membership's stored one changes nothing and is still answered as a success; an equal or newer
one replaces the asset set and records its version; a versionless one replaces it and clears the stored
version; and the v2 join clears it. The world counts applied and refused publishes separately, so a test can
tell "refused as older" from "not published". Without it, a crossed pair that the real backend refuses would
overwrite in the harness, and the device's handling of the refusal could not be tested.

The direct manifest **injection helper** used to set up foreign devices is not a route and SHALL keep
creating an active membership; constraining it would make test setup model an enrolment flow it is not
exercising.

The per-device listing SHALL answer in **identity terms** under v2 — each entry carrying the asset
identity, the resource role, and a capture filename — and SHALL mint no download URL, while keeping the
object-name shape under v1. Serving one shape for both would let a client that misreads the field pass
every test, because the two shapes carry a field of the same name meaning different things. The world does
not model a capture name distinct from the storage key, and SHALL answer with the key: a client consumes
only that value's extension, which the two share.

Under **v2** the mini-edge SHALL also accept the **byte upload** the app's uploader addresses —
`PUT /files/devices/<deviceId>/<assetId>/<role>?filename=<capture name>` — refusing an unknown role or a
missing filename `400`, and storing the object under the key the real backend composes for that resource,
answered `201`. The world's own uploader keeps depositing store-direct, because it plays the operating
system's transfer; the route exists so a caller that enters "a device holds uploads" through the edge's
public surface — the backend port contracts' setup (capability `port-contracts`) — reaches the same state
on the mini-edge as on the real backend.

The mini-edge SHALL be able to enforce the **version gate**: when armed, a v2 request that declares no app
version, or one below the configured minimum, SHALL be refused `426` with the minimum in the body, so the
client's handling of that refusal is exercisable without a backend (capability `min-app-version`). It
SHALL be **off by default** and armed by an operator lever — a gate that refused by default would fail
every seam that does not yet declare a version, which is all of them until the client half ships.

#### Scenario: Real seams round-trip against the mini-edge

- **WHEN** the real `HttpDeviceFilesSource`, `HttpEventUnionSource`, and `HttpEventCreation` are
  each given the mini-edge client and invoked
- **THEN** each parses a well-formed response computed from the backend object store (the listing, the
  union, and a minted event id respectively)

#### Scenario: Both versions are served side by side

- **WHEN** the same logical call is made under the v1 prefix and under the v2 prefix
- **THEN** each is routed to that version's behaviour, and a path carrying no prefix is served as v1

#### Scenario: A v2 join creates the membership and writes no manifest

- **WHEN** the bodyless join route is called under v2 for an event and device
- **THEN** the membership exists and its asset set is unchanged, so a device that had contributed before
  still participates in the union with no republish

#### Scenario: A v2 manifest publish lands in the store

- **WHEN** the manifest seam publishes to the v2 manifest sub-resource for a device holding a membership
- **THEN** the manifest is deposited into the store and subsequently participates in the union
  completeness computation

#### Scenario: A v2 manifest from a non-member is refused

- **WHEN** the v2 manifest sub-resource is called for a device that holds no membership in that event
- **THEN** the request is refused and no membership is created as a side effect

#### Scenario: An older v2 manifest is refused as a success

- **WHEN** a member holding manifest version 9 publishes version 7 to the v2 manifest sub-resource
- **THEN** the request succeeds, the asset set and the stored version are unchanged, and the world counts one
  refused publish and no applied one

#### Scenario: A v2 manifest does not reactivate a departed member

- **WHEN** a departed member publishes to the v2 manifest sub-resource
- **THEN** the asset set is replaced and the membership stays departed, unlike the v1 publish

#### Scenario: The v1 publish still enrols

- **WHEN** a manifest is published to the v1 route
- **THEN** it is deposited and the membership is marked active, exactly as before this change

#### Scenario: The listing answers in identity terms under v2

- **WHEN** the per-device listing is read under the v2 prefix
- **THEN** each entry carries the asset identity, the role and a capture filename, and no entry carries a
  minted download URL — while the v1 prefix still answers with object names

#### Scenario: The version gate is off until armed

- **WHEN** a v2 request reaches the mini-edge with no app-version declaration and the gate has not been
  armed
- **THEN** it is served normally, so seams that do not yet declare a version are unaffected

#### Scenario: An armed gate refuses a request declaring no version

- **WHEN** the gate is armed and a v2 request declares no app version, or one below the configured minimum
- **THEN** it is refused `426` carrying the minimum, so the client's update-required handling is exercised

#### Scenario: Event creation registers the marker

- **WHEN** `POST /events` is answered
- **THEN** a canonical event id is minted, the response is `201 { eventId, name, createdAt }`, and the
  event marker is registered so a subsequent union read is gated in (not 404)

#### Scenario: A v2 byte upload is listed and completes an asset

- **WHEN** a device uploads a resource through the v2 byte route and has published a manifest declaring
  only that resource for an asset
- **THEN** the per-device listing carries the resource, and the union lists the asset as complete
