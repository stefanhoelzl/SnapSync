## ADDED Requirements

### Requirement: The mini-edge answers the event rename

The `:test:world` mini-edge SHALL answer `PATCH /events/<eventId>` with the same faithfulness as the
real backend route (capability `event-rename`), so the integration tests exercise the shipped
`EventRename` client against a route that behaves like the one it will meet in production.

It SHALL validate `name` by the **same** rule the mini-edge's `POST /events` applies — trimmed,
non-empty, at most 100 characters — answering `400` otherwise; it SHALL answer `404` for an event that
is not registered; and it SHALL answer `502` while the backend-offline lever is set, like every other
routed read.

On success it SHALL replace **only** the event's name, leaving its `startsAt`, `endsAt`, and every other
registered fact untouched, and SHALL respond `200` with the **same** event-details shape
`GET /events/<eventId>` serves. Both routes SHALL build that response from one place, so a rename's echo
can never drift from the details fetch that follows it.

Validation SHALL precede the existence check, matching the real route's order, so a bad name against a
missing event is a `400` in both.

#### Scenario: A valid rename rewrites only the name
- **WHEN** the mini-edge receives `PATCH /events/<eventId>` carrying a valid name for a registered event
- **THEN** it responds `200` with the event's details carrying the trimmed new name, and the registered
  `startsAt` and `endsAt` are unchanged

#### Scenario: An invalid name is refused
- **WHEN** the request carries a name that is absent, empty, whitespace-only, or over 100 characters
- **THEN** the mini-edge responds `400` and the registered name is unchanged

#### Scenario: An unregistered event is a 404
- **WHEN** the request targets an event the world never registered, or one the sweep removed
- **THEN** the mini-edge responds `404` and registers nothing

#### Scenario: The offline lever applies
- **WHEN** the backend-offline lever is set and a rename arrives
- **THEN** the mini-edge responds `502`, exactly as it does for the routed reads

#### Scenario: The rename echo matches the details fetch
- **WHEN** a rename succeeds and `GET /events/<eventId>` is then requested
- **THEN** both responses carry the same name and the same event facts
