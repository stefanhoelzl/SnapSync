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

When the change's completion reports **success**, the importer SHALL record the row as imported **against
the marker it already holds**, from that same callback. The completion is the party that learns the
outcome, and it runs whether or not anything is still awaiting it — so an import whose wait was abandoned
settles itself, rather than staying unconfirmed until some later pass pays for a synchronous library
lookup to discover what the completion already knew. This write SHALL be **guarded on the marker**: a
completion that arrives after the row's marker has been cleared and replaced SHALL NOT settle that row,
because it would mark the row terminal against an identifier it no longer describes.

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

#### Scenario: An abandoned wait keeps its marker

- **WHEN** an import's wait is abandoned on its deadline
- **THEN** the marker is retained, because the transaction may still commit — and if it does, the asset
  it created remains suppressed

#### Scenario: A completion settles its row with nothing awaiting it

- **WHEN** the photo library reports a successful change for an import whose wait was already abandoned
- **THEN** the row is recorded as imported against the marker it holds, without any later pass having to
  ask the library whether the asset exists

#### Scenario: A late completion cannot settle a row whose marker moved on

- **WHEN** a completion reports success for a marker that the row no longer carries, because the marker
  was cleared and the asset re-imported under a new one
- **THEN** the row is not settled by that completion, and the marker it now holds is left intact

## ADDED Requirements

### Requirement: A row's unconfirmed state is readable for a specific marker

The store SHALL answer whether a given ref is still unconfirmed **with a given `createdLocalId`** — true
only when the row is not terminal and carries exactly that marker.

This exists because presence verdicts are computed outside the download controller's lock and applied
under it (capability `photo-download`), so the row may settle in between. Without a marker-scoped read the
only available question is "is this row unconfirmed", which cannot distinguish a row still awaiting the
verdict's own marker from a row that has since been cleared and re-imported under a different one —
and applying a stale verdict to the latter overwrites a live suppression handle.

The read SHALL be part of the store's port, so every implementation answers it identically.

#### Scenario: The marker the verdict was computed for still stands

- **WHEN** a row is non-terminal and carries the marker a verdict was computed for
- **THEN** the store answers that it is still unconfirmed with that marker, and the verdict is applied

#### Scenario: The row moved on to a different marker

- **WHEN** a row carries a different marker than the one a verdict was computed for
- **THEN** the store answers that it is not unconfirmed with that marker, and the verdict is discarded

#### Scenario: The row was settled

- **WHEN** a row has become terminal since a verdict was computed
- **THEN** the store answers that it is not unconfirmed with that marker, and the verdict is discarded
