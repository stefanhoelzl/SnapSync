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

### Requirement: Listing-backed source

The status domain SHALL provide a listing-backed `SyncStatusSource` constructed via a **non-suspending**
factory taking a `CompletedAssetsSource`, a `PendingManifestsSource`, a `PermissionStatusSource`, a
`GalleryStatusSource`, and a `CoroutineScope`. It SHALL seed its `status` with `SyncStatus.Loading` and,
on the scope, combine the completed-assets count, the in-flight manifest count, permission, and the
gallery size to emit `SyncStatus.Ready(SyncProgress)` once the completed-assets count, permission, **and**
gallery size have each produced a first value, re-emitting a new `Ready` per input change. Each minted
`SyncProgress` SHALL set `completed` = the completed-assets count, `pending` = the in-flight manifest
count, `total` = the gallery size, `active = (permission == GRANTED)`, `failed = 0`, and
`estimatedRemaining = null`, and SHALL carry no completion timestamp. The source SHALL read **no ledger**.

#### Scenario: Initial value is Loading

- **WHEN** the source is constructed
- **THEN** `status.value` is `SyncStatus.Loading` synchronously, before any source read completes

#### Scenario: Ready waits for completed count, permission, and gallery

- **WHEN** permission has produced a value but the completed-assets count or the gallery size has not
- **THEN** `status.value` is still `SyncStatus.Loading`, and the first `Ready` is emitted only once all three have a value

#### Scenario: A newly complete asset re-mints a Ready snapshot

- **WHEN** the completeness listing reports one more complete asset after the first `Ready`
- **THEN** the source emits a new `Ready` whose `progress.completed` is incremented

#### Scenario: A new in-flight manifest re-mints pending

- **WHEN** the in-flight manifest set gains an asset after the first `Ready`
- **THEN** the source emits a new `Ready` whose `progress.pending` is incremented

#### Scenario: Gallery and permission changes re-mint

- **WHEN** the gallery size changes, or permission flips, after the first `Ready`
- **THEN** the source emits a new `Ready` with the updated `progress.total`, respectively `progress.active`, and otherwise unchanged counts

#### Scenario: Constants of the source

- **WHEN** any `Ready` snapshot is minted
- **THEN** `progress.failed == 0` and `progress.estimatedRemaining == null`

### Requirement: CompletedAssetsSource seam

The status domain SHALL define `CompletedAssetsSource` whose value is a level-triggered holder of the
event's **complete assets** (a count, and the `assetId` set used for pruning), obtained from the
completeness listing (`GET /event/<id>/files`, via the `EventFilesSource`), with a `suspend fun refresh()`
that re-reads it. The source SHALL refresh on **foreground entry** and on **each manifest `URLSession`
completion**. It SHALL be observation-only (it SHALL NOT upload or mutate storage), and a failed listing
SHALL leave the last good value in place rather than throw. A settable fake SHALL exist for tests; the iOS
implementation SHALL use an HTTP client against the compile-time device-facing host.

#### Scenario: Refresh re-reads the complete-asset set

- **WHEN** `refresh()` is called and the listing reports assets `{A, B}`, then later `{A, B, C}`
- **THEN** the value is `{A, B}` after the first refresh and `{A, B, C}` after the second

#### Scenario: Foreground entry and manifest completion trigger a refresh

- **WHEN** the app enters the foreground, or a manifest `URLSession` upload completes
- **THEN** `CompletedAssetsSource.refresh()` is invoked

#### Scenario: A failed listing keeps the last value

- **WHEN** a `refresh()` fails (network error, non-2xx)
- **THEN** the source retains its previous value and does not throw to the status projection

### Requirement: PendingManifestsSource seam

The status domain SHALL define `PendingManifestsSource` whose value is the set of assets that have an
**on-disk manifest** in the App Group not yet reported complete (the in-flight set), with a
`suspend fun refresh()`. On refresh the source SHALL **prune** on-disk manifest files whose asset is in
the current complete-asset set (a backstop to the extension's own prune-on-success) and SHALL exclude
those assets from the in-flight set. A settable fake SHALL exist for tests; the iOS implementation SHALL
read and prune the shared App-Group manifest directory.

#### Scenario: In-flight set excludes already-complete assets

- **WHEN** an asset's manifest file is on disk but the complete-asset set already contains that asset
- **THEN** the asset is not in the in-flight set and its on-disk manifest file is pruned

#### Scenario: A started-but-incomplete asset is in flight

- **WHEN** an asset has an on-disk manifest file and is absent from the complete-asset set
- **THEN** the asset is in the in-flight set and its file is retained

### Requirement: Module placement plugs the engine leak
`SyncStatus`, `SyncState`, `SyncStatusSource`, and the listing-backed source SHALL live in
`:domain:status`, which depends on `:domain:permission` and `:domain:gallery` (and the event file-list
seam) with **implementation** scope only and SHALL **no longer depend on `:domain:engine`** (no ledger
type is reachable from status). `:domain:presentation` SHALL depend on `:domain:status` (and
`:domain:permission`) and SHALL NOT depend on `:domain:engine` or `:domain:gallery` — engine and gallery
types stay off presentation's compile classpath.

#### Scenario: Status compiles without the engine
- **WHEN** `:domain:status` is compiled
- **THEN** `:domain:engine` is not on its compile classpath and no ledger type is reachable from status code

#### Scenario: Presentation compiles without the engine or gallery
- **WHEN** `:domain:presentation` is compiled
- **THEN** neither `:domain:engine` nor `:domain:gallery` is on its compile classpath, and no engine or gallery type is reachable from presentation code

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
`SyncProgress(pending, completed, total, failed, active, estimatedRemaining: Duration?)`
in `:domain:status` (package `app.snapsync.status`). `completed` is the count of **complete assets**
reported by the completeness listing (an asset all of whose manifest-named resources are stored), counted
by PHOTO (asset). `total` is the live photo-library count (the gallery size, `N`) — **not** a storage
count, so it reflects photos not yet uploaded. `active` is operational state ("the backup machinery is
allowed to run"), never an event-recency heuristic. `pending` is the count of assets with an in-flight
on-disk manifest not yet complete; it remains available but does **not** drive classification.
`SyncProgress` carries no completion timestamp — the status surface reports completeness and live activity
only, never how long ago anything happened.

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

#### Scenario: Virgin event with photos classifies as in progress
- **WHEN** a snapshot has `total = 5` and `completed = 0`
- **THEN** the state is IN_PROGRESS with displayed `n = 0` (never a distinct never-synced state)

#### Scenario: All present photos synced classifies as complete
- **WHEN** a snapshot has `total = 30` and `completed = 30`
- **THEN** the state is COMPLETE

