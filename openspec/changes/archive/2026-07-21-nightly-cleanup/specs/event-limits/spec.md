# event-limits Delta

## MODIFIED Requirements

### Requirement: Event lifecycle from the marker alone

An event's lifecycle state SHALL be a pure function of its marker's `endsAt` and the server's current
wall-clock: **live** while `now <= endsAt`, and **grace** while `now > endsAt`. There is no distinct
served **expired** state — an event past `endsAt` remains in grace (closed to new devices, open to
existing members) until the scheduled cleanup deletes it (capability `scheduled-cleanup`), and deletion
by that sweep *is* expiry. A marker missing `endsAt` or `capacity` (written before the `event-limits`
capability) SHALL be treated as **grace** by the gate and is deleted by the sweep. No stored state
machine, flag, or rewrite SHALL represent the lifecycle — the marker stays write-once, and the state is
recomputed on every read. The configured grace period governs only **when the sweep deletes** an event
(`now > endsAt + grace`), not how the gate classifies it.

#### Scenario: Live within the window

- **WHEN** an event-scoped request arrives while `now <= endsAt`
- **THEN** the event is treated as live and the request proceeds under the capacity rules

#### Scenario: Grace after the end

- **WHEN** an event-scoped request arrives while `now > endsAt`
- **THEN** the event is treated as in grace — closed to new devices, open to existing members — until the
  scheduled cleanup deletes it

#### Scenario: A legacy marker is in grace

- **WHEN** an event-scoped request reads a marker that carries no `endsAt` or no `capacity`
- **THEN** the event is treated as in grace (closed to new devices, open to existing members) and is left
  for the scheduled cleanup to delete

## REMOVED Requirements

### Requirement: Expiry reap on first touch

**Reason**: Deletion of expired events moves from lazy, on-touch reaping to the scheduled nightly sweep
(capability `scheduled-cleanup`), which reclaims expired events whether or not anyone touches them —
closing the accepted "an event nobody touches lingers as storage until touched" leak. The gate no longer
deletes anything; an event past `endsAt` simply stays in grace (serving members, closed to joins) until
the sweep removes it.

**Migration**: The nightly sweep deletes every event whose marker gives `now > endsAt + grace` (and every
legacy marker), notifying active members via the admin-authorized notify route before deleting the marker
and manifests. Between `endsAt` and the sweep, event-scoped requests are served under the grace rules; no
request triggers a delete.
