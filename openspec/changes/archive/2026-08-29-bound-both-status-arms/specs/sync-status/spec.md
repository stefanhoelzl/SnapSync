## ADDED Requirements

### Requirement: The cheap local status reads are one bounded group

The ledger `aggregates()` and the download projection SHALL be treated as **one group** — the *cheap local status
reads* — wherever status is refreshed or bounded, and the own-device library enumeration SHALL NOT be a member of
it. The two members are local, read-only database reads; the enumeration is a PhotoKit walk measured in seconds,
which is why it is excluded rather than merely ordered last.

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

## RENAMED Requirements

- FROM: `### Requirement: Foreground-gated ledger-counts poll`
- TO: `### Requirement: Foreground-gated status-counts poll`

## MODIFIED Requirements

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
- **THEN** a poll tick re-reads `aggregates()` within 2 seconds and the status projection re-emits
  with the updated counts, with no network read

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
projection read **together with** `downloaded`/`total` — one round-trip, not three, so the projection cannot
publish a torn composite of its own counts (capability `download-store`) — and the projection SHALL be
refreshed as a member of the cheap local status reads group: on foreground entry, and on every tick of the
foreground-gated poll.

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

The refresh reads the cheap local status reads before the library enumeration; that ordering is the group's own
property (see "The cheap local status reads are one bounded group") and is stated there rather than here.

This concurrency has a consequence the refresh cannot fix by itself: the status refresh and the download reconcile
are siblings, so the refresh typically reads the download projection **before** that reconcile has planned newly
discovered foreign assets. Repairing that read is the foreground-gated poll's first tick, not a re-ordering —
sequencing the refresh behind its siblings is what this requirement forbids.

A failure in any one refresh SHALL NOT cancel its siblings.

#### Scenario: A blocked pump does not delay the status refresh

- **WHEN** foreground entry occurs and the upload pump does not return (its cycle's discovery walk is
  still outstanding from a previous session)
- **THEN** the foreground-gated poll has started and the status sources have been refreshed, and the
  joined screen shows read counts

#### Scenario: The flow still completes only when its children do

- **WHEN** the foreground flow's children include a pump that takes `T` to return
- **THEN** `run()` returns no earlier than `T`, so the shell reports completion to the OS truthfully

#### Scenario: The entry read is repaired by the poll, not by re-ordering

- **WHEN** the foreground status refresh reads the download projection before the concurrent reconcile has
  planned newly discovered foreign assets
- **THEN** the refresh is still not sequenced behind the reconcile, and the stale read is corrected by a
  subsequent poll tick

#### Scenario: One failing refresh does not cancel the others

- **WHEN** the gallery enumeration throws during a foreground status refresh
- **THEN** the ledger counts, the download projection, the download reconcile and the membership
  refresh still complete, and the failure is logged at `Error` severity
