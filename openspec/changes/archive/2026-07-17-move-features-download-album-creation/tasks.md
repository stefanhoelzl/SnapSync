# Tasks — move-features-download-album-creation

## 1. Baseline
- [x] 1.1 Beacon BEFORE: total 66 (module 27 · edges 1 · shells 36 · mixed 0 · ledger 2)

## 2. Feature moves (git mv; package/import lines only)
- [x] 2.1 `feature/download`: DownloadController, QueuedPhotoDownloadJobs, DownloadPushReceiver,
      StoreDownloadStatusSource (from `:capability:download`) + DownloadStatusSource.kt
      (read-model, from `:domain:status`)
- [x] 2.2 `feature/album`: AlbumCoordinator, AlbumMapMigration; `ports/`: AlbumSeams.kt
      (AlbumManager + AlbumMapStore)
- [x] 2.3 `feature/creation`: CreateEvent.kt, CreationStatus.kt
- [x] 2.4 `feature/upload`: ResourceEnumerator (from `:domain:gallery`; D4)
- [x] 2.5 `feature/membership`: DeviceManifestProducer (from `:domain:gallery`; one-writer, D5)
- [x] 2.6 Tests moved with subjects into `:domain` commonTest: QueuedPhotoDownloadJobsTest,
      AlbumCoordinatorTest, AlbumMapMigrationTest, CreateEventTest, DeviceManifestProducerTest
- [x] 2.7 Fixture-pinned tests re-homed: 3 download tests → `:domain:download-store` commonTest;
      2 status tests → `:domain:gallery` commonTest (D6)

## 3. Module deletions + edge kills
- [x] 3.1 Delete `:capability:{upload-url,config,join,membership,album,event-creation-ui,download}`,
      `:domain:{logging,permission,status}` (settings.gradle.kts + build files + dirs)
- [x] 3.2 Prune every consumer dep line (app/ios, photokit-extension, app/desktop, app/desktop/ui,
      domain/presentation, test/world, test/integration) + orphaned comments
- [x] 3.3 `presentation→event-creation-ui` edge dead (edges law → 0)
- [x] 3.4 ext-safe interim edges dead: `api(":capability:album")` + `api(":domain:gallery")`
      removed, comment rewritten
- [x] 3.5 Import sweep in consumers (11 files) + ext-safe adapters gain explicit seam imports
      (previously same-package)

## 4. Gates and ride-alongs
- [x] 4.1 `./gradlew build` green
- [x] 4.2 `compileIosMainKotlinMetadata` green
- [x] 4.3 `architectureDiagrams` regenerated, output in tree (no new top-level scan roots)
- [x] 4.4 `:test:architecture:test` green; feature-blindness verified non-vacuously (planted
      sibling reference → red naming both packages → removed; D8)
- [x] 4.5 Beacon AFTER: total 55 (module 17 · edges 0 · shells 36 · mixed 0 · ledger 2) —
      matches "edges −1 (→0) · modules −10"; no law increased
- [x] 4.6 Beacon `targetModules` untouched (verified: no deleted module was listed)
- [x] 4.7 CLAUDE.md: deleted-module rows removed; `:domain`, `:domain:gallery`,
      `:domain:download-store`, `:capability:upload` rows updated; logging section re-pointed;
      laws digest untouched (LawsDigestTest green)

## 5. Ceremony
- [x] 5.1 Spec deltas (10 capabilities) + Purpose prose reconciled in-place where a dead module was
      named outside requirements (diagnostic-logging, edge-upload-provider, sync-status,
      gallery-status)
- [x] 5.2 `openspec validate --specs --strict` green (pinned CLI)
- [x] 5.3 Archive gates: no placeholder Purpose; delta completeness accounted in proposal;
      dead-types grep clean (moves only, no type removed)
