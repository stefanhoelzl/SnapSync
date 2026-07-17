# sync status Specification

## Purpose

The status projection: the user-facing truth about what this device has shared, minted from the engine's
ledger. Defines the `SyncStatus` snapshot contract (lifetime counts × the live gallery total, three-state
classification), the `SyncStatusSource` seam presentation consumes, and the ledger-backed source that
combines the ledger's aggregate stream with permission-derived operational state and the live gallery
size. Lives in `:domain:status`, which plugs the engine-type leak toward presentation.

**Why snapshots, not an event stream.** On iOS the uploads run in a separate process while the app is
suspended or dead, so the app can only ever learn what happened by reading persisted state — the UI is
inherently a projection, not a fold over events it witnessed. An event seam would duplicate the engine's fold
into presentation with drift risk. Snapshots are self-healing: every emission is the whole truth, so there is
no late-subscriber problem, no missed-event corruption, conflation is safe, and first render is the same code
path as any update. Platform signals (library change, foreground entry, the extension's cross-process liveness
ding, a join) are **invalidation dings** handled inside the source — they trigger a re-read and a fresh
emission, and none of them leak into the contract.

Decision record: `changes/archive/2026-06-12-status-core` (the snapshot seam),
`changes/archive/2026-07-05-notify-driven-status` (the ledger-sourced, notify-driven source).
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

### Requirement: Module placement plugs the engine leak
`SyncStatus`, `SyncState`, `SyncStatusSource`, and the ledger-backed source SHALL live in
`:domain:status`, which depends on `:domain:permission` and `:domain:gallery` (and the event file-list
seam) with **implementation** scope only and SHALL **declare no dependency on `:domain:engine`**. No
status source file SHALL reference an engine type — no import, and no fully-qualified
`app.snapsync.engine.…` — so the ledger status was freed from cannot be reached back for. This SHALL be
mechanically guarded (`architecture-guards`): the compiler is content for status to import `LedgerWriter`,
which is precisely the problem.

Engine is nonetheless **on** status's compile classpath, transitively and unavoidably: `:domain:gallery`
`api`-exports `:domain:engine` because `PhotoLibrary.enumerate()` returns `List<Resource>`, and
status consumes that seam. Status therefore *uses* an engine type by inference — legitimately; that is what
the seam is for — while *naming* none. The claim made here is the one that is true and can be held: a
stricter sentence sat in this spec for weeks while a probe importing `LedgerWriter` into `:domain:status`
compiled. Cleaning the classpath itself would mean moving `Resource` out of engine; see the decision record.

`:domain:presentation` SHALL depend on `:domain:status` (and
`:domain:permission`) and SHALL NOT depend on `:domain:engine` or `:domain:gallery` — engine and gallery
types stay off presentation's compile classpath.

#### Scenario: Status names no engine type
- **WHEN** `:domain:status`'s source is inspected
- **THEN** it declares no `:domain:engine` dependency and no file references `app.snapsync.engine` — by import or fully qualified — so no ledger type is named in status code

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
in `:domain:status` (package `app.snapsync.status`). `completed` is the count of the device's
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

### Requirement: Independent download-progress projection

The status surface SHALL expose download progress as an **independent** indicator, separate from the
own-device upload status: a count of foreign complete assets imported (`downloaded`) out of the
foreign complete assets currently in the union (`total`), asset-counted to match the upload progress
convention. `DownloadProgress` SHALL additionally carry an **`inFlight`** count — the number of
foreign assets with at least one resource whose download has been **sent to the OS but not yet
staged** (the download analogue of `SyncProgress.pending`). `inFlight` is **display-only**: it drives
only the live-activity signal of the download direction arrow (per `sync-status-screen`) and SHALL NOT
alter the `downloaded`/`total` completeness notion. This projection SHALL NOT alter the own-device
upload "Completed" notion — uploads are "done" when the device's own qualifying assets are all present
in storage, regardless of download progress. `total` MAY grow as other contributors add assets, and
the indicator SHALL reflect that honestly. `inFlight` SHALL be sourced from the `download-store`
`inFlightCount()` read and refreshed on foreground entry alongside `downloaded`/`total`.

#### Scenario: Download line is independent of upload completion

- **WHEN** the device's own uploads are complete but foreign downloads are still in progress
- **THEN** the download projection reports its own `downloaded`/`total`/`inFlight`; upload "Completed"
  and download progress do not gate each other

#### Scenario: Download denominator is foreign complete assets

- **WHEN** the union reports `total` foreign complete assets and `downloaded` of them are imported
- **THEN** the projection reports `downloaded` of `total`, asset-counted

#### Scenario: In-flight reflects downloads sent to the OS

- **WHEN** `k` foreign assets have a resource download enqueued to the OS and not yet staged
- **THEN** `DownloadProgress.inFlight == k`; **WHEN** all such downloads have staged or none are
  enqueued, `inFlight == 0`

#### Scenario: Denominator grows with new contributions

- **WHEN** other contributors add complete assets to the event
- **THEN** `total` increases accordingly on the next union read, with no false "all downloaded" state

### Requirement: Ledger-backed source

The status domain SHALL provide a **ledger-backed** `SyncStatusSource` constructed via a
**non-suspending** factory taking a `LedgerCountsSource`, a `PhotoAccessStatusSource`, a
`GalleryStatusSource`, and a `CoroutineScope`. Status is **own-device progress** derived from (a) the
ledger's asset-counted `completed` and `pending` (via `LedgerCountsSource`); (b) permission; and (c) the
gallery total. The source SHALL read completeness and in-flight **only** through the `LedgerCountsSource`
and SHALL issue **no** storage LIST for upload status — `completed` is the ledger's complete-asset count,
`total` is the gallery count.

