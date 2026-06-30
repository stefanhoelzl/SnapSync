# device-identity Specification

## Purpose

The stable per-install device id: a UUID minted once and persisted in the shared Keychain access
group, read identically by the app process and the upload extension. It is the partition segment for
the device-global byte store (`/files/<deviceId>/…`) and the key for the per-event device manifest
(`/events/<eventId>/device/<deviceId>.json`). Exposed through the `DeviceIdentity` seam in a tested
module, with a settable fake for tests and a Keychain-backed implementation wired only in `:app:ios`.

## Requirements

### Requirement: Stable per-install device id

The app SHALL carry a stable per-install device id: a UUID minted exactly once and persisted in the
shared Keychain access group, so that the app process and the upload extension read the **same**
value. The id SHALL be reinstall-stable — because the Keychain entry survives app uninstall, a
reinstalled device SHALL recover the same id rather than minting a new one. On first read with no
persisted value, the seam SHALL mint a fresh UUID, persist it to the shared Keychain, and return it;
every subsequent read SHALL return that persisted value verbatim, never re-minting.

#### Scenario: First read mints and persists

- **WHEN** the device id is read and no value is present in the shared Keychain
- **THEN** a new UUID is minted, written to the shared Keychain access group, and returned

#### Scenario: Subsequent reads return the same id

- **WHEN** the device id is read again after it was minted
- **THEN** the same id is returned and no new UUID is minted

#### Scenario: A pre-seeded store returns its id verbatim

- **WHEN** the shared Keychain already holds a device id (e.g. surviving a reinstall)
- **THEN** that exact id is returned and nothing is minted or overwritten

#### Scenario: App and extension observe one id

- **WHEN** the app process and the upload extension each read the device id
- **THEN** both observe the same value, because both read the same shared Keychain access group

### Requirement: DeviceIdentity seam in a tested module

The device id SHALL be exposed through a `DeviceIdentity` seam defined in `commonMain` and placed in a
tested `domain`/`capability` module, with a settable fake usable from tests. The Keychain-backed
implementation SHALL be wired only in the `:app:ios` shell; no platform Keychain dependency SHALL leak
into the seam's contract. The seam SHALL be the single source consumers use to obtain the `/files/`
partition segment and the device manifest key.

#### Scenario: Tests drive a settable fake

- **WHEN** a test needs a known device id
- **THEN** it sets the id on the `DeviceIdentity` fake and the code under test reads that value, with
  no Keychain access

#### Scenario: Keychain impl wired only in the app shell

- **WHEN** the production app obtains the device id
- **THEN** it reads through the `DeviceIdentity` seam whose Keychain-backed implementation is wired in
  `:app:ios`, and no Keychain type appears in the seam's `commonMain` contract
