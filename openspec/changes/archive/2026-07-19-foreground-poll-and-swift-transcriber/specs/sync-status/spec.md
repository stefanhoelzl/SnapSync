# sync-status — delta for foreground-poll-and-swift-transcriber

## ADDED Requirements

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

## MODIFIED Requirements

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
