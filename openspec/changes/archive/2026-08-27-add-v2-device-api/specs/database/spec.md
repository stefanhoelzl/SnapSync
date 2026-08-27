## MODIFIED Requirements

### Requirement: Five tables, with resources outside the event ownership chain

The database SHALL hold exactly five tables: `events`, `memberships`, `event_assets`, `resources`, and
`devices`.

`memberships` SHALL reference `events`, and `event_assets` SHALL reference `memberships`, both
`ON DELETE CASCADE`, so deleting an event removes its memberships and their assets in one statement.

`resources` SHALL be **device-scoped and event-independent**, keyed by `(device_id, asset_id, role)` and
joined to `event_assets` by `(device_id, asset_id)`. It SHALL NOT carry an event identifier and SHALL NOT
sit under the cascade.

That placement is **forced, not chosen**: the byte upload route addresses a resource row from the URL path
alone (capability `api-endpoints`), and that path carries no event. A resource row bearing `event_id`
could not be written by the route that knows a byte landed. The upload URL is compile-time on the client,
so this constraint outlives any schema revision — a future proposal to move `resources` under the event
chain SHALL first explain how the byte route learns the event.

The key is the resource's **identity** — the asset it belongs to and the role it plays within that asset —
rather than the name of the object it is stored under. The stored object name SHALL be a column, not the
key, so that the storage layout can change without changing what a resource *is*, and so that two versions
of the API can address the same row while composing that name differently.

Keying by `(asset_id, role)` asserts that an asset carries **at most one resource per role**. That
assertion is upheld by the client and **cannot be verified by the backend**: a second same-role upload is
indistinguishable from a legitimate re-upload of the same resource, because both are last-write-wins on
one identity. It is stated here so the dependency is visible rather than implicit, and the consequence of
a violation is bounded by construction — the second write overwrites the first, leaving no orphan object
and no divergence between a row and the bytes it names.

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

#### Scenario: A resource is addressed by identity, not by object name

- **WHEN** two API versions compose the stored object name differently for the same asset and role
- **THEN** both resolve to the same `resources` row, because the row is keyed by identity and the object
  name is a column

### Requirement: A manifest publish is one atomic transaction

Recording a device manifest SHALL be atomic across every effect it has — at minimum the full-state replace
of that membership's `event_assets`, together with any other row the publish writes. A partially-applied
publish SHALL NOT be observable by any read.

Where the write would exceed the platform's bound-parameter limit it SHALL be chunked **within** the same
transaction. Chunking across transactions SHALL NOT be used: it would leave a half-replaced asset set
visible to the union, which is exactly the partial state the atomicity requirement exists to forbid.

Any work the publish performs **outside** the database — notably a push fan-out (capability
`apns-push-sender`) — SHALL happen **after** the transaction commits, never within it. A recipient woken
before the commit is visible would read the union and find the very state the notification announced to be
missing.

#### Scenario: A failed publish leaves the previous state intact

- **WHEN** any statement of a manifest publish fails
- **THEN** none of the membership, asset-set, or resource changes are applied

#### Scenario: A large publish stays atomic

- **WHEN** a manifest lists more assets than one statement's bound parameters allow
- **THEN** the write is split within one transaction, and no read observes a partially-replaced asset set

#### Scenario: Work outside the database follows the commit

- **WHEN** a manifest publish both writes rows and triggers an external side effect
- **THEN** the transaction commits first, and the side effect runs only after it is visible to reads

### Requirement: The database holds only rebuildable state

Every row SHALL be reconstructible from the storage zone plus one full-state manifest publish per
device. No user-visible fact SHALL exist only in the database.

This bounds the consequence of losing the store — the union goes empty until devices republish, and no
photo is destroyed — and it is what makes the platform's stated limits acceptable: **public preview**, a
1 GB per-database ceiling, and a **10-second maximum data-loss window** on primary failover.

It is also what makes a **schema migration** an acceptable operation on live data: the worst outcome of a
failed migration is the same worst outcome as losing the store, and the recovery is the same — devices
republish. A migration SHALL therefore NOT require a reverse migration as its rollback plan.

An acknowledged write lost to that failover window SHALL be repaired by the next manifest publish where
the publishing device still asserts the fact, and otherwise by the device re-performing the work the write
recorded. There SHALL be no dedicated reconciliation pass.

#### Scenario: A lost attestation record is repaired by re-attesting

- **WHEN** a device's attestation record is lost
- **THEN** its next renewal is refused, it completes a fresh attestation, and the record is restored with
  no operator action

#### Scenario: A lost upload record is repaired by the next publish

- **WHEN** an acknowledged upload record is lost to a primary failover, so the resource's row is gone
- **THEN** the device's next full-state manifest publish restores it, with no re-upload of bytes

