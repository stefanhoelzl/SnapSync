# sync-ledger — delta for collapse-harness-onto-shared-composition

## MODIFIED Requirements

### Requirement: SQLDelight backend

A SQLDelight-backed `LedgerStore` SHALL be provided in `:adapter:generic` commonMain (SQLDelight
package `app.snapsync.engine.db`; moved from `:domain:engine` at migration step 4, whose module
died at step 10) with the schema
`key TEXT PRIMARY KEY, assetId TEXT NOT NULL, state TEXT NOT NULL, attempt INTEGER NOT NULL`
plus an index on `assetId` (backing `deleteByAssetId` and the `assetId`-grouped aggregate). `state`
SHALL be a SQLDelight typed column (`AS LedgerState` via the built-in enum adapter); adapter wiring
SHALL be hidden in a single factory function so construction sites never see it. The schema carries
no timestamp column. `put` SHALL be a single SQL upsert statement; `aggregates()` SHALL be a single
SQL round-trip (an `assetId`-grouped query). Every `LedgerStore` implementation SHALL satisfy the
shared `LedgerStoreContract` (hosted in `:test:world` commonMain since step 10): the JVM/sqlite and
native (simulator) driver tests extend it from `:adapter:generic`'s test source sets, and
`:adapter:fake`'s honest `InMemoryLedgerStore` — the store the world harness runs on — extends it
from `:test:world`'s own tests. The native (iOS) driver is wired by `:adapter:ios:ext-safe`'s
factory over the App-Group container.

#### Scenario: Backend contract holds on SQLite
- **WHEN** the storage-seam, aggregate, and change-signal scenarios run against the SQLDelight
  backend on a JVM sqlite driver
- **THEN** they pass unchanged

#### Scenario: Every backend satisfies one contract
- **WHEN** the shared `LedgerStoreContract` scenarios run
- **THEN** they pass unchanged against the SQLDelight store (JVM and native drivers) and against
  `:adapter:fake`'s in-memory store
