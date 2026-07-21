## MODIFIED Requirements

### Requirement: Event routes require a device token

`POST /events` SHALL require a valid device token (capability `device-attestation`) in
`Authorization: Bearer`. A request without one SHALL be rejected with `401`, and no event marker SHALL be
written. Gating creation is the point: an ungated `POST /events` lets a stranger mint unbounded event
markers in the storage zone.

`GET /events/<eventId>` (the metadata/existence read) SHALL **not** require a token: a request that
carries a valid token and a request that carries none SHALL both be served the marker's metadata (or
`404`). The read is authorized by `eventId`-possession alone — the no-app download page (capability
`web-event-download`) fetches the event name over this route from a browser that holds no attestation.
Existence-probing by a tokenless caller is an accepted consequence of opening this read (decision record:
`changes/web-event-download`).

#### Scenario: Unauthenticated creation is refused

- **WHEN** `POST /events` arrives with no valid token
- **THEN** the endpoint responds `401` and writes no marker

#### Scenario: Unauthenticated metadata read is served

- **WHEN** `GET /events/<eventId>` arrives with no valid token
- **THEN** the endpoint reads the marker and responds with its metadata (`200`) or `404` — not `401`

#### Scenario: An attested device creates an event unchanged

- **WHEN** `POST /events` carries a valid token and a valid name
- **THEN** an event is minted and returned exactly as before
