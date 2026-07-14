## ADDED Requirements

### Requirement: Notify requires a device token

`POST /events/<eventId>/notify` SHALL require a valid device token (capability `device-attestation`) in
`Authorization: Bearer`. A request without one SHALL be rejected with `401`, and the endpoint SHALL NOT
read the event marker, SHALL NOT enumerate members, and SHALL NOT send any push.

The token gate SHALL be applied **before** the event-existence gate, so an unauthenticated caller can
neither probe which events exist nor cause a push fan-out.

#### Scenario: An unauthenticated notify sends no push

- **WHEN** `POST /events/<uuid>/notify` arrives with no valid token
- **THEN** the endpoint responds `401`, reads no marker, enumerates no members, and sends no push

#### Scenario: An attested notify fans out unchanged

- **WHEN** `POST /events/<uuid>/notify` carries a valid token for an existing event
- **THEN** the silent-push fan-out to active members proceeds exactly as before, returning a bare `202`
