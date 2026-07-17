# sync-status — delta for move-features-upload-membership-status-trust

## MODIFIED Requirements

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
read (the presentation-imports gate arming at migration step 9 adds the presentation-side
containment; until then the requirement holds it).

`:domain:presentation` SHALL consume status only through the `SyncStatusSource` seam and the
feature's read-model types — never a ledger type, a port, or the engine.

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
SHALL be invoked on **foreground entry**, on the **extension liveness notification**, and, on
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

#### Scenario: Foreground, notification, and pump each trigger a refresh
- **WHEN** the app enters the foreground, **or** the extension liveness notification arrives while
  foreground, **or** an app-driven pump cycle completes
- **THEN** `LedgerCountsSource.refresh()` is invoked
