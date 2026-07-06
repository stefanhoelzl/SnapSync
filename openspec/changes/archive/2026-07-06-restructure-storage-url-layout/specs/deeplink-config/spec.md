## MODIFIED Requirements

### Requirement: Event name is fetched, not carried in the deeplink

The event `name` SHALL be obtained by `eventId`, never from the QR. On **scan-provision** (a decoded
`EventLinkPayload`), the provision path SHALL save `EventConfig(eventId, name = null)` **immediately**
(joining SHALL NOT block on the name — it is cosmetic), then perform a **best-effort** `GET /events/:id`
and, on success, `save(EventConfig(eventId, name))` to fill the name. On **create**, the returned
`POST /events` body already carries the name, so the create path SHALL save `EventConfig(eventId, name)`
directly with **no** fetch. The name SHALL be refreshed by re-fetching `GET /events/:id` on **foreground
entry**. A failed or unreachable fetch SHALL leave the last-known name (or `null`) unchanged and SHALL
NOT affect joining or syncing.

#### Scenario: Scan provisions immediately, name fills in after
- **WHEN** a valid event QR is scanned
- **THEN** `EventConfig(eventId, name = null)` is saved at once (the join proceeds), and a successful
  `GET /events/:id` afterward updates the config to carry the fetched `name`

#### Scenario: Create saves the name without a fetch
- **WHEN** an event is created and `POST /events` returns `{eventId, name, createdAt}`
- **THEN** `EventConfig(eventId, name)` is saved directly, with no `GET /events/:id` call

#### Scenario: A failed name fetch does not block joining
- **WHEN** the `GET /events/:id` fetch fails or the device is offline after a scan
- **THEN** the config remains `EventConfig(eventId, name = null)`, the join and sync proceed normally,
  and a later foreground refresh may fill the name
