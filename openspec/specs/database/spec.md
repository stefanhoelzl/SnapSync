# database Specification

## Purpose

**The backend's relational store**, and the invariants it is here to make unforgettable.

Before this capability every relational fact was encoded in the shape of an S3 key namespace: an event
existed iff a marker object was present, a device was an active member iff one object was newer than its
sibling, and the event union was assembled by listing one directory per member. Storage has no referential
integrity, no atomic multi-key write, and no compare-and-set, so each of those invariants was
re-implemented by every consumer that needed it — and two whole classes of cleanup logic existed only to
repair states a foreign key forbids.

This capability owns the schema, what atomicity each write requires, and how existence and capacity are
decided. It does **not** own the routes that read and write it (capability `api-endpoints`), nor any
product rule about what a bound *means* (capabilities `event-limits`, `photo-selection-policy`).

The store holds only **derived, rebuildable** state: every row can be reconstructed without operator
action, by a device round-trip — a full-state manifest republish, or a fresh attestation. That is what
makes it safe to put on a platform in public preview. The cost of that repair is not uniform, and the
requirements say where: a lost upload record costs a republish, a lost attestation record costs the device
one full Apple attestation, which is the throttled path.

Decision record: `changes/record-uploads-in-database` (`design.md` for the decisions, `PROBE-FINDINGS.md`
for the measurements each one rests on).

## Requirements
### Requirement: Five tables, with resources outside the event ownership chain

The database SHALL hold exactly five tables: `events`, `memberships`, `event_assets`, `resources`, and
`devices`.

`memberships` SHALL reference `events`, and `event_assets` SHALL reference `memberships`, both
`ON DELETE CASCADE`, so deleting an event removes its memberships and their assets in one statement.

`resources` SHALL be **device-scoped and event-independent**, keyed by `(device_id, key)` and joined to
`event_assets` by `(device_id, asset_id)`. It SHALL NOT carry an event identifier and SHALL NOT sit under
the cascade.

That placement is **forced, not chosen**: the byte upload route addresses a resource row from the URL path
alone (`/api/v1/files/devices/<deviceId>/<filename>`, capability `api-endpoints`), and that path carries no
event. A resource row bearing `event_id` could not be written by the route that knows a byte landed. The
upload URL is compile-time on the client, so this constraint outlives any schema revision — a future
proposal to move `resources` under the event chain SHALL first explain how the byte route learns the event.

The same separation is what lets one uploaded byte be referenced by two events during an event switch
without being stored twice.

`devices` SHALL carry **two independently-written groups of columns for one device**: the attestation
group — the attested public key, the environment that attested it, when it was attested, and the expiry of
the most recently minted device token — and the push-registration group. The attestation group SHALL be
`NOT NULL`, because a row exists only where a device has attested (capability `device-attestation`); the
push group SHALL be nullable together, because a device that has not yet registered a push token is an
ordinary state and is why notify is best-effort.

Each group SHALL have exactly **one** writer, and each writer SHALL name only its own columns, so neither
can overwrite the other's fact. The row's creation time SHALL be written once, on insert, and never
rewritten.

#### Scenario: Deleting an event cascades two levels

- **WHEN** an `events` row is deleted
- **THEN** its `memberships` rows and their `event_assets` rows are removed in the same statement

#### Scenario: A resource survives its event

- **WHEN** an event is deleted while the device that uploaded a resource is still enrolled elsewhere
- **THEN** the `resources` row remains, because it is not under the event cascade

#### Scenario: One group's writer leaves the other untouched

- **WHEN** a push registration is written for a device that has attested
- **THEN** the attestation columns and the creation time are unchanged

#### Scenario: A re-attestation leaves the push registration untouched

- **WHEN** a device that already holds a push registration attests again
- **THEN** its attestation columns are replaced and its push columns are unchanged

### Requirement: Every text primary key is explicitly NOT NULL

Every `TEXT` primary-key column SHALL be declared `NOT NULL` explicitly.

Only `INTEGER PRIMARY KEY` implies `NOT NULL` in SQLite. Measured on this platform, an explicit
`INSERT … VALUES (NULL)` into a bare `TEXT PRIMARY KEY` column **succeeded**, while the same column
declared `PRIMARY KEY NOT NULL` rejected it (`PROBE-FINDINGS.md` §4.5). Without the explicit declaration a
stray `undefined` inserts a NULL-keyed row instead of failing, and the row is then unaddressable by the
route that created it.

#### Scenario: A null key is rejected

- **WHEN** an insert supplies NULL for a text primary-key column
- **THEN** the insert fails rather than creating a NULL-keyed row

### Requirement: Foreign keys are relied upon, and their enforcement is trusted rather than asserted

