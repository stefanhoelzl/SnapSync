# sync status Specification

## Purpose

The status projection: the user-facing truth about the backup, minted from the engine's ledger.
Defines the `SyncStatus` snapshot contract (lifetime counts × the live gallery total, three-state
classification), the `SyncStatusSource` seam presentation consumes, and the ledger-backed source that
combines the ledger's aggregate stream with permission-derived operational state and the live gallery
size. Lives in `:domain:status`,
which plugs the engine-type leak toward presentation. Authoritative design: docs/design.md §2.4.
## Requirements
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
factory taking a `LedgerWatcher`, a `PermissionStatusSource`, a `GalleryStatusSource`, an
`ObservedCompletionsSource`, and a `CoroutineScope`. It SHALL seed its `status` with
`SyncStatus.Loading` and, on the scope, collect the watcher's **snapshot** combined with permission,
the gallery size, and the observed-completions set to emit `SyncStatus.Ready(SyncProgress)` once the
snapshot, permission, **and** gallery size have each produced a first value, re-emitting a new `Ready`
per input change. The observed set seeds synchronously (an empty set is a valid first value) and so
SHALL NOT delay the first `Ready`. Before minting, the source SHALL apply the **overlay** to the
snapshot using the observed set (a pending photo all of whose outstanding keys are observed is counted
complete), retaining observed keys per the **sticky** rule so a released key does not blink its photo
backward. Each minted `SyncProgress` SHALL combine the overlaid counts with the current permission and
gallery size: `completed` = overlaid completed, `pending` = overlaid pending, `total` = the gallery
size, `lastFinishedAt = newestCompletionAt` (taken from the snapshot — never fabricated by the
overlay), `active = (permission == GRANTED)` — the shared operational-state rule lives here and only
here — `failed = 0` (retry-forever never gives up a key) and `estimatedRemaining = null` (this version
does not estimate). With an empty observed set the overlay is the identity, so the minted counts equal
the ledger snapshot's. The factory SHALL NOT block on a source read before constructing; the
`Loading → Ready` transition is the seam's honest representation of those asynchronous first reads.

#### Scenario: Initial value is Loading
- **WHEN** the source is constructed
- **THEN** `status.value` is `SyncStatus.Loading` synchronously, before any source read completes

#### Scenario: Ready waits for snapshot, permission, and gallery
- **WHEN** the snapshot and permission have produced a first value but the gallery size has not
- **THEN** `status.value` is still `SyncStatus.Loading`, and the first `Ready` is emitted only once the
  gallery size also produces a value (the observed set, seeding empty, does not gate)

#### Scenario: First Ready reflects ledger and gallery
- **WHEN** the source is constructed over a ledger with 2 completed photos, a gallery size of 5, and
  an empty observed set
- **THEN** the source emits `SyncStatus.Ready(progress)` with `progress.completed = 2` and
  `progress.total = 5`

#### Scenario: A ledger change re-mints a Ready snapshot
- **WHEN** a photo's last resource is recorded `COMPLETED` after the first `Ready`
- **THEN** the source emits a new `Ready` whose `progress.completed` is incremented and
  `progress.lastFinishedAt` equals that completion's timestamp

#### Scenario: An observed completion promotes a pending photo before any ledger write
- **WHEN** the snapshot has one pending photo with outstanding keys `{p-photo.jpg, p-video.mov}` and
  a `refresh()` makes the observed set `{p-photo.jpg, p-video.mov}`, with no ledger change
- **THEN** the source emits a new `Ready` whose `progress.completed` is incremented and
  `progress.pending` is decremented, while `progress.lastFinishedAt` is still the ledger snapshot's
  value (the overlay fabricates no timestamp)

#### Scenario: A released observed key does not revert its photo
- **WHEN** a photo was promoted by an observed key and the next `refresh()` no longer reports that key,
  but the snapshot still lists it as outstanding (the ledger ding has not yet arrived)
- **THEN** the photo stays counted complete (sticky retention), and only once the snapshot records it
  `COMPLETED` does the source rely on the ledger for it

