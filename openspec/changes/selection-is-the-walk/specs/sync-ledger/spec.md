## MODIFIED Requirements

### Requirement: Reader and writer capability split

The ledger SHALL expose a concrete shared `LedgerWriter` carrying both the record operations and the
per-key query (`entry(key): LedgerEntry?`). Record and query semantics SHALL be implemented once in
this shared class, delegating storage to the injected `LedgerStore`. There SHALL be no separate
reader type: the writer is constructed only by the upload cycle's shared assembly (`uploadCore`), once per
process that runs a cycle — the app on **every** iOS version, and the extension on iOS ≥26.1 — and components
that must not record are simply never handed a writer: status read access goes through `LedgerStore`'s read
operations (`assetProgress()`, per `sync-status`), never through a writer instance. The app also invokes the
**reset family** (`clear()`, `resetTo`) on the `LedgerStore` at membership transitions — a leave clears, a
join loads (see "The ledger is the current membership's share set") — through the use cases that own them,
not through a writer. Those are not record operations (see "Storage seam — dumb row store").

**The invariant is code ownership of each write, and that each write is one guarded transaction** — not how
many processes write. Every ledger write SHALL be exactly one of: the running cycle's `LedgerWriter` record
family (and its key-scoped prune), a guarded `markTerminal` — a transport's, or the app's foreground settle of
in-flight rows the backend already stores (capability `upload-state-reconciliation`, "Foreground settles
in-flight rows the backend already stores") — or a named reset-family use case (the join-time load's
`resetTo`, the leave's `clear()`, the device reset). Each SHALL be one storage
transaction whose guard, where it has one, is inside the statement (see "Storage seam — dumb row store"), so
it is safe against any other write landing between its read and its write — from the same process or the
other one. How many processes hold a `LedgerWriter` at once is **not** an invariant: on iOS ≥26.1 under a
full grant both the app's and the extension's cycles run over the one shared ledger, and a cycle in either
process picks only `DISCOVERED` rows and records `REQUESTED` only after its job was created (write-after-act,
capability `sync-engine`), so two cycles overlapping can at worst each upload the same key's identical bytes
to the same destination, and the second terminal write of the pair is a declined no-op. Decision record:
`changes/both-uploaders-active`.

Handing a writer instance only to the cycle is the **mechanism** that confines the record family to the code
that owns it. That mechanism is deliberately relaxed for one operation: `markTerminal` (see "Guarded terminal
write") is declared on **`TransferRecord`** — a narrow interface `LedgerStore` extends, carrying only
`markTerminal` and two reads: `entryForDestination` (see "The ledger records the destination a job was sent
to") and `get(key)`, the per-key row read. A transport needs the second read to tell a job whose row an
authoritative walk deleted from one it can still settle (capability `ios-photokit-upload`, "Completion and
retry adjudication"; decision record `changes/selection-is-the-walk`, D3) — because the party the platform tells that an upload terminated is a platform callback, and it cannot
suspend. The ownership holds — the terminal write belongs to the transport whose job terminated, or to the foreground
settle that found the key's bytes stored, and its guard applies it only to a row still `REQUESTED` — while the
type-level codification does not cover it. A spec or a
review that reads the type-level rule as the invariant will reach the wrong conclusion about this call, which
is why both are stated.

A **transport** — an implementation of the upload transfer lifecycle (`BackgroundTransfer`) — SHALL receive a
`TransferRecord` and SHALL NOT receive a `LedgerStore`. What a transport may touch in the ledger is therefore
exactly the one guarded terminal write and the two row reads; every other read and write belongs to the
cycle.

No record operation other than `markTerminal` SHALL be added to `TransferRecord` or to `LedgerStore` on this
argument; a further record operation belongs on the writer. The foreground settle is a second *caller* of
`markTerminal`, not a second operation, and it SHALL record only `COMPLETED`.

#### Scenario: Writer reads what it wrote

- **WHEN** a `LedgerWriter` records an entry and `entry(key)` is called on the same instance
- **THEN** the recorded entry is returned

#### Scenario: Record access exists only where the writer is constructed

- **WHEN** a component is composed without receiving the cycle's `LedgerWriter`
- **THEN** it has no record operation available beyond `markTerminal` — it can otherwise read the ledger
  only through `LedgerStore`'s read operations, and write it only through the reset family

#### Scenario: Both processes' cycles record over one ledger

- **WHEN** on iOS ≥26.1 under a full grant the app's cycle and the extension's cycle each record through their
  own `LedgerWriter`, and a platform callback records a terminal upload through `TransferRecord`
- **THEN** every write is one guarded transaction owned by that code, and no write overwrites a settled row
  or claims a job another cycle created

#### Scenario: A transport holds only the narrow surface

- **WHEN** a transport adapter is composed
- **THEN** it is handed a `TransferRecord`, and no other ledger read or write is reachable from it

#### Scenario: The app resets the ledger through the reset family

- **WHEN** the app leaves an event or loads a join, on any iOS version
- **THEN** the owning use case calls `clear()` or `resetTo` on its `LedgerStore`, not a `LedgerWriter`, and
  records no per-key fact

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

Deletion from the **library** is a different fact, and SHALL be decided only by **presence**, never by
admission: a row is removed when an authoritative walk of the library shows its asset is gone (see
"Deletion is a presence diff over an authoritative walk"). Under a partial grant the member's selection
**is** the library, from the app's point of view, so leaving the selection is a fact of presence, not of
policy. A read selection snapshot is an authoritative walk, and de-selecting a photo removes its rows
(capability `limited-photo-access`; decision record `changes/selection-is-the-walk`, D1). The policy rules
this requirement protects — the capture range, the direction, the origin exclusions — still remove
nothing. The retired retain-live reconcile was fed the
policy-admitted set, which is exactly the conflation this requirement forbids; presence-driven deletion is
fed the walk's whole candidate set and judges only rows inside the policy's window, so narrowing the
policy moves rows **out** of the set it may delete rather than into it.

#### Scenario: A narrowing scope removes no rows
- **WHEN** the membership's capture cutoff is raised and a full enumeration then runs
- **THEN** every ledger row of an asset still in the library is retained, including those for assets now
  outside the range

#### Scenario: Turning the direction off removes no rows
- **WHEN** a contributing membership's direction is turned off and a cycle runs
- **THEN** the event's ledger rows are retained in full, so re-enabling the direction re-uploads nothing

#### Scenario: An origin exclusion removes no rows
- **WHEN** an asset with a `COMPLETED` row is still in the library and the walk returns it, but the policy's
  admission now excludes it (for example, it was added to a denylisted album)
- **THEN** its rows are retained: the walk returned it, so it is present

#### Scenario: Narrow then widen re-lists without re-uploading
- **WHEN** a member narrows their scope, a full enumeration runs, and the member then widens it back
- **THEN** the previously-uploaded assets are listed again and no byte is re-uploaded

#### Scenario: A restored asset re-uploads under its own key
- **WHEN** an asset whose rows an authoritative walk deleted is restored to the library, and the next walk
  returns it
- **THEN** its resources are recorded `DISCOVERED` again and re-uploaded under the same keys; this is the
  accepted cost of storing presence rather than remembering absence

### Requirement: Guarded terminal write

`TransferRecord` — which `LedgerStore` extends — SHALL declare `markTerminal(key, outcome): Boolean` — a
**single guarded statement** that sets a row's state **only while that row is still `REQUESTED`**, and answers
whether it applied. On the SQLDelight backend it SHALL be one
`UPDATE … WHERE key = :key AND state = 'REQUESTED'` whose applied/not-applied answer is read inside that
statement's own transaction.

`outcome` SHALL be a `TerminalOutcome` — `COMPLETED` or `FAILED`, declared in `:domain` `model/` — and not a
`LedgerState`. The outcome names what the **platform** reported; the state it records is the ledger's answer:
`COMPLETED` records `COMPLETED` — the platform reported the upload succeeded, and no further work is owed for
the key — and `FAILED` records `DISCOVERED`, returning the row to the work source (see "The DISCOVERED state and
the ledger as the upload work source"). This is the
one record operation a platform callback reaches through `TransferRecord` rather than through the writer (see
"Reader and writer capability split"), which is why the set it may record is fixed by its type rather than by
convention: a callback SHALL NOT be able to claim that a job exists (`REQUESTED`) through it.

It has exactly one caller that is not a platform callback: the app's foreground settle, which records
`COMPLETED` for a `REQUESTED` row whose bytes the backend's per-device listing reports stored (capability
`upload-state-reconciliation`, "Foreground settles in-flight rows the backend already stores"). That caller
uses the same operation, under the same guard, and SHALL NOT record `FAILED`. The guard is what lets it run
beside a cycle with no shared lock, as the callback does (decision record `changes/selection-is-the-walk`,
D4).

It SHALL be **non-suspending**, so a platform callback that cannot call a suspending function may record
through it directly.

The guard is the operation's purpose, not a defence: two writers reach this row with no shared lock — a
platform callback on the platform's own queue, and the upload cycle on the composition lane — and a
read-then-write pair is not atomic against the one that does not take the lock. Every other column
(`assetId`, the manifest detail, and the destination path) SHALL be preserved by the statement rather than
re-supplied by the caller.

A write that applies to no row SHALL be reported to the caller and **SHALL NOT be silent**: "the row moved
on" and "this fact was recorded" have different consequences.

#### Scenario: A REQUESTED row is flipped

- **WHEN** `markTerminal(key, COMPLETED)` is called for a row whose state is `REQUESTED`
- **THEN** the row becomes `COMPLETED`, every other column is unchanged, and the call answers that it applied

#### Scenario: A failed upload returns its row to the work source

- **WHEN** `markTerminal(key, FAILED)` is called for a row whose state is `REQUESTED`
- **THEN** the row becomes `DISCOVERED`, every other column is unchanged, the call answers that it applied,
  and the work-source read returns the row

#### Scenario: A row that moved on is not clobbered

- **WHEN** `markTerminal(key, FAILED)` is called for a row whose state is no longer `REQUESTED`
- **THEN** no row is changed and the call answers that it did not apply

#### Scenario: An absent row is reported, not assumed

- **WHEN** `markTerminal` is called for a key with no row
- **THEN** no row is created and the call answers that it did not apply

#### Scenario: A job's existence cannot be claimed through it

- **WHEN** a caller attempts to record `REQUESTED` through `markTerminal`
- **THEN** the build fails, because the parameter's type admits only the outcomes `COMPLETED` and `FAILED`

#### Scenario: The foreground settle and a late acknowledgement converge

- **WHEN** the foreground settle records `COMPLETED` for a `REQUESTED` row, and the OS later acknowledges
  that row's job as succeeded
- **THEN** the acknowledgement's guarded write applies to nothing, and the row stays `COMPLETED`

### Requirement: The DISCOVERED state and the ledger as the upload work source

`LedgerState` SHALL carry a `DISCOVERED` value meaning **the resource's asset was admitted and its key needs an
upload job: no job is in flight for it and its bytes are not on the backend**. It SHALL be recorded for
every resource a cycle's walk admitted and the engine judged to be new work, **before** any upload
job is created for that cycle. It SHALL also be what a failed upload returns its row to — through the engine's
failure record or a transport's terminal write — because a
failure and a never-attempted discovery are the same fact to a producer. Nothing else returns a `REQUESTED` row
to it: there is no stranded reconciliation and no bulk demote, because nothing orphans a `REQUESTED` row any
more (decision record: `changes/both-uploaders-active`). There is no separate failed state:
the engine retries forever with no attempt budget, so "an attempt was already made" decides nothing.
`LedgerState` therefore has exactly three values: `DISCOVERED`, `REQUESTED` and `COMPLETED`.

The ledger SHALL be the upload cycle's **source of work**: a producer SHALL enqueue from the ledger's
rows rather than from the walk's return value, so a cycle can make progress on work it already knows
about whatever the walk returns. Every walk is a full enumeration, but it re-reads resources only for assets
the ledger does not fully know (see "A walk re-reads only the assets the ledger does not fully know"), so a
row that needs a job is found by this read, never re-derived by the walk. The `LedgerStore` SHALL expose a
state-scoped read of the rows that need a job, and it SHALL return the `DISCOVERED` rows — whether never
attempted or returned there by a failure.

A cycle in **either** process SHALL pick its work only from these `DISCOVERED` rows, and SHALL NOT create a job
for a `REQUESTED` or `COMPLETED` row. With both uploaders active over one ledger, that is what keeps a second
cycle off the first one's in-flight work: a key the first cycle recorded `REQUESTED` is never offered to the
second, so only two cycles picking the same `DISCOVERED` key before either records can duplicate an upload —
identical bytes to the same destination, converged by the guarded terminal write (see "Reader and writer
capability split").

A row needing a job records that the policy admitted its asset **when the row was written**, which is not
the same fact as the membership's *current* admission (`photo-selection-policy`). The cycle SHALL
therefore admit the rows this read returns before resolving or enqueuing any of them, and any bound on
how much work one cycle takes SHALL be applied to the **admitted** rows — bounding what a cycle
**resolves**, never what it reads. A bound applied to the read instead can starve: rows are returned in a
stable key order, so excluded rows sorting ahead of admitted ones would fill the bound on every cycle and
admitted work further down would never be reached. The cycle resolves the admitted rows **one at a time**
and creates each one's job until the platform refuses (`LIMIT_EXCEEDED`), so a refusal wastes no resolve.
Creation is bounded only by the platform's refusal, which follows the admitted rows (capability
`sync-engine`; decision records: `changes/both-uploaders-active`, and `changes/selection-is-the-walk` D5,
which retired the resolve chunk).

A row this read returned whose key the platform resolves to **nothing** SHALL have that row, and only that
row, deleted (see "Deletion is a presence diff over an authoritative walk"). Its asset has left the library,
or, under a partial grant, left the selection. Either way it can no longer be uploaded, and a row that
still needs a job for it would be offered on every cycle. Deleting by key rather than by asset is what
leaves the asset's settled rows alone: this read selects rows by key.

`DISCOVERED` SHALL NOT be a done state, so a row in it counts toward the backlog everywhere. It SHALL
nonetheless be **included** in the device-manifest projection: the manifest declares what this device
intends to provide, and a resource the walk found and the policy admitted is precisely that (capability
`device-manifest`).

#### Scenario: A discovered resource is recorded before any job exists

- **WHEN** a cycle's walk admits a resource the engine judges to be new work
- **THEN** a `DISCOVERED` row is recorded for that resource's key before `createJob` is called for it

#### Scenario: A top-up enqueues from the ledger, not from the walk's output

- **WHEN** a cycle runs with rows in `DISCOVERED` and its walk returns no asset it has not
  already recorded
- **THEN** it resolves those rows' keys and enqueues them, rather than treating a walk with nothing new as
  no work

#### Scenario: A failed row is re-enqueued without re-reading its asset

- **WHEN** a row's upload failed, returning it to `DISCOVERED`, and its asset is fully recorded, so the walk
  skips its resources
- **THEN** the next cycle re-enqueues it from the ledger, rather than waiting for the walk to re-derive it

#### Scenario: The ledger has three states

- **WHEN** the `LedgerState` values are listed
- **THEN** they are exactly `DISCOVERED`, `REQUESTED` and `COMPLETED`, and `DISCOVERED` is the only state that
  needs a job

#### Scenario: A row that no longer resolves is deleted by key

- **WHEN** a `DISCOVERED` row's key resolves to nothing at enqueue, while a sibling row of the same asset is
  `COMPLETED`
- **THEN** the unresolved row is deleted, the `COMPLETED` sibling is untouched, and no upload is attempted
  for the deleted key

#### Scenario: A discovered row is backlog AND manifest

- **WHEN** a row is `DISCOVERED`
- **THEN** it counts toward the pending aggregate and the pending-resource read, and it appears in the
  device-manifest projection for every membership whose policy admits its asset

#### Scenario: A second cycle does not re-pick an in-flight row

- **WHEN** one cycle has created a job for a key and recorded it `REQUESTED`, and a cycle in the other process
  then reads the work source
- **THEN** that key is not among the rows needing a job, and the second cycle creates no job for it

#### Scenario: The bound applies to the resolved work, not the read

- **WHEN** the rows needing a job exceed what the platform accepts in one cycle and some of them are excluded
  by the membership's current policy
- **THEN** the cycle resolves and creates only **admitted** rows until the platform refuses, and the excluded
  ones neither consume the bound nor prevent admitted rows from being enqueued

#### Scenario: An excluded row is retained, not pruned

- **WHEN** the membership's policy stops admitting an asset whose row needs a job
- **THEN** the row is left in the ledger untouched — no state change, no prune — so widening the policy
  again re-admits it and the next cycle enqueues it with no re-read of its asset

### Requirement: Deletion is a presence diff over an authoritative walk

The upload cycle SHALL delete a ledger row because its asset left the library **only** when all of the
following hold:

1. **The walk is authoritative.** The cycle's discovery reported `fullEnumeration`. Either it read the
   library itself under a full grant and the read succeeded, so every asset inside the policy's capture
   window was returned; or, under a partial grant, it returned a selection snapshot that **has been read**,
   which is the whole gallery from the app's point of view (capability `limited-photo-access`). An
   unreadable library returns nothing and is not evidence of absence. A selection that has not been read
   yet never reaches a walk: the app's cycle is withheld while it is unread. A walk that is not
   authoritative SHALL delete nothing.
2. **The row is inside the walk's window.** The row's asset is admitted by the membership's policy through
   the same row-admission derivation the device manifest and the enqueue use (`admittedAssetIds`). The
   ledger holds rows outside the window — the join-time load seeds everything the device ever stored, for
   any event — and the walk is bounded by the policy's capture range, so a row outside that range is not
   evidence either way. A bare row (empty `creationDate`) is never admitted, so it is never
   deleted this way.
3. **The asset is absent from the walk.** Presence SHALL be the asset ids of **every** candidate the walk
   returned, before admission. Being in the library is not a question of scope.

A row's upload state SHALL NOT exempt it. A `REQUESTED` row is deleted like any other. Its transfer may
still complete, and then its guarded terminal write matches no row and applies to nothing ("Guarded
terminal write"). The bytes land and are listed in no manifest, because the manifest projects the rows. A
failure or retry the platform later presents for that key SHALL write nothing and SHALL NOT be retried
(capability `upload-lifecycle`, "A presented job whose row is gone is answered and nothing more"). The
earlier exemption kept a photo that had left the library (or the selection) listed until its job settled.
It existed only because a late terminal write for a missing row was treated as an anomaly, and it no longer
is one (decision record `changes/selection-is-the-walk`, D2).

The cycle SHALL decide the deletion from the same walk that supplies presence, and SHALL apply it through
`deleteKeys` before it records that walk's discoveries and before it publishes the device manifest, so a
departed photo is never listed by the cycle that saw it leave.

A second path deletes one row at a time: a ledger key the cycle asked the platform to resolve for a job that
resolves to **nothing** SHALL have **that row** deleted, and no other (see "The DISCOVERED state and the
ledger as the upload work source"). It needs no authoritative gate, because it only ever reaches a row that
still needs a job, and deleting one costs a re-discovery, never a photo. It SHALL NOT be reached with a
selection that has not been read: an unread scope answers no resolution at all (capability
`limited-photo-access`), because an empty answer there would delete every admitted row that needs a job.

#### Scenario: A departed in-window asset's rows are deleted
- **WHEN** an authoritative walk does not return asset `X`, whose `COMPLETED` rows carry a capture date the
  policy admits
- **THEN** the cycle deletes those rows before recording its discoveries, and the manifest it publishes no
  longer lists `X`

#### Scenario: A row outside the walk's window is kept
- **WHEN** the ledger holds a `COMPLETED` row whose capture date is before the membership's cutoff (dated
  while an earlier cutoff admitted it), and an authoritative walk does not return its asset
- **THEN** the row is kept

#### Scenario: A bare row is never deleted by the walk
- **WHEN** the ledger holds a row with an empty `creationDate` and an authoritative walk does not return its
  asset
- **THEN** the row is kept

#### Scenario: A read selection snapshot deletes a de-selected photo's rows
- **WHEN** the cycle runs under a partial grant with a read selection snapshot, and the member has
  de-selected a photo whose `COMPLETED` row is in-window
- **THEN** the discovery is authoritative, the photo's rows are deleted, and the manifest that cycle
  publishes no longer lists it

#### Scenario: An un-read selection deletes nothing
- **WHEN** the app's cycle runs under a partial grant before the selection has been read
- **THEN** the cycle is withheld and no row is deleted, by the walk or by the enqueue's resolve

#### Scenario: An unreadable walk deletes nothing
- **WHEN** the platform reports the library not readable and the discovery returns no candidates
- **THEN** no row is deleted

#### Scenario: An in-flight row is deleted with its asset
- **WHEN** an authoritative walk does not return asset `X`, one of whose rows is `REQUESTED`
- **THEN** all of `X`'s in-window rows are deleted, including the `REQUESTED` one, and the manifest that cycle
  publishes no longer lists `X`

#### Scenario: A late completion for a deleted row writes nothing
- **WHEN** the transfer of a `REQUESTED` row the walk deleted then succeeds
- **THEN** its guarded terminal write applies to no row, no row is created, and `X` stays unlisted

#### Scenario: A late failure for a deleted row writes nothing
- **WHEN** the platform presents a failure for a key whose row the walk deleted
- **THEN** no row is recorded for that key, no retry is made, and no job is created

#### Scenario: A resolve failure deletes only its own key
- **WHEN** a `DISCOVERED` row `X-live.mov` resolves to nothing while its sibling `X-primary.heic` is
  `COMPLETED`
- **THEN** `X-live.mov` is deleted and `X-primary.heic` is untouched

