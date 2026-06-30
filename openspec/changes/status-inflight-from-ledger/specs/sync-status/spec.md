## MODIFIED Requirements

### Requirement: Listing-backed source

The status domain SHALL provide a listing-backed `SyncStatusSource` constructed via a
**non-suspending** factory taking a `CompletedAssetsSource`, a `PermissionStatusSource`, a
`GalleryStatusSource`, an `InFlightSource`, and a `CoroutineScope`. Status is **own-device progress**,
derived from (a) the `gallery-status` library resource-enumeration seam, which yields each qualifying
asset's **expected** resource filenames; (b) the **per-device** file listing `GET
/files/device/<deviceId>`, which yields the **present** filenames; (c) permission; and (d) the gallery
total. An asset is **complete** when every expected filename in its set is present in the per-device
listing; `completed` = the count of qualifying assets that are complete and `total` = the gallery
count. The expected × present join is supplied by the `CompletedAssetsSource`; the source SHALL read
**no** `device.json` and SHALL read the ledger **only** through the `InFlightSource` (the in-flight
count), **never** for completeness or classification.

`pending` SHALL be the **in-flight count clamped to remaining**: `pending = min(inFlight, max(0, total
− completed))`, where `inFlight` is the `InFlightSource` value. The clamp absorbs the ledger's
transient over-count (a finished-but-not-yet-acknowledged job still reports in-flight) so `pending` is
never greater than `total − completed`. `pending` remains **display-only** — it does **not** drive
classification.

It SHALL seed its `status` with `SyncStatus.Loading` and, on the scope, combine the completed-assets
count, permission, the gallery size, **and the in-flight count** to emit `SyncStatus.Ready(SyncProgress)`
once the completed-assets count, permission, **and** gallery size have each produced a first value
(the in-flight count seeds `0` and never blocks the first `Ready`), re-emitting a new `Ready` per input
change. Each minted `SyncProgress` SHALL set `completed` = the completed-assets count, `pending` = the
clamped in-flight count, `total` = the gallery size, `active = (permission == GRANTED)`, `failed = 0`,
and `estimatedRemaining = null`, and SHALL carry no completion timestamp. The completed-assets read and
the in-flight read SHALL both be re-driven on **foreground entry**; a failed per-device listing SHALL
keep the last good completed value rather than throw, and a failed in-flight read SHALL yield `0`.

#### Scenario: Initial value is Loading

- **WHEN** the source is constructed
- **THEN** `status.value` is `SyncStatus.Loading` synchronously, before any source read completes

#### Scenario: Ready waits for completed count, permission, and gallery

- **WHEN** permission has produced a value but the completed-assets count or the gallery size has not
- **THEN** `status.value` is still `SyncStatus.Loading`, and the first `Ready` is emitted only once all three have a value (the in-flight count, seeded `0`, does not gate the first `Ready`)

#### Scenario: Completed derives from expected × present

- **WHEN** the gallery enumeration says asset `A` expects filenames `{a-primary.jpg, a-motion.mov}`
  and the per-device listing `GET /files/device/<deviceId>` contains both
- **THEN** `A` counts toward `completed`; **WHEN** the listing is missing `a-motion.mov`, `A` does
  not count toward `completed`

#### Scenario: Pending is the clamped in-flight count

- **WHEN** the gallery total is `7`, `completed` is `5`, and the `InFlightSource` reports `2`
- **THEN** the minted `progress.pending` is `2` (`min(2, max(0, 7 − 5))`)

#### Scenario: Pending is clamped to remaining when the ledger over-counts

- **WHEN** the gallery total is `7`, `completed` is `5` (remaining `2`), and the `InFlightSource`
  reports `3` (a finished-but-unacknowledged job still in-flight)
- **THEN** the minted `progress.pending` is `2` — never greater than `total − completed`

#### Scenario: In-flight change re-mints a Ready snapshot

- **WHEN** the `InFlightSource` value changes after the first `Ready`
- **THEN** the source emits a new `Ready` with the updated (clamped) `progress.pending` and otherwise unchanged counts

#### Scenario: A newly present resource set re-mints a Ready snapshot

- **WHEN** the per-device listing gains the last missing expected filename for one more asset after
  the first `Ready`
- **THEN** the source emits a new `Ready` whose `progress.completed` is incremented

#### Scenario: Gallery and permission changes re-mint

- **WHEN** the gallery size changes, or permission flips, after the first `Ready`
- **THEN** the source emits a new `Ready` with the updated `progress.total`, respectively `progress.active`, and otherwise unchanged counts

#### Scenario: Constants of the source

- **WHEN** any `Ready` snapshot is minted
- **THEN** `progress.failed == 0` and `progress.estimatedRemaining == null`

### Requirement: SyncProgress contract — lifetime truth, three-state classification

The status domain SHALL define
`SyncProgress(pending, completed, total, failed, active, estimatedRemaining: Duration?)`
in `:domain:status` (package `app.snapsync.status`). `completed` is the count of the device's
**complete assets** — assets all of whose expected resource filenames (from the `gallery-status`
enumeration seam) are present in the per-device file listing `GET /files/device/<deviceId>` — counted
by PHOTO (asset). `total` is the live photo-library count (the gallery size, `N`) — **not** a storage
count, so it reflects photos not yet uploaded. `active` is operational state ("the backup machinery is
allowed to run"), never an event-recency heuristic. `pending` is the **ledger-reported in-flight asset
count clamped to remaining** — `min(inFlight, max(0, total − completed))`, where `inFlight` is the
count of the device's photos with any non-`COMPLETED` ledger row (a job created but not yet done),
read **read-only** from the shared ledger; it remains available but does **not** drive classification.
`SyncProgress` carries no completion timestamp — the status surface reports completeness and live
activity only, never how long ago anything happened.

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

#### Scenario: In-flight count does not change classification
- **WHEN** a snapshot has `total = 7`, `completed = 7`, and `inFlight = 3` (stale ledger rows)
- **THEN** the state is COMPLETE (classification ignores `pending`), and `pending` is `0` (clamped to `max(0, 7 − 7)`)

#### Scenario: Virgin event with photos classifies as in progress
- **WHEN** a snapshot has `total = 5` and `completed = 0`
- **THEN** the state is IN_PROGRESS with displayed `n = 0` (never a distinct never-synced state)

#### Scenario: All present photos synced classifies as complete
- **WHEN** a snapshot has `total = 30` and `completed = 30`
- **THEN** the state is COMPLETE

## ADDED Requirements

### Requirement: InFlightSource seam

The status domain SHALL define `InFlightSource` in `:domain:status` (`commonMain`) exposing
`inFlight: StateFlow<Int>` and a `suspend fun refresh()`. Its value is the device's **asset-counted
in-flight** upload count: the number of the device's photos that have **any non-`COMPLETED` ledger
row** (a job answered but not yet observed complete) — i.e. the ledger's `aggregates().pending`. The
seam exposes a **count only**; it SHALL NOT expose the ledger nor any write capability.

The seam and its general implementation SHALL live in `:domain:status` and take the count as an
**injected `suspend () -> Int` read**, so `:domain:status` keeps **no** `:domain:engine` dependency
(the engine-leak rule holds) and the read failure (→ `0`) is testable platform-free. The iOS
composition root SHALL supply a read that reads the shared App-Group ledger **read-only** — calling
only the backend's aggregate read (`iosLedgerBackend().aggregates().pending`), never
`put`/`clear`/`resetTo` — so the **extension remains the sole writer** and **no `LedgerWriter` is
constructed in `:app:ios`**. The cross-process read is safe under the ledger driver's WAL mode (one
writer plus concurrent readers). `refresh()` SHALL be invoked on **foreground entry**. On any read
failure (including a not-yet-created ledger) the value SHALL be `0`. A settable fake SHALL exist for
tests and the desktop harness.

#### Scenario: Value is the asset-counted in-flight ledger count

- **WHEN** the ledger has photos `{A, B}` with a non-`COMPLETED` row and photo `C` fully `COMPLETED`
- **THEN** after `refresh()` the `InFlightSource` value is `2` (A and B), counted by photo, not by resource row

#### Scenario: Read-only access preserves the single-writer invariant

- **WHEN** the iOS `InFlightSource` reads the ledger
- **THEN** it calls only the aggregate read and never a write; the app constructs no `LedgerWriter`

#### Scenario: A failed read yields zero

- **WHEN** `refresh()` cannot read the ledger (absent file, open error)
- **THEN** the value is `0` and no exception propagates to the status projection

#### Scenario: Foreground entry triggers a refresh

- **WHEN** the app enters the foreground
- **THEN** `InFlightSource.refresh()` is invoked (alongside `CompletedAssetsSource.refresh()`)
