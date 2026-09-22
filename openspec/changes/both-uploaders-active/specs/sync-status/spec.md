## MODIFIED Requirements

### Requirement: LedgerCountsSource seam

The status feature SHALL define `LedgerCountsSource` in `:domain`'s `feature/status` zone
(package `app.snapsync.feature.status`, `commonMain`) exposing `counts: StateFlow<LedgerCounts>`
and a `suspend fun refresh()`, where `LedgerCounts` is a `feature/status` type carrying the ledger's
**per-asset done-ness**: the `assetId`s **all** of whose ledger rows are `COMPLETED`, and the `assetId`s with
**any** non-`COMPLETED` ledger row. Both SHALL come from a **single** ledger `assetProgress()` read (capability
`sync-ledger`) so they are mutually consistent and disjoint. The seam does **not** count against the
membership's admitted set; the ledger-backed source does that (see "Ledger-backed source"), so the seam needs
no policy and no gallery. The seam exposes **done-ness only**; it SHALL NOT expose the ledger's rows nor any
write capability.

`LedgerCounts` SHALL additionally carry whether its value was **read** from the ledger, so that
"no photos are recorded" and "the ledger has not been read" are distinguishable by the status
projection. The value before any successful read SHALL report **un-read**; every value published by a
successful read SHALL report **read**, including a genuine empty answer. Only the un-read value holds the
status projection at `Loading` (see "Ledger-backed source"); a read empty answer is a real answer.

The seam and its general implementation SHALL live in `feature/status` and take the answer as an
**injected `suspend () -> LedgerCounts` read**, so the status feature names **no** ledger type
(the ledger-independence rule of "Module placement plugs the engine leak" holds) and the
read-failure behavior is testable platform-free. The iOS composition root SHALL supply a read
that reads the shared App-Group ledger **read-only** — calling only the backend's per-asset done-ness read
(`iosLedgerStore().assetProgress()`), never a record write, `clear` or `resetTo` — so **status writes
nothing**: every ledger write belongs to the code that owns it (the upload cycles' `LedgerWriter`s, a
transport's guarded terminal write, the membership use cases' reset family — capability `sync-ledger`,
"Reader and writer capability split"), and the status read is handed none of them. The ledger's `aggregates()` read
is not the status read (it remains for its other callers, capability `sync-ledger`). The cross-process read
is safe under the ledger driver's WAL mode (writes serialized — from either process — with concurrent readers). `refresh()`
SHALL be invoked on **foreground entry**, on each **foreground-gated poll tick**, and after **each app
pump cycle**. On any read failure the value SHALL retain its
last good `LedgerCounts` — which, before any successful read, is the **un-read** value, never a
read empty answer. A settable fake SHALL exist for tests and the desktop harness.

#### Scenario: Value is the per-asset ledger done-ness
- **WHEN** the ledger has photos `{A, B}` fully `COMPLETED`, photo `C` with a non-`COMPLETED` row, and
  photo `D` with no rows
- **THEN** after `refresh()` the value reports `{A, B}` all-done and `{C}` not-done — asset-keyed, `D`
  (undiscovered) in neither

#### Scenario: Before any read the counts report un-read
- **WHEN** a `LedgerCountsSource` has been constructed and `refresh()` has not yet succeeded
- **THEN** its value reports **un-read**, and a consumer can distinguish it from a ledger that holds
  nothing

#### Scenario: A read empty ledger reports a read empty answer
- **WHEN** `refresh()` succeeds against a ledger with no rows
- **THEN** the value reports no all-done and no not-done asset, reporting **read** — a real answer, not the
  un-read seed

#### Scenario: Both sets come from one consistent read
- **WHEN** `refresh()` reads the ledger
- **THEN** the all-done and not-done asset sets are taken from a single `assetProgress()` round-trip, so the
  two sets are disjoint and never double-count a photo

#### Scenario: Read-only access writes nothing
- **WHEN** the iOS `LedgerCountsSource` reads the ledger
- **THEN** it calls only the per-asset done-ness read and never a write, and it is handed no `LedgerWriter`

#### Scenario: A failed read keeps the last good counts
- **WHEN** `refresh()` cannot read the ledger (absent file, open error)
- **THEN** the value retains its last good `LedgerCounts` — the **un-read** value if never read — and no
  exception propagates to the status projection

#### Scenario: Foreground, poll tick, and pump each trigger a refresh
- **WHEN** the app enters the foreground, **or** the foreground-gated poll ticks, **or** an
  app-driven pump cycle completes
- **THEN** `LedgerCountsSource.refresh()` is invoked

