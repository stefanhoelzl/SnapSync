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
