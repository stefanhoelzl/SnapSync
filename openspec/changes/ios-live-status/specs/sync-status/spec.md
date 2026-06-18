## ADDED Requirements

### Requirement: SyncStatus — loading vs ready

The status domain SHALL define a sealed `SyncStatus` in `:domain:status` (package `app.snapsync.status`) with exactly two cases:

- `Loading` — the source has not yet read persisted state; the honest "I am reading the ledger and do not yet know the result." It is a real, source-derived value, **not** a placeholder guess.
- `Ready(progress: SyncProgress)` — the source holds the whole truth as a minted `SyncProgress`.

`SyncStatus` is the vocabulary of the `SyncStatusSource` seam (not the ledger's). A source MAY seed `Loading` and later transition to `Ready`; once `Ready`, a source MUST NOT regress to `Loading`.

#### Scenario: Loading is a real value, not a placeholder
- **WHEN** a source's current value is `SyncStatus.Loading`
- **THEN** it is the genuine state "persisted state not yet read" — a consumer treats it as real data, not a default to be ignored

#### Scenario: Ready carries the whole truth
- **WHEN** a source's current value is `SyncStatus.Ready(progress)`
- **THEN** `progress` is a complete `SyncProgress` snapshot (lifetime counts and classification), with no event folding by the consumer

### Requirement: SyncProgress contract — lifetime truth, five-state classification
The status domain SHALL define
`SyncProgress(pending, completed, failed, active, estimatedRemaining: Duration?, lastFinishedAt: Instant?)`
in `:domain:status` (package `app.snapsync.status`). Counts are lifetime aggregates over ledger
keys: `pending` = keys not yet proven uploaded (absent, `REQUESTED` hopes, transient `FAILED`),
`completed` = keys with `COMPLETED` proof — never "the most recent pass". `active` is operational
state ("the backup machinery is allowed to run"), never an event-recency heuristic.
`lastFinishedAt` is the newest completion recorded in the ledger; `null` means nothing has ever
completed. The type SHALL expose a computed `state` as the single source of truth for
classification, evaluated in decision-table order:

- `!active` → **SUSPENDED**
- `pending > 0` → **IN_PROGRESS**
- `lastFinishedAt == null` → **NEVER_SYNCED**
- `failed > 0` → **INCOMPLETE**
- otherwise → **COMPLETE**

`SyncState` SHALL have exactly these five values — there is no FAILED state: under
retry-forever no key is ever given up on, and a "nothing ever completed but something finished"
state is untellable when `lastFinishedAt` is the newest completion.

#### Scenario: Machinery off outranks everything
- **WHEN** a snapshot has `active = false`, regardless of all other fields
- **THEN** the state is SUSPENDED

#### Scenario: Outstanding work classifies as in progress
- **WHEN** a snapshot has `active = true` and `pending > 0`
- **THEN** the state is IN_PROGRESS

#### Scenario: Virgin ledger classifies as never synced
- **WHEN** a snapshot has `active = true`, `pending = 0`, and `lastFinishedAt = null`
- **THEN** the state is NEVER_SYNCED

#### Scenario: Finished with casualties classifies as incomplete
- **WHEN** a snapshot has `active = true`, `pending = 0`, `lastFinishedAt != null`, and `failed > 0`
- **THEN** the state is INCOMPLETE

#### Scenario: Everything proven classifies as complete
- **WHEN** a snapshot has `active = true`, `pending = 0`, `lastFinishedAt != null`, and `failed = 0`
- **THEN** the state is COMPLETE

## MODIFIED Requirements

### Requirement: SyncStatusSource seam
The status domain SHALL define `SyncStatusSource` whose `status` is a `StateFlow<SyncStatus>` —
a level-triggered state holder whose current value is always available synchronously. The current
value is always a real `SyncStatus` (`Loading` or `Ready`); `Loading` is a genuine value
("persisted state not yet read"), never a placeholder, guess, or default. Every `Ready` value is
the whole truth; consumers never fold events.

The seam no longer promises a synchronously-available `SyncProgress`: a source backed by persisted
state cannot read it synchronously at construction, so the honest synchronous value at that moment
is `Loading`.

#### Scenario: First value without waiting
- **WHEN** a consumer reads `status.value` immediately after obtaining a source
- **THEN** it receives a real `SyncStatus` — either `Ready` with a real snapshot, or `Loading` —
  never a placeholder or default

#### Scenario: A source that knows its truth synchronously seeds Ready
- **WHEN** an in-memory source already holds the whole truth at construction
- **THEN** its `status.value` is `Ready(snapshot)` immediately, never `Loading`

### Requirement: Ledger-backed source
The status domain SHALL provide `LedgerSyncStatusSource`, constructed via a **non-suspending**
factory taking a `LedgerWatcher`, a `PermissionStatusSource`, and a `CoroutineScope`. It SHALL seed
its `status` with `SyncStatus.Loading` and, on the scope, collect the watcher's aggregates
combined with permission to emit `SyncStatus.Ready(SyncProgress)` once the ledger's current truth
is available, re-emitting a new `Ready` per input change. Each minted `SyncProgress` SHALL combine
the watcher's aggregates with the current permission: `pending`/`completed` from the aggregates,
`lastFinishedAt = newestCompletionAt`, `active = (permission == GRANTED)` — the shared
operational-state rule lives here and only here — `failed = 0` (retry-forever never gives up a key)
and `estimatedRemaining = null` (v1 does not estimate). The factory SHALL NOT block on a ledger
read before constructing; the `Loading → Ready` transition is the seam's honest representation of
that asynchronous first read.

#### Scenario: Initial value is Loading
- **WHEN** the source is constructed
- **THEN** `status.value` is `SyncStatus.Loading` synchronously, before any ledger read completes

#### Scenario: First Ready reflects the ledger
- **WHEN** the source is constructed over a ledger with 2 completed and 1 requested key
- **THEN** the source emits `SyncStatus.Ready(progress)` with `progress.completed = 2` and
  `progress.pending = 1`

#### Scenario: A ledger change re-mints a Ready snapshot
- **WHEN** a key is recorded `COMPLETED` after the first `Ready`
- **THEN** the source emits a new `Ready` whose `progress.completed` is incremented and
  `progress.lastFinishedAt` equals that completion's timestamp

#### Scenario: Permission flip re-mints a Ready snapshot
- **WHEN** permission changes from `GRANTED` to `DENIED` with no ledger activity
- **THEN** the source emits `Ready(progress)` with `progress.active = false` and unchanged counts

#### Scenario: Constants of the v1 source
- **WHEN** any `Ready` snapshot is minted by the ledger-backed source
- **THEN** `progress.failed == 0` and `progress.estimatedRemaining == null`

## REMOVED Requirements

### Requirement: SyncStatus contract — lifetime truth, five-state classification
**Reason**: The lifetime-truth snapshot data class is renamed `SyncStatus` → `SyncProgress` to free the `SyncStatus` name for the new loading-vs-ready seam wrapper. Same fields, same five-state classification — name only.
**Migration**: Replaced by the `SyncProgress contract — lifetime truth, five-state classification` requirement (identical contract under the new type name). `SyncStatus` now denotes the sealed `Loading | Ready(SyncProgress)` seam type.
