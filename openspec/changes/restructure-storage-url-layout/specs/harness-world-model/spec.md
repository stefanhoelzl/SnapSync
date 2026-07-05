## MODIFIED Requirements

### Requirement: Backend object store with faithful read-models

The world SHALL provide an in-memory backend object store holding the edge's state: deposited object
keys per device byte-partition (`files/devices/<deviceId>/<filename>`), one device manifest per
`(eventId, deviceId)`, and a registered-event marker set. From this state it SHALL compute the edge's
read-models **faithfully in behavior** — the per-device file listing (`GET /files/devices/<id>`), the
event-wide union (`GET /events/<id>/files`), and the reconcile-seed listing — where the reconcile-seed
listing is the **same** per-device read-model consumed by the rejoin reconciler. Byte-level fidelity to
the real Deno `backend/` edge is **NOT** required: drift is **accepted**, there is **no golden
fixture**, and the store SHALL NOT mint real presigned S3 URLs (each `url` is a synthetic in-memory
handle the fake download seams resolve store-direct). The per-device listing SHALL return one
`{filename, size, url}` entry per stored object. The event-union SHALL include an asset **only when
every** resource named by that asset's manifest entry is present in its device's byte partition, tag
each asset with its owning `deviceId`, and gate on event-marker presence (an unregistered event is
absent, not empty).

#### Scenario: Per-device listing reflects deposited objects

- **WHEN** objects are deposited into a device's byte partition and the per-device listing is computed
- **THEN** it returns one `{filename, size, url}` entry per deposited object

#### Scenario: Union includes only complete assets, tagged by device

- **WHEN** a device's manifest names an asset whose every resource `key` is present in that device's
  partition, and another asset with a missing resource
- **THEN** the union includes the complete asset tagged with its `deviceId` and omits the incomplete one

#### Scenario: Unregistered event is absent, not empty

- **WHEN** the union is computed for an event with no registered marker
- **THEN** the read-model reports the event absent (a 404-equivalent that surfaces as a failed
  `union` `Result`), distinct from a registered event with no complete assets (an empty array)

#### Scenario: Reconcile-seed listing is the per-device listing

- **WHEN** the rejoin reconciler and own-device status completeness each read a device's stored files
- **THEN** both consume the same per-device listing read-model (the world exposes it once)

### Requirement: MockEngine mini-edge over the four common-Ktor seams

The world SHALL expose a Ktor `MockEngine`-backed `HttpClient` — a "mini-edge" — that answers the
app-side metadata calls by dispatching on HTTP method + request path against the backend object store,
so the **real** common-Ktor seams run unmodified against it. The mini-edge SHALL route
`GET /files/devices/<id>` (per-device listing), `GET /events/<id>/files` (event-union; a `404` when the
event marker is absent), `POST /events` (a `201` `{ eventId, name, createdAt }` that registers the
marker), and `PUT /events/<id>/devices/<id>` (a `200` that deposits the manifest into the store), and
SHALL answer any unmatched request `404`. The same `HttpClient` SHALL be injected into the real
`HttpDeviceFilesSource`, `HttpEventUnionSource`, `HttpEventCreationClient`, and the module's common
`HttpDeviceManifestUploader`, mirroring the extension composition root's single shared client.

#### Scenario: Real seams round-trip against the mini-edge

- **WHEN** the real `HttpDeviceFilesSource`, `HttpEventUnionSource`, and `HttpEventCreationClient` are
  each given the mini-edge client and invoked
- **THEN** each parses a well-formed response computed from the backend object store (the listing, the
  union, and a minted event id respectively)

#### Scenario: A manifest PUT lands in the store

- **WHEN** the common `HttpDeviceManifestUploader` PUTs a manifest to `/events/<id>/devices/<id>` via the
  mini-edge
- **THEN** the manifest is deposited into the store and subsequently participates in the union
  completeness computation

#### Scenario: Event creation registers the marker

- **WHEN** `POST /events` is answered
- **THEN** a canonical event id is minted, the response is `201 { eventId, name, createdAt }`, and the
  event marker is registered so a subsequent union read is gated in (not 404)
