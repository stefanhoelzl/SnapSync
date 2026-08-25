## MODIFIED Requirements

### Requirement: Storage seam — dumb row store
The ledger SHALL access storage exclusively through a `LedgerStore` interface with the row
operations `get(key): LedgerEntry?` and `put(entry)` (a single-row upsert), the aggregate read
`aggregates(): LedgerAggregates`, a change signal `changes: Flow<Unit>`, `clear()` — a
delete-all reset, `resetTo(entries)` — an **atomic** delete-all-then-insert-all replacement, the
asset-targeted bulk mark `markAbsent(assetId)` — set the **absent** flag on every row whose `assetId`
equals the argument, without deleting it — and the provenance sweep `backfillEventId(eventId)` (see
"Event provenance and the backfill sweep").
Backends SHALL store entries verbatim (no interpretation, no precedence logic, last write wins, no
clocks of their own). A `LedgerEntry` SHALL carry `key`, `assetId`, `state` (`REQUESTED` |
`COMPLETED` | `FAILED`), `attempt`, `eventId` — the event that was joined when the row was
recorded — and `absent`, whether the asset has since left the device's library. `clear()`, `resetTo`,
and `markAbsent` SHALL each remove, insert, or update the matching
rows and signal `changes` **once** like a `put` (so watchers re-read the now-current truth).
`clear()`, `resetTo`, and `markAbsent` are **reset/bulk** operations, not the
per-key **record** operations; recording per-upload facts remains the single-record-writer's job
(`LedgerWriter`), so a non-writer holder of the backend may reset the store without breaching the
single-record-writer invariant. `assetId` is a second opaque field: the backend stores, groups, and
matches it by equality but never interprets it (it does not know what an "asset" means — any value is
valid, set by the caller), so the ledger remains a dumb, platform-neutral row store. `eventId` is a
third opaque field with the same posture: the backend stores it verbatim and matches it by equality
only where an operation's contract says so (the backfill's sentinel match); it does not know what an
"event" means. `absent` is a stored flag the backend also never interprets: it sets it on `markAbsent`
and returns it verbatim, and no backend operation filters on it.

There SHALL be **no** operation that deletes rows by asset. A row records that a resource's bytes are
on the backend, and nothing on the device can make that false — no local action deletes an uploaded
object (capability `scheduled-cleanup` owns the only deletion, and it deletes whole events). Absence
from the library is therefore recorded, not enacted by removal.

#### Scenario: Put then get round-trips
- **WHEN** `put(entry)` is called and then `get(entry.key)`
- **THEN** the returned entry equals the one put, field for field — including `assetId`, `eventId`, and
  `absent`

#### Scenario: Put overwrites unconditionally
- **WHEN** `put` is called twice for the same key with different states
- **THEN** `get` returns the second entry — the backend applies no precedence of its own

#### Scenario: Unknown key reads null
- **WHEN** `get` is called for a key never put
- **THEN** it returns null

#### Scenario: Clear empties the store and signals
- **WHEN** `clear()` is called on a store holding rows
- **THEN** every subsequent `get` returns null, `aggregates()` reports zero pending and completed,
  and a `changes` signal is emitted

#### Scenario: Reset replaces all rows and signals once
- **WHEN** `resetTo(entries)` is called on a store holding different rows
- **THEN** every prior key not in `entries` returns null, every key in `entries` returns its supplied
  entry verbatim, and exactly one `changes` signal is emitted

#### Scenario: Mark absent flags only that asset's rows and signals
- **WHEN** the store holds rows for assetId `A` (keys `A-photo.jpg`, `A-video.mov`) and assetId `B`
  (key `B-photo.jpg`), and `markAbsent("A")` is called
- **THEN** `get("A-photo.jpg")` and `get("A-video.mov")` return rows whose `absent` is set and whose
  other fields are unchanged, `get("B-photo.jpg")` is unchanged, and a `changes` signal is emitted

#### Scenario: Marking absent is idempotent
- **WHEN** `markAbsent` is called twice for the same assetId
- **THEN** the rows are unchanged after the second call and remain readable

#### Scenario: An absent row keeps its upload state
- **WHEN** a `COMPLETED` row is marked absent
- **THEN** `get` still returns it with `state = COMPLETED`, so it continues to suppress re-upload of the
  same key

