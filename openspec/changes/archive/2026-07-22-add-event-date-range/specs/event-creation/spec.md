## MODIFIED Requirements
### Requirement: Event creation route

The backend SHALL accept an HTTP `POST` at the path `/events` whose body is a JSON object containing a
`name` **and a `startsAt`**, optionally carrying an `endsAt`, and on success SHALL respond `201` with a
JSON body `{ eventId, name, createdAt, startsAt, endsAt, capacity }`. The endpoint SHALL be served by the
same Hono application as the upload and list endpoints, so it is available on every deployment target
without separate configuration. A request using any method other than `POST` on `/events` (or a path
that does not match) SHALL yield `404`.

`createdAt` and `startsAt` are **distinct facts** and SHALL NOT be conflated: `createdAt` is
server-minted wall-clock at the moment the marker is written, whereas `startsAt` is the host's statement
of when the event began, supplied by the client and honored verbatim.

`endsAt` is **creator-supplied at mint when present** (capability `event-limits`): when the body carries
a valid `endsAt` — validated per the *Event end-date validation* requirement — the endpoint SHALL stamp
it verbatim as the marker's `endsAt`; when the body carries no `endsAt`, the endpoint SHALL fall back to
`startsAt` plus the configured event duration (the legacy `+30d` default), so a client that sends only
`startsAt` keeps working. `capacity` remains a **server-resolved limit**: the client supplies none and it
is the configured device capacity, resolved at mint time.

#### Scenario: Valid create with a client endsAt returns the new event

- **WHEN** a `POST /events` arrives with body `{ "name": "Birthday", "startsAt": "2026-07-14T18:00:00Z",
  "endsAt": "2026-07-21T23:00:00Z" }`
- **THEN** the endpoint responds `201` with a JSON body containing `eventId`, `name` (`"Birthday"`),
  `createdAt`, `startsAt` (`"2026-07-14T18:00:00Z"`), `endsAt` (`"2026-07-21T23:00:00Z"`, the
  creator-supplied value verbatim), and `capacity` (the configured device capacity)

#### Scenario: Valid create without an endsAt falls back to +30d

- **WHEN** a `POST /events` arrives with body `{ "name": "Birthday", "startsAt": "2026-07-14T18:00:00Z" }`
  and no `endsAt`
- **THEN** the endpoint responds `201` with `endsAt` equal to `startsAt` plus the configured
  duration, and `capacity` the configured device capacity

#### Scenario: createdAt and startsAt are independent

- **WHEN** a `POST /events` supplies a `startsAt` that differs from the server's current time
- **THEN** the response carries the server-minted `createdAt` **and** the client's `startsAt`
  unchanged, as two separate fields

#### Scenario: Client-supplied capacity is ignored

- **WHEN** a `POST /events` body includes a `capacity` field alongside `name`, `startsAt`, and any
  `endsAt`
- **THEN** the endpoint ignores `capacity` and stamps the server-resolved value

#### Scenario: Wrong method on the create path

- **WHEN** a `GET` (or any non-`POST`) is sent to `/events`
- **THEN** the endpoint responds `404` and makes no upstream request

## ADDED Requirements
### Requirement: Event end-date validation

The endpoint SHALL accept an **optional** `endsAt` field on the `POST /events` body. When present it
SHALL be validated against the **canonical cutoff form** `yyyy-MM-dd'T'HH:mm:ss'Z'` — UTC (`Z`), second
precision, no timezone offset, no fractional seconds — SHALL name a **real, round-tripping instant** (the
same instant check `startsAt` receives, rejecting e.g. rolled-over components), and SHALL be **strictly
after** `startsAt` (`startsAt < endsAt`). A request whose `endsAt` is present but is not a string, is the
empty string, does not match that exact shape, is not a real instant, or is not strictly after `startsAt`
SHALL yield `400` and SHALL NOT make any upstream write.

The canonical form is required **at the boundary** for the same reason as `startsAt`: `endsAt` is
consumed directly as a capture-date ceiling, compared lexicographically and parsed without normalization
(capability `photo-selection-policy`). There SHALL be **no upper-duration cap** — `endsAt - startsAt` MAY
be arbitrarily large; a creator-chosen duration is the additive future paid-tier lever, not a validation
bound (capability `event-limits`).

An **absent** `endsAt` is valid and SHALL trigger the legacy fallback `endsAt = startsAt + configured
duration` (capability `event-limits`), so an un-updated client that sends only `startsAt` keeps working.
A present, valid `endsAt` SHALL be stored and returned verbatim.

#### Scenario: A canonical endsAt after startsAt is accepted and echoed

- **WHEN** a `POST /events` arrives with body `{ "name": "Party", "startsAt": "2026-07-14T18:00:00Z",
  "endsAt": "2026-07-21T23:00:00Z" }`
- **THEN** the endpoint responds `201` and the stored and returned `endsAt` is exactly
  `2026-07-21T23:00:00Z`

#### Scenario: An absent endsAt is accepted and triggers the fallback

- **WHEN** a `POST /events` body carries a valid `name` and `startsAt` but no `endsAt`
- **THEN** the endpoint responds `201` and stamps `endsAt = startsAt + configured duration`

#### Scenario: A non-canonical endsAt is rejected

- **WHEN** a `POST /events` body carries an `endsAt` bearing fractional seconds
  (`2026-07-21T23:00:00.000Z`), a timezone offset (`2026-07-21T23:00:00+02:00`), a missing `Z`, or a
  non-timestamp string
- **THEN** the endpoint responds `400` and writes nothing upstream

#### Scenario: An endsAt not after startsAt is rejected

- **WHEN** a `POST /events` body carries an `endsAt` equal to or earlier than `startsAt`
- **THEN** the endpoint responds `400` and writes nothing upstream, because the event window must be
  non-empty (`startsAt < endsAt`)

#### Scenario: An empty endsAt is rejected

- **WHEN** a `POST /events` body carries `endsAt` as the empty string
- **THEN** the endpoint responds `400` and writes nothing upstream

#### Scenario: A large duration is accepted (no upper cap)

- **WHEN** a `POST /events` carries a valid `endsAt` many months after `startsAt`
- **THEN** the endpoint responds `201` and stores it unchanged — no upper bound on `endsAt - startsAt`
  is enforced
