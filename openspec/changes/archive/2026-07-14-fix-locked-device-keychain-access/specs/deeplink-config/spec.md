## MODIFIED Requirements

### Requirement: iOS Keychain-backed config store

The capability SHALL provide an iOS adapter (`iosMain`) implementing both `ConfigSource` and
`ConfigStore` against the iOS Keychain. It SHALL store the serialized `EventConfig` (its `eventId`,
`name`, its **required, non-null** `minPhotoDate`, its `direction`, and its `saveToAlbum`) as a single
Keychain item under a **shared keychain-access-group** (paired with an App Group) so the background
upload extension can read the same event config — the extension reads the `eventId`, the `minPhotoDate`
(the cutoff that scopes its upload cycle, capability `photo-date-cutoff`), **and the `saveToAlbum` flag**
(to decide whether to add completed uploads to the event album, capability `event-album`). It SHALL seed
its `config` `StateFlow` **synchronously** at construction by reading the Keychain item (mapping a missing
item to `null`), and `save` SHALL write the item and then emit. `clear` SHALL delete the item and then
emit `null`; deleting an absent item SHALL be treated as success. Deserialization SHALL ignore unknown
keys, so an item written before the `saveToAlbum`/`direction` fields existed decodes with
`saveToAlbum = false` and `direction = Both`, and an item lacking a `name` decodes to an empty non-null
name.

The Keychain item SHALL be stored with an accessibility class that permits reads while the device is
**locked**, once the device has been unlocked at least once since boot
(`kSecAttrAccessibleAfterFirstUnlock`), because the upload extension is invoked by the OS while the
device is idle — and therefore usually locked — and reads this item on every cycle. An item persisted by
an earlier build under a weaker accessibility class SHALL be migrated in place on the first successful
read, preserving its value exactly.

An item lacking `minPhotoDate` SHALL **fail to decode and read as no config** (`config.value == null`),
rather than decoding to any default. No default cutoff SHALL be substituted at decode time: the store
seeds synchronously and cannot consult the event's `createdAt`, and the empty string is not a legal cutoff
(capability `photo-date-cutoff`, *Cutoff string format invariant*). The decode failure SHALL be logged.
The device therefore presents the setup gate and the user re-scans the invite; neither the app nor the
extension uploads while no config is readable. The item SHALL persist across app updates and survive
process death.

#### Scenario: Persisted config survives relaunch
- **WHEN** a config is saved, the app terminates, and the adapter is reconstructed on next launch
- **THEN** `config.value` immediately reflects the previously-saved `EventConfig` (eventId, name, minPhotoDate, direction, and saveToAlbum)

#### Scenario: The extension reads the persisted album flag
- **WHEN** the background upload extension reads the shared Keychain config
- **THEN** it obtains the `eventId`, the persisted `minPhotoDate` cutoff, and the `saveToAlbum` flag for scoping its upload cycle and album placement

#### Scenario: The extension reads the config on a locked device
- **WHEN** the OS invokes the upload extension while the device is locked, and the device has been
  unlocked at least once since boot
- **THEN** the Keychain item is read successfully and the cycle proceeds with the persisted config

#### Scenario: A legacy item is upgraded in place
- **WHEN** the adapter reads a Keychain item whose accessibility class is weaker than required
- **THEN** the item's accessibility class is updated in place and its stored config value is unchanged

#### Scenario: A legacy item without the new fields decodes to defaults
- **WHEN** the adapter reads a Keychain item serialized before the `saveToAlbum` field existed
- **THEN** the decoded `EventConfig` has `saveToAlbum = false` (and `direction = Both`, and a non-null name) and no error is raised

#### Scenario: A legacy item without a cutoff reads as no config
- **WHEN** the adapter reads a Keychain item serialized before `minPhotoDate` existed, in either the app
  or the extension process
- **THEN** the decode fails, the failure is logged, `config.value` is `null`, no default cutoff is
  substituted, and no upload occurs until the user re-joins

#### Scenario: No config reads as null
- **WHEN** the adapter is constructed with no Keychain item present
- **THEN** `config.value` is `null`

#### Scenario: Cleared config does not survive relaunch
- **WHEN** a config is saved, `clear()` is invoked, the app terminates, and the adapter is
  reconstructed on next launch
- **THEN** `config.value` is `null` (the Keychain item was deleted)

## ADDED Requirements

### Requirement: An unreadable config is not an absent config

The config seam SHALL distinguish three outcomes: a **readable** config, a **definitely absent** config
(the Keychain reports no such item), and an **unreadable** config (the read failed for any other reason,
notably because protected data is unavailable on a locked device). An unreadable config SHALL NOT be
reported as an absent config.

A reader that acts on the absence of a config — in particular the extension's re-join reconciliation,
for which "no event configured" means *the device left the event* and triggers clearing the persisted
`joinedEventId` marker (capability `event-rejoin-reconciliation`) — SHALL act **only** on a definitely
absent config. On an unreadable config the extension SHALL skip its cycle entirely: it SHALL NOT
reconcile, SHALL NOT clear the join marker, SHALL NOT reset the discovery cursor, and SHALL NOT create
upload jobs; the invocation SHALL complete cleanly and the next cycle SHALL retry.

Conflating the two is what makes an ordinary locked-device wake perform a *false leave*: the marker is
cleared, and the next readable cycle sees a marker mismatch and pays for a full re-join reconciliation
(a device listing, an atomic ledger clear-and-seed, and a discovery-cursor reset that forces a complete
library re-enumeration) — repeatedly, without the marker ever settling.

#### Scenario: An unreadable config does not clear the join marker
- **WHEN** the extension's cycle reads the config and the read fails because protected data is
  unavailable
- **THEN** the cycle is skipped, the reconciliation is not invoked, the persisted `joinedEventId` marker
  is left intact, the discovery cursor is not reset, and the invocation completes cleanly

#### Scenario: A definitely-absent config still drives the leave path
- **WHEN** the extension's cycle reads the config and the Keychain reports no such item
- **THEN** the reconciliation runs for the no-config case and clears the `joinedEventId` marker, exactly
  as a leave requires

#### Scenario: A joined device stays settled across locked wakes
- **WHEN** a joined device is invoked repeatedly by the OS while locked and its config is unreadable
- **THEN** its join marker still matches its configured event on the next readable cycle, so no re-join
  reconciliation, ledger re-seed, or full re-enumeration is performed
