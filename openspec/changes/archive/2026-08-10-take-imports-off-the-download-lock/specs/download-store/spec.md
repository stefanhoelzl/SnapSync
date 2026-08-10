## ADDED Requirements

### Requirement: A marker write that lands on no row is loud

The in-block marker write SHALL report whether it updated a row, and a write that matched **none** SHALL be
logged at a severity that reaches the crash-reporting sink (capability `crash-reporting`), naming the ref.

That outcome means the row was deleted between the import being selected and its change block running — the
failure the prune's protection exists to prevent (below). Its consequence is that an asset is created with no
suppression handle, so the device uploads a downloaded photo back into the event days later, with nothing
anywhere recording why. Today that write returns nothing and tells no one, which makes the protection a
safety gate whose failure is only ever inferred from its damage. "The marker was written" and "the marker
landed on nothing" are different answers with different consequences, so they SHALL be distinguishable.

The ordinary case costs nothing: the line never fires, because the row is always there.

#### Scenario: A marker write onto a surviving row is silent

- **WHEN** an import's change block writes its marker and the row exists
- **THEN** the write is recorded and no failure is reported

#### Scenario: A marker write onto a deleted row is reported

- **WHEN** an import's change block writes its marker and no row matches
- **THEN** the failure is logged at a severity that reaches the crash-reporting sink, naming the ref

## MODIFIED Requirements

### Requirement: Suppression marker written before the asset is observable

The `createdLocalId` SHALL be obtained from the import's `placeholderForCreatedAsset` and written into
the store **inside the `performChanges` change block**, before the change commits — so the created
asset is recorded as suppressed before it can be observed by the upload extension's discovery.

Because the block always runs to completion before the library commits, **a created asset always has a
recorded marker**: there is no window in which an asset exists and its marker does not. The marker is
therefore the store's record that an irreversible act was requested, and the pair
`state = PENDING` + a non-null `createdLocalId` SHALL be read as **"an asset was created for this ref,
and its import is unconfirmed"** — not as "not yet imported".

When the change's completion reports **failure**, the importer SHALL **clear** the marker it wrote — the
exact mirror of the in-block write, in the same callback — so an observed failure never leaves an
unconfirmed row behind. A marker SHALL be cleared for exactly one reason: the library said its change
failed. It SHALL NOT be cleared because time passed, because nothing is awaiting the transaction any longer,
or because a presence lookup answered *absent* while that transaction was still open — it may still commit,
and clearing it is what orphans the created asset (capability `photo-download`).

When the change's completion reports **success**, the importer SHALL record the row as imported **against
the marker it already holds**, from that same callback. The completion is the party that learns the
outcome, and it runs whether or not anything is still awaiting it — so an import whose outcome arrives after
its requester is gone settles itself, rather than staying unconfirmed until some later pass pays for a
synchronous library lookup to discover what the completion already knew.

The **clearing** and the **confirming** writes SHALL both be **guarded on the marker**: each SHALL take
effect only while the row still carries the marker the write was computed for, and each SHALL report whether
it did. A completion or verdict that arrives after the row's marker has been cleared and replaced SHALL
change nothing — confirming would mark the row terminal against an identifier it no longer describes, and
clearing would strip a live suppression handle off a row that has already settled, which is permanent
because a terminal row is never adjudicated or re-imported again.

The guard SHALL live in the store's write, not in a caller's preceding read. Two writers reach these with no
shared lock — the callback writes are non-suspending precisely because the platform's change and completion
blocks cannot call a suspending function — so a read-then-write pair is not atomic against the writer that
does not take the caller's lock. A store SHALL NOT expose a separate marker-scoped read for callers to guard
themselves with; the guarded write is the mechanism.

The in-block marker write is **not** guarded, and SHALL NOT be: it *creates* the addressing the other two
match, so there is nothing for it to match against.

All three callback writes — the marker, its failure mirror, and the confirming write — SHALL be part of
the **store's port**, not of one implementation, so every store honours them and they are exercisable
against each. They SHALL be non-suspending — alone on that interface — because the platform's change block
and completion callback cannot call a suspending function; the constraint that creates the methods shapes
their signature.

#### Scenario: The marker write is available through the port

- **WHEN** any download store implementation is used
- **THEN** the created-asset marker can be recorded, cleared, and confirmed through the store's own
  interface, without reaching for a particular implementation

#### Scenario: Marker precedes discoverability

- **WHEN** a foreign asset is imported
- **THEN** its created `localIdentifier` is persisted to the store within the same change block that
  creates the asset, before the commit is observable

#### Scenario: An observed failure undoes its own marker

- **WHEN** the photo library reports the change failed after the block had already written a marker
- **THEN** the marker is cleared and the asset stays importable, leaving no unconfirmed row

#### Scenario: An unreported outcome keeps its marker

- **WHEN** an import's outcome has not been reported by the library
- **THEN** the marker is retained, because the transaction may still commit — and if it does, the asset
  it created remains suppressed

#### Scenario: A completion settles its row with nothing awaiting it

- **WHEN** the photo library reports a successful change for an import whose requester is gone
- **THEN** the row is recorded as imported against the marker it holds, without any later pass having to
  ask the library whether the asset exists

