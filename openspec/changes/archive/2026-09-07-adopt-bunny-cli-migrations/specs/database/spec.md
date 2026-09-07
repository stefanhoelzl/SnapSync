## MODIFIED Requirements

### Requirement: Schema changes are ordered migrations, verified against the created schema

The schema SHALL be expressed **twice**: as an ordered list of migrations that evolve an existing store,
and as the statements that create it from nothing. The created form SHALL be **generated** by replaying
every migration into an empty store and dumping its schema, SHALL be committed, and a check SHALL fail
when the committed form is not what replaying produces. It SHALL NOT be hand-maintained.

Each migration SHALL be an ordered `.sql` file whose name carries its position, applied at most once and
recorded in the store itself with a **checksum** of its contents, so that applying the list to an
already-migrated store is a no-op rather than an error, and so that editing a migration that has already
been applied is refused rather than silently ignored.

Both forms are required because they answer different questions and are read by different callers: the
ordered form is the only thing that can change a store that already holds rows, and is what every store —
deployed, local rig, and test — is built by; the created form is the readable statement of the current
shape and the artifact a reviewer reads to see what a migration did to it. Deriving the second from the
first is what makes them unable to disagree; a store's shape and the code's expectation of it SHALL NOT be
reconciled by hand.

The generated form SHALL be checked for **freshness**, which is not the same as correctness: a migration
that silently drops an object produces a smaller generated form and a passing freshness check. That loss
is visible in the generated form's diff. Mechanical detection of such losses is deliberately out of scope
here; see the change's design record for what is deferred and what triggers it.

#### Scenario: The two forms agree

- **WHEN** the committed created form is compared with the schema produced by replaying every migration
- **THEN** they are identical, and a difference fails the check

#### Scenario: Re-applying migrations changes nothing

- **WHEN** the migration list is applied to a store that has already had it applied
- **THEN** no statement runs a second time and the store is unchanged

#### Scenario: An edited shipped migration is refused

- **WHEN** a migration file that a store has already applied is modified and an apply is attempted while
  another migration is pending
- **THEN** the apply is refused, naming the modified file, rather than proceeding without applying the edit

### Requirement: A migration migrates its data; it does not drop it

A migration that replaces a table SHALL carry that table's rows into its replacement. Dropping a table
whose contents another program is responsible for saving SHALL NOT be a migration's behaviour, however
that program is scheduled.

This is stated because SQLite makes the wrong shape the easy one: a column's constraints cannot be altered
in place, so any change to them forces a create-new / drop-old rebuild, and the copy in the middle is the
step it is possible to simply not write. The result reads as a schema change and behaves as a deletion.

Where a migration **narrows** a constraint it cannot carry every row by construction — only rows that
already satisfy the narrower shape qualify. Such a migration SHALL declare a **precondition** that refuses
the migration when any row does not, and SHALL NOT proceed by discarding those rows. The precondition
SHALL be expressed **inside the migration file**, as SQL that aborts, so that it runs against the
deployed store's rows and shares the migration's transaction. A precondition expressed outside the file
would not run where the rows are. The refusal SHALL leave the store on the previous version, so the
failure is fail-closed: nothing is half-applied, the deployment that triggered it does not publish
(capability `backend-deployment`), and the previous bundle keeps serving.

A refusal SHALL name what would satisfy it. The operator is being told to run something; a message that
reports only that the migration declined leaves them to discover what. Because SQL abort messages are
**literal** and cannot interpolate a count, a refusal SHALL name the query that lists the offending rows
rather than reporting how many there are.

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
- **THEN** the failure names the condition that was not met and the query that lists the rows failing it

#### Scenario: A narrowing migration applies cleanly once its data qualifies

- **WHEN** every row satisfies the tighter shape and the migration is applied
- **THEN** it succeeds and every row is carried into the rebuilt table

#### Scenario: The rule is checked, not reviewed

- **WHEN** a migration drops a table without first copying from it, or narrows a column without declaring
  a precondition
- **THEN** the test suite fails

### Requirement: The migration mechanism is permanent; a data cutover is throwaway

