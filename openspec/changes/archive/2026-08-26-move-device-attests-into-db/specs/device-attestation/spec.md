## MODIFIED Requirements

### Requirement: Attestation mints a device token

The backend SHALL expose `POST /api/v1/attest/token`, which accepts a `deviceId`, an App Attest `keyId`, and an
attestation object, and SHALL verify **all** of: the attestation's certificate chain to Apple's App
Attest root CA; that the attestation's nonce matches the challenge it was issued for; that the app-id
hash matches this app; that the signing counter is `0`; and that the `aaguid` names an accepted
attestation environment. Only when every check passes SHALL it mint a token.

The backend SHALL persist the attested public key **in the relational store, keyed by `deviceId`**
(capability `database`), so that renewal can verify a later assertion against it. It SHALL be read **only**
when renewing — never on a gated request — so that no gated route pays a read to authenticate.

The backend SHALL persist, alongside the key, the **expiry of the token it mints**. That value is what
lets the nightly sweep tell a device that may still be holding a working credential from one that cannot
(capability `scheduled-cleanup`); nothing else records it, because a minted token is verified from its own
signature and is never stored.

A route that mints a token SHALL persist before it mints, and SHALL respond `502` and mint **nothing** if
it cannot persist. A token handed out against a record the backend failed to write is a credential nothing
knows about, and the client retries at its next wake, so refusing costs nothing.

A minted token SHALL carry the `deviceId` it was minted for and an expiry, and SHALL be verifiable by
signature alone, with no storage read and no call to Apple.

#### Scenario: A valid attestation mints a token

- **WHEN** `POST /api/v1/attest/token` receives an attestation that passes every check above
- **THEN** the attested public key and the minted token's expiry are recorded against that `deviceId`, and
  a signed token for it is returned

#### Scenario: An attestation failing any check mints nothing

- **WHEN** the certificate chain, nonce, app-id hash, counter, or `aaguid` check fails
- **THEN** the endpoint responds `401`, no public key is stored, and no token is minted

#### Scenario: A record that cannot be persisted mints nothing

- **WHEN** every check passes but the record cannot be written
- **THEN** the endpoint responds `502` and no token is returned

#### Scenario: Verifying a token touches no storage and no Apple service

- **WHEN** a gated route verifies a request's token
- **THEN** the decision is made from the signature alone — no storage object is read and no request is
  made to Apple

### Requirement: Renewal is a local assertion, never a re-attestation

The backend SHALL expose `POST /api/v1/attest/renew`, which accepts a `deviceId`, an App Attest **assertion**,
and the server-issued `challenge` the assertion is over; SHALL verify that assertion against the public
key **recorded for that `deviceId`**; and SHALL mint a fresh token on success. It SHALL NOT
accept a `keyId` — the stored key is found by `deviceId`, so renewal cannot be pointed at another key
(unlike `/api/v1/attest/token`, which takes a `keyId` because it is establishing which key to store).

Renewal SHALL record the new token's expiry **before** minting it, and SHALL respond `502` and mint
nothing if it cannot — the same posture as `POST /api/v1/attest/token`, for the same reason. Renewal is
attempted at every wake, so a refused renewal is retried within hours.

Renewal SHALL NOT require a new attestation and SHALL NOT call Apple. (Apple's model attests a key
**once**; repeatedly re-attesting — or minting a fresh key per renewal — is the throttled path. Making
renewal cheap is what allows it to be attempted at every wake rather than in a narrow window near
expiry.)

The backend SHALL NOT maintain an assertion counter. A replayed assertion re-mints the same device's
token, which grants nothing the caller did not already hold.

#### Scenario: A valid assertion renews the token

- **WHEN** `POST /api/v1/attest/renew` receives an assertion that verifies against the device's stored public key
- **THEN** the device's recorded token expiry is advanced and a fresh token is minted, with no call to Apple

#### Scenario: A device with no stored key must attest

- **WHEN** `POST /api/v1/attest/renew` names a `deviceId` the backend holds no attestation record for
- **THEN** the endpoint responds `401`, and the device must complete a full attestation instead

#### Scenario: A renewal whose expiry cannot be recorded mints nothing

- **WHEN** the assertion verifies but the new expiry cannot be written
- **THEN** the endpoint responds `502` and no token is returned

#### Scenario: A re-attestation after reinstall overwrites the stored key

- **WHEN** a device whose Secure-Enclave key is gone (reinstall/restore) attests again for the same
  `deviceId`
- **THEN** the attestation succeeds and the recorded key is overwritten with the new one

## ADDED Requirements

### Requirement: An attestation record is the device's enrolment

The backend SHALL hold a device record **if and only if** that device has completed an attestation. The
attestation route SHALL be the only route that creates one; every other route that writes device-scoped
state SHALL update an existing record and SHALL NOT create one.

This ordering is forced rather than chosen: every route other than `/api/v1/attest/*` requires a device
token, and a token is obtainable only by attesting — so no device can reach any other device-scoped write
before it has attested.

A route that requires the record and finds none SHALL respond `401`. This widens `401` from "no valid
token" to also mean "**the backend holds no attestation for this device**". The two are one answer to the
client because they have one remedy — attest afresh — and the shipped client already takes it: a `401`
drops the token, triggers a refresh, and re-sends what the refused request carried. Responding anything
else would require a client change, and a client that ignored the new status would lose the write
permanently.

The check SHALL NOT be performed by the token gate. Verifying a token touches no storage, and that is what
keeps the streaming byte-upload path free of a per-request round-trip; a route that needs the record reads
it itself, after the gate has passed.

#### Scenario: A device-scoped write with no attestation on file is refused

- **WHEN** a request bearing a valid token writes device-scoped state for a device the backend holds no
  attestation record for
- **THEN** the endpoint responds `401` and creates no record

#### Scenario: The client recovers without operator action

- **WHEN** the client receives that `401`
- **THEN** it discards its token, completes a fresh attestation — which creates the record — and re-sends
  the refused write, which then succeeds

#### Scenario: The record check is the route's, not the gate's

- **WHEN** a request reaches a route that requires the device's record
- **THEN** the gate has already admitted it on the token's signature alone, and the record is read by the
  route rather than by the gate — so a route that needs no record pays no read

### Requirement: The device-token lifetime is independent of the event lifetime

The device token's lifetime SHALL be configured independently of the event lifetime and the event window
maximum, and SHALL NOT be derived from either.

The three values may hold the same number, and today do. That agreement is a **coincidence**, not a fact:
the event lifetime and window are product rules the host's experience can argue up, while the token
lifetime bounds how long a backup-extracted token remains a usable write credential and therefore may
never be lengthened for a product reason. Deriving one from the other would make a decision to run longer
events silently a decision to widen that window.

#### Scenario: Lengthening the event lifetime does not lengthen the token

- **WHEN** a deployment raises the event lifetime or the event window maximum
- **THEN** the device-token lifetime is unchanged
