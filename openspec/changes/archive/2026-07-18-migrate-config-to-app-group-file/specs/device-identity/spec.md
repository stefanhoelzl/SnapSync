# device-identity — delta for migrate-config-to-app-group-file

## MODIFIED Requirements

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
`event-rejoin-reconciliation`). Nothing in the config migration, including the eventual deletion
of the config's written-through Keychain copy, applies to this item.

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
