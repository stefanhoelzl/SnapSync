# event-notify-endpoint Delta

## MODIFIED Requirements

### Requirement: Event notify route

The backend SHALL accept an HTTP `POST` at the path template `/events/<eventId>/notify` (the literal
labels `events` and `notify` are required) and, for an existing event, dispatch a silent push to every
member device, responding with a bare `202` and an empty body (no per-device results). `eventId` MUST
match a UUID pattern. A request whose path does not match this route (missing a label, wrong depth)
SHALL yield `404`; a matched request whose `eventId` is not a UUID SHALL yield `400`; neither case
SHALL make an upstream request. A request using any method other than `POST` on this path SHALL yield
`404` (no matching route). The route SHALL be served by the same application as the upload, list, and
union endpoints. The caller SHALL authenticate as required by the authorization requirement below — a
valid App Attest device token (capability `device-attestation`) **or** the notify **admin key** (used by
the scheduled cleanup, capability `scheduled-cleanup`). The endpoint SHALL NOT accept any
caller-supplied payload or device-exclusion parameter. The dispatched push payload is **server-chosen**
and carries the route's `eventId` (so a receiving device knows which event to reconcile); the caller
supplies nothing beyond the path, and any device exclusion is a future use-case concern.

#### Scenario: Valid event id accepted

- **WHEN** a `POST /events/<uuid>/notify` arrives with a valid UUID for an existing event
- **THEN** the endpoint dispatches to the event's members a push carrying that `eventId` in its payload
  and responds `202` with an empty body

#### Scenario: Non-UUID event id rejected

- **WHEN** the `eventId` segment is not a UUID
- **THEN** the endpoint responds `400` and makes no upstream request

#### Scenario: Unmatched path or wrong method rejected

- **WHEN** the path does not match `/events/<eventId>/notify`, or the method is not `POST`
- **THEN** the endpoint responds `404` and makes no upstream request

#### Scenario: A notify without any credential is rejected

- **WHEN** a `POST /events/<uuid>/notify` carries a valid event id and no body, but neither a device token
  nor the admin key
- **THEN** it is rejected with `401` and no push is dispatched

#### Scenario: The caller supplies nothing beyond the path

- **WHEN** an authorized `POST /events/<uuid>/notify` is accepted
- **THEN** the push payload is server-chosen and carries the route's event id; no caller-supplied body or
  device-exclusion parameter is read

### Requirement: Notify requires a device token

`POST /events/<eventId>/notify` SHALL require **either** a valid device token (capability
`device-attestation`) in `Authorization: Bearer` **or** the notify **admin key** — a dedicated bearer
secret (`ADMIN_NOTIFY_KEY`, an Edge Script environment secret; capability `backend-deployment`) whose sole
authorization is this notify route, held by the scheduled cleanup (capability `scheduled-cleanup`) so it
can notify members of an expiring event before deleting it despite holding no device token. A request
presenting neither SHALL be rejected with `401`, and the endpoint SHALL NOT read the event marker, SHALL
NOT enumerate members, and SHALL NOT send any push.

The authorization gate SHALL be applied **before** the event-existence gate, so an unauthorized caller
can neither probe which events exist nor cause a push fan-out. The admin key grants **only** this notify
fan-out — no other route or capability accepts it.

#### Scenario: An unauthorized notify sends no push

- **WHEN** `POST /events/<uuid>/notify` arrives with neither a valid device token nor the admin key
- **THEN** the endpoint responds `401`, reads no marker, enumerates no members, and sends no push

#### Scenario: An attested notify fans out unchanged

- **WHEN** `POST /events/<uuid>/notify` carries a valid device token for an existing event
- **THEN** the silent-push fan-out to active members proceeds exactly as before, returning a bare `202`

#### Scenario: An admin-key notify fans out

- **WHEN** `POST /events/<uuid>/notify` carries the admin key for an existing event
- **THEN** the silent-push fan-out to active members proceeds and the endpoint responds `202`

#### Scenario: The admin key is scoped to notify only

- **WHEN** the admin key is presented to any route other than `POST /events/<eventId>/notify`
- **THEN** it does not authorize that request
