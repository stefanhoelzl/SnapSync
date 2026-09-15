## MODIFIED Requirements

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
(`iosLedgerStore().aggregates()`), never a record write, `clear` or `resetTo` — so the **extension remains
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