#### Scenario: A late completion cannot settle a row whose marker moved on

- **WHEN** a completion reports success for a marker that the row no longer carries, because the marker
  was cleared and the asset re-imported under a new one
- **THEN** the row is not settled by that completion, and the marker it now holds is left intact

#### Scenario: A stale clear cannot strip a settled row's marker

- **WHEN** a clear is applied for a marker that the row no longer carries, or to a row that has become
  terminal
- **THEN** the write matches no row, the marker is left intact, and the caller is told it did not apply

### Requirement: Handle-carrying rows are permanent

A row carrying a `createdLocalId` SHALL NOT be cleared by leave, by an event switch, or by a durable
state reset, and the store SHALL NOT be wiped on those transitions — **whether or not that row has
reached a terminal state**. The marker, not the state, is the record that an asset was created; deleting
a row that still carries one destroys the only evidence that the created asset must never be uploaded,
and the asset then echoes back into the event.

A row whose import has been **claimed but whose change block has not yet run** carries no marker and is
equally protected: the caller SHALL name the refs whose imports are in flight, and the prune SHALL spare
their rows. Deleting one makes its marker write land on nothing, so the asset it creates has no suppression
handle at all. That protection is required of the caller and SHALL carry no default value — a permissive
default on a safety gate is how a caller ships without one, and an empty set SHALL be a claim rather than an
omission.

The prune SHALL drop its rows and return the staged paths those rows owned, in **one** operation, so the
caller frees exactly the files it stranded. Reading the paths and pruning separately is two reads at two
instants over a store that the platform's change and completion blocks mutate without taking any lock; a
marker cleared in that gap turns a row the read protected into a row the prune deletes, leaving its files on
disk with nothing referencing them — unfindable, and surviving relaunch.

This makes a downloaded asset permanently recognized — never re-downloaded after deletion, and
deduplicated across events. Non-terminal rows that carry **no** marker and are **not** protected MAY be
dropped on leave, switch, or reset, to be re-enqueued later.

#### Scenario: Leave and switch preserve terminal rows

- **WHEN** the user leaves the event or switches to another event
- **THEN** terminal imported rows (and thus the suppression set) are preserved, while non-terminal rows
  carrying no marker and not protected may be discarded

#### Scenario: Leave preserves an unconfirmed row's marker

- **WHEN** the user leaves or switches while a row is `PENDING` and carries a `createdLocalId`
- **THEN** that row and its marker survive, so the asset it created is still suppressed from upload

#### Scenario: A claimed row survives a prune that has not seen its marker yet

- **WHEN** a prune runs while an import is claimed for a ref whose change block has not run, so its row
  carries no marker
- **THEN** that row is spared, and the marker its change block later writes lands on a row that exists

#### Scenario: The prune frees exactly what it stranded

- **WHEN** a prune drops rows that owned staged files
- **THEN** it returns those files' paths, so the caller releases exactly them and no file is left with
  nothing referencing it

#### Scenario: A durable state reset preserves markers

- **WHEN** this device's durable sync state is reset
- **THEN** every row carrying a `createdLocalId` is retained, on the same reasoning as leave, and every
  protected row is retained too

### Requirement: Staged bytes are released only once their row is settled

The store SHALL expose the staged paths of an asset's resources and of all assets whose import is
confirmed, and SHALL return the staged paths of the rows a prune drops as part of that prune — so the
download side can release those bytes. Releasing an asset's bytes SHALL also drop that asset's resource
rows, so the store never records a staged path for a file that no longer exists, and so a backlog pass over
already-imported assets is **self-extinguishing**.

Staged bytes SHALL be released **only** after the confirming write has committed, or as part of dropping
the rows referencing them. They SHALL NOT be released while an import is unconfirmed, failed, or still
claimed: those bytes are the only source for the retry, and a resource already recorded as staged is never
re-downloaded, so releasing early loses the photo permanently.

#### Scenario: Bytes survive a failed or unreported import

- **WHEN** an import reports failure, or its outcome has not been reported
- **THEN** the asset's staged bytes are retained and the retry imports from them

#### Scenario: Bytes are released once the import is confirmed

- **WHEN** an asset's import is confirmed
- **THEN** its staged bytes are released and its resource rows dropped, while the asset row and its
  marker are retained

#### Scenario: A backlog pass runs once and finds nothing thereafter

- **WHEN** a release pass runs over assets whose import is confirmed but whose resource rows remain
- **THEN** their bytes are released and their rows dropped, so a second pass finds no work

## REMOVED Requirements

### Requirement: A row's unconfirmed state is readable for a specific marker

**Reason**: It existed solely so a caller could guard itself before an unguarded write — *"presence verdicts
are computed outside the download controller's lock and applied under it, so the row may settle in between"*.
That check-then-act pair is not atomic against the photo library's completion callback, which writes from
the platform's own queue and takes no lock, so the read never closed the window it was introduced for. With
both the clearing and the confirming writes guarded on the marker and reporting whether they applied, the
read has no callers and offering it invites the same non-atomic pattern back.

**Migration**: Callers that read this before writing SHALL instead apply the write and act on its result.
The guarded write answers the same question atomically, at the moment it matters.
