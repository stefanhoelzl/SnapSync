## MODIFIED Requirements

### Requirement: Stable per-install device id

The app SHALL carry a stable per-install device id: a UUID minted exactly once and persisted in the
shared Keychain access group, so that the app process and the upload extension read the **same**
value. The id SHALL be reinstall-stable — because the Keychain entry survives app uninstall, a
reinstalled device SHALL recover the same id rather than minting a new one. On first read with **no
persisted value**, the seam SHALL mint a fresh UUID, persist it to the shared Keychain, and return it;
every subsequent read SHALL return that persisted value verbatim, never re-minting.

"No persisted value" SHALL mean **exactly** that the Keychain reports the item as not found. A read
that fails for any other reason — notably because protected data is unavailable on a locked device —
SHALL NOT be treated as "no persisted value", SHALL NOT mint, and SHALL NOT write. It SHALL surface as
an unavailability error distinct from absence. A read error therefore SHALL NEVER change this device's
identity.

#### Scenario: First read mints and persists

- **WHEN** the device id is read and the shared Keychain reports no such item
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

#### Scenario: A read error never mints a new identity

- **WHEN** the device id is read and the Keychain returns a failure that is **not** item-not-found
  (e.g. protected data is unavailable)
- **THEN** no UUID is minted, no write is attempted, an unavailability error distinct from absence is
  surfaced, and this device's persisted id is left untouched

## ADDED Requirements

### Requirement: The device id is readable by background work on a locked device

The device id's Keychain item SHALL be stored with an accessibility class that permits reads while the
device is **locked**, once the device has been unlocked at least once since boot
(`kSecAttrAccessibleAfterFirstUnlock`). Background work — a `BGProcessingTask`, a silent push, a
background `URLSession` completion, and the OS-scheduled upload extension — runs while the device is
idle and therefore usually locked, and each such context resolves the device id.

The item SHALL NOT be restricted to the device (`…ThisDeviceOnly`): it SHALL remain restorable from an
encrypted backup, so that a restored device recovers the **same** id as the app container (ledger and
discovery cursor) that is restored alongside it. Decision record: `changes/archive/…-fix-locked-device-keychain-access`.

#### Scenario: A locked background wake resolves the device id

- **WHEN** background work resolves the device id while the device is locked, and the device has been
  unlocked at least once since boot
- **THEN** the persisted id is returned, nothing is minted, and no error is raised

#### Scenario: The id is restorable alongside the app container

- **WHEN** an encrypted backup is restored to a device
- **THEN** the restored device reads the same device id it had before, consistent with the ledger and
  discovery cursor restored with it, and therefore does not re-upload its already-stored resources

### Requirement: Accessibility migration preserves the device id

An item persisted by an earlier build under a weaker accessibility class SHALL be migrated in place on
the first successful read: its accessibility class SHALL be updated to the required one, and its
**value SHALL be preserved exactly**. Migration SHALL NOT mint, delete-and-re-add a different value, or
otherwise change the device's identity — a changed device id would orphan this device's byte-store
partition and its ledger.

Migration is required because the Keychain item survives app uninstall and the device id is written
exactly once, at mint: without an in-place migration an already-provisioned device would keep its
unreadable item indefinitely, and no reinstall or update would heal it.

#### Scenario: A legacy item is upgraded in place

- **WHEN** the device id is read successfully and the stored item's accessibility class is weaker than
  required
- **THEN** the item's accessibility class is updated in place, its value is unchanged, and the same id
  is returned

#### Scenario: A migrated device keeps its partition and ledger

- **WHEN** a device that was provisioned by an earlier build migrates its device-id item
- **THEN** the device id is byte-for-byte the same as before, so its `/files/devices/<deviceId>/`
  partition and ledger rows remain valid and nothing re-uploads

#### Scenario: An already-correct item is not rewritten

- **WHEN** the device id is read and the stored item already carries the required accessibility class
- **THEN** no write is performed
