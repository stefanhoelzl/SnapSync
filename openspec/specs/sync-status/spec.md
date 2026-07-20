# sync status Specification

## Purpose

The status projection: the user-facing truth about what this device has shared, minted from the engine's
ledger. Defines the `SyncStatus` snapshot contract (lifetime counts × the live gallery total, three-state
classification), the `SyncStatusSource` seam presentation consumes, and the ledger-backed source that
combines the ledger's aggregate stream with permission-derived operational state and the live gallery
size. Lives in `:domain` — the `SyncStatus`/`SyncProgress` vocabulary in `model/`, the projections in
`feature/status` — where the feature-blindness zone gate plugs the engine-type leak toward presentation.

**Why snapshots, not an event stream.** On iOS the uploads run in a separate process while the app is
suspended or dead, so the app can only ever learn what happened by reading persisted state — the UI is
inherently a projection, not a fold over events it witnessed. An event seam would duplicate the engine's fold
into presentation with drift risk. Snapshots are self-healing: every emission is the whole truth, so there is
no late-subscriber problem, no missed-event corruption, conflation is safe, and first render is the same code
path as any update. Platform signals (library change, foreground entry, a foreground-gated poll tick, a
join) are **invalidation triggers** handled inside the source — they trigger a re-read and a fresh
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

The status projections SHALL live in `:domain`'s `feature/status` zone (package
`app.snapsync.feature.status`) — `SyncStatusSource`, the ledger-backed source,
`LedgerCountsSource`, and the own-device gallery source; the `SyncStatus`/`SyncProgress` vocabulary lives in `model/`
(package `app.snapsync.model`, seated there by migration step 3a). No status source SHALL reach
back for the ledger it was freed from (`ledger-free-status`): completeness and in-flight counts
enter **only** through the injected `suspend () -> LedgerCounts` read, and no status source
SHALL take, construct, or reference the ledger port (`LedgerStore`), the ledger writer, or the
sync engine.

The boundary is mechanically held by the feature-blindness zone gate (`architecture-guards`): a
`feature/status` file may reference only `model/`, `ports/`, and itself — so the ledger writer
and engine (seated in `feature/upload`, migration step 5) and every legacy module are violations
by source-text match, fully-qualified references included. One clause the gate cannot see —
`LedgerStore` is a legal `ports/` reference for other features — is carried by this requirement:
for status it remains forbidden, so the counts seam stays the only ledger surface status can
read (the presentation-imports gate, **armed at migration step 9** over `ui/presentation/src`, adds
the presentation-side containment mechanically).

`:ui:presentation` (re-homed from `:domain:presentation` at migration step 9) SHALL consume
status only through the `SyncStatusSource` seam and the feature's read-model types — never a
ledger type, a port, or the engine.

#### Scenario: Status names no ledger type
- **WHEN** the `feature/status` sources are inspected
- **THEN** no file references the sync engine, the ledger writer, or `LedgerStore` — counts
  arrive only through the injected `LedgerCounts` read

#### Scenario: A status source reaching for a sibling feature fails the build
- **WHEN** a file under `feature/status` references a declaration under `feature/upload` (the
  ledger writer's and engine's seat) or any legacy module
- **THEN** the feature-blindness gate fails, naming both packages

#### Scenario: Presentation consumes the seam only
- **WHEN** presentation's status consumption is inspected
- **THEN** it observes `SyncStatusSource` and the feature's read-model types, and no ledger
  type, port, or engine type is named in presentation code

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
gallery size, `active = (permission == GRANTED || permission == LIMITED)` — syncing is operational under
both full and limited grants (under `LIMITED` the total is the selection-scoped count per
`limited-photo-access`) — `failed = 0`, and `estimatedRemaining = null`, and
SHALL carry no completion timestamp.

