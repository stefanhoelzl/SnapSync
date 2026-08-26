## ADDED Requirements

### Requirement: A migration migrates its data; it does not drop it

A migration that replaces a table SHALL carry that table's rows into its replacement. Dropping a table
whose contents another program is responsible for saving SHALL NOT be a migration's behaviour, however
that program is scheduled.

This is stated because SQLite makes the wrong shape the easy one: a column's constraints cannot be altered
in place, so any change to them forces a create-new / drop-old rebuild, and the copy in the middle is the
step it is possible to simply not write. The result reads as a schema change and behaves as a deletion.

Where a migration **narrows** a constraint it cannot carry every row by construction — only rows that
already satisfy the narrower shape qualify. Such a migration SHALL declare a **precondition** that refuses
the migration when any row does not, and SHALL NOT proceed by discarding those rows. The refusal SHALL
leave the store on the previous version, so the failure is fail-closed: nothing is half-applied, the
deployment that triggered it does not publish (capability `backend-deployment`), and the previous bundle
keeps serving.

A refusal SHALL name what would satisfy it. The operator is being told to run something; a message that
reports only that the migration declined leaves them to discover what.

This rule SHALL be enforced by a check rather than by review. A migration is written once and read
rarely, and the failure it guards against is invisible in the diff — a `DROP TABLE` looks the same whether
or not a copy precedes it.

#### Scenario: A rebuild carries its rows

- **WHEN** a migration replaces a table in order to change a column's constraints
- **THEN** every row of the old table is present in the new one after the migration

#### Scenario: A narrowing migration refuses rather than discarding

- **WHEN** a migration would tighten a column and at least one row does not satisfy the tighter shape
- **THEN** the migration is refused, the store remains on the previous version, and no row is deleted

#### Scenario: The refusal says what to do

- **WHEN** a migration is refused by its precondition
- **THEN** the failure names the condition that was not met and what will satisfy it

#### Scenario: A narrowing migration applies cleanly once its data qualifies

- **WHEN** every row satisfies the tighter shape and the migration is applied
- **THEN** it succeeds and every row is carried into the rebuilt table

#### Scenario: The rule is checked, not reviewed

- **WHEN** a migration drops a table without first copying from it, or narrows a column without declaring
  a precondition
- **THEN** the test suite fails

### Requirement: The migration mechanism is permanent; a data cutover is throwaway

The ordered migration list, its runner, and the schema it produces SHALL live in the repository: they run
on every deployment, for as long as the store exists.

A **one-time data cutover** — a program that moves data into or out of the store once, against one store,
on one day — SHALL NOT be committed. It runs from a scratchpad with credentials injected from the
operator's own store, and goes away with the cutover.

The test separating them is what the program does on the *next* deployment. The migration list is applied
again and does nothing, because its versions are recorded; the cutover has no next run at all. Committing
the second leaves a module whose only reader is a day in the past, and a reviewer a year later has to
establish whether it still means anything — while a single-use workflow additionally sits in the CI
surface indefinitely, offering itself to be run again.

What SHALL survive a cutover is what a later reader actually needs: the migration plan in the change's
design record, any measurements it took, and the run's own output. A tool is scaffolding; a measurement is
evidence.

#### Scenario: The migration list ships and re-runs harmlessly

- **WHEN** a deployment applies the migration list to an already-migrated store
- **THEN** no statement runs a second time and the deployment proceeds

#### Scenario: A one-time cutover program is not in the repository

- **WHEN** a change requires a one-time data move that the migration list cannot express
- **THEN** that program lives in a scratchpad rather than in the repository or in a CI workflow, and the
  change's design record carries the plan and the run's result instead
