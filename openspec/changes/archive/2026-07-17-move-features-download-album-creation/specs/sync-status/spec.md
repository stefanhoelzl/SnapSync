# sync-status — delta for move-features-download-album-creation

## MODIFIED Requirements

### Requirement: SyncStatus — loading vs ready

The status domain SHALL define a sealed `SyncStatus` in `:domain`'s `model/` zone (package `app.snapsync.model`, seated there by migration step 3a) with exactly two cases:

- `Loading` — the source has not yet read persisted state; the honest "I am reading the ledger and do not yet know the result." It is a real, source-derived value, **not** a placeholder guess.
- `Ready(progress: SyncProgress)` — the source holds the whole truth as a minted `SyncProgress`.

`SyncStatus` is the vocabulary of the `SyncStatusSource` seam (not the ledger's). A source MAY seed `Loading` and later transition to `Ready`; once `Ready`, a source MUST NOT regress to `Loading`.

#### Scenario: Loading is a real value, not a placeholder
- **WHEN** a source's current value is `SyncStatus.Loading`
- **THEN** it is the genuine state "persisted state not yet read" — a consumer treats it as real data, not a default to be ignored

#### Scenario: Ready carries the whole truth
- **WHEN** a source's current value is `SyncStatus.Ready(progress)`
- **THEN** `progress` is a complete `SyncProgress` snapshot (lifetime counts and classification), with no event folding by the consumer

### Requirement: SyncProgress contract — lifetime truth, three-state classification

The status domain SHALL define
`SyncProgress(pending, completed, total, failed, active, estimatedRemaining: Duration?)`
in `:domain`'s `model/` zone (package `app.snapsync.model`, seated there by migration step 3a). `completed` is the count of the device's
**complete assets** — assets **all of whose ledger rows are `COMPLETED`** (asset-counted, from the
extension's ledger via `aggregates().completed`). `total` is the live photo-library count (the gallery
size, `N`) — **not** a storage or ledger-discovered count, so it reflects photos not yet discovered or
uploaded. `active` is operational state ("the backup machinery is allowed to run"), never an
event-recency heuristic. `pending` is the **ledger-reported in-flight asset count** — assets with **any
non-`COMPLETED` ledger row** (a job created but not yet done), from `aggregates().pending` — **clamped to
the shown remainder**: `pending = min(ledgerPending, total − completed)`. `completed` and `pending` SHALL
be read from the **same** `aggregates()` round-trip so they are mutually consistent; both are read
**read-only** from the shared ledger, and `pending` remains available but does **not** drive
classification.

The clamp is required, not defensive. The two counts come from different universes — `pending` from the
ledger, `total` from a live gallery enumeration — so a photo deleted from the library but not yet pruned
from the ledger is counted in `pending` while absent from `total`, and an unclamped `pending` then reads
above the remainder the screen shows. It is display-only: it never changes what is uploaded, only what
the count can say. `SyncProgress` carries no completion timestamp — the status surface reports completeness
and live activity only, never how long ago anything happened.

The type SHALL expose a computed `state` as the single source of truth for classification. Let
`n = min(completed, total)` (the displayed synced count, clamped so a ledger `completed` that briefly
leads the gallery total — e.g. before `photoLibraryDidChange` is processed — can never make `n` exceed
`total`). The classification, evaluated in decision-table order, SHALL be:

- `total == 0` → **NOTHING_TO_SYNC**
- `n >= total` → **COMPLETE**
- otherwise → **IN_PROGRESS**

`SyncState` SHALL have exactly these three values. There is no SUSPENDED state (the setup gate shadows
every non-`GRANTED`/not-joined case — `active = false` is never rendered as a sync state), no
NEVER_SYNCED state (it folds into `IN_PROGRESS` at `n = 0` or `NOTHING_TO_SYNC` at `total = 0`), no
INCOMPLETE and no FAILED state (untellable under retry-forever, `failed ≡ 0`).

Classification reading the ledger is safe under the **no-deletion-during-an-active-event** invariant:
storage is never reset or pruned while an event is active, so a `COMPLETED` ledger row always maps to a
durable object and the ledger cannot over-count. The sole ledger↔storage divergence point — (re)join —
is reconciled by `event-rejoin-reconciliation` (already-stored photos are seeded `COMPLETED` before
enabling).

#### Scenario: No in-scope photos classifies as nothing to sync
- **WHEN** a snapshot has `total = 0`
- **THEN** the state is NOTHING_TO_SYNC, regardless of `completed`

#### Scenario: Fewer synced than present classifies as in progress
- **WHEN** a snapshot has `total = 47` and `completed = 12`
- **THEN** the state is IN_PROGRESS with displayed `n = 12`

#### Scenario: Undiscovered photos keep the state in progress
- **WHEN** the gallery `total = 7` but the ledger has rows for only `5` assets, all `COMPLETED`
  (`completed = 5`, `pending = 0`, two photos not yet discovered)
- **THEN** the state is IN_PROGRESS (`n = 5 < 7`) — an undiscovered photo, having no ledger row, is
  counted in neither `completed` nor `pending`, so it never yields a false COMPLETE

#### Scenario: In-flight count does not change classification
- **WHEN** a snapshot has `total = 7`, `completed = 7`, and `pending = 0`
- **THEN** the state is COMPLETE (classification ignores `pending`)

#### Scenario: Virgin event with photos classifies as in progress
- **WHEN** a snapshot has `total = 5` and `completed = 0`
- **THEN** the state is IN_PROGRESS with displayed `n = 0` (never a distinct never-synced state)

#### Scenario: All present photos synced classifies as complete
- **WHEN** a snapshot has `total = 30` and `completed = 30`
- **THEN** the state is COMPLETE

