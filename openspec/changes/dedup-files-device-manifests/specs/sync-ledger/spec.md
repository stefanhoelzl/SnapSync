## MODIFIED Requirements

### Requirement: Atomic baseline reset

`LedgerBackend.resetTo(entries)` SHALL replace the entire store with `entries` in a single atomic
transaction: either all prior rows are removed and all `entries` inserted, or — on failure or
interruption — the store is left unchanged (no partial replacement is ever observable). It SHALL emit
exactly one `changes` signal on success. Entries are stored verbatim (the caller supplies `state`);
`resetTo` performs no clock stamping of its own. On the SQLDelight backend it SHALL execute as one
transaction.

The atomic baseline reset (`resetTo`, the clear-then-seed primitive) SHALL **no longer be invoked by
rejoin reconciliation on an event switch**. Because the ledger key is the bare, event-independent
resource filename, a `COMPLETED` row stays valid across events; reconciliation therefore seeds
**additively** via plain per-row upserts (`put`) and never clears, so globally-valid `COMPLETED` rows
survive any event switch. The `resetTo` primitive remains defined on the backend (and atomic) for any
future caller, but is not called on a switch.

#### Scenario: Interrupted reset leaves the store unchanged
- **WHEN** a `resetTo` transaction fails partway (e.g. an insert errors)
- **THEN** the store retains exactly its pre-call rows and no `changes` signal claims a new baseline

#### Scenario: Reset to a non-empty baseline is observable as a whole
- **WHEN** `resetTo(entries)` succeeds over a previously empty store
- **THEN** `aggregates()` reflects all `entries` at once and `get` returns each supplied entry verbatim

#### Scenario: Reset baseline holds on the SQLDelight backend
- **WHEN** the reset scenarios run against the SQLDelight backend on a JVM sqlite driver
- **THEN** they pass unchanged (a single-transaction replacement, one change signal)

#### Scenario: An additive switch seed preserves prior COMPLETED rows
- **WHEN** the store holds `COMPLETED` rows from a prior event and reconciliation seeds the new event with plain `put` upserts (no `resetTo`, no `clear`)
- **THEN** every prior `COMPLETED` row is still present, the upserted rows are added or overwritten, and no row is removed by the seed

## ADDED Requirements

### Requirement: Event-independent key

The ledger key SHALL be the **bare resource filename** (`<assetId>-<role>.<ext>`), carrying no event
scoping. Because the key is event-independent, a `COMPLETED` row recorded while one event is
configured stays valid and continues to read as `COMPLETED` after the configured event changes — the
ledger neither records nor consults an event when keying, recording, or reading a row. This is what
lets cross-event dedup come purely from the reconcile seed source (an additive per-device seed)
without any ledger key change.

#### Scenario: A COMPLETED row stays valid after the configured event changes
- **WHEN** a resource is recorded `COMPLETED` under one event and the configured event later changes
- **THEN** `get`/`entry` for that bare filename still returns the `COMPLETED` row, unaffected by the event change

#### Scenario: The key carries no event scoping
- **WHEN** two configured events would reference the same resource
- **THEN** they resolve to the **same** ledger key (the bare filename), so a single `COMPLETED` row serves both
