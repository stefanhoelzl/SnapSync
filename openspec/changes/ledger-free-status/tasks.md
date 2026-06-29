## 1. New status seams (`:domain:status`)

- [x] 1.1 Define `CompletedAssetsSource` (level-triggered complete-asset count + `assetId` set,
      `refresh()`), backed by the `EventFilesSource` listing on iOS and a settable fake on JVM; a
      failed listing keeps the last good value.
- [x] 1.2 Define `PendingManifestsSource` (in-flight set from on-disk App-Group manifests,
      `refresh()`), pruning files whose asset is in the complete-asset set; iOS App-Group reader +
      JVM fake.
- [x] 1.3 `commonTest`: refresh re-reads the complete set; failed listing retains last value;
      in-flight excludes already-complete assets; a now-complete asset's file is pruned.

## 2. Listing-backed status source (`:domain:status`)

- [x] 2.1 Implement the listing-backed `SyncStatusSource` combining `CompletedAssetsSource` +
      `PendingManifestsSource` + `PermissionStatusSource` + `GalleryStatusSource`: seed `Loading`,
      emit `Ready` once completed-count + permission + gallery have a first value, map
      `SyncProgress` (`completed`=listing, `pending`=in-flight, `total`=gallery, `active`=GRANTED,
      `failed=0`, `estimatedRemaining=null`).
- [x] 2.2 Re-source `SyncProgress.completed`/`pending` per the spec; keep the three-state
      classification (`n=min(completed,total)`) unchanged.
- [x] 2.3 Drop the `:domain:status` → `:domain:engine` Gradle dependency; remove all
      `LedgerWatcher`/`ObservedCompletionsSource` references from the status domain.
- [x] 2.4 `commonTest` (JVM + `iosSimulatorArm64`): `Loading→Ready` gating; re-mint on completed++,
      pending++, gallery change, permission flip; `failed==0` and `estimatedRemaining==null`.

## 3. Remove the overlay, the watcher, and the cross-process ding

- [x] 3.1 Delete the `observed-completion-overlay` capability — `ObservedCompletionsSource`, overlay
      promotion, sticky retention, foreground-and-pending cadence — and its tests.
- [x] 3.2 Delete the `:app:ios` `ObservedCompletionsSource` implementation that read succeeded
      `PHAssetResourceUploadJob`s.
- [x] 3.3 Remove `LedgerWatcher`/`LedgerSnapshot` from `sync-ledger` and from app construction;
      retain `LedgerReader`/`LedgerWriter`/`aggregates()`/record/reset for the extension.
- [x] 3.4 Remove the extension's end-of-cycle Darwin notification and the app-process observer; keep
      the in-process `changes` ding.

## 4. iOS app wiring (`:app:ios`)

- [x] 4.1 In the composition root, stop constructing any `LedgerReader`/`LedgerWatcher`; construct
      the listing-backed status source from the new seams + `GalleryStatusSource` +
      `PermissionStatusSource`.
- [x] 4.2 Wire `handleEventsForBackgroundURLSession` (manifest session) completion →
      `CompletedAssetsSource.refresh()` and `PendingManifestsSource` prune.
- [x] 4.3 Wire foreground entry → `CompletedAssetsSource.refresh()` (+ pending refresh).

## 5. Docs

- [x] 5.1 Update `docs/design.md`: the app derives status from the completeness listing + on-disk
      manifests + PhotoKit; the ledger is extension-private; the overlay is deleted; record the
      event-driven liveness decision and the staleness Open Question.

## 6. Verification

- [x] 6.1 `./gradlew build` green (incl. new `commonTest`s; overlay tests removed).
- [x] 6.2 `./gradlew compileIosMainKotlinMetadata` green.
- [~] 6.3 On device (SE2, iOS 26.5, dev IPA build 181): **partially verified.** Confirmed: the
  listing-backed `ListingSyncStatusSource` renders the hero ("0 of 7 images synced" — `total` from
  PhotoKit, `completed` from the completeness listing, **no ledger read**); join via the
  completeness listing works (empty `200 []` → joined hero); real uploads land in the backend
  storage zone (21 objects = 7 assets × manifest+resources); `FilesCompletedAssetsSource`
  keep-last-good holds (a non-parseable listing leaves "0 of 7" rather than crashing/blanking, and
  survives foreground-entry refreshes). **Blocked:** `completed` climbing to reflect complete assets
  requires Change 1's asset-grouped `GET /event/<id>/files` response, which is **not yet deployed**
  — `backend-deploy` ships only from `main` (`if: github.ref == 'refs/heads/main'`), so the live
  host still serves the pre-Change-1 flat-file format. Completes automatically once the
  storage-redesign branch reaches `main`; re-run the same on-device loop then.