The ordered migrations and the schema they produce SHALL live in the repository: they run on every
deployment, for as long as the store exists. The **runner** that applies them MAY be a platform tool
rather than repository code, provided the migrations themselves remain the repository's.

A **one-time data cutover** — a program that moves data into or out of the store once, against one store,
on one day — SHALL NOT be committed. It runs from a scratchpad with credentials injected from the
operator's own store, and goes away with the cutover.

The test separating them is what the program does on the *next* deployment. The migrations are applied
again and do nothing, because they are recorded; the cutover has no next run at all. Committing the second
leaves a module whose only reader is a day in the past, and a reviewer a year later has to establish
whether it still means anything — while a single-use workflow additionally sits in the CI surface
indefinitely, offering itself to be run again.

What SHALL survive a cutover is what a later reader actually needs: the migration plan in the change's
design record, any measurements it took, and the run's own output. A tool is scaffolding; a measurement is
evidence.

#### Scenario: The migrations ship and re-run harmlessly

- **WHEN** a deployment applies the migrations to an already-migrated store
- **THEN** no statement runs a second time and the deployment proceeds

#### Scenario: A one-time cutover program is not in the repository

- **WHEN** a change requires a one-time data move that the migrations cannot express
- **THEN** that program lives in a scratchpad rather than in the repository or in a CI workflow, and the
  change's design record carries the plan and the run's result instead

## ADDED Requirements

### Requirement: A migration's row copy names its columns

A migration's `INSERT … SELECT` SHALL name the columns it copies on both sides. `SELECT *` SHALL NOT be
used, and a check SHALL fail a migration that uses it.

`SELECT *` maps **by position**, not by name. A rebuild that reorders columns — an ordinary thing to do
while changing a table — therefore writes each value into its neighbour's column. Where the columns share
a type, as every text column in this schema does, nothing rejects it: the rows are all carried, no
constraint is violated, no object disappears from the schema, and every other guard in this capability
passes. It is the only failure in this schema's migration path that produces **wrong** data rather than
missing data, and nothing observes it.

Its blast radius is bounded by the store holding only rebuildable state — a transposed attestation fails
verification, the device is refused and re-attests — but the repair is the throttled path, taken by every
affected device at once, with no signal that anything happened.

#### Scenario: A migration using SELECT * is rejected

- **WHEN** a migration file copies rows with `INSERT … SELECT *`
- **THEN** the test suite fails, naming the file

#### Scenario: A reordering rebuild carries values into the right columns

- **WHEN** a migration rebuilds a table with its columns in a different order and copies the rows
- **THEN** each value is written to the column it was read from, because both sides name their columns

### Requirement: Migrations apply with foreign-key enforcement disabled

Migrations SHALL be applied with foreign-key enforcement **off** for the duration of each migration, and
enforcement SHALL be restored afterwards. Every applier of these migrations SHALL do this identically,
whether it is the platform runner used by the deployment or the in-repository runner used by the local rig
and the tests.

This is forced by SQLite. A constraint change requires a table rebuild, and `DROP TABLE` performs an
implicit delete of every row, which **fires `ON DELETE CASCADE`** — so rebuilding a table that others
reference deletes their rows while the migration reports success. Deferring enforcement does not prevent
this: deferral postpones the *check*, not the cascading *action*. Only genuine disablement does.

Enforcement SHALL be disabled **outside** the migration's transaction and within the same connection,
because SQLite ignores the setting inside a transaction — a migration that sets it as its first statement
changes nothing and reports success.

Because appliers must agree, this is a requirement rather than an implementation detail: an applier that
deferred instead of disabling would produce a **different store**, not a different error, and no schema
comparison would reveal it.

#### Scenario: Rebuilding a referenced table keeps the referencing rows

- **WHEN** a migration rebuilds a table that another table references with `ON DELETE CASCADE`
- **THEN** the referencing rows are still present after the migration

#### Scenario: Both appliers agree

- **WHEN** the same migration is applied by the deployment's runner and by the in-repository runner
- **THEN** both disable foreign-key enforcement for its duration and produce the same store
