One PR. The groups are ordered for review, and each one leaves `./gradlew build` green, except that groups 4
and 5 land together: the cycle's `reconcile` port and the marker port are removed across the same call
sites.

## 1. The join-time load (`feature/membership`)

- [x] 1.0 Widen `DeviceFilesSource.list` to return, per stored resource, the recomposed key and the reported
  `assetId` (a small `model/` value type). Update `HttpDeviceFilesSource`, the fakes, the world's mini-edge
  seam and their tests (capability `upload-state-reconciliation`, "Event file list seam").
- [x] 1.1 Add `ShareSetLoad` in `domain/feature/.../membership/` over `LedgerStore`, `DeviceFilesSource` and a
  `deviceId` thunk (design D1). It fetches `list(deviceId)` bounded at 15 s. On success it calls
  `resetTo(one bare COMPLETED row per key, carrying the reported assetId — never parsed from the key)`. On failure or timeout it calls
  `clear()`. It never throws. Transport failure and timeout log at `Warn`; `DeviceListingShapeException` logs
  at `Error`. Its KDoc carries the stated cost of a failed load and why no gate or "owed" bit exists.
- [x] 1.2 Add commonTest coverage:
  - success replaces prior rows, including `DISCOVERED`/`REQUESTED` leftovers and an in-window stale
    `COMPLETED`;
  - an empty listing leaves an empty ledger;
  - transport failure, timeout and decode failure each clear the ledger, with the right severity;
  - it signals `changes` once.

## 2. Provision: a three-way transition, stop on switch, load on join

- [x] 2.1 `SwitchDecision` gains `Join` (no current membership) beside `Stay` (same event) and
  `LeavePrevious`; `Join` and `LeavePrevious` share the sealed supertype `Enter(previousEventId)`. Update
  `switchDecision` and its tests.
- [x] 2.2 `flow/Provision` replaces `notifyLeave` with one effect, `enterMembership(previousEventId)`, over a
  new `feature/membership` `MembershipEntry` (stop uploads, then the awaited backend leave, on a switch; then
  the load) with its own tests. The order is:
  1. `switchDecision(active, next)` → `is Enter` / `Stay`;
  2. on `Enter`, `enterMembership(previousEventId)` — before the save (design D2: the closed grammar cannot
     express a post-save load, and no cycle may see the new membership over the old ledger);
  3. `saveConfig`;
  4. `refreshStatus`, `uploadArm.onProvision()`, album, downloads + push, as today.

  `Stay` stops nothing and loads nothing. Rewrite the class KDoc's order list and its "no destructive verb"
  sentence, which is reversed here (design D3).
- [x] 2.3 In `compose/SnapSyncApp`, bind `enterMembership` to `MembershipEntry({ uploadArm.onLeave() },
  notifyLeave, { shareSetLoad.load() })::enter`, with `ShareSetLoad` built by a top-level `shareSetLoadFor`
  (kept out of `AppCore`, whose `LargeClass` ceiling it would otherwise breach).
- [x] 2.4 Update the Provision flow tests for ordering: switch → stop before notify, load before save, save
  before arm; a join loads; a same-event re-provision neither stops nor loads.
- [x] 2.5 Run `./gradlew architectureDiagrams`, check that `Provision` still transcribes under the closed
  grammar, and commit the regenerated `architecture/`.

## 3. Leave clears the upload ledger

- [x] 3.1 `LeaveEvent` gains a required `clearLedger: suspend () -> Unit` step. The order is stop → clear
  ledger → clear config → notify (fire-and-forget), each best-effort. Rewrite the class KDoc: remove "leaving
  destroys no dedup state" and the marker story, and add the late-completion reasoning (design D3). Bind it in
  `SnapSyncApp` to `ports.uploadRecord.ledger.clear()`. The download store is not touched.
- [x] 3.2 Update `LeaveEvent` tests: order; a failed ledger clear still clears the config and notifies; the
  download store is untouched.
- [x] 3.3 Rewrite the `ResetDeviceState` KDoc section "Why this is not just 'leave harder'". The leave
  command already prunes non-terminal downloads (`downloadController.onLeaveOrSwitch()`) and now clears the
  ledger, so the one remaining difference is that a reset notifies no backend. No behaviour change.

