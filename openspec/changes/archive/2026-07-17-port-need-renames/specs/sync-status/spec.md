# sync-status — delta for port-need-renames

## MODIFIED Requirements

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
