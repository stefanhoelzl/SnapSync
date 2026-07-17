# device-identity — delta for delete-dead-weight

## REMOVED Requirements

### Requirement: DeviceIdentity seam in a tested module

**Reason**: The `DeviceIdentity` interface and `FixedDeviceIdentity` fake carried a one-method
ceremony with exactly one production implementation. Migration step 1 deletes the interface: use
sites take the device id as a plain `() -> String` supplier and tests inject a lambda — replaced by
the placement requirement below.

## ADDED Requirements

### Requirement: Device-id access is a plain supplier; the Keychain implementation lives in `:domain:keychain`

Consumers of the device id (attestation, the join enrollment, and the composition roots) SHALL take
it as a plain `() -> String` supplier — there SHALL be no dedicated device-identity interface or
module. Tests SHALL inject a lambda returning a known id, with no Keychain access.

The Keychain-backed implementation (`KeychainDeviceIdentity`) SHALL live in `:domain:keychain` —
the only module permitted to touch `SecItem*` (capability `architecture-guards`) — and SHALL be
constructed only in the iOS composition roots. It SHALL keep the pinned Keychain identity pair
(`service = "app.snapsync.deviceid"`, `account = "deviceid"`) byte-identical and single-sited (the
runtime-identity pin guard asserts the pair appears exactly once in production Kotlin), and SHALL
resolve at most once per instance, caching the id for the process lifetime.

#### Scenario: Tests drive a plain lambda

- **WHEN** a test needs a known device id
- **THEN** it injects `{ "<id>" }` as the supplier and the code under test reads that value, with
  no Keychain access

#### Scenario: Keychain impl wired only in the composition roots

- **WHEN** the production app or the upload extension obtains the device id
- **THEN** it reads through a supplier backed by `KeychainDeviceIdentity` from `:domain:keychain`,
  constructed in the composition root, and no Keychain type appears in any consumer's constructor
  beyond the supplier

#### Scenario: The pinned pair survives the placement

- **WHEN** the runtime-identity guard scans production Kotlin
- **THEN** the pair (`app.snapsync.deviceid`, `deviceid`) is found exactly once, in
  `:domain:keychain`'s `KeychainDeviceIdentity`
