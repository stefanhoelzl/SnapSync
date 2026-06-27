## MODIFIED Requirements

### Requirement: Config source and store seams

The capability SHALL define a `ConfigSource` state port exposing `config: StateFlow<EventConfigPayload?>`
— a level-triggered holder whose current value (the active payload, or `null` when none) is always
available synchronously — and a `ConfigStore` command port with `suspend fun save(config: EventConfigPayload)`
that persists the payload and updates the source, and `suspend fun clear()` that removes the persisted
payload and updates the source to `null`. `save` of a payload equal to the current one
SHALL be an idempotent no-op; `save` of a different payload SHALL replace it silently (the ledger
is not touched). `clear` SHALL remove the persisted payload and set the source to `null`; `clear`
when no payload is persisted SHALL be an idempotent no-op. `clear` SHALL NOT touch the ledger (the
caller orchestrates any ledger reset). Consumers SHALL depend on each port separately. Combining the
`EventConfigPayload` with the compile-time upload host and the device id into a full upload
destination is the responsibility of the consuming composition root, not these seams.

#### Scenario: Source seeds the current payload synchronously
- **WHEN** a `ConfigSource` implementation is constructed while a payload is already persisted
- **THEN** `config.value` immediately reflects the persisted `EventConfigPayload` without waiting for an emission

#### Scenario: Saving a new payload hot-swaps the source
- **WHEN** `save(newPayload)` is invoked with a payload different from the current one
- **THEN** the persisted payload is replaced and `config` emits `newPayload`, with no restart and no
  change to the ledger

#### Scenario: Saving an identical payload is a no-op
- **WHEN** `save(payload)` is invoked with a payload equal to the current value
- **THEN** no change and no redundant emission occur

#### Scenario: Clearing removes the payload and nulls the source
- **WHEN** `clear()` is invoked while a payload is persisted
- **THEN** the persisted payload is removed and `config` emits `null`, with no change to the ledger

#### Scenario: Clearing when already absent is a no-op
- **WHEN** `clear()` is invoked while no payload is persisted
- **THEN** no change and no redundant emission occur

### Requirement: iOS Keychain-backed config store

The capability SHALL provide an iOS adapter (`iosMain`) implementing both `ConfigSource` and
`ConfigStore` against the iOS Keychain. It SHALL store the serialized `EventConfigPayload` as a single
Keychain item under a **shared keychain-access-group** (paired with an App Group) so the
background upload extension can read the same event config. It SHALL seed its `config` `StateFlow`
**synchronously** at construction by reading the Keychain item (mapping a missing item to `null`),
and `save` SHALL write the Keychain item and then emit. `clear` SHALL delete the Keychain item and
then emit `null`; deleting an absent item SHALL be treated as success (no error). The item SHALL
persist across app updates and survive process death.

#### Scenario: Persisted payload survives relaunch
- **WHEN** a payload is saved, the app terminates, and the adapter is reconstructed on next launch
- **THEN** `config.value` immediately reflects the previously-saved `EventConfigPayload`

#### Scenario: No payload reads as null
- **WHEN** the adapter is constructed with no Keychain item present
- **THEN** `config.value` is `null`

#### Scenario: Cleared config does not survive relaunch
- **WHEN** a payload is saved, `clear()` is invoked, the app terminates, and the adapter is
  reconstructed on next launch
- **THEN** `config.value` is `null` (the Keychain item was deleted)