#### Scenario: A gallery change re-mints a Ready snapshot
- **WHEN** the gallery size changes after the first `Ready` with no ledger activity
- **THEN** the source emits a new `Ready` with the updated `progress.total` and unchanged counts

#### Scenario: Permission flip re-mints a Ready snapshot
- **WHEN** permission changes from `GRANTED` to `DENIED` with no ledger activity
- **THEN** the source emits `Ready(progress)` with `progress.active = false` and unchanged counts

#### Scenario: Constants of the source
- **WHEN** any `Ready` snapshot is minted by the ledger-backed source
- **THEN** `progress.failed == 0` and `progress.estimatedRemaining == null`

### Requirement: Module placement plugs the engine leak
`SyncStatus`, `SyncState`, `SyncStatusSource`, and `LedgerSyncStatusSource` SHALL live in
`:domain:status`, which depends on `:domain:engine`, `:domain:permission`, and `:domain:gallery` with
**implementation** scope only. `:domain:presentation` SHALL depend on `:domain:status` (and
`:domain:permission`) and SHALL NOT depend on `:domain:engine` or `:domain:gallery` — engine types
(events, decisions, jobs, ledger) and gallery types stay off presentation's compile classpath.

#### Scenario: Presentation compiles without the engine or gallery
- **WHEN** `:domain:presentation` is compiled
- **THEN** neither `:domain:engine` nor `:domain:gallery` is on its compile classpath, and no engine or
  gallery type is reachable from presentation code

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

### Requirement: SyncProgress contract — lifetime truth, three-state classification
The status domain SHALL define
`SyncProgress(pending, completed, total, failed, active, estimatedRemaining: Duration?, lastFinishedAt: Instant?)`
in `:domain:status` (package `app.snapsync.status`). `completed` is a lifetime aggregate over the
ledger, counted by PHOTO (asset): photos all of whose resources are `COMPLETED`. `total` is the live
photo-library count (the gallery size, `N`) — **not** a ledger count, so it reflects photos the ledger
has not yet discovered. `active` is operational state ("the backup machinery is allowed to run"), never
an event-recency heuristic. `lastFinishedAt` is the newest completion recorded in the ledger; `null`
means nothing has ever completed. `pending` remains available (ledger photos not yet complete) but does
**not** drive classification.

The type SHALL expose a computed `state` as the single source of truth for classification. Let
`n = min(completed, total)` (the displayed synced count, clamped so a not-yet-pruned deleted photo can
never make `n` exceed `total`). The classification, evaluated in decision-table order, SHALL be:

- `total == 0` → **NOTHING_TO_SYNC**
- `n >= total` → **COMPLETE**
- otherwise → **IN_PROGRESS**

`SyncState` SHALL have exactly these three values. There is no SUSPENDED state (the setup gate shadows
every non-`GRANTED`/not-joined case — `active = false` is never rendered as a sync state), no
NEVER_SYNCED state (it folds into `IN_PROGRESS` at `n = 0` or `NOTHING_TO_SYNC` at `total = 0`), no
INCOMPLETE and no FAILED state (untellable under retry-forever, `failed ≡ 0`).

#### Scenario: No in-scope photos classifies as nothing to sync
- **WHEN** a snapshot has `total = 0`
- **THEN** the state is NOTHING_TO_SYNC, regardless of `completed`

#### Scenario: Fewer synced than present classifies as in progress
- **WHEN** a snapshot has `total = 47` and `completed = 12`
- **THEN** the state is IN_PROGRESS with displayed `n = 12`

#### Scenario: Virgin ledger with photos classifies as in progress
- **WHEN** a snapshot has `total = 5`, `completed = 0`, and `lastFinishedAt = null`
- **THEN** the state is IN_PROGRESS with displayed `n = 0` (never a distinct never-synced state)

#### Scenario: All present photos synced classifies as complete
- **WHEN** a snapshot has `total = 47` and `completed = 47`
- **THEN** the state is COMPLETE

#### Scenario: Completed overshooting total clamps and classifies as complete
- **WHEN** a snapshot has `total = 5` and `completed = 6` (a deleted photo not yet pruned)
- **THEN** the state is COMPLETE and the displayed `n` is `5`, never `6`

