## Context

Change 2 of three from `docs/sync-refactor.md` (the "Move B" relocation). It is **behavior-preserving**:
no logic changes, only where code lives and which module compiles it. Per `CLAUDE.md`, a pure
behavior-preserving refactor may skip OpenSpec — but the `ios-background-upload` spec's "Background
upload extension target" requirement **names `:app:ios:photokit-extension` as the home of the
extension's logic**, so keeping specs the contract of record requires a module-placement delta. That
one delta is this change's entire spec footprint.

Verified current state (2026-07-02):

- `UploadCycle.kt` (160), `UploadJobPlatform.kt` (85), `DiscoveryStore.kt` (23), `UploadConfig.kt` (19)
  are `commonMain` under `:app:ios:photokit-extension`. Their imports are **only** `app.snapsync.engine.*`
  (+ kermit/coroutines) today; after change 1, `UploadCycle.reconstruct` additionally calls
  `:domain:gallery`'s `assetIdFromUploadKey`.
- `:app:ios:photokit-extension/build.gradle.kts` declares `iosArm64()` + `iosSimulatorArm64()` only — **no
  `jvm()`** — so `UploadCycleTest.kt` (354) + `UploadConfigTest.kt` never run on JVM.
- The iOS adapters `IosUploadJobPlatform` (233), `IosDiscoveryStore` (50), the composition root
  `UploadExtensionRoot` (203), and `uploadHostFromBundle` (`UploadHost.kt`) are `iosMain` and construct
  / implement the four movers. `UploadExtensionRoot` passes `suppressedAssetIds`/`onDiscovery` as
  **lambdas**, so `UploadCycle` itself carries no download-store/gallery-manifest/rejoin edge.
- `:capability:download` / `:capability:rejoin` are the template: `jvmToolchain` + `jvm()` +
  `iosArm64()` + `iosSimulatorArm64()`.

## Goals / Non-Goals

**Goals:**
- Relocate the four orchestration files into `:capability:upload` so their `commonTest` runs on JVM
  **and** `iosSimulatorArm64` (testing rule 1) and CI regression-covers the whole cycle.
- Zero behavior change — the diff is move + package rename + a single new module edge.
- Leave `:app:ios:photokit-extension` a thin iosMain adapter + composition root, and keep the lean
  `SnapSyncUploadKit` static framework building.

**Non-Goals:**
- No `RawAsset` walk seam (change 3). No seam reshape, no ledger/engine change.
- No desktop-harness driver build-out (the optional change 4) — this change delivers the automated
  coverage without any UI work.
- No folding into `:domain:engine` (would pollute its "sync core + SQL ledger, no platform deps"
  contract with job-lifecycle vocabulary).

## Decisions

### D1 — New `:capability:upload` module; depends on `:domain:engine` + `:domain:gallery`
Standard capability build: `jvmToolchain` + `jvm()` + `iosArm64()` + `iosSimulatorArm64()`;
`commonMain` deps `implementation(project(":domain:engine"))`, `implementation(project(":domain:gallery"))`,
`libs.coroutines.core`, `libs.kermit`; `commonTest` deps `kotlin("test")` + `libs.coroutines.test`. No
`iosMain` source set (the iOS adapters stay in the extension module). Add to `settings.gradle.kts`.

*The gallery dependency resolves change 1's flagged interaction.* `docs/sync-refactor.md §2` described
the movers as importing "only `app.snapsync.engine.*`" — true **before** change 1. Change 1 routes
`UploadCycle.reconstruct` through `:domain:gallery`'s `assetIdFromUploadKey`, so `:capability:upload`
takes a `:domain:gallery` edge. Gallery is agnostic and already `jvm()`+ios+tested, so the edge is
clean and does not compromise JVM reachability. *Alternative rejected:* rehome the parser into
`:domain:engine` to keep `:capability:upload` engine-only — but the upload-key **format** is a gallery
concern; bending the engine's vocabulary to protect a module-graph aesthetic is the wrong trade
(recorded in change 1 design D2).

### D2 — Package rename `app.snapsync.ios.upload` → `app.snapsync.upload`
The `.ios.` segment is misleading in a platform-agnostic capability and inconsistent with sibling
capabilities (`app.snapsync.rejoin`, `app.snapsync.downloadstore`). Rename the four movers + their
tests. The churn is mechanical import updates in the four iosMain adapters that reference them.
*Alternative rejected:* keep the old package to minimize diff — cheaper, but bakes a wrong-signal name
into a fresh module; the rename is a one-time mechanical cost paid at the natural moment.

### D3 — iOS adapters and composition root stay in `:app:ios:photokit-extension`
`IosUploadJobPlatform`, `IosDiscoveryStore`, `UploadExtensionRoot`, `uploadHostFromBundle`, the
manifest/marker/log adapters — all iosMain — stay. The extension module adds
`implementation(project(":capability:upload"))` and its adapters import the interfaces/types from there.
The download-store, rejoin, gallery-manifest, and ktor edges **remain on the extension module** (used
by the composition root and adapters, not by the cycle), so no cross-module cycle is created and
`:capability:upload` stays minimal.

### D4 — Framework packaging unchanged
`:app:ios:photokit-extension` still exports the `SnapSyncUploadKit` static framework; `:capability:upload`
is compiled into it transitively. Verify the two static frameworks (`SnapSyncKit` + `SnapSyncUploadKit`)
still build and the extension stays lean after the added edge (`compileIosMainKotlinMetadata` locally;
full framework build on macOS CI).

## Risks / Trade-offs

- **[Relocation silently changes behavior]** → It should not; mitigation is that the moved tests
  (`UploadCycleTest`/`UploadConfigTest`) are moved **with** the code and must stay green, now on JVM
  too. Any JVM/simulator divergence surfaces immediately.
- **[New module edge bloats the extension framework]** → `:capability:upload` pulls only engine +
  gallery (both already in the extension's transitive graph via the composition root), so no net new
  code enters the framework. Verify leanness on CI (D4).
- **[Ordering vs change 1]** → If this lands before change 1, the `:domain:gallery` edge is unnecessary
  (the movers are engine-only) and `reconstruct` still has the phantom-`assetId` bug. Mitigation:
  enforce the sequence (change 1 → change 2); the gallery dep is harmless even if change 1 is pending
  (gallery compiles standalone).
- **[In-memory SQLite name collisions on Native]** → the moved test `InMemoryLedgerBackend` must keep
  unique db names per instance (existing `app/ios/CLAUDE.md` gotcha) so simulator test runs don't leak.

## Migration Plan

No runtime/data migration — a source relocation. Steps: (1) create `:capability:upload` + register in
`settings.gradle.kts`; (2) `git mv` the four sources + three tests, apply the package rename; (3) add
the extension's dependency edge and fix imports; (4) `./gradlew build` (JVM tests now include the moved
cycle tests) + `./gradlew compileIosMainKotlinMetadata`; (5) update docs. Rollback is a straight revert
(move back, drop the module). Sequence: **after** change 1, **before** change 3
(`add-rawasset-walk-seam`, which reshapes the discovery seam this module will own).

## Open Questions

- **`:capability:upload` `iosMain`:** none needed now (adapters stay in the extension). Change 3 may add
  an iosMain here when the `RawAsset` walk seam lands — defer that decision to change 3.
- **Harness driver (change 4):** whether/when to extend `:app:desktop`'s control panel to drive the now-
  reachable cycle is explicitly deferred; this change delivers the automated coverage without it.
