## MODIFIED Requirements

### Requirement: Config source and store seams

The capability SHALL define a `ConfigSource` state port exposing `config: StateFlow<EventConfigPayload?>`
— a level-triggered holder whose current value (the active payload, or `null` when none) is always
available synchronously — and a `ConfigStore` command port `suspend fun save(config: EventConfigPayload)`
that persists the payload and updates the source. `save` of a payload equal to the current one
SHALL be an idempotent no-op; `save` of a different payload SHALL replace it silently (the ledger
is not touched). Consumers SHALL depend on each port separately. Combining the `EventConfigPayload`
with the compile-time upload host into a full upload destination is the responsibility of the
consuming composition root, not these seams.

#### Scenario: Source seeds the current payload synchronously
- **WHEN** a `ConfigSource` implementation is constructed while a payload is already persisted
- **THEN** `config.value` immediately reflects the persisted `EventConfigPayload` without waiting for an emission

#### Scenario: Saving a new payload hot-swaps the source
- **WHEN** `save(newPayload)` is invoked with a payload different from the current one
- **THEN** the persisted payload is replaced and `config` emits `newPayload`, with no restart and no
  change to the ledger
