# join-event — delta for create-flow-zone-and-drain-shell

## MODIFIED Requirements

### Requirement: One details client

The app SHALL have exactly one `GET /events/:eventId` client: the `EventDirectory` port (`:domain` `ports/`) and its
`HttpEventDirectory` implementation in `:adapter:generic` (seated there by migration step 4). Every consumer of an event's details
SHALL read through it — the join gate's details fetch AND the best-effort name refresh (the
scan-path fill and the foreground re-fetch, capability `event-link`). The name refresh SHALL read
the name from a `Found` outcome and treat every other outcome (`NotFound`, `Failed` — including a
`200` lacking a name or a canonical `startsAt`) as "no name this time", leaving the last-known name
unchanged. There SHALL be no second, looser event-fetch client: a duplicate client is how producer
and consumer semantics drift (the deleted `EventMetadataSource` accepted responses the gate
rejects). Whether a fetched name is **persisted** SHALL be a membership-feature rule
(`EventName.storeEventNameIfChanged`, `:domain` `feature/membership`): the name is stored iff the
fetched event is still the configured one (a fetch resolving after a switch or leave must not
resurrect the departed membership's name) and the name actually changed, and the save is the
**whole** current config with only `name` replaced — never clobbering the persisted cutoff
(capability `photo-selection-policy`). The fetch itself is coordinated by the `flow/` triggers
(`Foreground` unconditionally; `Provision` only for a nameless config) through a `compose/`-built
`EventDirectory` effect over this one client.

#### Scenario: The name refresh reads through the details client

- **WHEN** the foreground name refresh (or the scan-path fill) fetches the configured event
- **THEN** it calls the same `EventDirectory` the join gate uses, and updates the stored name
  only from a `Found` outcome

#### Scenario: A non-Found outcome leaves the name unchanged

- **WHEN** the details fetch resolves to `NotFound` or `Failed` during a name refresh
- **THEN** the persisted config's name keeps its last-known value and syncing is unaffected

#### Scenario: A stale fetch after a switch stores nothing

- **WHEN** a name fetch resolves for an event that is no longer the configured one
- **THEN** the membership rule stores nothing (the departed membership's name is not resurrected)
