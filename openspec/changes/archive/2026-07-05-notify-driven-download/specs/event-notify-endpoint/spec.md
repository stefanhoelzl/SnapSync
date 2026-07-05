## MODIFIED Requirements

### Requirement: Event notify route

The backend SHALL accept an HTTP `POST` at the path template `/event/<eventId>/notify` (the literal
labels `event` and `notify` are required) and, for an existing event, dispatch a silent push to every
member device, responding with a bare `202` and an empty body (no per-device results). `eventId` MUST
match a UUID pattern. A request whose path does not match this route (missing a label, wrong depth)
SHALL yield `404`; a matched request whose `eventId` is not a UUID SHALL yield `400`; neither case
SHALL make an upstream request. A request using any method other than `POST` on this path SHALL yield
`404` (no matching route). The route SHALL be served by the same application as the upload, list, and
union endpoints. The endpoint SHALL NOT require any authorization token and SHALL NOT accept any
caller-supplied payload or device-exclusion parameter. The dispatched push payload is **server-chosen**
and carries the route's `eventId` (so a receiving device knows which event to reconcile); the caller
supplies nothing beyond the path, and any device exclusion is a future use-case concern.

#### Scenario: Valid event id accepted

- **WHEN** a `POST /event/<uuid>/notify` arrives with a valid UUID for an existing event
- **THEN** the endpoint dispatches to the event's members a push carrying that `eventId` in its payload
  and responds `202` with an empty body

#### Scenario: Non-UUID event id rejected

- **WHEN** the `eventId` segment is not a UUID
- **THEN** the endpoint responds `400` and makes no upstream request

#### Scenario: Unmatched path or wrong method rejected

- **WHEN** the path does not match `/event/<eventId>/notify`, or the method is not `POST`
- **THEN** the endpoint responds `404` and makes no upstream request

#### Scenario: No token or caller payload required

- **WHEN** a `POST /event/<uuid>/notify` carries a valid event id, no authorization token, and no body
- **THEN** it is accepted (the event id is the capability; the push payload is server-chosen and
  carries the route's event id)

### Requirement: Best-effort silent fan-out to all members

The endpoint SHALL send a **silent** push (capability `apns-push-sender`) **carrying the route's
`eventId` in its payload** to every member whose `config.json` yields a usable `pushToken`, addressing
**all** members (no server-side exclusion of any device). The fan-out SHALL be **best-effort**: a member
whose config object is absent (`404`), unparseable, or missing a `pushToken` SHALL be **skipped** (that
member is simply not notified), and an individual push failure or APNs rejection SHALL NOT fail the
request. Provided the marker gate passed and the member-directory List succeeded, the endpoint SHALL
respond `202` regardless of how many individual sends succeeded. A member-directory List that fails at
the transport level (non-`404`) SHALL yield `502` (nothing could be enumerated); an empty or `404`
member directory SHALL yield `202` with no sends (an event with no members is notified vacuously).

#### Scenario: All members with a token are pushed

- **WHEN** every member has a `config.json` carrying a valid `pushToken`
- **THEN** each member's token receives a silent push carrying the route's `eventId` and the endpoint
  responds `202`

#### Scenario: A member without a registered token is skipped

- **WHEN** a member's `config.json` is absent (`404`) or carries no `pushToken`
- **THEN** that member is skipped, the remaining members are still pushed, and the endpoint responds
  `202`

#### Scenario: An individual push failure does not fail the request

- **WHEN** one member's push is rejected by APNs (or its config read fails)
- **THEN** the endpoint still responds `202` (best-effort; per-device outcomes are not reported)

#### Scenario: Empty member directory notifies vacuously

- **WHEN** `events/<eventId>/device/` lists no members (empty or `404`) for an existing event
- **THEN** the endpoint responds `202` with no sends

#### Scenario: Member-directory List failure yields 502

- **WHEN** the `events/<eventId>/device/` List fails with a non-`404` error or times out
- **THEN** the endpoint responds `502` and dispatches no pushes
