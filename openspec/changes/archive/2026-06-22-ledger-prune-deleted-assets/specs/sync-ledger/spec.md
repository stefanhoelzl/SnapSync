## MODIFIED Requirements

### Requirement: Storage seam — dumb row store
The ledger SHALL access storage exclusively through a `LedgerBackend` interface with the row
operations `get(key): LedgerEntry?` and `put(entry)` (a single-row upsert), the aggregate read
`aggregates(): LedgerAggregates`, a change signal `changes: Flow<Unit>`, `clear()` — a
delete-all reset, and two key-targeted bulk removals: `deleteByKeyPrefix(prefix)` — delete every
row whose key begins with `prefix` — and `retainKeys(keep)` — delete every row whose key is not
in the `keep` set. Backends SHALL store entries verbatim (no interpretation, no precedence logic,
last write wins, no clocks of their own). A `LedgerEntry` SHALL carry `key`, `state` (`REQUESTED` |
`COMPLETED` | `FAILED`), `attempt`, `version`, and `updatedAt: Instant`. `clear()`,
`deleteByKeyPrefix`, and `retainKeys` SHALL each remove the matching rows and signal `changes`
like a `put` (so watchers re-read the now-current truth). The bulk removals operate purely on the
key string — the backend interprets no structure within a key — so the ledger remains
platform-neutral (any asset/resource grouping a key encodes is the caller's convention, unknown
to the seam).

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

#### Scenario: Prefix delete removes only matching rows and signals
- **WHEN** the store holds keys `A-photo.jpg`, `A-video.mov`, and `B-photo.jpg`, and
  `deleteByKeyPrefix("A-")` is called
- **THEN** `get("A-photo.jpg")` and `get("A-video.mov")` return null, `get("B-photo.jpg")` is
  unchanged, and a `changes` signal is emitted

#### Scenario: Retain keys removes the complement and signals
- **WHEN** the store holds keys `A-photo.jpg`, `B-photo.jpg`, and `C-photo.jpg`, and
  `retainKeys({"A-photo.jpg", "C-photo.jpg"})` is called
- **THEN** `get("B-photo.jpg")` returns null, the two retained keys are unchanged, and a `changes`
  signal is emitted

#### Scenario: Retain with empty set empties the store
- **WHEN** `retainKeys(emptySet)` is called on a store holding rows
- **THEN** every subsequent `get` returns null and a `changes` signal is emitted

## ADDED Requirements

### Requirement: Prune operations are writer-only
The two bulk removals SHALL be exposed on `LedgerWriter` (delegating to the backend), and SHALL
NOT be reachable through `LedgerReader`. They are sync writes by the single ledger writer, not
the app-side `clear()` reset; unlike the record operations they neither stamp `updatedAt` nor
depend on a prior read. Granting read-only access by handing out the writer typed as
`LedgerReader` SHALL therefore deny prune access at compile time, preserving the single-writer
invariant.

#### Scenario: Writer prunes by prefix
- **WHEN** a `LedgerWriter` records `X-photo.jpg` and then calls `deleteByKeyPrefix("X-")`
- **THEN** `entry("X-photo.jpg")` returns null

#### Scenario: Writer retains a key set
- **WHEN** a `LedgerWriter` holds keys `X-photo.jpg` and `Y-photo.jpg` and calls
  `retainKeys({"X-photo.jpg"})`
- **THEN** `entry("Y-photo.jpg")` returns null and `entry("X-photo.jpg")` is unchanged

#### Scenario: Reader-typed access cannot prune
- **WHEN** a component receives the ledger typed as `LedgerReader`
- **THEN** neither `deleteByKeyPrefix` nor `retainKeys` is available to it at compile time

### Requirement: Prune operations hold on the SQLDelight backend
The SQLDelight-backed `LedgerBackend` SHALL implement `deleteByKeyPrefix` and `retainKeys`
without a schema change (additive query statements over the existing table). `deleteByKeyPrefix`
SHALL match on the key's leading substring using the existing `TEXT PRIMARY KEY`. `retainKeys`
SHALL delete the complement of the supplied set without relying on an unbounded SQL `IN`/`NOT IN`
parameter list (so a multi-thousand-row library does not exceed the driver's bind-variable
limit). The storage-seam scenarios for both operations SHALL pass against the SQLDelight backend
on the JVM sqlite driver via the shared backend contract.

#### Scenario: Backend prune contract holds on SQLite
- **WHEN** the prefix-delete and retain-keys storage-seam scenarios run against the SQLDelight
  backend on a JVM sqlite driver
- **THEN** they pass unchanged

#### Scenario: Retain keys over a large store stays within bind limits
- **WHEN** `retainKeys` is called on the SQLDelight backend with a keep-set larger than the
  driver's single-statement bind-variable limit
- **THEN** the complement is deleted with no bind-variable error
