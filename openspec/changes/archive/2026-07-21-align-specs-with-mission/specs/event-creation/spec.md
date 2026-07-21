# event-creation Specification

## MODIFIED Requirements

### Requirement: Event start-date validation

The endpoint SHALL require a `startsAt` field on the `POST /events` body and SHALL validate it against
the **canonical cutoff form** `yyyy-MM-dd'T'HH:mm:ss'Z'` — UTC (`Z`), second precision, no timezone
offset, no fractional seconds. A request whose `startsAt` is absent, is not a string, is the empty
string, or does not match that exact shape SHALL yield `400` and SHALL NOT make any upstream write.

The canonical form is required **at the boundary**, rather than accepted loosely and normalized, because
`startsAt` is consumed directly as a capture-date cutoff: it is compared lexicographically against
PhotoKit `creationDate` and parsed by a bare `NSISO8601DateFormatter` (capability `photo-selection-policy`).
A marker that stores the canonical form is usable as a cutoff with **no** client-side normalization —
unlike `createdAt`, which the backend mints with `new Date().toISOString()` and which therefore always
carries milliseconds.

`startsAt` SHALL NOT be bounded: an event MAY start arbitrarily far in the past **or** in the future.
A future `startsAt` is meaningful — it is how an event is created ahead of time, and it is what the app
renders as its not-started state.

Unboundedness interacts with the event lifetime (capability `event-limits`,
`endsAt = startsAt + duration`), in opposite directions. A `startsAt` more than the duration plus grace
in the past mints an event that is **already expired** — reaped on the first touch, self-defusing —
while a within-window past start is a feature (an event created mid-trip). A far-**future** `startsAt`
extends the marker's total life to `startsAt + duration`, beyond the duration-from-now an event minted
today carries. That extension is accepted while creation is attestation-gated and free; it is
re-examined when duration becomes creator-chosen under paid events.

The value SHALL be stored and returned verbatim.

#### Scenario: A canonical startsAt is accepted and echoed
- **WHEN** a `POST /events` arrives with body `{ "name": "Party", "startsAt": "2026-07-14T18:00:00Z" }`
- **THEN** the endpoint responds `201` and the stored and returned `startsAt` is exactly
  `2026-07-14T18:00:00Z`

#### Scenario: A missing startsAt is rejected
- **WHEN** a `POST /events` body carries a valid `name` but no `startsAt`
- **THEN** the endpoint responds `400` and writes nothing upstream

#### Scenario: A non-canonical startsAt is rejected
- **WHEN** a `POST /events` body carries a `startsAt` bearing fractional seconds
  (`2026-07-14T18:00:00.000Z`), a timezone offset (`2026-07-14T18:00:00+02:00`), a missing `Z`, or a
  non-timestamp string
- **THEN** the endpoint responds `400` and writes nothing upstream

#### Scenario: An empty startsAt is rejected
- **WHEN** a `POST /events` body carries `startsAt` as the empty string
- **THEN** the endpoint responds `400` and writes nothing upstream, because an empty cutoff admits every
  asset (`creationDate >= ""` holds for all) and would silently restore whole-library scope

#### Scenario: A future startsAt is accepted
- **WHEN** a `POST /events` carries a `startsAt` later than the server's current time
- **THEN** the endpoint responds `201` and stores it unchanged, the event being created ahead of time

#### Scenario: A far-past startsAt is accepted
- **WHEN** a `POST /events` carries a `startsAt` years in the past
- **THEN** the endpoint responds `201` and stores it unchanged — the value is a floor on the event's
  contents, and bounding it is not the backend's concern

### Requirement: Event routes require a device token

`POST /events` and `GET /events/<eventId>` SHALL require a valid device token (capability
`device-attestation`) in `Authorization: Bearer`. A request without one SHALL be rejected with `401`, and
no event marker SHALL be written or read.

Gating creation is the point: an ungated `POST /events` lets a stranger mint unbounded event markers in
the storage zone.

Attestation is the **only** creation gate today — creation is free for every attested device. A future
paid-events change would attach its payment/authorization check **on this route**: creation is the one
moment an event's tier is decided, and the tier's substance (capacity, duration) is already stamped at
mint from values that can become creator-chosen with no enforcement change (capability `event-limits`).
Nothing else in the API is shaped by payment.

#### Scenario: Unauthenticated creation is refused

- **WHEN** `POST /events` arrives with no valid token
- **THEN** the endpoint responds `401` and writes no marker

#### Scenario: Unauthenticated metadata read is refused

- **WHEN** `GET /events/<eventId>` arrives with no valid token
- **THEN** the endpoint responds `401` and reads no marker — so it does not reveal whether the event exists

#### Scenario: An attested device creates an event unchanged

- **WHEN** `POST /events` carries a valid token and a valid name
- **THEN** an event is minted and returned exactly as before
