## MODIFIED Requirements

### Requirement: Notify requires a device token

`POST /events/<eventId>/notify` SHALL require a valid device token (capability `device-attestation`) in
`Authorization: Bearer`. There SHALL be **no** second credential: no admin key, no shared secret, and no
route-scoped bypass. A request presenting no valid device token SHALL be rejected with `401`, and the
endpoint SHALL NOT read the event marker, SHALL NOT enumerate members, and SHALL NOT send any push.

The authorization gate SHALL be applied **before** the event-existence gate, so an unauthorized caller
can neither probe which events exist nor cause a push fan-out.

The former admin key existed solely so the scheduled cleanup could notify an expiring event's members
before deleting it. That notify is removed (capability `scheduled-cleanup`), so the credential has no
remaining caller and is retired rather than left as an unused authorization path.

#### Scenario: An unauthorized notify sends no push

- **WHEN** `POST /events/<uuid>/notify` arrives with no valid device token
- **THEN** the endpoint responds `401`, reads no marker, enumerates no members, and sends no push

#### Scenario: An attested notify fans out unchanged

- **WHEN** `POST /events/<uuid>/notify` carries a valid device token for an existing event
- **THEN** the silent-push fan-out to active members proceeds exactly as before, returning a bare `202`

#### Scenario: A retired admin key authorizes nothing

- **WHEN** `POST /events/<uuid>/notify` presents the former admin-key secret instead of a device token
- **THEN** the endpoint responds `401` and sends no push — the notify route accepts device tokens only
