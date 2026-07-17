# Tasks — move-features-upload-membership-status-trust

## 1. Baseline

- [x] 1.1 Beacon before: `:test:architecture:migration:test` → total 66 (module 27, edges 1,
      shells 36, mixed 0, ledger 2) at 464c6af.

## 2. Moves (git mv; bodies byte-identical)

- [x] 2.1 `feature/upload`: UploadCycle, UploadArm, BackgroundUploadPump, UploadConfig (from
      `:capability:upload`); ClearRequested, Reconciler (from `:capability:membership`);
      SyncEngine, LedgerWriter (from `model/`). LedgerStore `model/` → `ports/`.
- [x] 2.2 `feature/membership`: JoinEvent, DeviceEnroller (from `:capability:join`); LeaveEvent
      (from `:capability:membership`).
- [x] 2.3 `feature/status`: SyncStatusSource, LedgerBackedSyncStatusSource, LedgerCountsSource,
      OwnDeviceGalleryStatusSource (from `:domain:status`); DownloadStatusSource stays.
- [x] 2.4 `feature/trust`: DeviceAttestation (from `:capability:attest`).
- [x] 2.5 `model/` rider: Invocation, LogContext (from `:domain:logging`).
- [x] 2.6 Tests into `domain/src/commonTest/.../feature/<name>/` where commonMain-pure;
      stay-behinds per design D7.

## 3. Package/import reconciliation

- [x] 3.1 Package lines in moved files; explicit imports where a same-package reference went
      cross-package (SyncEngine, LedgerWriter, LedgerStore, the two log writers, stay-behind
      tests, UploadPushReceiver).
- [x] 3.2 Per-symbol import sweep across consumers (app/ios ×4, adapters, world, integration,
      desktop ×3, presentation, capability/download); no blanket package sed (BGTask-id string
      literals stay).
- [x] 3.3 `ledgerBackend`→`ledgerStore` rename in the four app/ios root files (D10);
      `World.ledgerBackend` deliberately left.

## 4. Build wiring

- [x] 4.1 `:capability:upload` deps trimmed (drop `:domain:logging`, `:domain:engine`,
      `:domain:gallery`); `:capability:download` drops `:domain:logging`.
- [x] 4.2 `:adapter:ios:ext-safe` drops `api(":domain:logging")`, interim-edge comment updated;
      `:adapter:ios:app-only` drops `implementation(":domain:logging")`.

## 5. Guard flips & ride-alongs

- [x] 5.1 Delete `StatusEngineBoundaryTest` (D9).
- [x] 5.2 Verify feature-blindness + flow-no-ports arm: `:test:architecture:test` shows
      feature gate active (no PENDING line) and flow still PENDING; deliberate-red proof —
      planted cross-feature import fails the gate, removed.
- [x] 5.3 CLAUDE.md module rows updated (domain, engine, logging, status, upload, membership,
      join, attest); laws digest untouched.
- [x] 5.4 `./gradlew architectureDiagrams` regenerated and committed.

## 6. Gates

- [x] 6.1 `./gradlew build` green.
- [x] 6.2 `./gradlew compileIosMainKotlinMetadata` green.
- [x] 6.3 `:test:architecture:test` green.
- [x] 6.4 Beacon after: total 66, per-law counts unchanged (module 27, edges 1, shells 36,
      mixed 0, ledger 2) — matches "modules Δ = 0" (no create/delete this step); no law
      increased.
- [x] 6.5 `openspec validate --specs --strict` green (pinned 1.5.0).
