# event-creation-ui — delta for delete-dead-weight

## ADDED Requirements

### Requirement: Create performs no event fetch of its own

The capability's only HTTP surface SHALL be the `POST /events` create client. It SHALL NOT carry a
`GET /events/:id` client of any kind: the create response already returns the event's name and
`startsAt`, and every details/name fetch — including the scan-path name fill the deleted
`EventMetadataSource` used to serve — goes through capability `join-event`'s single
`EventDetailsSource` client (see its "One details client" requirement).

#### Scenario: The capability exposes only the create call

- **WHEN** the capability's HTTP clients are inspected
- **THEN** the only route it calls is `POST /events`, and event details are obtained through
  `:capability:join`'s `EventDetailsSource`