## 4. The cycle loses reconciliation

- [x] 4.1 In `UploadCycle`:
  - remove the `reconcile` constructor parameter, the not-joined branch's `reconcile(null)`, the phase-0
    seed and `CycleOutcome.SeedDeferred`;
  - `CycleOutcome.Declined` loses `seedSucceeded`, and everything downstream that read it (the manifest and
    notify decisions) is re-derived without it;
  - rewrite the comments that name the marker or the seed.
- [x] 4.2 `compose/UploadCore` / `UploadPorts` drop `deviceFiles` and `joinedMarker`, and stop building
  `UploadReconciler`.
- [x] 4.3 Delete `feature/upload/Reconciler.kt` and `ReconcilerTest`. Update `UploadCycleTest`,
  `CycleGateTest` and `:test:integration`'s `CycleEntryGateIntegrationTest`, deleting the reconcile, marker
  and seed-deferral cases.

## 5. The marker goes

- [x] 5.1 Delete `ports/JoinedEventMarker.kt`, `:adapter:ios:ext-safe`'s `IosJoinedEventMarker` and
  `IosJoinedEventMarkerTest`, and the marker fakes in `:adapter:generic:fake` (`Factories.kt`,
  `InMemoryStores.kt`).
- [x] 5.2 Remove the marker from `SnapSyncRoot`, `UploadExtensionRoot` and `UrlSessionUploadController`.
- [x] 5.3 Design D8: on app process start, remove the App-Group `NSUserDefaults` key `rejoin.joinedEventId`.
  The removal is idempotent and one line beside the App-Group constants in `IosLedgerStore.kt`, with a KDoc
  giving the rollback reason. Call it from `SnapSyncRoot`'s start. The extension does not need to.
- [x] 5.4 Keep `rejoin.joinedEventId` pinned in `RuntimeIdentityTest`, exactly once, now at the removal
  site (capability `architecture-guards`: a drifted literal would make the removal a silent no-op). Update
  the pin's comment from "the marker's key" to "the removal target".

## 6. The audit goes

- [x] 6.1 Delete `UploadLedgerAudit`, its test, and `uploadLedgerAuditFor`. `UploadForeground` loses `check`
  and collapses to the pump. Update `flow/Foreground`'s fan-out and its tests, and regenerate the diagrams
  if the transcription moves.
- [x] 6.1a Delete `LedgerState.bytesBelievedStored` from `domain/model/.../Ledger.kt` (its only reader was
  the audit), and its tests (capability `sync-ledger`, "The needs-job set is decided in Kotlin").
- [x] 6.2 `UploadRecordPorts` drops `joinedMarker`. Rewrite its KDoc: the app reads aggregates and the
  per-asset progress, and invokes the reset family at membership transitions (design D4).

## 7. Status counts only what the membership admits

- [x] 7.1 Add `LedgerStore.assetProgress()`: one query over `ledgerRow` grouped by `assetId`, answering done
  or not-done per asset. Implement it in `Ledger.sq`/`SqlDelightLedgerStore` and in every in-memory store
  (`:adapter:generic:fake`, `domain/feature` commonTest), and extend `:test:world`'s `LedgerStoreContract`.
  `aggregates()` is unchanged.
- [x] 7.2 `OwnDeviceGalleryStatusSource` publishes the admitted own-asset set (normalized `assetId`s) beside
  `size`, with the same `null`-until-counted and never-withdraw rules. The `GalleryStatusSource` port and its
  fakes follow.
- [x] 7.3 Compute `completed`/`pending` over the admitted set from one `assetProgress()` read (design D5):
  - `completed` counts admitted assets that are done;
  - `pending` counts admitted assets that are not done;
  - an admitted asset with no row is neither;
  - no policy derivation per poll tick;
  - `LedgerCounts`' un-read/un-counted semantics hold;
  - the remainder clamp stays as a guard.
- [x] 7.4 Add tests: many `COMPLETED` rows outside the window plus one pending in-window photo must not
  classify as in sync; a listing-loaded bare `COMPLETED` row counts once `N` is counted; not-yet-counted `N`
  yields un-counted status.

## 8. Album placement when a bare row is healed

