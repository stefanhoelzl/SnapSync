## MODIFIED Requirements

### Requirement: Device config write

`PUT /api/v1/devices/<deviceId>` SHALL accept the device's config document as its JSON body and record it
against that device (capability `database`). Writes SHALL be last-write-wins.

The document SHALL carry the device's push token (capability `push-registration`). The config is not a
member of the device's byte partition and SHALL NOT appear in the per-device file listing or the event
union.

The route SHALL **update** an existing device record and SHALL NOT create one. A device record exists only
where a device has attested (capability `device-attestation`), and this route cannot attest on the device's
behalf.

When the write affects **no row**, the route SHALL respond `401`. It SHALL NOT collapse that outcome into
success: a `201` for a registration the backend did not record would leave the device believing it is
reachable while no push can ever reach it, and the device PUTs once per OS-delivered token, so nothing
would retry. The `401` is what the shipped client already recovers from — it attests afresh, which creates
the record, and re-sends the registration.

#### Scenario: A config write is recorded

- **WHEN** a valid `PUT /api/v1/devices/<uuid>` arrives with a JSON body for a device that has attested
- **THEN** the device's record carries that document

#### Scenario: Repeated writes are last-write-wins

- **WHEN** two config writes arrive for the same device
- **THEN** the later one is the one retained

#### Scenario: A write for a device with no attestation on file is refused

- **WHEN** a valid `PUT /api/v1/devices/<uuid>` arrives bearing a valid token, for a device the backend
  holds no attestation record for
- **THEN** the endpoint responds `401` and creates no record

#### Scenario: The refusal does not disturb the attestation columns

- **WHEN** a config write succeeds
- **THEN** only the push-registration fields are written, and the device's attestation record is unchanged
