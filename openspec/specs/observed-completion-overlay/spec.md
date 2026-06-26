# observed-completion-overlay Specification

## Purpose
TBD - created by archiving change observed-completion-overlay. Update Purpose after archive.
## Requirements
### Requirement: Observed-completions seam

The status domain SHALL define an `ObservedCompletionsSource` seam exposing the set of resource keys
the platform reports as **successfully uploaded but not yet recorded in the ledger**: a
`StateFlow<Set<String>>` of keys (each a `LedgerEntry.key`) and a `suspend fun refresh()` that
re-reads the platform and updates that flow with the **current** succeeded set (replace, not
accumulate — keys leave as the platform releases them). The seam SHALL be observation-only: obtaining
or refreshing the set SHALL NOT mutate the ledger or the platform's job state. A no-op implementation
(empty set, `refresh()` does nothing) SHALL be available so the overlay is inert where no platform
observation exists (e.g. the desktop harness).

#### Scenario: Refresh replaces the observed set

- **WHEN** `refresh()` is called and the platform reports keys `{a, b}`, then later reports `{b, c}`
- **THEN** the flow's value is `{a, b}` after the first refresh and `{b, c}` after the second (the
  released key `a` is gone, not retained by the source)

#### Scenario: No-op source yields the empty set

- **WHEN** the no-op `ObservedCompletionsSource` is used and `refresh()` is called
- **THEN** the flow's value is always the empty set

### Requirement: Overlay promotion

The status projection SHALL compute a photo (assetId) as **complete** when every one of its
still-outstanding resource keys is in the observed set, combining the ledger snapshot with the
observed keys purely (no clock, no ledger write):

- `promoted` = count of pending photos all of whose `pendingByAsset` keys are in the observed set
- `completed' = snapshot.completed + promoted`
- `pending' = (count of pending photos) − promoted`
- the completion timestamp is taken verbatim from the ledger snapshot; the overlay SHALL NOT fabricate
  a timestamp

An empty observed set SHALL yield `promoted = 0` (the projection equals the ledger snapshot). A photo
is "outstanding" on any non-`COMPLETED` ledger row, so a stale `FAILED` row whose key is observed
SHALL promote like a `REQUESTED` one. Promoted photos MAY drive the terminal complete classification
before any ledger write records them.

#### Scenario: A photo promotes when all its resources are observed

- **WHEN** the snapshot reports `completed = 2` and one pending photo with keys `{p-photo.jpg,
  p-video.mov}`, and the observed set is `{p-photo.jpg, p-video.mov}`
- **THEN** the projection reports `completed = 3` and `pending = 0`

#### Scenario: A partially-observed photo does not promote

- **WHEN** a pending photo has keys `{p-photo.jpg, p-video.mov}` and the observed set is
  `{p-photo.jpg}`
- **THEN** that photo stays pending and `completed` is unchanged

#### Scenario: Empty observation is the identity

- **WHEN** the observed set is empty
- **THEN** the projected `completed` and `pending` equal the ledger snapshot's, unchanged

### Requirement: Sticky retention

The projection SHALL retain an observed key in effect until the ledger snapshot confirms its photo is
no longer outstanding, so a key released by the platform (e.g. acknowledged) before the ledger ding
arrives does not blink its photo backward. The retained set SHALL be `S' = (S ∪ freshlyObserved) ∩
(keys present in the snapshot's pendingByAsset)`, so a key is dropped exactly when it leaves the
backlog (recorded `COMPLETED` or pruned), and the retained set stays bounded by the backlog.

#### Scenario: A released key is retained until the ledger confirms it

- **WHEN** key `p-photo.jpg` was observed and is still in the snapshot's `pendingByAsset`, and the
  next `refresh()` no longer reports it
- **THEN** `p-photo.jpg` remains in effect for the overlay (its photo does not revert to pending)

#### Scenario: A confirmed key is dropped from the retained set

- **WHEN** a previously observed key is no longer present in the snapshot's `pendingByAsset` (its row
  reached `COMPLETED` or was pruned)
- **THEN** the key is dropped from the retained set, and the snapshot's `completed` already accounts
  for it

### Requirement: Foreground-and-pending refresh cadence

The status container SHALL drive `ObservedCompletionsSource.refresh()` so progress stays live while
the screen is shown: it SHALL refresh once when foreground is entered, then re-refresh on a bounded
interval **while** foreground is active AND the projected pending count is greater than zero, and SHALL
stop refreshing when pending reaches zero or foreground is lost. Driving SHALL NOT block state
rendering. The cadence SHALL be gated on a foreground signal supplied to the container.

#### Scenario: Polls while work remains in foreground

- **WHEN** the container is foreground and the projected pending count is greater than zero
- **THEN** it calls `refresh()` and continues to call it on the interval until pending reaches zero

#### Scenario: Stops when drained

- **WHEN** the projected pending count reaches zero
- **THEN** the container stops calling `refresh()`

#### Scenario: Stops when backgrounded

- **WHEN** the foreground signal becomes false
- **THEN** the container stops calling `refresh()` until foreground returns

