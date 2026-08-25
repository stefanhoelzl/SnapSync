## ADDED Requirements

### Requirement: Event fields are write-once except the name

An event's stored fields SHALL be **write-once except `name`**: `eventId`, `createdAt`, `startsAt`,
`endsAt`, `capacity`, and `lifetimeSeconds` SHALL be immutable after creation, and no route SHALL change
any of them.

The backend has no owner field and no authentication beyond attestation, so a general mutation route would
let anyone holding the event id retroactively widen every future joiner's default scope — or extend an
event's own limits. The lifecycle is recomputed from the stored fields on every read precisely so that no
rewrite of those fields is ever needed.

`name` is the **single** exception, writable **only** through the dedicated rename route (capabilities
`api-endpoints`, `event-rename`). It is exempt because it touches neither named threat: a name cannot widen
a capture-date scope and cannot extend a lifetime. It is cosmetic to the upload gate, cosmetic to the
extension, and load-bearing for display alone.

Any future proposal to make another field mutable SHALL argue against the two threats by name; the
exemption granted here does not generalize.

This rule was previously stated against a write-once JSON marker object, where violating it required
rewriting a whole document. It is **relocated, not weakened, and it now matters more**: the fields live in
a table with an `UPDATE`, and SQLite offers no column-level immutability without a trigger, so nothing but
this requirement stands between a careless `SET` and a retroactively widened scope.

#### Scenario: No route mutates a field other than the name

- **WHEN** the backend's routes are enumerated
- **THEN** the rename route is the only one that writes an existing event, and it writes `name` alone — so
  `eventId`, `createdAt`, `startsAt`, `endsAt`, `capacity`, and `lifetimeSeconds` remain immutable after
  creation

#### Scenario: A rename leaves the lifecycle untouched

- **WHEN** an event is renamed
- **THEN** its `createdAt`, `startsAt`, and `lifetimeSeconds` are unchanged, so its derived delete-by is
  exactly what it was — a rename cannot resurrect an event for a fresh lifetime

## MODIFIED Requirements

### Requirement: Capacity bounds devices ever enrolled

The capacity check SHALL compare the event's `capacity` against the number of **distinct device ids ever
enrolled** on it — every device holding a membership row, `active` **or** `departed` (capability
`database`). An enrollment for a device id already holding a membership SHALL pass unconditionally (a
rejoin reuses the device's own slot; a manifest update is not an enrollment). An enrollment for a device id
holding none SHALL be rejected with `409 Conflict` when the ever-enrolled count has reached `capacity`, and
nothing SHALL be written. Leaving SHALL NOT free a slot — departed memberships keep counting, so churn
cannot route more than `capacity` distinct devices through an event.

The check SHALL be **exact**. It SHALL be performed as a single conditional statement that evaluates the
count and performs the insert together, never as a read of the count followed by a separate write.

The previous specification accepted an overshoot — *"the check is read-then-write without coordination (the
storage backend has no compare-and-set); concurrent first-time enrollments MAY transiently exceed
`capacity`"* — and that premise does not survive the move to a relational store. Measured with ten devices
racing for three slots: read-then-write admitted **ten**; the single conditional statement admitted
**exactly three**, in 158 ms (`PROBE-FINDINGS.md` §4.4). The caveat is therefore **retired rather than
carried forward**, and `capacity` is now a bound the backend actually holds.

#### Scenario: A new device is rejected at capacity

- **WHEN** an enrollment arrives for a device id with no membership on the event, and the event already has
  `capacity` distinct device ids enrolled (counting both active and departed)
- **THEN** the backend responds `409` and writes nothing

#### Scenario: A known device always passes

- **WHEN** an enrollment arrives for a device id that already holds an active or departed membership
- **THEN** the capacity check passes regardless of the ever-enrolled count and the write proceeds

#### Scenario: Leaving frees no slot

- **WHEN** a device leaves a full event and a different, never-seen device then attempts to enroll
- **THEN** the departed device still counts toward `capacity` and the new device is rejected with `409`

#### Scenario: A rejoin reuses the departed slot

- **WHEN** a device that previously left the event enrolls again while the event is live
- **THEN** the enrollment passes the capacity check (its device id is already counted) and the device is
  active again

#### Scenario: Concurrent first enrollments do not overshoot

- **WHEN** more never-seen devices than the remaining capacity enroll at the same moment
- **THEN** exactly the remaining capacity are admitted and every other is rejected with `409` — the count
  never exceeds `capacity`, transiently or otherwise

### Requirement: Event lifecycle from the marker alone

An event's lifecycle SHALL be binary — it **exists**, or it has been **deleted** — with no served
intermediate state and no stored state machine or flag. While its row is present, every event-scoped
operation SHALL be served: enrollment (under capacity), manifest publishes, photo-byte uploads, the union
read, notify fan-out, and leave.
**Joining SHALL NOT be closed by time under any condition** — an event is joinable for
as long as it exists, bounded only by capacity — because a guest who joins after the window closed still
holds in-window captures that belong in the event.

An event's **delete-by** instant SHALL be derived on every read as
`max(createdAt, startsAt) + lifetimeSeconds`, where `createdAt` and `startsAt` are parsed to absolute
instants rather than compared as strings (`createdAt` is not in canonical cutoff form, so a lexicographic
comparison silently yields the wrong anchor). Anchoring at the later of the two is what makes a
back-dated event (whose `startsAt` is already weeks past) survive long enough to be joined, and a
created-early event (whose `startsAt` is weeks away) survive its own window.

The delete-by SHALL be **derived, never stamped**: the row carries the lifetime *duration*, so the
per-event value is immutable while the anchor formula stays in shared code and can be corrected without
rewriting stored data.

The **incomplete** case is retired: `startsAt`, `endsAt`, `capacity`, and `lifetimeSeconds` are `NOT NULL`
columns, so an event row cannot exist while missing one. An event either exists and is complete, or it does
not exist. Existence is the row (capability `database`).

Deletion is performed solely by the scheduled cleanup (capability `scheduled-cleanup`). No route SHALL
delete an event on touch.

#### Scenario: An event past its window still serves everything

- **WHEN** an event-scoped request arrives after `endsAt` but before the event's delete-by
- **THEN** it is served exactly as it would have been while the window was open, including a
  first-time enrollment for a never-seen device

#### Scenario: The delete-by anchors at the later of createdAt and startsAt

- **WHEN** an event carries a `startsAt` five weeks before its `createdAt`
- **THEN** the derived delete-by is `createdAt + lifetimeSeconds`, so the event is not already past its
  deadline at the moment it is minted

#### Scenario: A created-early event survives its own window

- **WHEN** an event carries a `startsAt` three weeks after its `createdAt`
- **THEN** the derived delete-by is `startsAt + lifetimeSeconds`, so the event outlives the window it
  declares

#### Scenario: An incomplete event cannot exist

- **WHEN** a write would create an event row missing `startsAt`, `endsAt`, `capacity`, or
  `lifetimeSeconds`
- **THEN** the write fails on the column constraint, rather than creating an event that every route must
  later classify as gone

#### Scenario: No route deletes on touch

- **WHEN** an event-scoped request arrives for an event past its derived delete-by, before the next
  scheduled cleanup has run
- **THEN** the request is served normally and the route deletes nothing — deletion is the sweep's alone
