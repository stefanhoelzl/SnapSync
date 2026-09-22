## 1. Settle the delegation risk first

- [x] 1.1 Declare `PlatformEntries` in `:domain:ports` (members per design D2), and make `SnapSyncRoot` implement it by `by` delegation to a stub for one member (`onPushToken`) that still calls today's `LiveShell` path
- [x] 1.2 On the Mac runner (`ssh-mac-build`), build the iOS framework and the Swift targets, and confirm the delegated member is exported to ObjC under the name Swift calls (`SnapSyncRoot.shared.onPushToken`). Stop and report if it isn't
- [x] 1.3 Revert the stub once confirmed; keep a record of the build (runner, commit) for the PR — settled by CI instead of an interactive Mac session: `ios-build` run 35775835951 on spike commit `4b1c32fc` archived green with Swift calling the delegated `onPushToken(hex:)` and the port-named `onSilentPush(payload:)`

## 2. The tap table (sync-status-screen)

- [x] 2.1 Add the `statusActions(host, shareableCount, photoPermission)` factory beside `StatusActions` in `:ui:screens`, binding every callback, `onOpenLink` → `host.onOpenAppStore()` included
- [x] 2.2 Replace the three hand-written tables (`MainViewController`, `ForgeViewController`, `StatusPane`) with calls to it
- [x] 2.3 Click tests in `:ui:screens` `commonTest`: one per control group (join gate, joined, access, surfaces, switch, create, range form, update-required store button, diagnostics gesture), each asserting the container state or fired command
- [x] 2.4 Mutation check: cross two bindings in the factory and confirm a test fails; then restore

## 3. Inbound ports and the core's implementation (module-architecture)

- [x] 3.1 Declare `PlatformEntries` and `ExtensionEntries` in `:domain:ports` with `@PlatformEntry` on their members
- [x] 3.2 Add the `ProtectedStorage` port; its iOS adapter in `:adapter:ios:app-only`; its honest fake in `:adapter:generic:fake` (`FakeHonestyTest` green)
- [x] 3.3 Widen `AppUploadEngine` with `onBackgroundTransfers(completion)`; rename `UrlSessionUploadController.onBackgroundSessionEvents` to implement it; update the world's fake engine
- [x] 3.4 Implement `PlatformEntries` in `:domain:compose` (`AppCore.platformEntries(hooks)`): move `LiveShell`'s bodies verbatim, including the `OsReceipt` deadlines, the `log.invocation` wrapping, the protected-storage log field, and the backstop re-schedule in `finally`; route `onBackgroundTask`/`onBackgroundTransfers` by the identifiers the hooks carry, and release plus log an unknown one
- [x] 3.5 Implement `ExtensionEntries` over `uploadCore` in `:domain:compose` (moving `runProcessCycle`'s call site and `onTerminate`'s log)
- [x] 3.6 Confirm no platform constant entered `model/`, `ports/` or `feature/`, and that `compileIosMainKotlinMetadata` is green

## 4. The shells delegate

- [x] 4.1 `SnapSyncRoot : PlatformEntries by …`, supplying the hooks (host `onOpenUrl`, host assembly, push-token delivery) and the BGTask and upload-channel identifiers; delete `Shell`, `LiveShell`, and the per-entry forwarding members; keep `onLaunch`, `onUserActivity`/`deliverUserActivity`, `onSwiftUiOpenUrl` and the log-only callbacks, now calling the port where they forward
- [x] 4.2 `UploadExtensionRoot : ExtensionEntries by …`; `processRawValue()` becomes `runBlocking { process() }.processingResultRawValue()`
- [x] 4.3 Swift: both `BGTaskScheduler.register` blocks call `SnapSyncRoot.shared.onBackgroundTask(task.identifier)`; `handleEventsForBackgroundURLSession` calls `onBackgroundTransfers`
- [x] 4.4 `KotlinShellGuardTest`: drop the `SnapSyncRoot.kt` pin (the routing suppression is gone) and update its KDoc; `SwiftShellGuardTest` still green; `detektAppShell` green
- [x] 4.5 Rig: update `Boot.kt`'s trigger wiring for the renamed entries, and keep `/os` routes driving the same entry points

## 5. The contracts (port-contracts)

- [x] 5.1 In `:test:contracts`: `PlatformEntriesContract` and `ExtensionEntriesContract` with state vocabularies, `…Observations` interfaces and the subject types (design D7). Document in KDoc why there is no fake binding and why `onTerminate` has no clause
- [x] 5.2 Clauses per design D7's starting set, each asserting outcomes only, with "completion released exactly once, after the work" where an entry takes one
- [x] 5.3 Bindings in `:test:integration` over `World.core` (`Live`, `JVM` and `IOS_SIM_KEXE`); add the `:test:contracts` dependency; decide here whether `World` needs an entry surface (the design's open question), and add a `harness-world-model` delta if it does — decided: no entry surface and no delta. The bindings call `platformEntries({ w.core }, hooks)` / `extensionEntries(...)` themselves; `World` gained only inspection (`operatorEngine` counters, `backstopsScheduled`, the cycle's `uploadPorts`), which the spec's operator-inspection rules already cover
- [ ] 5.4 `ContractCoverageTest` green (every clause has a real host); run both suites on JVM and on `iosSimulatorArm64` (the Mac runner or CI)
- [x] 5.5 Mutation check: route `onSilentPush` to the upload arm only and confirm a clause fails; then restore

## 6. Documentation

- [x] 6.1 Correct the KDocs of `PlatformEntry`, `RigHooks` and `Boot.kt`: no guard derives or checks the population
- [x] 6.2 Update `MainViewController`'s and `SnapSyncRoot`'s KDocs for the delegation; update CLAUDE.md's module lines for `:domain` (inbound ports in `ports/`, their implementation in `compose/`) and `:app:ios`, and drop the retired `LawsDigestTest` from the `:test:architecture` line
- [x] 6.3 `./gradlew architectureDiagrams` and commit

## 7. Verify and ship

- [x] 7.1 `./gradlew build` green, including `compileIosMainKotlinMetadata`
- [x] 7.2 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` and `validate shell-as-driving-adapter --strict` green
- [ ] 7.3 Branch → PR → `/ship --keep-workspace`, label `internal`
- [ ] 7.4 On device (after the internal TestFlight build lands): foreground, silent push and backstop entries log as before under the new entry names
