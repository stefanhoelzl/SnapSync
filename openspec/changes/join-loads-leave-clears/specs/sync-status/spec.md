## MODIFIED Requirements

### Requirement: Module placement plugs the engine leak

The status projections SHALL live in `:domain`'s `feature/status` zone (package
`app.snapsync.feature.status`) — `SyncStatusSource`, the ledger-backed source,
`LedgerCountsSource`, and the own-device gallery source; the `SyncStatus`/`SyncProgress` vocabulary lives in `model/`
(package `app.snapsync.model`, seated there by migration step 3a). No status source SHALL reach
back for the ledger it was freed from (`ledger-free-status`): completeness and in-flight state
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
- **THEN** no file references the sync engine, the ledger writer, or `LedgerStore` — the ledger's
  per-asset done-ness arrives only through the injected `LedgerCounts` read

#### Scenario: A status source reaching for a sibling feature fails the build
- **WHEN** a file under `feature/status` references a declaration under `feature/upload` (the
  ledger writer's and engine's seat) or any legacy module
- **THEN** the feature-blindness gate fails, naming both packages

#### Scenario: Presentation consumes the seam only
- **WHEN** presentation's status consumption is inspected
- **THEN** it observes `SyncStatusSource` and the feature's read-model types, and no ledger
  type, port, or engine type is named in presentation code

### Requirement: SyncProgress contract — lifetime truth, three-state classification

The status domain SHALL define
`SyncProgress(pending, completed, total, failed, active, estimatedRemaining: Duration?)`
in `:domain`'s `model/` zone (package `app.snapsync.model`, seated there by migration step 3a). `total` is
the live count of the membership's **admitted own assets** (the gallery total, `N`, capability
`gallery-status`) — **not** a storage or ledger-discovered count, so it reflects photos not yet discovered or
uploaded. `completed` and `pending` SHALL be counted over **that same admitted asset set**, never over every
ledger row:

- `completed` is the number of admitted assets **all of whose ledger rows are `COMPLETED`**;
- `pending` is the number of admitted assets with **any non-`COMPLETED` ledger row** (a job created or owed
  but not yet done);
- an admitted asset with **no** ledger row yet is in neither, and stays in the remainder `total − completed`.

Both SHALL be derived from **one** per-asset done-ness read of the ledger (`assetProgress()`, capability
`sync-ledger`) intersected with the admitted set the gallery source published beside `N`, so they are mutually
consistent and disjoint. Both are read **read-only** from the shared ledger. `active` is operational state
("the backup machinery is allowed to run"), never an event-recency heuristic. `pending` remains available but
does **not** drive classification.

**Why the admitted set, not the ledger.** The ledger holds rows outside the membership's admitted set: the
join-time load (capability `join-event`) marks `COMPLETED` every resource the backend holds for this device,
whatever event it was uploaded for, and a narrowing reconfigure keeps the rows it stops admitting. Counting
every row let historical completions stand in for in-window work: a `completed` far above the in-window total,
clamped down to `N`, read "in sync" while admitted photos were still pending. Anchoring both counts to the set
`N` counts makes `completed ≤ total` and `pending ≤ total − completed` **structural**. The clamps below stay
as **guards** only; on a healthy device they decide nothing. They are display-only: they never change what is
uploaded, only what the count can say. `SyncProgress` carries no completion timestamp — the status surface
reports completeness and live activity only, never how long ago anything happened.

`pending` SHALL still be **clamped to the shown remainder** as a guard: `pending = min(pending, total −
completed)`.

The type SHALL expose a computed `state` as the single source of truth for classification. Let
`n = min(completed, total)` (the displayed synced count, clamped as a guard so it can never exceed `total`).
The classification, evaluated in decision-table order, SHALL be:

- `total == 0` → **NOTHING_TO_SYNC**
- `n >= total` → **COMPLETE**
- otherwise → **IN_PROGRESS**

`SyncState` SHALL have exactly these three values. There is no SUSPENDED state (the setup gate shadows
every non-`GRANTED`/not-joined case — `active = false` is never rendered as a sync state), no
NEVER_SYNCED state (it folds into `IN_PROGRESS` at `n = 0` or `NOTHING_TO_SYNC` at `total = 0`), no
INCOMPLETE and no FAILED state (untellable under retry-forever, `failed ≡ 0`).

