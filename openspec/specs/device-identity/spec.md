# device-identity Specification

## Purpose

The stable per-install device id: a UUID minted once and persisted in the shared Keychain access
group, read identically by the app process and the upload extension. It is the partition segment for
the device-global byte store (capability `bunny-upload-endpoint`) and the key for the per-event device
manifest (`/events/<eventId>/devices/<deviceId>.json`). Exposed to consumers as a plain `() -> String`
supplier (tests inject a lambda), backed by `KeychainDeviceIdentity` in `:domain:keychain` and wired
only in the iOS composition roots.
## Requirements
### Requirement: Stable per-install device id

The app SHALL carry a stable per-install device id: a UUID minted exactly once and persisted in the
shared Keychain access group, so that the app process and the upload extension read the **same**
value. The id SHALL be reinstall-stable — because the Keychain entry survives app uninstall, a
reinstalled device SHALL recover the same id rather than minting a new one. On first read with **no
persisted value**, the seam SHALL mint a fresh UUID, persist it to the shared Keychain, and return it;
every subsequent read SHALL return that persisted value verbatim, never re-minting.

The device id SHALL **remain** a Keychain item as the event config migrates to an App-Group file
(migration step 11a, capability `event-link`): the two stores diverge deliberately, because their
reinstall contracts are opposite. Identity must survive uninstall — a forked id orphans the
device's byte-store partition and corrupts the event union for every member, remotely unfixably —
while the membership's decided end state is reinstall = **left** (capability
`event-rejoin-reconciliation` — staged: the read-only fallback lasts until a post-ship change).
Nothing in the config migration — the write-through's end, the read-only fallback, or its
eventual deletion — applies to this item: the device id stays a Keychain item precisely so it
survives reinstall.

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

### Requirement: The device id is readable by background work on a locked device

The device id's Keychain item SHALL be stored with an accessibility class that permits reads while the
device is **locked**, once the device has been unlocked at least once since boot
(`kSecAttrAccessibleAfterFirstUnlock`). Background work — a `BGProcessingTask`, a silent push, a
background `URLSession` completion, and the OS-scheduled upload extension — runs while the device is
idle and therefore usually locked, and each such context resolves the device id.

The item SHALL NOT be restricted to the device (`…ThisDeviceOnly`): it SHALL remain restorable from an
encrypted backup, so that a restored device recovers the **same** id as the app container (ledger and
discovery cursor) that is restored alongside it. Decision record:
`changes/archive/2026-07-14-fix-locked-device-keychain-access`.

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

