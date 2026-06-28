## 1. New status seams (`:domain:status`)

- [ ] 1.1 Define `CompletedAssetsSource` (level-triggered complete-asset count + `assetId` set, `refresh()`), backed by the `EventFilesSource` listing on iOS and a settable fake on JVM; a failed listing keeps the last good value.
- [ ] 1.2 Define `PendingManifestsSource` (in-flight set from on-disk App-Group manifests, `refresh()`), pruning files whose asset is in the complete-asset set; iOS App-Group reader + JVM fake.
- [ ] 1.3 `commonTest`: refresh re-reads the complete set; failed listing retains last value; in-flight excludes already-complete assets; a now-complete asset's file is pruned.

## 2. Listing-backed status source (`:domain:status`)

- [ ] 2.1 Implement the listing-backed `SyncStatusSource` combining `CompletedAssetsSource` + `PendingManifestsSource` + `PermissionStatusSource` + `GalleryStatusSource`: seed `Loading`, emit `Ready` once completed-count + permission + gallery have a first value, map `SyncProgress` (`completed`=listing, `pending`=in-flight, `total`=gallery, `active`=GRANTED, `failed=0`, `estimatedRemaining=null`).
- [ ] 2.2 Re-source `SyncProgress.completed`/`pending` per the spec; keep the three-state classification (`n=min(completed,total)`) unchanged.
- [ ] 2.3 Drop the `:domain:status` → `:domain:engine` Gradle dependency; remove all `LedgerWatcher`/`ObservedCompletionsSource` references from the status domain.
- [ ] 2.4 `commonTest` (JVM + `iosSimulatorArm64`): `Loading→Ready` gating; re-mint on completed++, pending++, gallery change, permission flip; `failed==0` and `estimatedRemaining==null`.

## 3. Remove the overlay, the watcher, and the cross-process ding

- [ ] 3.1 Delete the `observed-completion-overlay` capability — `ObservedCompletionsSource`, overlay promotion, sticky retention, foreground-and-pending cadence — and its tests.
- [ ] 3.2 Delete the `:app:ios` `ObservedCompletionsSource` implementation that read succeeded `PHAssetResourceUploadJob`s.
- [ ] 3.3 Remove `LedgerWatcher`/`LedgerSnapshot` from `sync-ledger` and from app construction; retain `LedgerReader`/`LedgerWriter`/`aggregates()`/record/reset for the extension.
- [ ] 3.4 Remove the extension's end-of-cycle Darwin notification and the app-process observer; keep the in-process `changes` ding.

## 4. iOS app wiring (`:app:ios`)

- [ ] 4.1 In the composition root, stop constructing any `LedgerReader`/`LedgerWatcher`; construct the listing-backed status source from the new seams + `GalleryStatusSource` + `PermissionStatusSource`.
- [ ] 4.2 Wire `handleEventsForBackgroundURLSession` (manifest session) completion → `CompletedAssetsSource.refresh()` and `PendingManifestsSource` prune.
- [ ] 4.3 Wire foreground entry → `CompletedAssetsSource.refresh()` (+ pending refresh).

## 5. Docs

- [ ] 5.1 Update `docs/design.md`: the app derives status from the completeness listing + on-disk manifests + PhotoKit; the ledger is extension-private; the overlay is deleted; record the event-driven liveness decision and the staleness Open Question.

## 6. Verification

- [ ] 6.1 `./gradlew build` green (incl. new `commonTest`s; overlay tests removed).
- [ ] 6.2 `./gradlew compileIosMainKotlinMetadata` green.
- [ ] 6.3 On device: status reflects complete assets after real uploads, and refreshes on foreground entry and on manifest `URLSession` completion.