#### Scenario: A failed migration recovers by republication

- **WHEN** a schema migration leaves the store unusable or incomplete
- **THEN** recovery is republication by devices, not a reverse migration

## ADDED Requirements

### Requirement: Row existence is the record that bytes are stored

A `resources` row SHALL exist exactly when the backend has observed that resource's bytes arrive. There
SHALL be no column expressing upload state.

The backend SHALL record only what it **witnessed**. A row SHALL NOT be created on the strength of a
device's assertion that it uploaded something, because a device's belief and the backend's observation are
different facts, and collapsing them lets a mistaken device write a truth the backend has no evidence for.

A resource a device intends to contribute but has not uploaded is therefore expressed as **absence** — it
is named by the manifest and has no `resources` row — rather than by a flag. What is pending is the
difference between the two, and needs no storage of its own.

#### Scenario: Bytes arriving create the row

- **WHEN** a resource's bytes are stored successfully
- **THEN** its `resources` row exists

#### Scenario: A declared but unuploaded resource has no row

- **WHEN** a manifest names a resource whose bytes have never arrived
- **THEN** no `resources` row exists for it, and it is pending by that absence

### Requirement: Each table has exactly one writer, on the current API version

On the **current** API version every table SHALL be written by exactly one route family: `events` by event
creation, `memberships` by join and leave, `event_assets` by the manifest publish, `resources` by the byte
upload, and `devices` by the two writers its own column groups define — attestation, and the device
config write (see "Five tables", which states that split and why each writer names only its own columns).
The nightly sweep (capability `scheduled-cleanup`) is the sole additional writer, and it only deletes.

No route on that version SHALL write a table another route owns, even to repair it. A second writer means
two sources for one fact, and reconciling them requires either a merge rule that can be wrong or a
monotonicity constraint that forbids retraction — both of which were present before this requirement, and
both of which made correct behaviour depend on the order in which two routes happened to run.

**A superseded version is exempt, and the exemption is bounded.** Legacy routes are spoken by builds that
cannot be updated, so their behaviour is frozen rather than corrected: where a legacy version's manifest
publish also writes `resources` and its membership state, it SHALL keep doing so, and the monotonicity
that makes those writes safe SHALL be preserved. That is a second writer by construction — accepted
because a device speaks exactly one version, so a legacy route can only ever write rows belonging to a
device that has not moved, and because the exemption ends when that version is retired.

An exemption SHALL be recorded against a **named** version. A new version SHALL NOT be granted one.

#### Scenario: The current version's manifest publish does not record uploads

- **WHEN** a manifest on the current version names resources
- **THEN** it writes the membership's asset set and nothing in `resources`

#### Scenario: Membership state changes only by join or leave on the current version

- **WHEN** a device publishes a manifest on the current version for an event it has departed
- **THEN** its membership state is unchanged, because only join and leave write it

#### Scenario: A legacy version keeps its second writer

- **WHEN** a legacy version's manifest publish repairs a resource record or reactivates a membership
- **THEN** it continues to do so, unchanged, for as long as that version is served

### Requirement: A migration preserves every served version's behaviour

A schema migration performed for one API version SHALL preserve the observable behaviour of every version
still served. Where an existing version's handlers must change to read or write the new schema, they SHALL
be adapted rather than reinterpreted: same requests, same responses, same status codes.

The evidence that behaviour was preserved SHALL be that version's existing **wire-contract** tests —
paths, status codes, response shapes and upstream effects — passing **unmodified**. A wire-contract test
that must be edited to accommodate the migration is evidence that behaviour changed, and the adaptation is
wrong.

A test asserting a **retired internal** — a column that no longer exists, holding a fact the new schema
still records by other means — SHALL be re-expressed rather than treated as a behaviour change, and the
re-expression SHALL assert the same fact. Such a test SHALL be enumerated in the change, with the fact it
asserts named in both spellings, so that "we edited a test" stays distinguishable from "we changed what
the system does".

Where the migration cannot recover a value the new schema requires from what the old one stored, the
migration SHALL derive it from data already present rather than defaulting it, and SHALL report how many
rows required derivation.

#### Scenario: A served version is unaffected by a migration made for another

- **WHEN** the schema migrates for a later version and an earlier version's route is called
- **THEN** it returns what it returned before, with the same status

#### Scenario: Unmodified wire-contract tests are the evidence

- **WHEN** a migration adapts an existing version's handlers
- **THEN** that version's existing wire-contract tests pass without being edited

#### Scenario: A test naming a retired column is re-expressed, not counted as a behaviour change

- **WHEN** a test asserted a column the migration retires, whose fact the new schema still records
- **THEN** it is re-expressed to assert that same fact, and is listed in the change as such
