# Tasks — complete-architecture-migration

## 1. `:capability:push` re-homing (module set → 0)

- [x] 1.1 `PushRegistration` + `EventNotifier` → `:domain` `feature/push` (package
      `app.snapsync.feature.push`; `deviceConfigJson` stays internal beside its consumer)
- [x] 1.2 Tests split: policy tests → `:domain` commonTest; the three Ktor mapping tests →
      `:adapter:generic` commonTest (`KtorPushHttpClientTest`)
- [x] 1.3 Delete `capability/push`; drop the settings include + the three build-file deps;
      re-import in `SnapSyncRoot` / `UploadExtensionRoot` / `UrlSessionUploadController` /
      `PushRegistrationIntegrationTest`

## 2. Shell decisions (D3 table)

- [x] 2.1 `refreshAttestation` → `DeviceAttestation.refreshOutcome()` + 3 tests
- [x] 2.2 `presentShareSheet` → `:adapter:ios:app-only` (`app.snapsync.share`)
- [x] 2.3 `MainViewController` transient error → `StatusContainerHost.transientError`
      (SetupEffect + the side-effect channel deleted; container → `Nothing`; test rewritten)
- [x] 2.4 `IosPhotoKitUploadPlatform` → `:adapter:ios:ext-safe` (git mv; doc updated with the
      law argument)
- [x] 2.5 `process()` requeue → `ports/` `requeueWhilePending` + 3 tests
- [x] 2.6 Pins: `handleBackgroundUrlSession`, `runLaunchEnvPolicyProbe`, `DevPhotoSeeder` ×3 —
      each `@Suppress("CyclomaticComplexMethod")` with its forcing proof
- [x] 2.7 `detektAppShell`: `ignoreFailures=false`, root `base` plugin, `check.dependsOn`;
      `KotlinShellGuardTest` (pin inventory + non-vacuity floor), red-proofed

## 3. The write-through ends (read fallback stays — revised per the behavior-review bounce)

- [x] 3.1 `configReadViaFile` keeps its 11a shape (file, fallback, migrate, repair); the fallback
      tests are the update-path pins (ship-at-once: the whole fleet is pre-11a at update time)
- [x] 3.2 `FileBackedConfigStore`: save file-only; clear deletes the legacy item FIRST (the 11a
      ordering's surviving half — a file-only clear would self-undo via the fallback); read
      consults the read-only `KeychainConfigReader` remnant; `KeychainConfigStore` deleted
- [x] 3.3 `RuntimeIdentityTest`: config pair pin SURVIVES (read-only seat), annotated to die with
      the post-ship Stage-2 change

## 4. Transcriber + flows + diagrams

- [x] 4.1 Flow refactors: `switchDecision`, `TitleNeed`/`fetchNeed`, null-tolerant
      `storeEventNameIfChanged`, `ensureAlbum(granted)` — behavior preserved
- [x] 4.2 `Flows.kt` rewritten: derived `flow/` scope, realized grammar, `GrammarViolation`
      hard-fails generation; red-proofed (planted `if` → named failure)
- [x] 4.3 Zones/Ports/Di editions dropped; Features → per-feature-package cards;
      `Scan.kt`/`kotlinSources` drop `capability`
- [x] 4.4 `architectureDiagrams` regenerated (old trigger-named flow files pruned)

## 5. Permanent gates + the beacon's death

- [x] 5.1 `ModuleSetTest`, `MixedPortImplTest`, `DeletionLedgerTest` written; all four new gates
      red-proofed (transcripts in the scratchpad/report)
- [x] 5.2 Final beacon run captured at **Total: 0** (every law 0), then
      `:test:architecture:migration` deleted (PLAN.md + RUN.md with it), settings include
      dropped, `verify` job removed from `architecture.yml`
- [x] 5.3 CLAUDE.md module section → target graph; migration prose swept; runbooks intact;
      `app/ios/CLAUDE.md` entitlements/root text updated

## 6. Verification

- [x] 6.1 `./gradlew build` green WITH detektAppShell gating in check
- [x] 6.2 `compileIosMainKotlinMetadata` green
- [x] 6.3 `:test:world:jvmTest` + `:test:integration:jvmTest` green
- [x] 6.4 `openspec validate --specs --strict` green (52/52)
- [ ] 6.5 Final device pass (operator; checklist in design.md)
