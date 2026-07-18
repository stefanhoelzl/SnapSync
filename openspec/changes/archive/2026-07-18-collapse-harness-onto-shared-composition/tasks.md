# Tasks: collapse-harness-onto-shared-composition

## 1. `:adapter:fake`

- [x] 1.1 Create the module (jvm + iosSimulatorArm64; `api(:domain)`); move the ten honest doubles
  in under `app.snapsync.fake`; cell-inject the three gallery fakes' state (D2)
- [x] 1.2 Arm `FakeHonestyTest`: deliberate-red proof (injected `var` + non-port `fun` → FAILED at
  the violations assert; reverted → green), non-vacuity (8 files scanned)

## 2. The world collapse

- [x] 2.1 `World.core = snapSyncApp(AppPorts(fakes))`; re-seat downloadController / status sources /
  creation / join / bundle onto `core`; keep `uploadCore` for the extension-tier cycle
- [x] 2.2 Delete `WorldLedgerStore` + the world `HttpEnrollment` copy (ledger → 0); world wrappers
  `WorldGallery` / `RecordingDownloadStore` / `OperatorUploadProducer`; `worldTest` child scope (D10)
- [x] 2.3 Re-install `onStaged` with Job retention (D6); operator `leave()` stays synchronous (D7)

## 3. Consumers

- [x] 3.1 `:test:integration` enters through `World.userCommands` / `World.joinEvent`; the bundle
  leave test awaits the fire-and-forget DELETE; assertions unchanged
- [x] 3.2 `WorldInspectorController` drives `AppCore`'s instances (no `rebuildSources` re-assembly);
  minted events route via the world's `onEventMinted` hook

## 4. The fold + deletions

- [x] 4.1 Fold `:app:desktop:ui` → `:app:desktop` (`runForge` JavaExec, D9); harness-driver dep +
  blurbs; CLAUDE.md runbooks
- [x] 4.2 Delete `:domain:gallery` / `:domain:engine` / `:domain:download-store` /
  `:capability:attest`; re-home contracts (D4), driver tests, `SyncEngineTest`, fake-driven feature
  tests; drop `:app:ios`'s vestigial attest dep

## 5. Gates & verification

- [x] 5.1 `./gradlew build` + `compileIosMainKotlinMetadata` green; `architectureDiagrams`
  regenerated; `:test:architecture:test` green (fake gate armed)
- [x] 5.2 Fresh `detektAppShell` + beacon: 29 → 22 (modules 10→4 · ledger 1→0 · shells 18); no law
  increased; `targetModules` untouched
- [x] 5.3 `:test:world:jvmTest` + `:test:integration:jvmTest` green; `driveWorld` live smoke
  (Enrolled → invoke → complete ×2 → invoke → "In sync") + `driveForge` launch/health
