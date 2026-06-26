## MODIFIED Requirements

### Requirement: Ledger-backed source
The status domain SHALL provide `LedgerSyncStatusSource`, constructed via a **non-suspending**
factory taking a `LedgerWatcher`, a `PermissionStatusSource`, a `GalleryStatusSource`, an
`ObservedCompletionsSource`, and a `CoroutineScope`. It SHALL seed its `status` with
`SyncStatus.Loading` and, on the scope, collect the watcher's **snapshot** combined with permission,
the gallery size, and the observed-completions set to emit `SyncStatus.Ready(SyncProgress)` once the
snapshot, permission, **and** gallery size have each produced a first value, re-emitting a new `Ready`
per input change. The observed set seeds synchronously (an empty set is a valid first value) and so
SHALL NOT delay the first `Ready`. Before minting, the source SHALL apply the **overlay** to the
snapshot using the observed set (a pending photo all of whose outstanding keys are observed is counted
complete), retaining observed keys per the **sticky** rule so a released key does not blink its photo
backward. Each minted `SyncProgress` SHALL combine the overlaid counts with the current permission and
gallery size: `completed` = overlaid completed, `pending` = overlaid pending, `total` = the gallery
size, `lastFinishedAt = newestCompletionAt` (taken from the snapshot — never fabricated by the
overlay), `active = (permission == GRANTED)` — the shared operational-state rule lives here and only
here — `failed = 0` (retry-forever never gives up a key) and `estimatedRemaining = null` (this version
does not estimate). With an empty observed set the overlay is the identity, so the minted counts equal
the ledger snapshot's. The factory SHALL NOT block on a source read before constructing; the
`Loading → Ready` transition is the seam's honest representation of those asynchronous first reads.

#### Scenario: Initial value is Loading
- **WHEN** the source is constructed
- **THEN** `status.value` is `SyncStatus.Loading` synchronously, before any source read completes

#### Scenario: Ready waits for snapshot, permission, and gallery
- **WHEN** the snapshot and permission have produced a first value but the gallery size has not
- **THEN** `status.value` is still `SyncStatus.Loading`, and the first `Ready` is emitted only once the
  gallery size also produces a value (the observed set, seeding empty, does not gate)

#### Scenario: First Ready reflects ledger and gallery
- **WHEN** the source is constructed over a ledger with 2 completed photos, a gallery size of 5, and
  an empty observed set
- **THEN** the source emits `SyncStatus.Ready(progress)` with `progress.completed = 2` and
  `progress.total = 5`

#### Scenario: A ledger change re-mints a Ready snapshot
- **WHEN** a photo's last resource is recorded `COMPLETED` after the first `Ready`
- **THEN** the source emits a new `Ready` whose `progress.completed` is incremented and
  `progress.lastFinishedAt` equals that completion's timestamp

#### Scenario: An observed completion promotes a pending photo before any ledger write
- **WHEN** the snapshot has one pending photo with outstanding keys `{p-photo.jpg, p-video.mov}` and
  a `refresh()` makes the observed set `{p-photo.jpg, p-video.mov}`, with no ledger change
- **THEN** the source emits a new `Ready` whose `progress.completed` is incremented and
  `progress.pending` is decremented, while `progress.lastFinishedAt` is still the ledger snapshot's
  value (the overlay fabricates no timestamp)

#### Scenario: A released observed key does not revert its photo
- **WHEN** a photo was promoted by an observed key and the next `refresh()` no longer reports that key,
  but the snapshot still lists it as outstanding (the ledger ding has not yet arrived)
- **THEN** the photo stays counted complete (sticky retention), and only once the snapshot records it
  `COMPLETED` does the source rely on the ledger for it

#### Scenario: A gallery change re-mints a Ready snapshot
- **WHEN** the gallery size changes after the first `Ready` with no ledger activity
- **THEN** the source emits a new `Ready` with the updated `progress.total` and unchanged counts

#### Scenario: Permission flip re-mints a Ready snapshot
- **WHEN** permission changes from `GRANTED` to `DENIED` with no ledger activity
- **THEN** the source emits `Ready(progress)` with `progress.active = false` and unchanged counts

#### Scenario: Constants of the source
- **WHEN** any `Ready` snapshot is minted by the ledger-backed source
- **THEN** `progress.failed == 0` and `progress.estimatedRemaining == null`
