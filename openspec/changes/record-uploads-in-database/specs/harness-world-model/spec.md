## MODIFIED Requirements

### Requirement: Backend object store with faithful read-models

The world SHALL provide an in-memory backend store holding the edge's state: deposited object keys per
device byte-partition (`files/devices/<deviceId>/<filename>`), and the **relational** state the real
backend keeps — events, per-`(eventId, deviceId)` memberships each carrying an `active`/`departed` state,
each membership's asset set, and the device-scoped resources with their `uploaded` flag. From this state it
SHALL compute the edge's read-models **faithfully in behavior** — the per-device file listing
(`GET /files/devices/<id>`), the event-wide union (`GET /events/<id>/files`), and the reconcile-seed
listing — where the reconcile-seed listing is the **same** per-device read-model consumed by the rejoin
reconciler. Byte-level fidelity to the real Deno `api/` edge is **NOT** required: drift is **accepted**,
there is **no golden fixture**, and the store SHALL NOT mint real presigned S3 URLs (each `url` is a
synthetic in-memory handle the fake download seams resolve store-direct).

The per-device listing SHALL return one `{filename, url}` entry per resource recorded as uploaded. The
event-union SHALL span a device's memberships whether `active` or `departed`, include an asset **only when
every** resource that asset names is recorded as uploaded, tag each asset with its owning `deviceId`, and
gate on event existence (an unregistered event is absent, not empty).

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

#### Scenario: The reconcile seed reads the per-device listing

- **WHEN** the rejoin reconciler seeds already-stored photos for a device
- **THEN** it consumes the world's per-device listing read-model — the same one the backend serves, exposed once
