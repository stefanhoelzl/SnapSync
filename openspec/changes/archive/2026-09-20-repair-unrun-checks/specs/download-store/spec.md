## ADDED Requirements

### Requirement: The download schema carries a committed snapshot, and one known divergence

The download database SHALL commit a **schema snapshot** beside its `.sq` and migrations, and its
migration chain SHALL be verified against the created schema by the same executing check the ledger's is
(capability `sync-ledger`, "Migration verification is backed by a committed schema snapshot", which
carries the mechanism and why a stale snapshot is not a defect). Before that snapshot existed the check
ran and compared nothing.

The snapshot is seeded at the schema version current when it is committed, and it is generated **from the
`CREATE` statements**. One consequence SHALL be recorded rather than silently carried: a divergence that
already exists between the migration chain and the `CREATE` statements is therefore neither detected by
the check nor repaired by it.

There is exactly one such divergence, and it SHALL be left as it is. The migration adding
`downloadAsset.creationDate` declares `DEFAULT ''` where the `CREATE` statement declares the column
`NOT NULL` with no default, and the migrated column lands in a different ordinal position. **Neither is
observable**: every statement names its columns, so position never leaks, and the column is `NOT NULL`
with every insert supplying it, so the default is never applied. Making them agree requires **rebuilding
the table** — SQLite can neither reorder a column nor alter a default in place — which would copy real
suppression rows to erase a difference nothing can read. Those rows are permanent by contract
("Handle-carrying rows are permanent"), which is what makes the copy the larger risk.

A future migration SHALL NOT be written on the assumption that the chain and the `CREATE` statements
agree about this column.

#### Scenario: A future download migration is verified

- **WHEN** a migration is added to the download database and the chain no longer produces the created
  schema
- **THEN** the verification task fails, reporting the difference

#### Scenario: The known divergence is not repaired

- **WHEN** the migration chain is replayed and compared with the `CREATE` statements by hand
- **THEN** `downloadAsset.creationDate` differs in default and ordinal position, and this is the recorded,
  accepted state rather than a defect to fix

#### Scenario: The divergence reaches no reader

- **WHEN** rows are written to and read from `downloadAsset` on a migrated database and on a created one
- **THEN** both behave identically, because every statement names its columns and every insert supplies
  `creationDate`