The schema SHALL rely on foreign-key enforcement rather than re-checking referential integrity in
application code. Measured on this platform, `PRAGMA foreign_keys` defaults to `1` — unlike stock SQLite —
violations are rejected both on a bare statement and inside a batch, and the value persists across requests
(`PROBE-FINDINGS.md` §4.1).

That measurement is now **trusted**, not re-asserted on every deploy. State the trust and its limits
precisely, because the failure it admits is silent:

- **What it rests on.** The value was measured against the deployed store, and no code path in this
  repository can change it. Only a provisioning or engine change on the platform's side could.
- **What it would cost.** Enforcement being off disables every constraint with no error and no rejected
  write, making two staleness classes the schema is designed to make unstateable quietly reachable again.
- **What would falsify it.** A platform provisioning or engine change that alters the default. Nothing in
  the deploy path would notice; the next signal would be a data anomaly.
- **Expiry trigger.** Re-measure when the store is re-provisioned, moved, or its engine version changes.

This is a guarantee traded away, not relocated. It was traded to make the boot probe's health route a
simple statement of reachability rather than a carrier of store diagnostics; if that trade proves wrong,
the assertion returns as its own change with its own argument.

#### Scenario: A dangling reference is rejected

- **WHEN** a write inserts a membership for an event id with no `events` row
- **THEN** the write is rejected by the constraint, not by application code

#### Scenario: Enforcement is not re-checked at deploy time

- **WHEN** a deployment boots and is probed
- **THEN** the probe witnesses that the store is reachable, and does not assert the pragma's value

### Requirement: Schema changes are ordered migrations, verified against the created schema

The schema SHALL be expressed **twice**: as the statements that create it from nothing, and as an ordered
list of migrations that evolve an existing store. A test SHALL assert that the two produce an **identical**
schema — one store built by creating, one by replaying every migration in order — so the pair cannot drift.

Each migration SHALL be applied at most once, recorded in the store itself, so applying the list to an
already-migrated store is a no-op rather than an error.

Both forms are required because they answer different questions and are read by different callers: the
created form is how a fresh dev or test store comes into being and is the readable statement of the current
shape; the ordered form is the only thing that can change a store that already holds rows. A store's shape
and the code's expectation of it SHALL NOT be reconciled by hand.

#### Scenario: The two forms agree

- **WHEN** one store is built from the create statements and another by replaying every migration
- **THEN** their schemas are identical

#### Scenario: Re-applying migrations changes nothing

- **WHEN** the migration list is applied to a store that has already had it applied
- **THEN** no statement runs a second time and the store is unchanged

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

### Requirement: A manifest publish is one atomic transaction

Recording a device manifest SHALL be atomic across all three of its effects — the membership upsert, the
full-state replace of that membership's `event_assets`, and the `resources` upserts. A partially-applied
publish SHALL NOT be observable by any read.

Where the write would exceed the platform's bound-parameter limit it SHALL be chunked **within** the same
transaction. Chunking across transactions SHALL NOT be used: it would leave a half-replaced asset set
visible to the union, which is exactly the partial state the atomicity requirement exists to forbid.

#### Scenario: A failed publish leaves the previous state intact

- **WHEN** any statement of a manifest publish fails
- **THEN** none of the membership, asset-set, or resource changes are applied

#### Scenario: A large publish stays atomic

- **WHEN** a manifest lists more assets than one statement's bound parameters allow
- **THEN** the write is split within one transaction, and no read observes a partially-replaced asset set

### Requirement: Membership state is a column with exactly two values

A device's participation in an event SHALL be one `memberships` row whose `state` is `active` or
`departed`. Enrollment sets `active`; leaving sets `departed`; re-joining sets `active` again on the same
row.

Consumers SHALL resolve membership by reading that column. No consumer SHALL infer membership from the
presence, absence, or relative age of stored objects.

A departed membership SHALL retain its `event_assets` rows, so the event union keeps serving what that
device shared before it left.

#### Scenario: A device is counted once, in one state

- **WHEN** a device has joined, left, and rejoined an event
- **THEN** exactly one membership row exists for that pair, its state is `active`, and no consumer can
  observe it as two devices

#### Scenario: A departed member keeps contributing

- **WHEN** a membership's state is `departed` and its event still exists
- **THEN** its assets remain in the event union

### Requirement: Event existence is a row

An event SHALL exist exactly when its `events` row exists. Every event-scoped route's existence gate SHALL
read that row.

No route SHALL delete an event on touch. Deletion belongs solely to the sweep (capability
`scheduled-cleanup`), which is what makes a `404` a **real** deletion and therefore safe as one of the two
witnesses a device requires before tearing down its own membership (capability `leave-event`).

#### Scenario: A served request never reaps

- **WHEN** any event-scoped route is called for an event past its delete-by that the sweep has not yet
  collected
- **THEN** the route serves the request normally and does not delete the event

