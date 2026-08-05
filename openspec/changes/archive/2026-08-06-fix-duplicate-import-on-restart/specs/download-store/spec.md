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
unconfirmed row behind. A marker SHALL NOT be cleared when the import's wait is abandoned on its
deadline (`ImportResult.TimedOut`, capability `photo-download`): that transaction may still commit, and
clearing it is what orphans the created asset.

Both the marker write and its mirror SHALL be part of the **store's port**, not of one implementation, so
every store honours them and the pair is exercisable against each. They SHALL be non-suspending — alone
on that interface — because the platform's change block cannot call a suspending function and the write
must happen inside it; the constraint that creates the method shapes its signature.

#### Scenario: The marker write is available through the port

- **WHEN** any download store implementation is used
- **THEN** the created-asset marker can be recorded and cleared through the store's own interface,
  without reaching for a particular implementation

#### Scenario: Marker precedes discoverability

- **WHEN** a foreign asset is imported
- **THEN** its created `localIdentifier` is persisted to the store within the same change block that
  creates the asset, before the commit is observable

#### Scenario: An observed failure undoes its own marker

- **WHEN** the photo library reports the change failed after the block had already written a marker
- **THEN** the marker is cleared and the asset stays importable, leaving no unconfirmed row

#### Scenario: An abandoned wait keeps its marker

- **WHEN** an import's wait is abandoned on its deadline
- **THEN** the marker is retained, because the transaction may still commit — and if it does, the asset
  it created remains suppressed

### Requirement: Handle-carrying rows are permanent

A row carrying a `createdLocalId` SHALL NOT be cleared by leave, by an event switch, or by a durable
state reset, and the store SHALL NOT be wiped on those transitions — **whether or not that row has
reached a terminal state**. The marker, not the state, is the record that an asset was created; deleting
a row that still carries one destroys the only evidence that the created asset must never be uploaded,
and the asset then echoes back into the event.

This makes a downloaded asset permanently recognized — never re-downloaded after deletion, and
deduplicated across events. Non-terminal rows that carry **no** marker MAY be dropped on leave, switch,
or reset, to be re-enqueued later.

#### Scenario: Leave and switch preserve terminal rows

- **WHEN** the user leaves the event or switches to another event
- **THEN** terminal imported rows (and thus the suppression set) are preserved, while non-terminal rows
  carrying no marker may be discarded

#### Scenario: Leave preserves an unconfirmed row's marker

- **WHEN** the user leaves or switches while a row is `PENDING` and carries a `createdLocalId`
- **THEN** that row and its marker survive, so the asset it created is still suppressed from upload

#### Scenario: A durable state reset preserves markers

- **WHEN** this device's durable sync state is reset
- **THEN** every row carrying a `createdLocalId` is retained, on the same reasoning as leave

## ADDED Requirements

### Requirement: An asset already created for a ref is never created again

The store SHALL expose the unconfirmed rows — those whose `state` is not terminal and whose
`createdLocalId` is non-null — so the import path can adjudicate them before creating a second asset for
the same `(sourceDeviceId, sourceAssetId)`. Selecting work to import SHALL NOT treat a row carrying a
marker as ordinary pending work.

The store SHALL hold at most one `createdLocalId` per ref, and recording a confirmed import MAY
overwrite it — but only for a ref whose prior marker has been adjudicated, never as a way of discarding
one. A marker overwritten while its asset still exists removes that asset from the suppression set,
which is the defect this requirement exists to prevent.

#### Scenario: An unconfirmed row is not offered as ordinary import work

- **WHEN** the import path selects assets whose resources are all staged
- **THEN** a row carrying a `createdLocalId` is adjudicated rather than imported outright

#### Scenario: The suppression set includes unconfirmed markers

- **WHEN** a row is `PENDING` and carries a `createdLocalId`
- **THEN** that identifier appears in the suppression projection the upload side reads, so the created
  asset is never uploaded

### Requirement: Staged bytes are released only once their row is settled

The store SHALL expose the staged paths of an asset's resources, of all assets whose import is
confirmed, and of all rows about to be dropped — so the download side can release those bytes. Releasing
an asset's bytes SHALL also drop that asset's resource rows, so the store never records a staged path
for a file that no longer exists, and so a backlog pass over already-imported assets is
**self-extinguishing**.

Staged bytes SHALL be released **only** after the confirming write has committed, or immediately before
the rows referencing them are dropped. They SHALL NOT be released while an import is unconfirmed,
failed, or abandoned on its deadline: those bytes are the only source for the retry, and a resource
already recorded as staged is never re-downloaded, so releasing early loses the photo permanently.

#### Scenario: Bytes survive a failed or abandoned import

- **WHEN** an import reports failure, or its wait is abandoned on its deadline
- **THEN** the asset's staged bytes are retained and the retry imports from them

#### Scenario: Bytes are released once the import is confirmed

- **WHEN** an asset's import is confirmed
- **THEN** its staged bytes are released and its resource rows dropped, while the asset row and its
  marker are retained

#### Scenario: A backlog pass runs once and finds nothing thereafter

- **WHEN** a release pass runs over assets whose import is confirmed but whose resource rows remain
- **THEN** their bytes are released and their rows dropped, so a second pass finds no work
