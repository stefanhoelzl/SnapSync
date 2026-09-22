## MODIFIED Requirements

### Requirement: SQLDelight backend

A SQLDelight-backed `LedgerStore` SHALL be provided in `:adapter:generic:app` commonMain (SQLDelight
package `app.snapsync.engine.db`; moved from `:domain:engine` at migration step 4, whose module
died at step 10) with the schema
`key TEXT PRIMARY KEY, assetId TEXT NOT NULL, state TEXT NOT NULL`, the four manifest-detail columns
(`creationDate`, `role`, `contentType`, `originalFilename`, each `TEXT NOT NULL DEFAULT ''`), and the nullable
`destinationPath TEXT`,
plus an index on `assetId` (backing the `assetId`-grouped aggregate) and an index on `destinationPath`. The
schema carries **no** `attempt`, `eventId` or `absent` column: `10.sqm` dropped them (see "Ledger schema
migration"). `state`
SHALL be a SQLDelight typed column (`AS LedgerState` via the built-in enum adapter); adapter wiring
SHALL be hidden in a single factory function so construction sites never see it. The schema carries
no timestamp column. Each manifest-detail column's `DEFAULT ''` SHALL be present in **both** the migration
and the CREATE statement (the SQLDelight migration-verify task proves the two schemas identical). The record write SHALL be a single guarded SQL upsert statement
whose applied/not-applied answer is read inside that statement's own transaction, like `markTerminal`'s;
`resetTo` SHALL insert with a plain `INSERT` inside its delete-all transaction;
`recordAllUnlessSettled` SHALL apply its entries through the same guarded statement inside **one**
transaction; `deleteKeys` SHALL delete by primary key in chunks below every driver's bind-variable limit;
`aggregates()` SHALL be a single
SQL round-trip (an `assetId`-grouped query). Every `LedgerStore` implementation SHALL satisfy the
shared `LedgerStoreContract` (hosted in `:test:contracts`, capability `port-contracts`): the JVM/sqlite
and native (simulator) driver bindings live in `:adapter:generic:app`'s test source sets, and
`:adapter:generic:fake`'s honest `InMemoryLedgerStore` — the store the world harness runs on — is bound
from `:adapter:generic:fake`'s own tests. Every other `LedgerStore` test double SHALL honour the record guard and
`deleteKeys` the same way, so no test passes against a store that does something the device does not. The
native (iOS) driver is wired by `:adapter:ios:ext-safe`'s
factory over the App-Group container.

#### Scenario: Backend contract holds on SQLite
- **WHEN** the storage-seam, aggregate, and change-signal scenarios run against the SQLDelight
  backend on a JVM sqlite driver
- **THEN** they pass unchanged

#### Scenario: Every backend satisfies one contract
- **WHEN** the shared `LedgerStoreContract` scenarios run
- **THEN** they pass unchanged against the SQLDelight store (JVM and native drivers) and against
  `:adapter:generic:fake`'s in-memory store

#### Scenario: The schema carries no retired column
- **WHEN** the columns of `ledgerRow` are listed on a database created fresh or migrated to the current schema
- **THEN** there is no `attempt`, `eventId` or `absent` column
