## MODIFIED Requirements

### Requirement: Storage seam — dumb row store
The ledger SHALL access storage exclusively through a `LedgerBackend` interface with the row
operations `get(key): LedgerEntry?` and `put(entry)` (a single-row upsert), the aggregate read
`aggregates(): LedgerAggregates`, a change signal `changes: Flow<Unit>`, and `clear()` — a
delete-all reset. Backends SHALL store entries verbatim (no interpretation, no precedence logic,
last write wins, no clocks of their own). A `LedgerEntry` SHALL carry `key`, `state` (`REQUESTED` |
`COMPLETED` | `FAILED`), `attempt`, `version`, and `updatedAt: Instant`. `clear()` SHALL remove every
row and signal `changes` like a `put` (so watchers re-read the now-empty truth); it is a deliberate
reset (e.g. the app re-provisioning config), distinct from the engine's record writes.

#### Scenario: Put then get round-trips
- **WHEN** `put(entry)` is called and then `get(entry.key)`
- **THEN** the returned entry equals the one put, field for field — including `updatedAt`

#### Scenario: Put overwrites unconditionally
- **WHEN** `put` is called twice for the same key with different states
- **THEN** `get` returns the second entry — the backend applies no precedence of its own

#### Scenario: Unknown key reads null
- **WHEN** `get` is called for a key never put
- **THEN** it returns null

#### Scenario: Clear empties the store and signals
- **WHEN** `clear()` is called on a store holding rows
- **THEN** every subsequent `get` returns null, `aggregates()` reports zero pending and completed,
  and a `changes` signal is emitted
