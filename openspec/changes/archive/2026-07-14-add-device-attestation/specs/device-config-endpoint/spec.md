## ADDED Requirements

### Requirement: The device-config write requires a device token

`PUT /devices/<deviceId>` SHALL require a valid device token (capability `device-attestation`) in
`Authorization: Bearer`. A request without one SHALL be rejected with `401` and SHALL NOT write
`devices/<deviceId>.json`.

The device id remains the capability addressing *which* config is written; the token establishes that the
writer is a genuine app instance at all.

#### Scenario: An unauthenticated config write is refused

- **WHEN** `PUT /devices/<uuid>` arrives with no valid token
- **THEN** the endpoint responds `401` and writes no object

#### Scenario: An attested config write proceeds unchanged

- **WHEN** `PUT /devices/<uuid>` carries a valid token
- **THEN** the push token is streamed into `devices/<deviceId>.json` with the same faithful `201`/`502`
  outcome and last-write-wins semantics as before
