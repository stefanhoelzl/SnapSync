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

The store holds only **derived, rebuildable** state: every row can be reconstructed from the storage zone
plus one manifest republish per device. That is what makes it safe to put on a platform in public preview.

Decision record: `changes/record-uploads-in-database` (`design.md` for the decisions, `PROBE-FINDINGS.md`
for the measurements each one rests on).

## Requirements
### Requirement: Five tables, with resources outside the event ownership chain

The database SHALL hold exactly five tables: `events`, `memberships`, `event_assets`, `resources`, and
`device_records`.

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

#### Scenario: Deleting an event cascades two levels

- **WHEN** an `events` row is deleted
- **THEN** its `memberships` rows and their `event_assets` rows are removed in the same statement

#### Scenario: A resource survives its event

- **WHEN** an event is deleted while the device that uploaded a resource is still enrolled elsewhere
- **THEN** the `resources` row remains, because it is not under the event cascade

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

### Requirement: Foreign keys are relied upon and asserted at boot

The schema SHALL rely on foreign-key enforcement rather than re-checking referential integrity in
application code. Measured on this platform, `PRAGMA foreign_keys` defaults to `1` — unlike stock SQLite —
violations are rejected both on a bare statement and inside a batch, and the value persists across requests
(`PROBE-FINDINGS.md` §4.1).

Because a provisioning change that turned enforcement off would disable every constraint **silently**, with
no error anywhere, the deployment boot probe (capability `deployment-configuration`) SHALL assert the
pragma's value at startup rather than trust that measurement.

#### Scenario: A dangling reference is rejected

- **WHEN** a write inserts a membership for an event id with no `events` row
- **THEN** the write is rejected by the constraint, not by application code

#### Scenario: The boot probe fails loudly if enforcement is off

- **WHEN** the deployment boots and `PRAGMA foreign_keys` does not report enforcement on
- **THEN** the probe fails, rather than the deployment serving with every constraint silently inert

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

Every row SHALL be reconstructible from the storage zone plus one full-state manifest publish per
device. No user-visible fact SHALL exist only in the database.

This bounds the consequence of losing the store — the union goes empty until devices republish, and no
photo is destroyed — and it is what makes the platform's stated limits acceptable: **public preview**, a
1 GB per-database ceiling, and a **10-second maximum data-loss window** on primary failover
(`PROBE-FINDINGS.md` §4.1, §4.3).

An acknowledged write lost to that failover window SHALL be repaired by the next manifest publish rather
than by any dedicated reconciliation.

#### Scenario: A lost upload record is repaired by the next publish

- **WHEN** an acknowledged `uploaded = 1` is lost to a primary failover
- **THEN** the device's next full-state manifest publish restores it, with no re-upload of bytes

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

