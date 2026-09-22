## ADDED Requirements

### Requirement: The membership stores the manifest version it last accepted

`memberships` SHALL carry a nullable integer `manifest_version`: the manifest version of the last current-version
publish the membership accepted, or absent when none has been accepted since the membership last (re)started
or since a versionless publish. It SHALL be added by an ordered, additive migration (`ALTER TABLE memberships
ADD COLUMN manifest_version INTEGER`), which leaves every existing row absent and derives nothing — absent is
the correct value for a membership that has never published a version. The column is rebuildable state
(see "The database holds only rebuildable state"): losing it costs at most one publish being accepted out of
order, which the device's next publish supersedes.

#### Scenario: The migration leaves existing memberships unversioned

- **WHEN** the migration runs over existing memberships
- **THEN** every row survives with `manifest_version` absent, and the next versioned publish for each is
  accepted

## MODIFIED Requirements

### Requirement: A manifest publish is one atomic transaction

Recording a device manifest SHALL be atomic across every effect it has — at minimum the full-state replace
of that membership's `event_assets`, together with any other row the publish writes. A partially-applied
publish SHALL NOT be observable by any read.

On the current API version the **version comparison belongs to the same transaction**. The publish SHALL
decide whether its manifest version is at least the membership's stored `manifest_version` (or the stored
value is absent), record the new version, and replace the asset set, all in one transaction — and when the
stored version is newer it SHALL change nothing at all. The comparison SHALL NOT be a read followed by a
separate write: two publishes racing would both pass a check made outside the transaction. Every chunk of a
chunked replace SHALL be conditioned on the same decision, so a refused publish cannot apply part of itself.

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

#### Scenario: An older publish changes nothing

- **WHEN** a current-version publish carries manifest version 4 and the membership stores version 7
- **THEN** neither the stored version nor any `event_assets` row is changed, including for a publish large
  enough to be chunked

#### Scenario: A large publish stays atomic

- **WHEN** a manifest lists more assets than one statement's bound parameters allow
- **THEN** the write is split within one transaction, and no read observes a partially-replaced asset set

#### Scenario: Work outside the database follows the commit

- **WHEN** a manifest publish both writes rows and triggers an external side effect
- **THEN** the transaction commits first, and the side effect runs only after it is visible to reads


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

**One column carries a named exception on the current version: `memberships.manifest_version`.** Its writer
is the manifest publish, which records the version it accepted there; the join also writes it, but only to
**clear** it when a membership starts or restarts (capability `api-endpoints`, "Joining is an explicit
route"). It is the only `memberships` column the publish writes, and the publish writes nothing else in
`memberships`. The exception is safe for a reason the rule's own rationale names: nothing merges the two
writes and no reconciliation rule exists between them — the join's write is a lifecycle reset, not a second
source for the version, and no route but the publish reads the column. Decision record:
`changes/manifest-versions` (D7), which weighed a per-column-group split with a join generation and rejected it
as three columns for identical behaviour.

#### Scenario: The current version's manifest publish does not record uploads

- **WHEN** a manifest on the current version names resources
- **THEN** it writes the membership's asset set and nothing in `resources`

#### Scenario: Membership state changes only by join or leave on the current version

- **WHEN** a device publishes a manifest on the current version for an event it has departed
- **THEN** its membership state is unchanged, because only join and leave write it

#### Scenario: The manifest version is written by the publish and cleared only by a join

- **WHEN** the columns each current-version route writes are listed
- **THEN** the manifest publish writes `memberships.manifest_version` and no other `memberships` column, and the
  join writes it only to clear it

#### Scenario: A legacy version keeps its second writer

- **WHEN** a legacy version's manifest publish repairs a resource record or reactivates a membership
- **THEN** it continues to do so, unchanged, for as long as that version is served