**Liveness is trigger-driven, plus a foreground-gated poll.** The ledger counts SHALL be re-read on
**foreground entry**, on each tick of the **foreground-gated poll** (see "Foreground-gated
ledger-counts poll" — the replacement for the deleted extension liveness notification), and, on the
app-driven tier, after **each in-process pump cycle** (see `ios-url-session-upload`). A failed ledger
read SHALL retain the last good counts rather than regress (so a transient read error never drops
`completed` to zero and flips the screen out of "In sync").

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

#### Scenario: A limited grant is active
- **WHEN** permission is `LIMITED` and the counts have produced values
- **THEN** the minted snapshot has `active = true` — a limited membership is syncing, not blocked

#### Scenario: A ledger count change re-mints a Ready snapshot
- **WHEN** the `LedgerCountsSource` value changes after the first `Ready`
- **THEN** the source emits a new `Ready` with the updated `completed`/`pending` and otherwise unchanged
  counts

#### Scenario: Gallery and permission changes re-mint
- **WHEN** the gallery size changes, or permission flips, after the first `Ready`
- **THEN** the source emits a new `Ready` with the updated `progress.total`, respectively
  `progress.active`, and otherwise unchanged counts

#### Scenario: A poll tick re-reads the ledger with no network
- **WHEN** the foreground-gated poll ticks while the app is foreground
- **THEN** the source re-reads the ledger counts and re-emits on change — issuing no storage LIST

#### Scenario: A failed ledger read keeps the last value
- **WHEN** a ledger count read fails (absent file, open error)
- **THEN** the source retains its previous counts and does not throw, and does not regress `completed`
  to zero

### Requirement: LedgerCountsSource seam

The status feature SHALL define `LedgerCountsSource` in `:domain`'s `feature/status` zone
(package `app.snapsync.feature.status`, `commonMain`) exposing `counts: StateFlow<LedgerCounts>`
and a `suspend fun refresh()`, where `LedgerCounts(completed, pending)` is a `feature/status`
type (both **asset-counted**): `completed` = the number of the device's photos with **all**
ledger rows `COMPLETED`; `pending` = the number of the device's photos with **any**
non-`COMPLETED` ledger row. Both SHALL come from a **single** ledger `aggregates()` read so they
are mutually consistent. The seam exposes **counts only**; it SHALL NOT expose the ledger nor
any write capability.

The seam and its general implementation SHALL live in `feature/status` and take the counts as an
**injected `suspend () -> LedgerCounts` read**, so the status feature names **no** ledger type
(the ledger-independence rule of "Module placement plugs the engine leak" holds) and the
read-failure behavior is testable platform-free. The iOS composition root SHALL supply a read
that reads the shared App-Group ledger **read-only** — calling only the backend's aggregate read
(`iosLedgerStore().aggregates()`), never `put`/`clear`/`resetTo` — so the **extension remains
the sole writer** and **no `LedgerWriter` is constructed in `:app:ios`**. The cross-process read
is safe under the ledger driver's WAL mode (one writer plus concurrent readers). `refresh()`
SHALL be invoked on **foreground entry**, on each **foreground-gated poll tick**, and, on
the app-driven tier, after **each pump cycle**. On any read failure the value SHALL retain its
last good `LedgerCounts` (seeded `LedgerCounts(0, 0)` before the first successful read). A
settable fake SHALL exist for tests and the desktop harness.

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

#### Scenario: Foreground, poll tick, and pump each trigger a refresh
- **WHEN** the app enters the foreground, **or** the foreground-gated poll ticks, **or** an
  app-driven pump cycle completes
- **THEN** `LedgerCountsSource.refresh()` is invoked

### Requirement: Foreground-gated ledger-counts poll

The status feature SHALL provide a foreground-gated poll (`LedgerCountsPoller`, `:domain`
`feature/status`) that, while the app is foregrounded — and only then — invokes
`LedgerCountsSource.refresh()` on a fixed cadence of **2 seconds**, bounding the staleness of the
displayed upload counts: while foregrounded, a ledger change (e.g. the extension recording a
completion in its own process) SHALL reach the status projection within **one cadence plus one
read**. Each tick is one local, read-only `aggregates()` read — no network, no storage LIST — and
a failed tick retains the last good counts (the `LedgerCountsSource` posture).

The **cadence is the feature's rule** (this staleness bound), tested in `commonTest`; the
**lifecycle is the flows' order**: the Foreground trigger flow starts the poll and the Background
trigger flow stops it (a suspended app cannot act on fresher counts; the next foreground entry's
refresh is the backstop). `start()` SHALL be idempotent while a poll is live — repeated foreground
entries never stack pollers — and the first tick SHALL wait one full cadence, because foreground
entry already refreshes the status sources.

The poll is **tier-neutral**: on the app-driven tier it is redundant beside the pump's in-process
refresh and harmless; a tier conditional here would re-introduce the enumerated-invokers failure
class. This poll replaces the extension's cross-process Darwin liveness notification (deleted —
see `ios-photokit-upload`): the poll needs no cross-process channel and cannot miss a signal,
because the read is the truth.

#### Scenario: A completion recorded mid-foreground reaches the screen within the bound

- **WHEN** the app is foregrounded and the extension's cycle records new `COMPLETED` rows in the
  shared ledger
- **THEN** a poll tick re-reads `aggregates()` within 2 seconds and the status projection re-emits
  with the updated counts, with no network read

#### Scenario: The poll runs only while foregrounded

- **WHEN** the app moves to the background
- **THEN** the poll is stopped, and it is started again on the next foreground entry (which itself
  also refreshes status immediately)

#### Scenario: Repeated foreground entries do not stack pollers

- **WHEN** the foreground trigger fires while a poll from a previous entry is still live
- **THEN** exactly one poll loop runs at the declared cadence

#### Scenario: A failed tick keeps the last good counts

- **WHEN** a poll tick's ledger read fails
- **THEN** the counts retain their last good value and the poll continues

