# device-identity — delta for extract-adapter-modules

## REMOVED Requirements

### Requirement: Device-id access is a plain supplier; the Keychain implementation lives in `:domain:keychain`

**Reason**: Migration step 4 moved the Keychain implementation into the extension-safe adapter
module; the requirement is re-added below under its new placement (content otherwise unchanged).

## ADDED Requirements

### Requirement: Device-id access is a plain supplier; the Keychain implementation lives in `:adapter:ios:ext-safe`

Consumers of the device id (attestation, the join enrollment, and the composition roots) SHALL take
it as a plain `() -> String` supplier — there SHALL be no dedicated device-identity interface or
module. Tests SHALL inject a lambda returning a known id, with no Keychain access.

The Keychain-backed implementation (`KeychainDeviceIdentity`) SHALL live in `:adapter:ios:ext-safe`
— the only module permitted to touch `SecItem*` (capability `architecture-guards`; before migration
step 4 that module was `:domain:keychain`) — and SHALL be
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
- **THEN** it reads through a supplier backed by `KeychainDeviceIdentity` from
  `:adapter:ios:ext-safe`, constructed in the composition root, and no Keychain type appears in any
  consumer's constructor beyond the supplier

#### Scenario: The pinned pair survives the placement

- **WHEN** the runtime-identity guard scans production Kotlin
- **THEN** the pair (`app.snapsync.deviceid`, `deviceid`) is found exactly once, in
  `:adapter:ios:ext-safe`'s `KeychainDeviceIdentity`
