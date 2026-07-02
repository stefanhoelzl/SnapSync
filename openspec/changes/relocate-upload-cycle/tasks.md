# Tasks — relocate-upload-cycle (Move B)

Behavior-preserving relocation. Depends on change 1 (`fix-sync-correctness`) having landed — in
particular the `:domain:gallery` `assetIdFromUploadKey` parser that `UploadCycle.reconstruct` calls.
The moved tests are the safety net: they must stay green, now on JVM **and** `iosSimulatorArm64`.

## 1. Create the `:capability:upload` module

- [ ] 1.1 Add `capability/upload/build.gradle.kts` from the capability template: `jvmToolchain` + `jvm()` + `iosArm64()` + `iosSimulatorArm64()`
- [ ] 1.2 `commonMain` deps: `implementation(project(":domain:engine"))`, `implementation(project(":domain:gallery"))`, `libs.coroutines.core`, `libs.kermit`
- [ ] 1.3 `commonTest` deps: `kotlin("test")`, `libs.coroutines.test`
- [ ] 1.4 Register `:capability:upload` in `settings.gradle.kts`
- [ ] 1.5 `./gradlew :capability:upload:build` resolves (empty module compiles)

## 2. Move the orchestration sources (behavior-preserving)

- [ ] 2.1 `git mv` `UploadCycle.kt`, `UploadJobPlatform.kt`, `DiscoveryStore.kt`, `UploadConfig.kt` from `app/ios/photokit-extension/src/commonMain/.../ios/upload/` into `capability/upload/src/commonMain/.../upload/`
- [ ] 2.2 Rename the package `app.snapsync.ios.upload` → `app.snapsync.upload` in the four moved files (no other edits — pure relocation)
- [ ] 2.3 Confirm `UploadCycle` imports resolve: `app.snapsync.engine.*`, `app.snapsync.gallery.assetIdFromUploadKey` (from change 1), kermit — and nothing else

## 3. Move the orchestration tests (now JVM + simulator)

- [ ] 3.1 `git mv` `UploadCycleTest.kt`, `UploadConfigTest.kt`, and the test `InMemoryLedgerBackend.kt` into `capability/upload/src/commonTest/.../upload/`; apply the same package rename
- [ ] 3.2 Ensure the test `InMemoryLedgerBackend` keeps a unique in-memory db name per instance (Native shared-cache gotcha, `app/ios/CLAUDE.md`)
- [ ] 3.3 `./gradlew :capability:upload:jvmTest` runs `UploadCycleTest` + `UploadConfigTest` green on JVM (the coverage win)

## 4. Re-point the extension module

- [ ] 4.1 Add `implementation(project(":capability:upload"))` to `app/ios/photokit-extension/build.gradle.kts`
- [ ] 4.2 Fix imports in `IosUploadJobPlatform.kt`, `IosDiscoveryStore.kt`, `UploadExtensionRoot.kt`, `UploadHost.kt` to reference `app.snapsync.upload.*` for the moved types (`UploadCycle`, `UploadJobPlatform`, `DiscoveryStore`, `UploadConfig`, `buildUploadConfig`, `CycleResult`, `PlatformUploadJob`, etc.)
- [ ] 4.3 Confirm the download-store / rejoin / gallery-manifest / ktor edges remain on the **extension** module (used by the composition root + adapters), not on `:capability:upload`

## 5. Build & verify (no behavior change)

- [ ] 5.1 `./gradlew build` green — JVM test run now includes the moved cycle/config tests
- [ ] 5.2 `./gradlew compileIosMainKotlinMetadata` green (iOS proxy compile of both modules, incl. the new edge)
- [ ] 5.3 Confirm no cross-module cycle and that `:capability:upload`'s transitive graph is engine + gallery only
- [ ] 5.4 (macOS CI) both static frameworks (`SnapSyncKit`, `SnapSyncUploadKit`) still build; the extension stays lean after the added edge
- [ ] 5.5 Diff review: the change is move + package rename + one module edge + import fixes — no logic delta in the moved files

## 6. Docs

- [ ] 6.1 Add `:capability:upload` to the `CLAUDE.md` module table with a one-line description (upload orchestration: `UploadCycle` + seams)
- [ ] 6.2 Update `app/ios/CLAUDE.md`'s extension-framework dependency note to list `:capability:upload`
- [ ] 6.3 `openspec validate relocate-upload-cycle --strict` passes

## 7. On-device confirmation (relocation safety)

- [ ] 7.1 One dev-IPA build + install + a re-provision against a fresh event id confirms an upload still lands in the bunny storage zone (per `CLAUDE.md` headless loop) — proves the relocation preserved runtime behavior end-to-end
