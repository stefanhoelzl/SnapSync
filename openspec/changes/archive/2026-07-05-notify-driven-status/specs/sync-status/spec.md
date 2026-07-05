## MODIFIED Requirements

### Requirement: SyncProgress contract — lifetime truth, three-state classification

The status domain SHALL define
`SyncProgress(pending, completed, total, failed, active, estimatedRemaining: Duration?)`
in `:domain:status` (package `app.snapsync.status`). `completed` is the count of the device's
**complete assets** — assets **all of whose ledger rows are `COMPLETED`** (asset-counted, from the
extension's ledger via `aggregates().completed`). `total` is the live photo-library count (the gallery
size, `N`) — **not** a storage or ledger-discovered count, so it reflects photos not yet discovered or
uploaded. `active` is operational state ("the backup machinery is allowed to run"), never an
event-recency heuristic. `pending` is the **ledger-reported in-flight asset count** — assets with **any
non-`COMPLETED` ledger row** (a job created but not yet done), from `aggregates().pending`. `completed`
and `pending` SHALL be read from the **same** `aggregates()` round-trip so they are mutually consistent
(the two asset sets are disjoint, so `pending` is never greater than the discovered remainder); both are
read **read-only** from the shared ledger, and `pending` remains available but does **not** drive
classification. `SyncProgress` carries no completion timestamp — the status surface reports completeness
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

## ADDED Requirements

### Requirement: Ledger-backed source

The status domain SHALL provide a **ledger-backed** `SyncStatusSource` constructed via a
**non-suspending** factory taking a `LedgerCountsSource`, a `PermissionStatusSource`, a
`GalleryStatusSource`, and a `CoroutineScope`. Status is **own-device progress** derived from (a) the
ledger's asset-counted `completed` and `pending` (via `LedgerCountsSource`); (b) permission; and (c) the
gallery total. The source SHALL read completeness and in-flight **only** through the `LedgerCountsSource`
and SHALL issue **no** storage LIST for upload status — `completed` is the ledger's complete-asset count,
`total` is the gallery count.

It SHALL seed its `status` with `SyncStatus.Loading` and, on the scope, combine the ledger counts,
permission, and the gallery size to emit `SyncStatus.Ready(SyncProgress)` once **all three** have each
produced a first value, re-emitting a new `Ready` per input change. Each minted `SyncProgress` SHALL set
`completed` = the ledger complete-asset count, `pending` = the ledger in-flight count, `total` = the
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
the backend's aggregate read (`iosLedgerBackend().aggregates()`), never `put`/`clear`/`resetTo` — so the
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

## REMOVED Requirements

### Requirement: Listing-backed source
**Reason**: Upload status is re-sourced from the extension's ledger; the listing-backed source (which
derived `completed` from the per-device storage LIST and `pending` from a separate `InFlightSource`) is
replaced by the **Ledger-backed source** requirement, which reads both counts from one `aggregates()`
call. This ends the split where `pending` came from the ledger but `completed` from storage.
**Migration**: Construct the ledger-backed `SyncStatusSource` from a `LedgerCountsSource`, a
`PermissionStatusSource`, a `GalleryStatusSource`, and a `CoroutineScope` (drop the `CompletedAssetsSource`
and `InFlightSource` inputs).

### Requirement: CompletedAssetsSource seam
**Reason**: Own-device upload completeness is now the ledger's `aggregates().completed`, not an
expected×present join over the per-device storage listing. The app no longer issues a storage LIST for
upload status.
**Migration**: Read completeness from the `LedgerCountsSource` (`LedgerCounts.completed`). The per-device
file listing (`bunny-list-endpoint`) is no longer consulted for upload status (the download direction's
foreign-object reconcile is unaffected).

### Requirement: InFlightSource seam
**Reason**: Folded into the `LedgerCountsSource` seam, which yields `completed` **and** `pending` from a
single consistent `aggregates()` read.
**Migration**: Replace the injected `suspend () -> Int` (pending only) with the injected
`suspend () -> LedgerCounts` (completed + pending); consumers read `LedgerCounts.pending` for the
in-flight count.