It SHALL seed its `status` with `SyncStatus.Loading` and, on the scope, combine the ledger counts,
permission, and the gallery size to emit `SyncStatus.Ready(SyncProgress)` once **all three** have each
produced a first value, re-emitting a new `Ready` per input change. Each minted `SyncProgress` SHALL set
`completed` = the ledger complete-asset count, `pending` = the ledger in-flight count **clamped to
`total − completed`**, `total` = the
gallery size, `active = (permission == GRANTED)`, `failed = 0`, and `estimatedRemaining = null`, and
SHALL carry no completion timestamp.

**Liveness is event-driven, not polled.** The ledger counts SHALL be re-read on **foreground entry**,
on the **extension liveness notification** (the cross-process Darwin ding posted after each PhotoKit
`process()` run — see `ios-photokit-upload`), and, on the app-driven tier, after **each in-process pump
cycle** (see `ios-url-session-upload`). A failed ledger read SHALL retain the last good counts rather
than regress (so a transient read error never drops `completed` to zero and flips the screen out of "In
sync").

#### Scenario: Initial value is Loading
- **WHEN** the source is constructed
- **THEN** `status.value` is `SyncStatus.Loading` synchronously, before any source read completes

#### Scenario: Ready waits for ledger counts, permission, and gallery
- **WHEN** permission has produced a value but the ledger counts or the gallery size has not
- **THEN** `status.value` is still `SyncStatus.Loading`, and the first `Ready` is emitted only once all
  three have a value

#### Scenario: Completed and pending derive from the ledger
- **WHEN** the ledger reports `4` complete assets and `2` in-flight assets and the gallery total is `7`
- **THEN** the minted snapshot has `completed = 4`, `pending = 2`, `total = 7`

#### Scenario: A ledger count change re-mints a Ready snapshot
- **WHEN** the `LedgerCountsSource` value changes after the first `Ready`
- **THEN** the source emits a new `Ready` with the updated `completed`/`pending` and otherwise unchanged
  counts

#### Scenario: Gallery and permission changes re-mint
- **WHEN** the gallery size changes, or permission flips, after the first `Ready`
- **THEN** the source emits a new `Ready` with the updated `progress.total`, respectively
  `progress.active`, and otherwise unchanged counts

#### Scenario: The extension notification re-reads the ledger with no network
- **WHEN** the extension liveness notification is delivered while the app is foreground
- **THEN** the source re-reads the ledger counts and re-emits — issuing no storage LIST

#### Scenario: A failed ledger read keeps the last value
- **WHEN** a ledger count read fails (absent file, open error)
- **THEN** the source retains its previous counts and does not throw, and does not regress `completed`
  to zero

### Requirement: LedgerCountsSource seam

The status domain SHALL define `LedgerCountsSource` in `:domain:status` (`commonMain`) exposing
`counts: StateFlow<LedgerCounts>` and a `suspend fun refresh()`, where `LedgerCounts(completed, pending)`
is a `:domain:status` type (both **asset-counted**): `completed` = the number of the device's photos with
**all** ledger rows `COMPLETED`; `pending` = the number of the device's photos with **any** non-`COMPLETED`
ledger row. Both SHALL come from a **single** ledger `aggregates()` read so they are mutually consistent.
The seam exposes **counts only**; it SHALL NOT expose the ledger nor any write capability.

The seam and its general implementation SHALL live in `:domain:status` and take the counts as an
**injected `suspend () -> LedgerCounts` read**, so `:domain:status` keeps **no** `:domain:engine`
dependency (the engine-leak rule holds) and the read-failure behavior is testable platform-free. The iOS
composition root SHALL supply a read that reads the shared App-Group ledger **read-only** — calling only
the backend's aggregate read (`iosLedgerStore().aggregates()`), never `put`/`clear`/`resetTo` — so the
**extension remains the sole writer** and **no `LedgerWriter` is constructed in `:app:ios`**. The
cross-process read is safe under the ledger driver's WAL mode (one writer plus concurrent readers).
`refresh()` SHALL be invoked on **foreground entry**, on the **extension liveness notification**, and,
on the app-driven tier, after **each pump cycle**. On any read failure the value SHALL retain its last
good `LedgerCounts` (seeded `LedgerCounts(0, 0)` before the first successful read). A settable fake SHALL
exist for tests and the desktop harness.

#### Scenario: Value is the asset-counted ledger completed and pending
- **WHEN** the ledger has photos `{A, B}` fully `COMPLETED`, photo `C` with a non-`COMPLETED` row, and
  photo `D` with no rows
- **THEN** after `refresh()` the value is `LedgerCounts(completed = 2, pending = 1)` — counted by photo,
  `D` (undiscovered) in neither

#### Scenario: Both counts come from one consistent read
- **WHEN** `refresh()` reads the ledger
- **THEN** `completed` and `pending` are taken from a single `aggregates()` round-trip, so the two asset
  sets are disjoint and never double-count a photo

#### Scenario: Read-only access preserves the single-writer invariant
- **WHEN** the iOS `LedgerCountsSource` reads the ledger
- **THEN** it calls only the aggregate read and never a write; the app constructs no `LedgerWriter`

#### Scenario: A failed read keeps the last good counts
- **WHEN** `refresh()` cannot read the ledger (absent file, open error)
- **THEN** the value retains its last good `LedgerCounts` (or `LedgerCounts(0, 0)` if never read) and no
  exception propagates to the status projection

#### Scenario: Foreground, notification, and pump each trigger a refresh
- **WHEN** the app enters the foreground, **or** the extension liveness notification arrives while
  foreground, **or** an app-driven pump cycle completes
- **THEN** `LedgerCountsSource.refresh()` is invoked

