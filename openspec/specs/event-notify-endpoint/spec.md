# event-notify-endpoint Specification

## Purpose

The fan-out route: given an event, enumerate its active member devices, read each one's registered push token
(`device-config-endpoint`), and send every one of them a silent push via `apns-push-sender`.

This is the backend half of "tell the other participants there are new photos". It is gated on event
existence — an unknown event notifies nobody — and it is **best-effort by construction**: a member with no
registered token, or a token APNs rejects, does not fail the fan-out. Push is an accelerant over foreground
discovery and the download backstop, so a partially-delivered notify degrades to "those devices catch up
later", never to lost photos.

The caller is `upload-completion-notify`, which pokes this route after an uploading device drains a cycle.

Decision record: `changes/archive/2026-07-05-push-notification-infra`.
## Requirements
### Requirement: Event notify route

The backend SHALL accept an HTTP `POST` at the path template `/api/v1/events/<eventId>/notify` (the
literal
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

- **WHEN** a `POST /api/v1/events/<uuid>/notify` arrives with a valid UUID for an existing event
- **THEN** the endpoint dispatches to the event's members a push carrying that `eventId` in its payload
  and responds `202` with an empty body

#### Scenario: Non-UUID event id rejected

- **WHEN** the `eventId` segment is not a UUID
- **THEN** the endpoint responds `400` and makes no upstream request

#### Scenario: Unmatched path or wrong method rejected

- **WHEN** the path does not match `/api/v1/events/<eventId>/notify`, or the method is not `POST`
- **THEN** the endpoint responds `404` and makes no upstream request

#### Scenario: A notify without any credential is rejected

- **WHEN** a `POST /api/v1/events/<uuid>/notify` carries a valid event id and no body, but neither a device token
  nor the admin key
- **THEN** it is rejected with `401` and no push is dispatched

#### Scenario: The caller supplies nothing beyond the path

- **WHEN** an authorized `POST /api/v1/events/<uuid>/notify` is accepted
- **THEN** the push payload is server-chosen and carries the route's event id; no caller-supplied body or
  device-exclusion parameter is read

### Requirement: Notify gated on event existence

Before any fan-out the endpoint SHALL read the event marker `events/<eventId>/metadata.json`. A marker
that is absent (bunny `404`) SHALL yield `404` "event not found" with no member enumeration. A non-`404`
marker read failure SHALL yield `502` (a transient failure is never mistaken for absence). When the
marker is present the endpoint SHALL proceed to member enumeration.

#### Scenario: Unknown event yields 404

- **WHEN** the event marker is absent for the requested `eventId`
- **THEN** the endpoint responds `404` and performs no member enumeration or push

#### Scenario: Non-404 marker read failure yields 502

- **WHEN** the marker read fails with a non-`404` status, a connection error, or a timeout
- **THEN** the endpoint responds `502` and is never mistaken for "event absent"

### Requirement: Member enumeration and per-member token read

For an existing event the endpoint SHALL enumerate the event's **active** member devices with a
**single** bunny native Storage List of the device-manifest directory `events/<eventId>/devices/`. A
device is an active member when its `<deviceId>.json` is present and (its `<deviceId>.left.json` sibling
is absent or its `<deviceId>.json` is the newer of the two by last-modified time; see `device-manifest`).
Departed devices — those whose winning sibling is `<deviceId>.left.json` — SHALL be **excluded** from the
notify audience (a device that has left is not pushed). For each active member the endpoint SHALL read
that member's config object `devices/<deviceId>.json` to obtain its `pushToken`. Every upstream read SHALL
carry the storage zone's `AccessKey` and never the account API key. The active-membership resolution is
the same last-write-wins rule the union and reap use.

#### Scenario: Active members enumerated with one LIST

- **WHEN** the event has active member devices
- **THEN** the endpoint enumerates them with one List of `events/<eventId>/devices/`, resolves active membership by last-write-wins, and reads each active member's `devices/<deviceId>.json`

#### Scenario: A departed device is not notified

- **WHEN** a device's winning manifest under `events/<eventId>/devices/` is its `<deviceId>.left.json` (it has left)
- **THEN** that device is excluded from the fan-out and receives no push

#### Scenario: Reads use the storage AccessKey

- **WHEN** the endpoint performs any upstream read in the fan-out
- **THEN** that request carries the configured `AccessKey` header and never the account API key

### Requirement: Best-effort silent fan-out to all members

The endpoint SHALL send a **silent** push (capability `apns-push-sender`) **carrying the route's
`eventId` in its payload** to every member whose config object yields a usable `pushToken`, addressing
**all** members (no server-side exclusion of any device). The fan-out SHALL be **best-effort**: a member
whose config object is absent (`404`), unparseable, or missing a `pushToken` SHALL be **skipped** (that
member is simply not notified), and an individual push failure or APNs rejection SHALL NOT fail the
request. Provided the marker gate passed and the member-directory List succeeded, the endpoint SHALL
respond `202` regardless of how many individual sends succeeded. A member-directory List that fails at
the transport level (non-`404`) SHALL yield `502` (nothing could be enumerated); an empty or `404`
member directory SHALL yield `202` with no sends (an event with no members is notified vacuously).

#### Scenario: All members with a token are pushed

- **WHEN** every member has a config object carrying a valid `pushToken`
- **THEN** each member's token receives a silent push carrying the route's `eventId` and the endpoint
  responds `202`

#### Scenario: A member without a registered token is skipped

- **WHEN** a member's config object is absent (`404`) or carries no `pushToken`
- **THEN** that member is skipped, the remaining members are still pushed, and the endpoint responds
  `202`

#### Scenario: An individual push failure does not fail the request

- **WHEN** one member's push is rejected by APNs (or its config read fails)
- **THEN** the endpoint still responds `202` (best-effort; per-device outcomes are not reported)

#### Scenario: Empty member directory notifies vacuously

- **WHEN** `events/<eventId>/devices/` lists no members (empty or `404`) for an existing event
- **THEN** the endpoint responds `202` with no sends

#### Scenario: Member-directory List failure yields 502

- **WHEN** the `events/<eventId>/devices/` List fails with a non-`404` error or times out
- **THEN** the endpoint responds `502` and dispatches no pushes

### Requirement: Notify requires a device token

`POST /api/v1/events/<eventId>/notify` SHALL require a valid device token (capability `device-attestation`) in
`Authorization: Bearer`. There SHALL be **no** second credential: no admin key, no shared secret, and no
route-scoped bypass. A request presenting no valid device token SHALL be rejected with `401`, and the
endpoint SHALL NOT read the event marker, SHALL NOT enumerate members, and SHALL NOT send any push.

The authorization gate SHALL be applied **before** the event-existence gate, so an unauthorized caller
can neither probe which events exist nor cause a push fan-out.

The former admin key existed solely so the scheduled cleanup could notify an expiring event's members
before deleting it. That notify is removed (capability `scheduled-cleanup`), so the credential has no
remaining caller and is retired rather than left as an unused authorization path.

#### Scenario: An unauthorized notify sends no push

- **WHEN** `POST /api/v1/events/<uuid>/notify` arrives with no valid device token
- **THEN** the endpoint responds `401`, reads no marker, enumerates no members, and sends no push

#### Scenario: An attested notify fans out unchanged

- **WHEN** `POST /api/v1/events/<uuid>/notify` carries a valid device token for an existing event
- **THEN** the silent-push fan-out to active members proceeds exactly as before, returning a bare `202`

#### Scenario: A retired admin key authorizes nothing

- **WHEN** `POST /api/v1/events/<uuid>/notify` presents the former admin-key secret instead of a device token
- **THEN** the endpoint responds `401` and sends no push — the notify route accepts device tokens only
