## MODIFIED Requirements

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

## ADDED Requirements

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
