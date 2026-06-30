## MODIFIED Requirements

### Requirement: Listing-backed source

The status domain SHALL provide a listing-backed `SyncStatusSource` constructed via a
**non-suspending** factory taking a `CompletedAssetsSource`, a `PermissionStatusSource`, a
`GalleryStatusSource`, and a `CoroutineScope`. Status is **own-device progress**, derived from (a)
the `gallery-status` library resource-enumeration seam, which yields each qualifying asset's
**expected** resource filenames; (b) the **per-device** file listing `GET /files/device/<deviceId>`,
which yields the **present** filenames; (c) permission; and (d) the gallery total. An asset is
**complete** when every expected filename in its set is present in the per-device listing;
`completed` = the count of qualifying assets that are complete, `pending` = qualifying − completed,
and `total` = the gallery count. The expected × present join is supplied by the `CompletedAssetsSource`
(below); the source SHALL read **no** `device.json` and **no ledger**.

It SHALL seed its `status` with `SyncStatus.Loading` and, on the scope, combine the completed-assets
count, permission, and the gallery size to emit `SyncStatus.Ready(SyncProgress)` once the
completed-assets count, permission, **and** gallery size have each produced a first value, re-emitting
a new `Ready` per input change. Each minted `SyncProgress` SHALL set `completed` = the
completed-assets count, `pending` = `max(0, total − completed)` (qualifying − completed), `total` =
the gallery size, `active = (permission == GRANTED)`, `failed = 0`, and `estimatedRemaining = null`,
and SHALL carry no completion timestamp. The completed-assets read SHALL be re-driven on **foreground
entry** (there is no manifest-completion ding anymore); a failed per-device listing SHALL keep the
last good value rather than throw.

#### Scenario: Initial value is Loading

- **WHEN** the source is constructed
- **THEN** `status.value` is `SyncStatus.Loading` synchronously, before any source read completes

#### Scenario: Ready waits for completed count, permission, and gallery

- **WHEN** permission has produced a value but the completed-assets count or the gallery size has not
- **THEN** `status.value` is still `SyncStatus.Loading`, and the first `Ready` is emitted only once all three have a value

#### Scenario: Completed derives from expected × present

- **WHEN** the gallery enumeration says asset `A` expects filenames `{a-primary.jpg, a-motion.mov}`
  and the per-device listing `GET /files/device/<deviceId>` contains both
- **THEN** `A` counts toward `completed`; **WHEN** the listing is missing `a-motion.mov`, `A` does
  not count toward `completed` and falls into `pending`

#### Scenario: Pending is qualifying minus completed

- **WHEN** the gallery total is `47` and `completed` is `12`
- **THEN** the minted `progress.pending` is `35` (`max(0, 47 − 12)`), derived without any per-asset
  manifest set

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

### Requirement: CompletedAssetsSource seam

The status domain SHALL define `CompletedAssetsSource` whose value is a level-triggered holder of the
device's **complete assets** (a count, and the `assetId` set used for pruning), computed as the join
of **expected** (each qualifying asset's resource filenames, from the `gallery-status` library
resource-enumeration seam) and **present** (the filenames returned by the per-device file listing
`GET /files/device/<deviceId>`, via the `EventFilesSource`/device-list seam), with a `suspend fun
refresh()` that re-reads it. An asset is complete when **every** one of its expected filenames is
present in the per-device listing. The source SHALL derive completeness this way, **not** from a
server-computed manifest-completeness listing and **not** from any `device.json`. It SHALL refresh on
**foreground entry**. It SHALL be observation-only (it SHALL NOT upload or mutate storage), and a
failed listing SHALL leave the last good value in place rather than throw. A settable fake SHALL exist
for tests; the iOS implementation SHALL use an HTTP client against the compile-time device-facing host.

#### Scenario: Refresh recomputes the complete-asset set from expected × present

- **WHEN** `refresh()` is called and the expected × present join yields complete assets `{A, B}`,
  then later (more filenames present) `{A, B, C}`
- **THEN** the value is `{A, B}` after the first refresh and `{A, B, C}` after the second

#### Scenario: Completeness is all-expected-present, not server-computed

- **WHEN** the gallery seam says asset `A` expects `{a-primary.jpg, a-motion.mov}` and the per-device
  listing contains exactly those two
- **THEN** `A` is in the complete set; the source consults the raw per-device file list and the
  enumeration seam only, never a manifest-completeness endpoint or a `device.json`

#### Scenario: Foreground entry triggers a refresh

- **WHEN** the app enters the foreground
- **THEN** `CompletedAssetsSource.refresh()` is invoked

#### Scenario: A failed listing keeps the last value

- **WHEN** a `refresh()` fails (network error, non-2xx)
- **THEN** the source retains its previous value and does not throw to the status projection

### Requirement: SyncProgress contract — lifetime truth, three-state classification
The status domain SHALL define
`SyncProgress(pending, completed, total, failed, active, estimatedRemaining: Duration?)`
in `:domain:status` (package `app.snapsync.status`). `completed` is the count of the device's
**complete assets** — assets all of whose expected resource filenames (from the `gallery-status`
enumeration seam) are present in the per-device file listing `GET /files/device/<deviceId>` — counted
by PHOTO (asset). `total` is the live photo-library count (the gallery size, `N`) — **not** a storage
count, so it reflects photos not yet uploaded. `active` is operational state ("the backup machinery is
allowed to run"), never an event-recency heuristic. `pending` is `max(0, total − completed)`
(qualifying − completed); it remains available but does **not** drive classification, and is **not**
derived from any in-flight manifest set. `SyncProgress` carries no completion timestamp — the status
surface reports completeness and live activity only, never how long ago anything happened.

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

#### Scenario: Virgin event with photos classifies as in progress
- **WHEN** a snapshot has `total = 5` and `completed = 0`
- **THEN** the state is IN_PROGRESS with displayed `n = 0` (never a distinct never-synced state)

#### Scenario: All present photos synced classifies as complete
- **WHEN** a snapshot has `total = 30` and `completed = 30`
- **THEN** the state is COMPLETE

## REMOVED Requirements

### Requirement: PendingManifestsSource seam

**Reason**: There are no per-asset in-flight manifest files anymore. The per-asset manifest
side-channel is gone — `device.json` is a single per-event mutable object PUT synchronously in-cycle
by the extension — so there is no on-disk `PENDING`/`DONE` manifest set to read or prune. `pending` is
now derived arithmetically as qualifying − completed by the listing-backed source, with no dedicated
seam.

**Migration**: None. The on-disk App-Group `PENDING`/`DONE` manifest files and `PendingManifestsSource`
(its reads and its prune-on-refresh) are deleted; the listing-backed source no longer takes a
`PendingManifestsSource` argument.
