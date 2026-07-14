## ADDED Requirements

### Requirement: The world's event marker carries a start date

The world's backend object store SHALL model the event marker as `{ eventId, name, createdAt, startsAt }`
— the same four fields the real marker carries (capability `event-creation`) — and its registration seam
SHALL accept a `startsAt` so a test or the harness operator can register an event that has **already
started**, **has not started yet**, or started in the **distant past**.

The mini-edge's `POST /events` SHALL read `startsAt` from the request body and SHALL reject a request
whose `startsAt` is absent or non-canonical with `400`, exactly as the real backend does — the mini-edge
being a faithful edge, not a lenient one. Its `GET /events/:eventId` SHALL return `startsAt` in the
marker body, and SHALL synthesize it from `createdAt` for a marker registered without one, mirroring the
real backend's legacy-marker read.

The world's canned `createdAt` deliberately carries **milliseconds** so the world is not "cleaner than
production". `startsAt` SHALL be the opposite: it SHALL be canonical (second-precision, no fraction),
because that is exactly what the real backend guarantees, and a world that emitted a fractional
`startsAt` would make the join gate's no-normalization path untestable.

#### Scenario: The world registers an event with a start date
- **WHEN** a test registers an event in the world with a given `startsAt`
- **THEN** `GET /events/:eventId` through the mini-edge returns that `startsAt` in the marker body

#### Scenario: The mini-edge rejects a non-canonical startsAt on create
- **WHEN** a `POST /events` reaches the mini-edge with an absent or non-canonical `startsAt`
- **THEN** it responds `400` and registers no event, faithfully to the real backend

#### Scenario: A world event registered without a start date synthesizes one
- **WHEN** an event is registered in the world with no `startsAt` and its details are fetched
- **THEN** the mini-edge returns `startsAt` equal to that marker's `createdAt`

#### Scenario: A not-yet-started world event uploads nothing
- **WHEN** the world holds an event whose `startsAt` is in the future, a device joins it, and the
  operator invokes an upload cycle over a gallery of photos
- **THEN** no object lands in the world's store and the ledger gains no entry — the floor admitting
  nothing (capability `photo-date-cutoff`)
