## Why

The upload orchestration — `UploadCycle` (all adjudication: drain both job buckets → engine →
retry-vs-recreate → create/retry → ack; backpressure; cursor advance), `UploadJobPlatform` (the
fine-grained OS-verb seam), `DiscoveryStore`, and `UploadConfig` — is **pure `commonMain`** yet lives
in `:app:ios:photokit-extension`, a module that declares **only `iosArm64`/`iosSimulatorArm64`, no
`jvm()`**. Consequences: its `commonTest` (the ~20-case `UploadCycleTest` + `UploadConfigTest`, driving
the real engine + real ledger through a `FakePlatform`) **does not run on JVM** — a latent
testing-rule-1 violation — and `:app:desktop` cannot reach the cycle to build a harness driver. This is
the **primary coverage win** of the `docs/sync-refactor.md` refactor (its "Move B").

Relocating those four files into a new **`:capability:upload`** — symmetric with
`:capability:download`/`:capability:rejoin`, with the standard `jvm()`+`iosArm64`+`iosSimulatorArm64`
targets — **auto-unlocks JVM regression coverage** of the whole upload orchestration in CI and makes it
harness-reachable. This is change 2 of three; it depends on change 1 (`fix-sync-correctness`) having
landed the correctness fixes and the shared `assetIdFromUploadKey` parser.

## What Changes

- **Create `:capability:upload`** (KMP module, `jvm()` + `iosArm64` + `iosSimulatorArm64`), depending on
  `:domain:engine` and `:domain:gallery` (the latter for the shared `assetIdFromUploadKey` parser
  introduced by change 1) plus kermit + coroutines. No Compose/UI, no download-store/rejoin/ktor edges
  (those wiring deps stay in the extension composition root).
- **Move four files** `UploadCycle.kt`, `UploadJobPlatform.kt`, `DiscoveryStore.kt`, `UploadConfig.kt`
  from `:app:ios:photokit-extension/src/commonMain/…/ios/upload/` into `:capability:upload/commonMain`.
  **Behavior-preserving** — no logic change.
- **Move their tests** `UploadCycleTest.kt`, `UploadConfigTest.kt`, and the test `InMemoryLedgerBackend`
  into `:capability:upload/commonTest`, where they now run on **JVM and `iosSimulatorArm64`**.
- **Rename the package** `app.snapsync.ios.upload` → `app.snapsync.upload` for the moved files (the
  `.ios.` segment is wrong for an agnostic capability; matches `app.snapsync.rejoin`/`.downloadstore`).
  The iOS adapters that stay behind keep their package.
- **Re-point the extension**: `:app:ios:photokit-extension` adds an `implementation(project(":capability:upload"))`
  edge; its iosMain adapters (`IosUploadJobPlatform`, `IosDiscoveryStore`, `UploadExtensionRoot`,
  `uploadHostFromBundle`) stay put and now import the interfaces/types from `:capability:upload`.
- **Update docs**: the `CLAUDE.md` module table (add `:capability:upload`) and `app/ios/CLAUDE.md`'s
  extension-framework dependency note.

## Capabilities

### New Capabilities

_None — no new behavior. This is a behavior-preserving relocation; the only spec impact is the
module-placement contract in `ios-background-upload`._

### Modified Capabilities

- `ios-background-upload`: the "Background upload extension target" requirement currently pins **all**
  the extension's logic to `:app:ios:photokit-extension`. It changes to: the platform-agnostic upload
  orchestration SHALL live in `:capability:upload` (a `jvm()`+ios module, so it runs on JVM per testing
  rule 1); `:app:ios:photokit-extension` retains the iOS platform adapters + composition root and
  **composes** the capability into its static framework.

## Impact

- **New module:** `capability/upload/` (`build.gradle.kts`, `src/commonMain`, `src/commonTest`); add to
  `settings.gradle.kts`.
- **Moved code:** four files out of `app/ios/photokit-extension/src/commonMain/.../ios/upload/`; three
  test files out of `.../commonTest/.../ios/upload/`.
- **Edited wiring:** `app/ios/photokit-extension/build.gradle.kts` (+`:capability:upload` dep);
  imports in `IosUploadJobPlatform.kt`, `IosDiscoveryStore.kt`, `UploadExtensionRoot.kt`,
  `UploadHost.kt` (package/import updates).
- **CI/coverage:** `UploadCycleTest`/`UploadConfigTest` now execute in the JVM test run — the durable,
  per-rule-1 regression coverage this change exists to deliver. No UI/harness work (that is the
  optional change 4).
- **Docs:** `CLAUDE.md`, `app/ios/CLAUDE.md`.
- **Depends on:** change 1 (`fix-sync-correctness`) — specifically the `:domain:gallery`
  `assetIdFromUploadKey` parser that `UploadCycle.reconstruct` now calls.
- **Not in scope:** the `RawAsset` walk seam (change 3), any behavior change, the desktop harness driver
  build-out.
- **Blind spot (unchanged):** the single-process JVM coverage cannot exercise the iOS cross-process
  rehydration (drain lands in a later process than post); that stays device-verified.
