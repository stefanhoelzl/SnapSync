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
permission, and the gallery size to emit `SyncStatus.Ready(SyncProgress)` once **each input has been
READ**, re-emitting a new `Ready` per input change. **A `StateFlow`'s seed is not a read.** Each input
SHALL therefore carry, in its own type, whether it holds a read value — the gallery size as
`Int?` (capability `gallery-status`), the ledger counts and the download projection as stated below —
and the source SHALL remain `Loading` while any of them reports "not read". A source that combined
three seeded `StateFlow`s and treated their presence as a first value would satisfy "all three have
produced a value" vacuously, on the first dispatch, before any read completed; that is the defect this
requirement exists to make unrepresentable.

Because `Ready` is reached only once every input is read, `SyncProgress`'s own fields SHALL remain
non-nullable: the un-read state is carried by `SyncStatus.Loading`, not by a hole inside a snapshot.

Each minted `SyncProgress` SHALL set
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

#### Scenario: Seeded inputs do not produce a Ready
- **WHEN** the source is constructed over inputs that hold only their seeds — the gallery size `null`,
  the ledger counts un-read, the download projection un-read — and permission has a value
- **THEN** `status.value` is still `SyncStatus.Loading` after the combine has dispatched, and no
  `Ready` snapshot is published

#### Scenario: Ready waits for ledger counts, permission, and gallery
- **WHEN** permission has produced a value but the ledger counts or the gallery size has not been read
- **THEN** `status.value` is still `SyncStatus.Loading`, and the first `Ready` is emitted only once all
  three have been read

#### Scenario: A counted zero total does produce a Ready
- **WHEN** the gallery reports a counted `0` (a non-contributing membership) and the ledger counts have
  been read
- **THEN** a `Ready` snapshot is emitted with `total = 0` — a counted zero is a read value and does not
  hold the source at `Loading`

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

`LedgerCounts` SHALL additionally carry whether its counts were **read** from the ledger, so that
"no photos are recorded" and "the ledger has not been read" are distinguishable by the status
projection. The value before any successful read SHALL report **un-read**; every value published by a
successful read SHALL report **read**, including a genuine `(0, 0)`. Only the un-read value holds the
status projection at `Loading` (see "Ledger-backed source"); a read `(0, 0)` is a real answer.

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
last good `LedgerCounts` — which, before any successful read, is the **un-read** value, never a
read `(0, 0)`. A settable fake SHALL exist for tests and the desktop harness.

#### Scenario: Value is the asset-counted ledger completed and pending
- **WHEN** the ledger has photos `{A, B}` fully `COMPLETED`, photo `C` with a non-`COMPLETED` row, and
  photo `D` with no rows
- **THEN** after `refresh()` the value is `LedgerCounts(completed = 2, pending = 1)` — counted by photo,
  `D` (undiscovered) in neither

#### Scenario: Before any read the counts report un-read
- **WHEN** a `LedgerCountsSource` has been constructed and `refresh()` has not yet succeeded
- **THEN** its value reports **un-read**, and a consumer can distinguish it from a ledger that holds
  nothing

#### Scenario: A read empty ledger reports read zeros
- **WHEN** `refresh()` succeeds against a ledger with no rows
- **THEN** the value is `completed = 0`, `pending = 0`, reporting **read** — a real answer, not the
  un-read seed

#### Scenario: Both counts come from one consistent read
- **WHEN** `refresh()` reads the ledger
- **THEN** `completed` and `pending` are taken from a single `aggregates()` round-trip, so the two asset
  sets are disjoint and never double-count a photo

#### Scenario: Read-only access preserves the single-writer invariant
- **WHEN** the iOS `LedgerCountsSource` reads the ledger
- **THEN** it calls only the aggregate read and never a write; the app constructs no `LedgerWriter`

#### Scenario: A failed read keeps the last good counts
- **WHEN** `refresh()` cannot read the ledger (absent file, open error)
- **THEN** the value retains its last good `LedgerCounts` — the **un-read** value if never read — and no
  exception propagates to the status projection

#### Scenario: Foreground, poll tick, and pump each trigger a refresh
- **WHEN** the app enters the foreground, **or** the foreground-gated poll ticks, **or** an
  app-driven pump cycle completes
- **THEN** `LedgerCountsSource.refresh()` is invoked

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

`DownloadProgress` SHALL further carry whether it was **read**, on the same terms as `LedgerCounts`
above: the value before any successful refresh reports **un-read**, and every refreshed value reports
**read**, including a genuine `(0, 0, 0)`. The reason is the direction arrows are **conjunctive** — the
settled "In sync" line is shown exactly when *both* arrows are hidden (`sync-status-screen`) — so an
un-read download projection whose `downloaded` and `total` both sit at a placeholder `0` hides the
download arrow and can carry the whole screen to "In sync" on its own. Making the un-read state
distinguishable in this projection is therefore not symmetry for its own sake; without it the defect
simply relocates to the other arm.

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

#### Scenario: Before any refresh the projection reports un-read

- **WHEN** the download projection has been constructed and no refresh has succeeded
- **THEN** its value reports **un-read**, the status source stays at `Loading`, and the download arrow
  is not derived from it

#### Scenario: A read empty union reports read zeros

- **WHEN** a refresh succeeds against an event whose union holds no foreign assets
- **THEN** the value is `downloaded = 0`, `total = 0`, `inFlight = 0`, reporting **read** — and the
  download arrow is legitimately hidden

## ADDED Requirements

### Requirement: Foreground status refresh is not sequenced behind the upload pump

The **foreground** trigger flow SHALL NOT await the upload pump before starting the foreground-gated
poll or refreshing the status sources. The pump SHALL be one of the flow's concurrent children,
alongside the status refresh, the download reconcile, the staged-byte reclaim and the membership
refresh; the flow SHALL still return only when every child has finished, so its completion report to
the OS remains truthful (`module-architecture`, "A trigger flow never outlives its own run").

The app-driven tier's pump awaits a whole upload cycle, and a cycle's discovery walk can remain
outstanding for as long as the app was suspended — 774 seconds, measured on device (`SNAPSYNC-16`,
build 0.3(605), iOS 18.7.9). Sequencing the status refresh behind it means a member whose visit is
shorter than that unwinding sees **no read value at all**, which is precisely the condition under
which the un-read total must not be mistaken for a settled one. The ordering is therefore part of this
capability's liveness guarantee, not an implementation detail of the flow.

The refresh SHALL read the cheap local sources — the ledger `aggregates()` and the download
projection — **before** the library enumeration, so the counted total and the counted completed
arrive together rather than the total arriving alone and the screen briefly reporting `0 of N`.

A failure in any one refresh SHALL NOT cancel its siblings.

#### Scenario: A blocked pump does not delay the status refresh

- **WHEN** foreground entry occurs and the upload pump does not return (its cycle's discovery walk is
  still outstanding from a previous session)
- **THEN** the foreground-gated poll has started and the status sources have been refreshed, and the
  joined screen shows read counts

#### Scenario: The flow still completes only when its children do

- **WHEN** the foreground flow's children include a pump that takes `T` to return
- **THEN** `run()` returns no earlier than `T`, so the shell reports completion to the OS truthfully

#### Scenario: The cheap reads precede the enumeration

- **WHEN** a foreground status refresh runs
- **THEN** the ledger counts and the download projection are read before the gallery enumeration is
  started

#### Scenario: One failing refresh does not cancel the others

- **WHEN** the gallery enumeration throws during a foreground status refresh
- **THEN** the ledger counts, the download projection, the download reconcile and the membership
  refresh still complete, and the failure is logged at `Error` severity