- [x] 8.1 In `UploadCycle`, carry the admitted assets whose rows are bare (the `fullyKnown` complement already
  computed in `decide`) into the plan. In `update`, for a `saveToAlbum` membership, place them in one
  best-effort add issued **before** the backfill writes their detail (design D6). Placement failure never fails the cycle.
- [x] 8.2 Add tests: a loaded bare `COMPLETED` row is placed on the walk that heals it, before its detail
  is written; an excluded asset is
  not placed; an opted-out membership places nothing; a second walk (now fully known) does not re-place.

## 9. World and harnesses

- [x] 9.1 `World.provision()` performs the join-time load through the same `ShareSetLoad` over the world's
  ledger and mini-edge listing. `World.leave()` clears the upload ledger (the download rows and imported
  photos are still retained) and no longer clears a marker. Rewrite both KDocs. Drop the world's marker and
  reconciler wiring (`World.kt`, `WorldRunner.kt`).
- [x] 9.2 Repoint the world's listing-failure lever from the reconcile seed to the join load. Update
  `:test:integration` and the world inspector (`WorldInspector*`, and the "Re-provision" affordance if it
  relied on the marker).
- [x] 9.3 Add integration tests:
  - join → upload → leave → rejoin the same event re-uploads nothing;
  - switch A → B stops, then loads, and re-uploads nothing already stored;
  - a join whose listing fails still uploads (idempotently) and never blocks.

## 10. Comment sweep and verification

- [x] 10.1 Sweep every remaining comment naming the marker, the reconciler, the audit or "the ledger is kept
  across a leave": `ConfigFileAbsence.kt`, `ConfigPorts.kt`, `UploadConfig.kt`, `OsDrivenUploadMechanism.kt`,
  `UploadArm.kt`, `LeaveEvent.kt`, `WorldRunner.kt`, `UrlSessionUploadController.kt`, `LedgerStore.kt`,
  `Ledger.sq` and the `.sqm` headers only if they claim present behaviour, and `LedgerSeeds.kt`. Confirm with
  `git grep -n -i 'joinedEventId\|JoinedEventMarker\|UploadReconciler\|UploadLedgerAudit\|re-join reconcil'`
  (expected: the D8 removal site only).
- [x] 10.2 Run `./gradlew build` (including the detekt tiers; no ceiling raised) and
  `./gradlew compileIosMainKotlinMetadata`.
- [x] 10.3 Run `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` and
  `… validate join-loads-leave-clears --strict`.
- [x] 10.4 On a simulator against a local backend (skills `ios-simulator` + `local-backend`):
  - join, upload, leave, rejoin: the second join's log shows the load seeding N rows and no re-upload;
  - switch between two self-created events: stop, then load;
  - the orphaned key is gone after launch.

  Record the measured load time beside phase 1's 1.67 s.

  **Measured 2026-09-21** (iPhone 17 simulator, iOS 26.x, rig build, local backend on the runner's loopback,
  three 4.8 MP photos, OS-driven tier via `/os/photokit-ext/processRawValue`):
  - first join: `loaded the share set — 0 stored resource(s)`; one cycle created 3 jobs, all `201`, ledger
    `completed: 3`;
  - leave: ledger `completed: 0` (the upload ledger cleared);
  - join of a NEW event after the leave: `loaded the share set — 3 stored resource(s) seeded COMPLETED`; the next
    cycle created **no** upload job, and placed the 3 healed photos in the new event's album
    (`AlbumCoordinator place: added 3 asset(s)`);
  - switch (joining a third event while joined): `arm.onLeave` first, then the backend leave, then the load
    re-seeded 3 `COMPLETED` — order as designed;
  - load cost on loopback: listing `GET` 2 ms, whole load ~4 ms for 3 rows — not comparable with phase 1's
    1.67 s / 1,433 rows on an SE2 (no network, three rows);
  - the orphaned `rejoin.joinedEventId` key was removed at launch, and the album map in the same suite
    survived. **Host quirk:** on an ad-hoc-signed simulator the `group.app.snapsync` defaults suite resolves to
    the app's OWN container (`Data/Application/…/Library/Preferences/group.app.snapsync.plist` — the album map
    lands there too), not the App-Group container, so a key planted in the App-Group plist is never read.
    The removal was verified against the file the app actually uses.

