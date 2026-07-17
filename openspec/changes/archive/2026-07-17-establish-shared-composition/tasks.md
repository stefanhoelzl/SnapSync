# Tasks — establish-shared-composition

## 1. compose/ zone + uploadCore

- [x] 1.1 Create `domain/src/commonMain/kotlin/app/snapsync/compose/UploadCore.kt`: `UploadPorts` +
      `uploadCore(scope, ports): UploadCycle` + the unified `readGate` carrying the D1 decision
      comment.
- [x] 1.2 Move `ResourceEnumerator` `feature/upload` → `compose/` (git mv + package line); update
      importers (ext-safe walk, world, `:domain:gallery` test).

## 2. Roots delegate

- [x] 2.1 `UploadExtensionRoot`: cycle assembly → `uploadCore`; delete
      `photokit-extension`'s `IosEnrollment`; enrollment = `:adapter:generic` `HttpEnrollment`.
- [x] 2.2 `UrlSessionUploadController`: readGate/cycle/reconciler/producer assembly → `uploadCore`;
      delete `app/ios`'s `IosEnrollment`.
- [x] 2.3 Create `domain/src/commonMain/kotlin/app/snapsync/compose/SnapSyncApp.kt`: `AppPorts` +
      `AppCore` + `snapSyncApp(scope, ports)`.
- [x] 2.4 `SnapSyncRoot`: assemble via `snapSyncApp`; shell keeps adapters, entry points,
      coordination lambdas (step-8 material).

## 3. World adopts uploadCore

- [x] 3.1 `World.cycle` built by `uploadCore`; `membershipUnreadable` lever as a `ConfigReader`;
      remove `readGate()`/`reconciler()`/`manifestProducer()` (no external consumers).

## 4. Specs + docs

- [x] 4.1 Spec deltas: upload-lifecycle · ios-photokit-upload · ios-url-session-upload ·
      harness-world-model · ios-app-shell · gallery-status; `validate --specs --strict` green.
- [x] 4.2 CLAUDE.md: `:domain` row gains compose/; root + app/ios composition prose updated.

## 5. Gates

- [x] 5.1 `./gradlew build` green; `compileIosMainKotlinMetadata` green.
- [x] 5.2 `architectureDiagrams` regenerated; `:test:architecture:test` green
      (`RuntimeIdentityTest` literals untouched).
- [x] 5.3 Beacon before/after per law; any increase halts.
- [x] 5.4 `:test:integration` + `:test:world` JVM tests green.