### Requirement: Capacity is enforced exactly, by a single conditional statement

An event SHALL admit at most its stamped `capacity` distinct devices, counted over **every** membership
ever created for it — `active` and `departed` alike, so leaving frees no slot (capability `event-limits`).

Enrollment SHALL be performed as a **single conditional insert** whose condition is evaluated in the same
statement as the write. It SHALL NOT be a read of the current count followed by a separate write.

The rule SHALL hold in all four cases: a device already enrolled always passes and its re-enrollment is
idempotent; a departed device rejoining reuses its own slot and does not increase the count; a new device
is refused once the count has reached capacity; and concurrent first enrollments never overshoot.

Measured (`PROBE-FINDINGS.md` §4.4): with ten devices racing for three slots, read-then-write enrolled
**ten**; the single conditional statement enrolled **exactly three**, in 158 ms. The prior specification's
accepted overshoot — *"concurrent first enrollments may transiently overshoot, accepted"* — is therefore
retired rather than carried forward.

#### Scenario: Concurrent first enrollments do not overshoot

- **WHEN** more devices than the remaining capacity attempt to enroll at the same moment
- **THEN** exactly the remaining capacity are admitted and the rest are refused

#### Scenario: A departed device rejoins into its own slot

- **WHEN** a device that previously left rejoins an event at capacity
- **THEN** it is admitted, reusing its existing membership row, and the device count does not increase

#### Scenario: Leaving frees no slot

- **WHEN** a device leaves an event at capacity and a new device then attempts to enroll
- **THEN** the new device is refused

### Requirement: A zero-row write outcome is never collapsed into one answer

A conditional write that affects zero rows SHALL NOT be reported as a single outcome when its zero has more
than one cause.

The conditional enrollment statement reports zero rows affected for **two different reasons** — the event
is at capacity, and the event does not exist — because the capacity subquery yields NULL for a missing
event and the condition is then false (`PROBE-FINDINGS.md` §4.5).

The enrollment path SHALL distinguish them deliberately, by a follow-up existence read on the zero-row
path, and SHALL answer `409` for at-capacity and `404` for absent. It SHALL NOT report one status for both.

This is the "absence is never silent" law reintroduced by a SQL idiom rather than by a swallowed
exception: no type and no test would notice the collapse, so it is stated here.

#### Scenario: At capacity and absent are told apart

- **WHEN** an enrollment affects zero rows
- **THEN** the path determines whether the event exists and answers `409` if it does and `404` if it does
  not

### Requirement: The database holds only rebuildable state

Every row SHALL be reconstructible **without operator action, by a device round-trip** — a full-state
manifest publish, or a fresh attestation — from the storage zone and what the devices themselves hold. No
user-visible fact SHALL exist only in the database.

This bounds the consequence of losing the store — the union goes empty until devices republish, devices
re-attest at their next wake, and no photo is destroyed — and it is what makes the platform's stated limits
acceptable: **public preview**, a 1 GB per-database ceiling, and a **10-second maximum data-loss window**
on primary failover (`PROBE-FINDINGS.md` §4.1, §4.3).

An acknowledged write lost to that failover window SHALL be repaired by the next device round-trip rather
than by any dedicated reconciliation. The cost of that repair SHALL be stated where it is not free: a lost
attestation record costs the device one full Apple attestation, which is the throttled path, where a lost
upload record costs only a republish.

#### Scenario: A lost upload record is repaired by the next publish

- **WHEN** an acknowledged `uploaded = 1` is lost to a primary failover
- **THEN** the device's next full-state manifest publish restores it, with no re-upload of bytes

#### Scenario: A lost attestation record is repaired by re-attesting

- **WHEN** a device's attestation record is lost
- **THEN** its next renewal is refused, it completes a fresh attestation, and the record is restored with
  no operator action

### Requirement: Replica staleness is unmeasured from the edge, and the sweep decides on the primary

A destructive operation SHALL NOT act on a read whose freshness has not been established for the context it
runs in.

Read-your-writes held in every trial measured, including from a separate read-only token
(`PROBE-FINDINGS.md` §4.2) — but those trials ran from a workstation against a test database, **not** from
an Edge Script against the production one, and replica routing is exactly what differs between them. The
measurement therefore does **not** settle edge behaviour.

Because a stale read that missed a rejoin would let the sweep delete a live event, the sweep's deletion
decision SHALL be made inside an interactive transaction, which runs against the primary (capability
`scheduled-cleanup`). Ordinary request handling MAY use ordinary reads.

Any future change that lets a **destructive** operation act on an ordinary read's word SHALL first
re-confirm read-your-writes **from the edge**.

#### Scenario: The sweep does not delete on a possibly-stale read

- **WHEN** the sweep evaluates an event for deletion
- **THEN** the decision is taken against the primary, not against whatever replica an ordinary read reaches

