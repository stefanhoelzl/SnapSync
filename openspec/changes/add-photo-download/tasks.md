## 1. device-manifest role rename (motion → live)

- [x] 1.1 Rename the role `motion` → `live` in `:domain:gallery` (`DeviceManifest`/`ResourceRole` and the producer) and update its tests
- [x] 1.2 Confirm the union pass-through and any consumers compile against `live`; verify `compileIosMainKotlinMetadata` + JVM tests green

## 2. :domain:download-store (shared schema)

- [x] 2.1 Create the `:domain:download-store` module (linked by app and the photokit extension); add SQLDelight + `-lsqlite3` wiring mirroring `:domain:engine`
- [x] 2.2 Define the SQLDelight schema: per-asset rows `{sourceDeviceId, sourceAssetId, createdLocalId, state}` + per-resource staging state (resource key, staged path, downloaded flag)
- [x] 2.3 Implement the writer seam (record staged resource, record import + `createdLocalId`, query pending/complete assets) with a JVM/in-memory fake; `commonTest`
- [x] 2.4 Implement `SuppressionSource` (read-only `createdLocalId` set) usable by the extension; `commonTest`
- [x] 2.5 Define terminal-row permanence (leave/switch preserves imported rows; non-terminal may be dropped); `commonTest`

## 3. Echo-suppression in the upload extension

- [x] 3.1 Add an injected `suppressed` port to `UploadCycle`; filter `discovery.resources` by suppressed `assetId` before fan-out and before `retainAssets`
- [x] 3.2 `commonTest` (`UploadCycleTest`): suppressed assets create no job and are excluded from `retainAssets`; non-suppressed upload normally
- [x] 3.3 Wire the iosMain extension reader (open `:domain:download-store` read-only over WAL) and inject it in `UploadExtensionRoot`

## 4. :capability:download — selection + planning

- [x] 4.1 Add `HttpEventUnionSource` consuming `GET /event/<eventId>/files` (Darwin on iOS; `MockEngine` in `commonTest`) → assets with `{assetId, deviceId, resources[{role, contentType, key, filename, url}]}`
- [x] 4.2 Implement the planner: foreign = `deviceId != myDeviceId` AND no terminal store row; emit per-resource download work; `commonTest`
- [x] 4.3 Define `PhotoDownloadJobs` seam (submit/cancel background downloads) and `PhotoLibraryImporter` seam (import + in-block suppression write); JVM fakes (folder fetch / record); `commonTest`

## 5. iOS transfer + import

- [x] 5.1 Implement `PhotoDownloadJobs` over a background `URLSession` (discretionary/Wi-Fi, bounded in-flight window); on completion move temp → durable App-Group staging and record in the store
- [x] 5.2 Implement `PhotoLibraryImporter`: when an asset's resources are all staged, one `PHAssetCreationRequest` (`live`→`.pairedVideo`, `primary`→`.photo`/`.video`/`.audio` by `contentType`, unknown → skip+log) into the camera roll; capture `placeholderForCreatedAsset.localIdentifier` and write the suppression row **inside** the change block
- [x] 5.3 Swift host: adopt the background `URLSession` and implement `handleEventsForBackgroundURLSession` → forward to the Kotlin download controller (reused the existing `AppDelegate` hook; `SnapSyncRoot.handleBackgroundUrlSession` now routes to `IosPhotoDownloadJobs.adoptBackgroundEvents`)
- [ ] 5.4 Register a `BGProcessingTask` backstop that drains pending imports (and re-reads nothing — discovery is foreground-only); add the required background-mode entitlement/Info.plist keys

## 6. Status (download line)

- [ ] 6.1 Add `DownloadStatusSource` projection (foreign complete assets imported `X` / in-union `Y`) in `:domain:status`; `commonTest`
- [ ] 6.2 Surface an independent "downloaded X of Y" line in `UiState`/the joined screen without altering upload "Completed"; presentation tests
- [ ] 6.3 Desktop harness: a fake importer (writes to a folder) + fake union source so the flow is exercised off-device

## 7. Composition + triggers

- [x] 7.1 Wire `:capability:download` + `:domain:download-store` into `SnapSyncRoot`; trigger union read + planning on join/(re)provision and on foreground entry (foreground-only discovery)
- [x] 7.2 Ensure leave/switch cancels in-flight transfers and drops non-terminal rows while preserving terminal (suppression) rows

## 8. Verify + docs

- [x] 8.1 `./gradlew build` (JVM + all unit tests) and `compileIosMainKotlinMetadata` green; `iosSimulatorArm64Test` green on CI (run 222/223)
- [x] 8.2 On-device verify (SE2, seeded foreign contributor): foreign photos auto-import without foreground ✓; idempotent (no re-import) ✓; echo-suppressed ✓. Caught + fixed a double-import race (which also caused a suppression gap); re-verified single import + suppression. (Relaunched-from-terminated import path leans on the already-verified background-import spike + the wired Swift hook.)
- [ ] 8.3 Update `docs/design.md` §1 (flip the contribute-only/no-download non-goal) and record the storage-truth download model, echo-suppression, the download store, and foreground-only discovery
- [ ] 8.4 `openspec validate add-photo-download`; ship via branch → PR → /ship
