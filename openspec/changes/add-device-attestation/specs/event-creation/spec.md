## ADDED Requirements

### Requirement: Event routes require a device token

`POST /events` and `GET /events/<eventId>` SHALL require a valid device token (capability
`device-attestation`) in `Authorization: Bearer`. A request without one SHALL be rejected with `401`, and
no event marker SHALL be written or read.

Gating creation is the point: an ungated `POST /events` lets a stranger mint unbounded event markers in
the storage zone.

#### Scenario: Unauthenticated creation is refused

- **WHEN** `POST /events` arrives with no valid token
- **THEN** the endpoint responds `401` and writes no marker

#### Scenario: Unauthenticated metadata read is refused

- **WHEN** `GET /events/<eventId>` arrives with no valid token
- **THEN** the endpoint responds `401` and reads no marker — so it does not reveal whether the event exists

#### Scenario: An attested device creates an event unchanged

- **WHEN** `POST /events` carries a valid token and a valid name
- **THEN** an event is minted and returned exactly as before
