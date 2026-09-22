## ADDED Requirements

### Requirement: The manifest version orders the device's manifest snapshots

The ledger database SHALL hold a **manifest version**: one integer counter, in a one-row table of its own
(`manifestVersion`) beside `ledgerRow`, that advances on every change that could alter the device manifest's
projection (capability `device-manifest`). It is not a row field, and it is the one value the backend
maintains itself. The "no clocks of their own" rule for row fields does not reach it, because it is not a
field of any write and carries no time. It orders snapshots; it measures nothing.

It SHALL advance:

- on every **insert** into and every **delete** from `ledgerRow`, and on every **update** that changes a
  projected column — `key`, `assetId`, `creationDate`, `role`, `contentType`, `originalFilename` — by
  SQLite **triggers** on `ledgerRow`, so that no write path, present or future, can change the projection
  without advancing it. The update trigger SHALL compare old and new values, not fire on the column being
  named: the guarded record write names every detail column in its `SET` list, and a bump for an unchanged
  value would force a republish for nothing. A trigger SHALL NOT advance the counter with a conflict-resolving
  insert (`INSERT OR REPLACE`): inside a trigger SQLite applies the outer statement's conflict resolution, so
  one fired by the record upsert aborts that write. It SHALL NOT advance on a change to `state` or
  `destinationPath` alone: the manifest carries no upload state, and a bump per finished upload would force a
  republish per cycle;
- on an explicit **bump**, which the reconfigure save uses because the membership's policy bounds live
  outside this database (capability `reconfigure-membership`).

Every bump SHALL commit in the **same transaction** as the change that caused it. A reader that sees version N
therefore sees every change numbered up to N, which is what lets two publishes with the same version carry the
same snapshot.

The counter SHALL NEVER move backwards, and the reset family SHALL NOT reset it: `clear()` and `resetTo()`
remove only `ledgerRow` rows, and those deletes and inserts advance the counter like any other change. A join, a
leave, and a device reset therefore move it forward.

`LedgerStore` SHALL declare `manifestVersion(): Long`, a single read, and `bumpManifestVersion()`, which
advances it by one and signals no change (the ledger's rows did not change). Every `LedgerStore` implementation
SHALL satisfy the version scenarios in the shared `LedgerStoreContract`, including `:adapter:generic:fake`'s
in-memory store, which SHALL apply the same advance rules without triggers.

The migration that adds it (`11.sqm`, v11 → v12) SHALL be **row-preserving**: it creates the table and the
triggers, touching no `ledgerRow` row, so an update in place creates no upload job and changes no state. The
counter SHALL NOT depend on a seed row: an absent row reads as `0`, and the first advance creates it, so no
create route or migration can leave it stuck. The `CREATE` statements in `Ledger.sq` SHALL carry the same table
and triggers, and the migration verification (see "Migration verification is backed by a committed schema snapshot") SHALL prove the two
schemas identical. A future migration that drops a column a trigger names SHALL drop and recreate the trigger,
because SQLite refuses `DROP COLUMN` for a column a trigger references. Decision record:
`changes/manifest-versions`.

#### Scenario: A recorded row advances the version

- **WHEN** a new row is recorded, or a row is deleted by `deleteKeys`
- **THEN** `manifestVersion()` is greater than it was before the write

#### Scenario: A detail backfill advances the version

- **WHEN** `backfillManifestDetail` fills a bare row's capture date and role
- **THEN** `manifestVersion()` advances

#### Scenario: A terminal write does not advance the version

- **WHEN** `markTerminal` moves a row from `REQUESTED` to `COMPLETED`
- **THEN** `manifestVersion()` is unchanged

#### Scenario: A declined record write does not advance the version

- **WHEN** a record write is declined because the row is settled, or re-records an unsettled row with identical
  projected fields
- **THEN** `manifestVersion()` is unchanged

#### Scenario: The reset family advances and never resets the version

- **WHEN** the version is N and `clear()` or `resetTo(entries)` runs over a non-empty ledger
- **THEN** `manifestVersion()` is greater than N afterwards

#### Scenario: An explicit bump advances the version by one

- **WHEN** `bumpManifestVersion()` is called at version N
- **THEN** `manifestVersion()` is N + 1

#### Scenario: The migration preserves every row

- **WHEN** a v11 ledger with `COMPLETED` rows is migrated by `11.sqm`
- **THEN** every row survives unchanged, the version reads `0`, and no upload job is created