#### Scenario: eventId is stored verbatim including the sentinel
- **WHEN** one entry is put with a real `eventId` and another with the pre-provenance sentinel `""`
- **THEN** `get` returns each `eventId` exactly as supplied — the backend neither fills nor
  interprets the sentinel on a row operation

### Requirement: Prune operations are writer-only

The asset-keyed bulk mark (`markAbsent`) SHALL be exposed on
`LedgerWriter` (delegating to the backend) and SHALL NOT be exposed on any other app-facing ledger
surface. It is a sync write by the single ledger writer, not the app-side `clear()` reset, and at
the writer layer it consults no engine state first. Because only the engine's
composition root constructs a `LedgerWriter`, mark access is confined to the single-writer process,
preserving the single-writer invariant.

#### Scenario: Writer marks an asset absent

- **WHEN** a `LedgerWriter` records a row for assetId `X` (key `X-photo.jpg`) and then calls
  `markAbsent("X")`
- **THEN** `entry("X-photo.jpg")` returns a row whose `absent` is set

#### Scenario: The mark is absent from the non-writer surface

- **WHEN** a component holds the ledger only as a `LedgerStore` reader (no writer)
- **THEN** `markAbsent` is not part of its sanctioned surface — it reaches the backend only through the
  root-constructed `LedgerWriter`

## ADDED Requirements

### Requirement: The ledger is never pruned by the selection policy

The ledger SHALL record every resource whose bytes are on the backend for an event, and that record
SHALL NOT depend on the membership's current selection policy. A member narrowing their scope changes
**what they share** (capability `device-manifest`); it SHALL NOT change **what they have uploaded**.

No operation SHALL remove a row because the current policy stopped admitting its asset. Doing so
discards the record that suppresses re-upload, which makes a narrowing irreversible: re-widening would
re-upload bytes already present on the backend. In the limit — a membership whose direction excludes
upload, admitting nothing — a policy-derived removal would discard the **entire** event's rows,
defeating the drain requirement (capability `reconfigure-membership`), which exists so that a settled
upload is recorded and re-enabling the direction re-uploads nothing.

Deletion from the library SHALL be recorded by the **precise** signal — the asset identifiers the
platform change feed reports removed — and SHALL mark the rows absent rather than removing them. There
SHALL be no full-enumeration retain-live reconcile: a deletion the change feed missed leaves a row
listed, whose bytes are still on the backend, so a member still downloads it successfully. The photo
remains in the event, which is what already happens when a member leaves. Exhaustive deletion-tracking
is therefore not required.

#### Scenario: A narrowing scope removes no rows
- **WHEN** the membership's capture cutoff is raised and a fully-drained full enumeration then runs
- **THEN** every ledger row is retained, including those for assets now outside the range

#### Scenario: Turning the direction off removes no rows
- **WHEN** a contributing membership's direction is turned off and a cycle runs
- **THEN** the event's ledger rows are retained in full, so re-enabling the direction re-uploads nothing

#### Scenario: A deletion reported by the change feed marks the rows
- **WHEN** the platform change feed reports an asset removed
- **THEN** that asset's rows are marked absent and remain readable, so the next manifest projection stops
  listing it while re-upload stays suppressed

#### Scenario: Narrow then widen re-lists without re-uploading
- **WHEN** a member narrows their scope, a full enumeration runs, and the member then widens it back
- **THEN** the previously-uploaded assets are listed again and no byte is re-uploaded

#### Scenario: A restored asset does not re-upload
- **WHEN** an asset marked absent is restored to the library and discovered again
- **THEN** its `COMPLETED` row still suppresses re-upload of the same key

## REMOVED Requirements

### Requirement: Prune operations hold on the SQLDelight backend

**Reason**: `retainAssets` is removed from the seam entirely — the ledger is never pruned by policy, and
deletion is recorded by marking rather than by removal (see *The ledger is never pruned by the selection
policy*). The requirement's substance was the bind-variable-limit avoidance for `retainAssets`'
complement delete, which no longer exists: `markAbsent` is an indexed `UPDATE … WHERE assetId = ?` over
one asset, so no unbounded parameter list arises.

**Migration**: Backends drop `retainAssets` and implement `markAbsent(assetId)` as an indexed `UPDATE`
setting the `absent` column. The mark's storage-seam scenarios in *Storage seam — dumb row store* SHALL
pass against the SQLDelight backend on the JVM sqlite driver via the shared backend contract, as the
prune scenarios previously did. A ledger schema migration adds the `absent` column, defaulting to unset
for existing rows.