Classification reading the ledger is safe under the **no-deletion-during-an-active-event** invariant:
storage is never reset or pruned while an event is active, so a `COMPLETED` ledger row always maps to a
durable object and the ledger cannot over-count. A row loaded `COMPLETED` at the join is the backend's own
statement that the object is stored. A loaded row is bare until a walk fills its detail, and it still counts
as soon as `N` is counted, because it is matched to the admitted set by its `assetId`, not by its capture date.

#### Scenario: No in-scope photos classifies as nothing to sync
- **WHEN** a snapshot has `total = 0`
- **THEN** the state is NOTHING_TO_SYNC, regardless of `completed`

#### Scenario: Fewer synced than present classifies as in progress
- **WHEN** a snapshot has `total = 47` and `completed = 12`
- **THEN** the state is IN_PROGRESS with displayed `n = 12`

#### Scenario: Undiscovered photos keep the state in progress
- **WHEN** the gallery `total = 7` but the ledger has rows for only `5` of the admitted assets, all `COMPLETED`
  (`completed = 5`, `pending = 0`, two photos not yet discovered)
- **THEN** the state is IN_PROGRESS (`n = 5 < 7`) — an undiscovered photo, having no ledger row, is
  counted in neither `completed` nor `pending`, so it never yields a false COMPLETE

#### Scenario: Historical completions do not mask in-window work
- **WHEN** the ledger holds `1,400` `COMPLETED` rows for assets outside the admitted set (loaded from the
  per-device listing at join), the admitted set is `10` assets, and `3` of those have only a non-`COMPLETED`
  row
- **THEN** `completed = 7`, `pending = 3`, `total = 10`, and the state is IN_PROGRESS — the out-of-set rows
  count toward neither, and the screen does not read "in sync"

#### Scenario: A loaded bare row counts as completed once N is counted
- **WHEN** the join-time load recorded a bare `COMPLETED` row for asset `A`, no walk has yet filled its
  detail, and the gallery then counts `N` with `A` in the admitted set
- **THEN** `A` counts toward `completed` in that snapshot, without waiting for a cycle to date the row

#### Scenario: In-flight count does not change classification
- **WHEN** a snapshot has `total = 7`, `completed = 7`, and `pending = 0`
- **THEN** the state is COMPLETE (classification ignores `pending`)

#### Scenario: Virgin event with photos classifies as in progress
- **WHEN** a snapshot has `total = 5` and `completed = 0`
- **THEN** the state is IN_PROGRESS with displayed `n = 0` (never a distinct never-synced state)

#### Scenario: All present photos synced classifies as complete
- **WHEN** a snapshot has `total = 30` and `completed = 30`
- **THEN** the state is COMPLETE

### Requirement: Ledger-backed source

The status domain SHALL provide a **ledger-backed** `SyncStatusSource` constructed via a
**non-suspending** factory taking a `LedgerCountsSource`, a `PhotoAccessStatusSource`, a
`GalleryStatusSource`, and a `CoroutineScope`. Status is **own-device progress** derived from (a) the
ledger's per-asset done-ness (via `LedgerCountsSource`); (b) permission; and (c) the gallery total `N` and
the admitted own-asset set it counted (capability `gallery-status`). The source SHALL read completeness and
in-flight state **only** through the `LedgerCountsSource` and SHALL issue **no** storage LIST for upload
status — `completed` and `pending` are counted from the ledger over the gallery's admitted set, `total` is
the gallery count.

It SHALL seed its `status` with `SyncStatus.Loading` and, on the scope, combine the ledger counts,
permission, and the gallery size and admitted set to emit `SyncStatus.Ready(SyncProgress)` once **each input
has been READ**, re-emitting a new `Ready` per input change. **A `StateFlow`'s seed is not a read.** Each
input SHALL therefore carry, in its own type, whether it holds a read value — the gallery size as
`Int?` and the admitted set as its nullable sibling (capability `gallery-status`), the ledger counts and the
download projection as stated below — and the source SHALL remain `Loading` while any of them reports "not
read". A source that combined three seeded `StateFlow`s and treated their presence as a first value would
satisfy "all three have produced a value" vacuously, on the first dispatch, before any read completed; that
is the defect this requirement exists to make unrepresentable. Until `N` has been counted the admitted set is
unknown, so `completed` and `pending` cannot be counted either; the source stays `Loading`, exactly as it
already did for an un-counted total — not counted is not zero.

