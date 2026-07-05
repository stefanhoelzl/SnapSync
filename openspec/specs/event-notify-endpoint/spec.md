# event-notify-endpoint Specification

## Purpose
TBD - created by archiving change push-notification-infra. Update Purpose after archive.
## Requirements
### Requirement: Event notify route

The backend SHALL accept an HTTP `POST` at the path template `/event/<eventId>/notify` (the literal
labels `event` and `notify` are required) and, for an existing event, dispatch a silent push to every
member device, responding with a bare `202` and an empty body (no per-device results). `eventId` MUST
match a UUID pattern. A request whose path does not match this route (missing a label, wrong depth)
SHALL yield `404`; a matched request whose `eventId` is not a UUID SHALL yield `400`; neither case
SHALL make an upstream request. A request using any method other than `POST` on this path SHALL yield
`404` (no matching route). The route SHALL be served by the same application as the upload, list, and
union endpoints. The endpoint SHALL NOT require any authorization token and SHALL NOT accept any
caller-supplied payload or device-exclusion parameter (the payload is fixed; any exclusion is a
future use-case concern).

#### Scenario: Valid event id accepted

- **WHEN** a `POST /event/<uuid>/notify` arrives with a valid UUID for an existing event
- **THEN** the endpoint dispatches to the event's members and responds `202` with an empty body

#### Scenario: Non-UUID event id rejected

- **WHEN** the `eventId` segment is not a UUID
- **THEN** the endpoint responds `400` and makes no upstream request

#### Scenario: Unmatched path or wrong method rejected

- **WHEN** the path does not match `/event/<eventId>/notify`, or the method is not `POST`
- **THEN** the endpoint responds `404` and makes no upstream request

#### Scenario: No token or payload required

- **WHEN** a `POST /event/<uuid>/notify` carries a valid event id, no authorization token, and no body
- **THEN** it is accepted (the event id is the capability; the payload is fixed and server-chosen)

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

For an existing event the endpoint SHALL enumerate the event's member devices with a **single** bunny
native Storage List of the device-manifest directory `events/<eventId>/device/`; each direct-child
`<deviceId>.json` names one member. For each member it SHALL read that member's config object
`devices/<deviceId>/config.json` to obtain its `pushToken`. Every upstream read SHALL carry the storage
zone's `AccessKey` and never the account API key. This is the same event-membership source the union
uses, so the notify audience equals the event's contributing devices.

#### Scenario: Members enumerated with one LIST

- **WHEN** the event has member devices
- **THEN** the endpoint enumerates them with one List of `events/<eventId>/device/` and reads each
  member's `devices/<deviceId>/config.json`

#### Scenario: Reads use the storage AccessKey

- **WHEN** the endpoint performs any upstream read in the fan-out
- **THEN** that request carries the configured `AccessKey` header and never the account API key

### Requirement: Best-effort silent fan-out to all members

The endpoint SHALL send a fixed **silent** push (capability `apns-push-sender`) to every member whose
`config.json` yields a usable `pushToken`, addressing **all** members (no server-side exclusion of any
device). The fan-out SHALL be **best-effort**: a member whose config object is absent (`404`),
unparseable, or missing a `pushToken` SHALL be **skipped** (that member is simply not notified), and an
individual push failure or APNs rejection SHALL NOT fail the request. Provided the marker gate passed
and the member-directory List succeeded, the endpoint SHALL respond `202` regardless of how many
individual sends succeeded. A member-directory List that fails at the transport level (non-`404`) SHALL
yield `502` (nothing could be enumerated); an empty or `404` member directory SHALL yield `202` with no
sends (an event with no members is notified vacuously).

#### Scenario: All members with a token are pushed

- **WHEN** every member has a `config.json` carrying a valid `pushToken`
- **THEN** each member's token receives a silent push and the endpoint responds `202`

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

