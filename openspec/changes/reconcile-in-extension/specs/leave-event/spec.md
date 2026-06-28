## MODIFIED Requirements

### Requirement: Leave use-case resets local event state

The capability SHALL provide a `LeaveEvent` use-case that tears down the configured event's **local**
state, best-effort, in this order: (1) **disable** the background-upload producer, then (2) **clear the
persisted config** (`ConfigStore.clear()`). The use-case SHALL **not** touch the ledger, the discovery
cursor, or any `EventStatus`: with the producer's reconciliation in the extension (see
`event-rejoin-reconciliation`), the extension resets its private ledger, cursor, and `joinedEventId`
marker on its next join (a configured `eventId` that no longer matches the marker, or a later provision of
a different event). The producer is disabled **before** the config is cleared so no producer work races
the teardown. The platform side-effect — disabling the producer — SHALL be injected as a suspend lambda,
so the use-case is pure `commonMain` logic and the app shell stays wiring-only. The use-case SHALL
construct **no** ledger type.

#### Scenario: Leaving disables the producer and clears config

- **WHEN** `LeaveEvent` runs with an event configured
- **THEN** the producer is disabled first, then the config is cleared, and no ledger or `EventStatus` operation is performed

#### Scenario: After leaving, the gate yields the setup screen

- **WHEN** a leave completes
- **THEN** `ConfigSource.config` is `null` and the presentation reduces to the setup gate (storage not connected)

### Requirement: Leave is best-effort with no rollback

A failing step SHALL be logged and SHALL NOT roll back earlier steps; there is no transaction across the
producer registration and the Keychain. The step order — disable producer, then clear config — SHALL be
chosen so the worst partial outcome self-heals: if the config clear fails, the event remains configured
(the user is simply still joined, with the producer disabled until the next enable), rather than leaving a
half-torn-down state. A stale private ledger left in the extension is reset on the next join via the
`joinedEventId` mismatch, not at leave time.

#### Scenario: A failed config clear leaves the user joined, not corrupted

- **WHEN** the producer has been disabled but `ConfigStore.clear()` fails
- **THEN** the event is still configured and consistent; re-running leave retries the clear, and no ledger corruption can occur because leave never touched the ledger
