# Tasks — foreground poll · Swift transcriber · ProtectedData never created

## 1. Foreground-gated poll replaces the Darwin ding

- [x] 1.1 `LedgerCountsPoller` in `:domain` `feature/status` (cadence 2 s, delay-first, idempotent
      start, stop; commonTest pins cadence/lifecycle/no-stacking)
- [x] 1.2 Foreground flow: `statusPoller.start()` (+ membership re-read first); Background flow:
      `statusPoller.stop()` (replaces `unregisterLiveness`); `AppCore.ledgerCountsPoller` wired
- [x] 1.3 Delete the ding: `UPLOAD_LIVENESS_DARWIN_NAME` (model/), the extension's post
      (`UploadExtensionRoot`), the app's `CFNotificationCenter` observer + `staticCFunction`
      bridge + register/unregister (`SnapSyncRoot`)

## 2. ProtectedData never created; `:domain:keychain` dies

- [x] 2.1 Delete `domain/keychain/` (ProtectedData.kt + ProtectedDataGateTest), the
      `settings.gradle.kts` include, `:app:ios`'s dependency, and `IosProtectedData.kt`
- [x] 2.2 `AppPorts`: drop `protectedDataGate`/`unregisterLiveness`, add `reloadConfig`;
      SilentPush/DownloadBackstop flows run directly with the re-read first (SilentPush takes the
      raw `userInfo`; `pushEventId` codec in model/)
- [x] 2.3 `FileBackedConfigStore.reload()` → `configAfterReload` (ports/, pure: unreadable retains
      last good; commonTest)
- [x] 2.4 Entry-point diagnostics keep `protectedData=` via a direct `UIApplication` read

## 3. Swift transcriber

- [x] 3.1 `iOSApp.swift`: silent push forwards `userInfo` whole (+ completion always `.newData`);
      scene delegate forwards the `NSUserActivity` whole (`onUserActivity`); scenePhase `onChange`
      deleted — `SnapSyncRoot.onLaunch()` installs NSNotificationCenter lifecycle observers
- [x] 3.2 `BackgroundUploadExtension.swift`: `switch` → `init?(rawValue:)` over
      `processRawValue()` with `?? .failure`; `CycleResult.processingResultRawValue()` in ports/
      (commonTest pins 0/1/2)
- [x] 3.3 `SwiftShellGuardTest`: `??` joins the counted keywords; pin table → all-zero + one `??`;
      red-proof run (planted `if` → fail → removed → green). `EventLinkDeliveryTest` asserts the
      whole-activity forward

## 4. Verification

- [x] 4.1 `./gradlew build` green (incl. `:test:world:jvmTest`, `:test:integration:jvmTest`,
      `:domain:jvmTest`, `:test:architecture:test`); `compileIosMainKotlinMetadata` green;
      `:domain:compileTestKotlinIosArm64` green (comma-free backtick names)
- [x] 4.2 `architectureDiagrams` regenerated; beacon re-measured (22 → 17: modules 4→3, shells
      18→14 [14 kt + 0 swift]; no law increased); fresh `detektAppShell`
- [ ] 4.3 Device **Session D** before merge: poll latency (a mid-cycle extension completion moves
      the foregrounded screen ≤ 2 s), transcriber flows (cold+warm link, silent push incl. a
      malformed payload, token delivery), lifecycle transitions (active/inactive/background pairs),
      the ① raw values against the SDK swiftinterface, zero-resume prediction re-confirmed in logs
