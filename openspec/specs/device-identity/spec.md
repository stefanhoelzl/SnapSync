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

The shared access group SHALL be addressed **explicitly** — named on every operation against the
device-id item (read, write, delete, accessibility migration) — and SHALL NOT be relied upon as a
default. Relying on the default places the item in whichever group the platform selects **at write
time**, which is a function of the signing entitlements in force for that build rather than of this
contract. Two processes can then each read a *different* item and both succeed, which is precisely
how the requirement above ("both read the same value") was violated in the field while every read
reported success: on 2026-07-20 the app held one id and the upload extension another, across four
events, with neither process minting.

Resolution SHALL follow this order, and the order is normative:

1. The item is **unreadable** → surface an unavailability error. Never mint, never write.
2. The shared group holds the item → return its value verbatim.
3. The shared group reports **not found** → consult the unscoped search, which spans every access
   group the process is entitled to.
   1. A value is found outside the shared group → **adopt** it: persist that exact value into the
      shared group and return it. It SHALL NOT be re-minted, and the out-of-group item SHALL NOT be
      deleted, so a rollback finds it intact.
   2. Nothing is found anywhere → mint, persist to the shared group, and return.

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

- **WHEN** the device id is read, the shared group reports no such item, and no item exists in any
  other entitled access group
- **THEN** a new UUID is minted, written to the shared Keychain access group, and returned

#### Scenario: Subsequent reads return the same id

- **WHEN** the device id is read again after it was minted
- **THEN** the same id is returned and no new UUID is minted

#### Scenario: A pre-seeded store returns its id verbatim

- **WHEN** the shared Keychain already holds a device id (e.g. surviving a reinstall)
- **THEN** that exact id is returned and nothing is minted or overwritten

#### Scenario: App and extension observe one id

- **WHEN** the app process and the upload extension each read the device id
- **THEN** both observe the same value, because both name the same shared Keychain access group
  explicitly rather than resolving it by default

#### Scenario: An id placed outside the shared group is adopted, not re-minted

- **WHEN** the shared group reports no device-id item but an id exists in another access group the
  process is entitled to
- **THEN** that exact value is persisted into the shared group and returned, no UUID is minted, and
  the out-of-group item is left in place

#### Scenario: A read error never mints a new identity

- **WHEN** the device id is read and the Keychain returns a failure that is **not** item-not-found
  (e.g. protected data is unavailable)
- **THEN** no UUID is minted, no write is attempted, an unavailability error distinct from absence is
  surfaced, and this device's persisted id is left untouched

#### Scenario: Unavailability outranks absence and adoption

- **WHEN** the shared-group read fails with an unavailability error rather than item-not-found
- **THEN** resolution stops there — the unscoped search is NOT consulted, no value is adopted, and
  nothing is minted

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
constructed only in the iOS composition roots. It SHALL keep the pinned Keychain identity triple
(`service = "app.snapsync.deviceid"`, `account = "deviceid"`, and the shared access group)
byte-identical and single-sited (the runtime-identity pin guard asserts each appears exactly once in
production Kotlin), and SHALL resolve at most once per instance, caching the id for the process
lifetime.

The access group is part of the item's identity, not an implementation detail: changing it strands
the installed base exactly as changing the service or account would, because the item it names is
the one the field already holds.

#### Scenario: Tests drive a plain lambda

- **WHEN** a test needs a known device id
- **THEN** it injects `{ "<id>" }` as the supplier and the code under test reads that value, with
  no Keychain access

#### Scenario: Keychain impl wired only in the composition roots

- **WHEN** the production app or the upload extension obtains the device id
- **THEN** it reads through a supplier backed by `KeychainDeviceIdentity` from
  `:adapter:ios:ext-safe`, constructed in the composition root, and no Keychain type appears in any
  consumer's constructor beyond the supplier

#### Scenario: The pinned triple survives the placement

- **WHEN** the runtime-identity guard scans production Kotlin
- **THEN** the service/account pair (`app.snapsync.deviceid`, `deviceid`) and the shared access-group
  literal are each found exactly once, in `:adapter:ios:ext-safe`

### Requirement: Only the app process mints a device identity

Minting SHALL be confined to the app process. The upload extension SHALL NOT mint an identity, and
SHALL NOT adopt a value found outside the shared access group — it reads the shared group and nothing
else.

Both prohibitions exist because the extension cannot distinguish "this device has no identity yet"
from "the app's identity is not visible from here". Acting on that ambiguity is what produces a
second identity, and a second identity is unrecoverable: it orphans the
`/files/devices/<deviceId>/` byte partition and makes the device's own uploads read as another
member's, so the device re-downloads and re-imports every photo it contributed.

When the extension finds no id in the shared group it SHALL skip its cycle without creating upload
jobs, and SHALL log the skip. Uploads resume on the next cycle after the app has resolved the
identity, which the app does on every launch.

#### Scenario: The extension defers rather than minting

- **WHEN** the upload extension resolves the device id and the shared access group reports no item
- **THEN** no UUID is minted, no adoption is attempted, the cycle is skipped with no upload jobs
  created, and the skip is logged

#### Scenario: The extension never adopts an out-of-group id

- **WHEN** the upload extension finds no id in the shared group while an id exists in an access group
  it is entitled to but which the app cannot read
- **THEN** that value is NOT adopted and NOT returned; the cycle is skipped

#### Scenario: Uploads resume once the app has resolved the identity

- **WHEN** the app resolves (mints or adopts) the device id into the shared group, and the extension
  is next invoked
- **THEN** the extension reads that id and proceeds to create upload jobs normally

### Requirement: The resolved device identity is observable

Each process SHALL log, once per process lifetime, the device id it resolved and **how** it was
obtained — read from the shared group, adopted from another group, or minted. The value SHALL be
logged verbatim to the device diagnostic log (capability `diagnostic-logging`), which is the
un-redacted channel.

A divergence between the two processes is otherwise undetectable from outside: every read reports
success, the status screen shows only an indefinite "pending", and the duplication surfaces in the
user's photo library with nothing tying it to identity. The field incident ran for nine hours before
the ids were known, because neither process logged one.

A `minted` outcome in the extension, or two processes reporting different ids, SHALL be treated as a
fault rather than as normal operation.

#### Scenario: Both processes report their identity

- **WHEN** the app and the upload extension each resolve the device id
- **THEN** each writes one line to its own diagnostic log carrying the resolved id verbatim and
  whether it was read, adopted, or minted

#### Scenario: A divergence is visible without instrumenting a build

- **WHEN** the app and the extension resolve different ids
- **THEN** the two diagnostic logs show different values for the same field, and the operator can
  detect the split by reading them
