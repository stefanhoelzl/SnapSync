# join-event — delta for delete-dead-weight

## ADDED Requirements

### Requirement: One details client

The app SHALL have exactly one `GET /events/:eventId` client: the `EventDetailsSource` seam and its
`HttpEventDetailsSource` implementation in `:capability:join`. Every consumer of an event's details
SHALL read through it — the join gate's details fetch AND the best-effort name refresh (the
scan-path fill and the foreground re-fetch, capability `event-link`). The name refresh SHALL read
the name from a `Found` outcome and treat every other outcome (`NotFound`, `Failed` — including a
`200` lacking a name or a canonical `startsAt`) as "no name this time", leaving the last-known name
unchanged. There SHALL be no second, looser event-fetch client: a duplicate client is how producer
and consumer semantics drift (the deleted `EventMetadataSource` accepted responses the gate
rejects).

#### Scenario: The name refresh reads through the details client

- **WHEN** the foreground name refresh (or the scan-path fill) fetches the configured event
- **THEN** it calls the same `EventDetailsSource` the join gate uses, and updates the stored name
  only from a `Found` outcome

#### Scenario: A non-Found outcome leaves the name unchanged

- **WHEN** the details fetch resolves to `NotFound` or `Failed` during a name refresh
- **THEN** the persisted config's name keeps its last-known value and syncing is unaffected
