## MODIFIED Requirements

### Requirement: Two ordered phases — events before assets

The sweep SHALL run the **event phase** to completion before the **asset phase**. The asset phase SHALL
compute the referenced-byte set and every device's retention floor over the events that **survive** the
event phase, so an asset whose only event was just deleted is correctly seen as unreferenced.

Both phases SHALL read their inputs from the database (capability `database`) rather than from storage
listings: the referenced-byte set is a query over surviving events' `event_assets` joined to `resources`,
and each device's floor is a query over the events it is an active member of. Only the byte objects
themselves are enumerated from storage.

#### Scenario: Assets are evaluated against surviving events

- **WHEN** the event phase deletes an event and the asset phase then runs
- **THEN** the asset phase treats that event's assets as gone and evaluates its bytes for collection

#### Scenario: The root set is a query, not a fan-out

- **WHEN** the asset phase computes the referenced-byte set
- **THEN** it issues a query over the surviving events' rows and performs no per-event, per-device manifest
  read

### Requirement: Stale-event deletion

The event phase SHALL delete every **stale event**, and SHALL leave every other event untouched. An event
is stale when **either** of the following holds:

- **past its deadline** — `now` is later than the row's derived delete-by
  (`max(createdAt, startsAt) + lifetimeSeconds`, per capability `event-limits`);
- **empty** — the event has **at least one** membership row and **none** of them is `active`.

An event with **no membership rows at all** SHALL NOT be treated as empty: it has been minted but never
joined (`POST /api/v1/events` always produces a zero-device event, because the creator confirms through the
same join gate a scanned QR uses), and it SHALL survive until its deadline like any other event.

Emptiness is **opportunistic reclamation, not a guarantee**. A leave whose backend request never lands
leaves an active membership behind, so an abandoned event may never empty; the deadline is the only bound
that always holds, and no requirement, client behavior, or user-facing statement SHALL be written as if
emptiness were assured.

Deletion SHALL be a single `DELETE` of the event row, whose `ON DELETE CASCADE` removes its memberships and
their assets atomically (capability `database`). There is no ordering to get right and no partially-deleted
event to observe. The sweep SHALL NOT notify the event's members before deleting it. Deletion SHALL be
idempotent — a re-run over an already-deleted event is a harmless no-op.

The deletion **decision** SHALL be taken inside an interactive transaction, which runs against the primary.
The emptiness rule is the exposed one: a stale replica that had not yet observed a **rejoin** would see a
fully-departed event and delete a live one. The deadline rule reads immutable stamped columns and is
stale-safe by contrast. Read-your-writes has not been measured from the edge (capability `database`).

#### Scenario: An event past its deadline is deleted

- **WHEN** an event's row gives a derived delete-by earlier than `now`
- **THEN** the sweep deletes it, and its memberships and assets go with it

#### Scenario: An emptied event is deleted

- **WHEN** an event has membership rows and every one of them is `departed`
- **THEN** the sweep deletes it, even though its deadline has not passed

#### Scenario: An event with one active member is left intact

- **WHEN** an event within its deadline has at least one `active` membership
- **THEN** the sweep deletes nothing for that event

#### Scenario: A minted-but-never-joined event is not empty

- **WHEN** an event within its deadline has no membership rows at all
- **THEN** the sweep deletes nothing for that event, and it survives until its deadline

#### Scenario: The decision is taken against the primary

- **WHEN** the sweep evaluates an event for deletion
- **THEN** the evaluation runs inside an interactive transaction, so a stale replica cannot cause a live
  event to be deleted

#### Scenario: Deletion sends no notification

- **WHEN** the sweep deletes any stale event
- **THEN** it dispatches no push and makes no notify request; members discover the deletion on their own
  next foreground details fetch (capability `leave-event`)

### Requirement: Stale-asset collection

The asset phase SHALL collect a byte object under `files/devices/<deviceId>/` if and only if **(a)** no
surviving event references its key — through any membership, `active` or `departed` — and **(b)** its
storage `DateCreated` (upload time) is **earlier than** `min(startsAt)` taken over the events the device is
an **active** member of among surviving events, where `min` over **no** such events is `+∞`. There SHALL be
no wall-clock age threshold: a live upload for any active event was uploaded at or after that event's
start, hence at or after the floor, so it is never collected.

When a byte is collected, its `resources` row SHALL be deleted **before** the byte object. The order is
load-bearing:

- row then byte — a crash leaves an orphan byte, still unreferenced and still below the floor, so the next
  run collects it. Self-healing.
- byte then row — a crash leaves a row asserting `uploaded = 1` for bytes that no longer exist. That
  residue is inert while nothing reads the row for dedup, and becomes a silently un-re-uploadable photo the
  moment something does.

A device that is in **no** surviving event (floor `= +∞`) SHALL additionally have its `device_records` row
and its attestation record `devices/<deviceId>.attest.json` collected — these carry no event date and are
reclaimed only in this fully-orphaned case (a returning device re-registers its push token on its next
launch or join and re-attests on demand).

#### Scenario: A pre-switch leftover byte is collected

- **WHEN** a byte is unreferenced by any surviving event and its upload time is earlier than the start of
  every surviving event the device is active in
- **THEN** the sweep deletes that byte, having first deleted its `resources` row

#### Scenario: A live upload is retained

- **WHEN** a byte is unreferenced but its upload time is at or after the start of an active event the
  device belongs to
- **THEN** the sweep retains the byte (it is at or above the device's floor)

#### Scenario: A referenced byte is retained

- **WHEN** a byte's key is named by a surviving event's assets
- **THEN** the sweep retains the byte regardless of its upload time

#### Scenario: A crash between the two deletions is self-healing

- **WHEN** the sweep deletes a resource row and is killed before deleting its byte
- **THEN** the byte remains unreferenced and below the floor, and the next run collects it

#### Scenario: A fully-orphaned device is collected whole

- **WHEN** a device is an active member of no surviving event
- **THEN** every unreferenced byte under `files/devices/<deviceId>/`, its `device_records` row, and its
  `devices/<deviceId>.attest.json` are deleted

#### Scenario: A departed member's bytes are retained while its event survives

- **WHEN** a device has left an event that has not yet expired, so that event's assets still reference its
  bytes
- **THEN** those bytes are retained until that event is deleted

## REMOVED Requirements

### Requirement: Tombstone reclamation
**Reason**: Unstateable. A tombstone was an event directory left holding neither a marker nor any manifest
— a residue only an object store can produce. Rows leave no empty directories behind, and the
`ON DELETE CASCADE` that removes an event's memberships makes the partially-deleted state the requirement
existed to reclaim impossible to reach.
**Migration**: None. `database` → *Five tables, with resources outside the event ownership chain* is what
forecloses the state.