Because `Ready` is reached only once every input is read, `SyncProgress`'s own fields SHALL remain
non-nullable: the un-read state is carried by `SyncStatus.Loading`, not by a hole inside a snapshot.

Each minted `SyncProgress` SHALL set
`completed` = the number of admitted assets the ledger reports all-`COMPLETED`, `pending` = the number of
admitted assets the ledger reports with a non-`COMPLETED` row, **clamped to `total − completed`** as a guard,
`total` = the gallery size, `active = (permission == GRANTED || permission == LIMITED)` — syncing is
operational under both full and limited grants (under `LIMITED` the total is the selection-scoped count per
`limited-photo-access`) — `failed = 0`, and `estimatedRemaining = null`, and
SHALL carry no completion timestamp. The intersection is an in-memory set operation over the admitted set,
whose size is bounded by the in-window library, not by the ledger.

**Liveness is trigger-driven, plus a foreground-gated poll.** The ledger counts SHALL be re-read on
**foreground entry**, on each tick of the **foreground-gated poll** (see "Foreground-gated
ledger-counts poll" — the replacement for the deleted extension liveness notification), and, on the
app-driven tier, after **each in-process pump cycle** (see `ios-url-session-upload`). A re-read ledger
answer SHALL be intersected with the admitted set the gallery source **last published**; a ledger re-read
SHALL NOT re-derive the selection policy or re-enumerate the library to obtain a fresh set. A failed ledger
read SHALL retain the last good counts rather than regress (so a transient read error never drops
`completed` to zero and flips the screen out of "In sync").

#### Scenario: Initial value is Loading
- **WHEN** the source is constructed
- **THEN** `status.value` is `SyncStatus.Loading` synchronously, before any source read completes

#### Scenario: Seeded inputs do not produce a Ready
- **WHEN** the source is constructed over inputs that hold only their seeds — the gallery size and admitted
  set `null`, the ledger counts un-read, the download projection un-read — and permission has a value
- **THEN** `status.value` is still `SyncStatus.Loading` after the combine has dispatched, and no
  `Ready` snapshot is published

#### Scenario: Ready waits for ledger counts, permission, and gallery
- **WHEN** permission has produced a value but the ledger counts or the gallery size has not been read
- **THEN** `status.value` is still `SyncStatus.Loading`, and the first `Ready` is emitted only once all
  three have been read

#### Scenario: A counted zero total does produce a Ready
- **WHEN** the gallery reports a counted `0` with an empty admitted set (a non-contributing membership) and
  the ledger counts have been read
- **THEN** a `Ready` snapshot is emitted with `total = 0`, `completed = 0`, `pending = 0` — a counted zero is
  a read value and does not hold the source at `Loading`, whatever rows the ledger holds

#### Scenario: Completed and pending derive from the ledger over the admitted set
- **WHEN** the gallery's admitted set is `{A, B, C, D, E, F, G}`, the ledger reports `A`–`D` all
  `COMPLETED` and `E`, `F` with a non-`COMPLETED` row, `G` has no row, and the ledger also reports `X`
  (outside the admitted set) all `COMPLETED`
- **THEN** the minted snapshot has `completed = 4`, `pending = 2`, `total = 7` — `X` counts toward neither

#### Scenario: A limited grant is active
- **WHEN** permission is `LIMITED` and the counts have produced values
- **THEN** the minted snapshot has `active = true` — a limited membership is syncing, not blocked

#### Scenario: A ledger count change re-mints a Ready snapshot
- **WHEN** the `LedgerCountsSource` value changes after the first `Ready`
- **THEN** the source emits a new `Ready` with the updated `completed`/`pending` and otherwise unchanged
  counts

#### Scenario: Gallery and permission changes re-mint
- **WHEN** the gallery size and admitted set change, or permission flips, after the first `Ready`
- **THEN** the source emits a new `Ready` with the updated `progress.total` (and `completed`/`pending`
  re-counted over the new set), respectively `progress.active`, and otherwise unchanged counts

#### Scenario: A poll tick re-reads the ledger with no network
- **WHEN** the foreground-gated poll ticks while the app is foreground
- **THEN** the source re-reads the ledger counts and re-emits on change — issuing no storage LIST, deriving
  no selection policy, and intersecting with the admitted set the gallery last published

#### Scenario: A failed ledger read keeps the last value
- **WHEN** a ledger count read fails (absent file, open error)
- **THEN** the source retains its previous counts and does not throw, and does not regress `completed`
  to zero

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
(`iosLedgerStore().assetProgress()`), never a record write, `clear` or `resetTo` — so the **extension remains
the sole writer** and **no `LedgerWriter` is constructed in `:app:ios`**. The ledger's `aggregates()` read
is not the status read (it remains for its other callers, capability `sync-ledger`). The cross-process read
is safe under the ledger driver's WAL mode (one writer plus concurrent readers). `refresh()`
SHALL be invoked on **foreground entry**, on each **foreground-gated poll tick**, and, on
the app-driven tier, after **each pump cycle**. On any read failure the value SHALL retain its
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

#### Scenario: Read-only access preserves the single-writer invariant
- **WHEN** the iOS `LedgerCountsSource` reads the ledger
- **THEN** it calls only the per-asset done-ness read and never a write; the app constructs no `LedgerWriter`

#### Scenario: A failed read keeps the last good counts
- **WHEN** `refresh()` cannot read the ledger (absent file, open error)
- **THEN** the value retains its last good `LedgerCounts` — the **un-read** value if never read — and no
  exception propagates to the status projection

#### Scenario: Foreground, poll tick, and pump each trigger a refresh
- **WHEN** the app enters the foreground, **or** the foreground-gated poll ticks, **or** an
  app-driven pump cycle completes
- **THEN** `LedgerCountsSource.refresh()` is invoked

### Requirement: The cheap local status reads are one bounded group

The ledger's per-asset done-ness read (`assetProgress()`) and the download projection SHALL be treated as
**one group** — the *cheap local status reads* — wherever status is refreshed or bounded, and the own-device
library enumeration SHALL NOT be a member of it. The two members are local, read-only database reads; the
enumeration is a PhotoKit walk measured in seconds, which is why it is excluded rather than merely ordered last.

The group exists because both of its properties are **joint**, not per-source:

- **Ordering.** The group SHALL be read **before** the library enumeration, so the counted total and the counted
  completed arrive together rather than the total arriving alone and the screen briefly reporting `0 of N`.
- **Freshness.** The group SHALL be bounded **as a whole**. The screen's direction arrows are conjunctive — "In
  sync" is shown exactly when both are hidden (`sync-status-screen`) — so a composite is only as fresh as its
  stalest member, and bounding one member alone does not half-bound the screen: it leaves the screen unbounded
  through the other. This is the same asymmetry the un-read/counted distinction closed, one property over; fixing
  one arm relocates the defect to the other.

Each member SHALL be read **consistently within itself** — one round-trip yielding mutually consistent values —
so that no member can publish a torn composite of its own counts. A member SHALL retain its last good value on a
failed read, and a failure in one member SHALL NOT cancel the others.

The group is a **rule kept in the status feature**, not order in a flow (`module-architecture`, "Rules in features,
order in flows", second remedy): it has more callers than any one flow, including a `feature/membership` use case
that cannot hold a flow's ordering at all.

#### Scenario: The cheap reads precede the enumeration

- **WHEN** a foreground status refresh runs
- **THEN** the ledger counts and the download projection are read before the gallery enumeration is
  started

#### Scenario: Each member is read consistently within itself

- **WHEN** a member of the group is read while the underlying store is being written
- **THEN** that member's counts come from a single round-trip and are mutually consistent, rather than
  from separate reads that can disagree

#### Scenario: A new cheap local source joins the group

- **WHEN** a further local, read-only status source is added
- **THEN** it is read with the group in both the refresh and the bound, rather than in one of them

#### Scenario: One failing member does not cancel the others

- **WHEN** one member's read fails
- **THEN** that member retains its last good value, the other members still complete, and the failure is
  logged at `Error` severity

### Requirement: Foreground-gated status-counts poll

The status feature SHALL provide a foreground-gated poll (`:domain` `feature/status`) that, while the app is
foregrounded — and only then — re-reads **the cheap local status reads** on a fixed cadence of **2 seconds**. A
change to **any** member of that group SHALL reach the status projection within **one cadence plus one read**:
a ledger change (e.g. the extension recording a completion in its own process), or a download-store change (a
resource staged, an asset imported, a reconcile planning newly-discovered foreign assets).

**Both members, deliberately.** Bounding the ledger alone left the download projection read once per foreground
entry and never again, so a burst of foreign photos discovered after that read rendered a checkmarked "In sync"
for the rest of the session. The arrows are conjunctive, so the composite is only as fresh as its stalest member.

**What the cadence bounds, and where it comes from.** It bounds **how long the screen may assert something that
is false** — not the smoothness of a counter. The value is **chosen for human perception, not derived**: no
measurement produces 2 seconds, and none is claimed. It is also **conditional on the foreground**; while
backgrounded the projection may be arbitrarily stale, which is harmless because nothing renders it, and the next
foreground entry plus first tick restores it.

Each tick SHALL be **local and read-only** — no network, no storage LIST, and never the own-device library
enumeration, which is orders of magnitude slower and belongs only to the foreground refresh. The per-tick cost is
stated by class rather than by counting reads, so a further cheap source joining the group does not falsify it. A
failed tick retains each member's last good value and the poll keeps its cadence.

A tick SHALL NOT **derive the selection policy**. Deriving it reads the download store and the album-exclusion
reader, which touches PhotoKit — a library read on every tick, where the limited-access rules confine library
reads to the cold-launch baseline and the selection observer's emissions (capability `limited-photo-access`).
The ledger answer a tick reads is counted against the admitted set the gallery source last published (see
"Ledger-backed source"), so the tick needs no policy.

The **cadence is the feature's rule** (this staleness bound), tested in `commonTest`; the **lifecycle is the
flows' order**: the Foreground trigger flow starts the poll and the Background trigger flow stops it (a suspended
app cannot act on fresher counts; the next foreground entry's refresh is the backstop). `start()` SHALL be
idempotent while a poll is live — repeated foreground entries never stack pollers.

The first tick SHALL wait one full cadence. Its reason is **not** that foreground entry already refreshed the
sources and the tick would be redundant: foreground entry's refresh is a concurrent child of the same flow as the
download reconcile, so it typically reads the download projection *before* discovery has planned anything. The
first tick is therefore what **repairs** that entry read, and one cadence is enough for a union fetch to land; a
slower fetch is caught by the tick after it.

The poll is **tier-neutral**: on the app-driven tier it is redundant beside the pump's in-process refresh and
harmless; a tier conditional here would re-introduce the enumerated-invokers failure class. This poll replaces the
extension's cross-process Darwin liveness notification (deleted — see `ios-photokit-upload`): the poll needs no
cross-process channel and cannot miss a signal, because the read is the truth.

#### Scenario: A completion recorded mid-foreground reaches the screen within the bound

- **WHEN** the app is foregrounded and the extension's cycle records new `COMPLETED` rows in the
  shared ledger
- **THEN** a poll tick re-reads `assetProgress()` within 2 seconds and the status projection re-emits
  with the updated counts, with no network read

#### Scenario: A tick derives no policy

- **WHEN** a poll tick runs
- **THEN** it reads the cheap local status reads only, derives no selection policy, reads no photo-library
  fact, and counts the ledger answer against the admitted set already published

#### Scenario: A foreign asset imported mid-foreground reaches the screen within the bound

- **WHEN** the app is foregrounded and a background-session transfer completes, its asset imports, and the
  download store records it
- **THEN** a poll tick re-reads the download projection within 2 seconds and the download counts and arrow
  re-emit, with no network read

#### Scenario: A burst discovered after the entry read does not leave the screen settled

- **WHEN** the app is foregrounded showing "In sync", and the foreground reconcile plans foreign assets that
  were not in the projection when that entry's status refresh read it
- **THEN** a poll tick re-reads the projection within 2 seconds and the screen leaves "In sync"

#### Scenario: The poll runs only while foregrounded

- **WHEN** the app moves to the background
- **THEN** the poll is stopped, and it is started again on the next foreground entry (which itself
  also refreshes status immediately)

#### Scenario: Repeated foreground entries do not stack pollers

- **WHEN** the foreground trigger fires while a poll from a previous entry is still live
- **THEN** exactly one poll loop runs at the declared cadence

#### Scenario: A failed tick keeps the last good counts

- **WHEN** a poll tick's read fails
- **THEN** the counts retain their last good value and the poll continues
