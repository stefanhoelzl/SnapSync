## 0. Verification gate (front-run — do before building the adapter)

- [ ] 0.1 On the real sub-26 device, install the current app (26.1 appex embedded) and confirm it **installs and launches** on iOS 18–26.0 — the host must not be blocked by the higher-min embedded extension
- [ ] 0.2 If it is blocked: spike conditional/weak embedding of the appex and re-verify install/launch; record the outcome (it may reshape module/target decisions)

## 1. Spec + capability rename (behavior-preserving)

> **Apply vs archive.** The requirement-content changes (sync-ledger generalization; the ≥26.1
> qualifiers) live in the change's **deltas** and are merged into the base by `/opsx:archive` — they
> are NOT hand-edited into the base during apply. The only genuine apply-phase work here is the
> capability **rename** (structural; the delta model can't express it) + cross-ref fixups.

- [x] 1.1 sync-ledger "Requested-state reset" generalization — **carried by the delta** (`specs/sync-ledger/spec.md`); applied to the base at archive. No hand-edit during apply.
- [x] 1.2 Renamed the base capability: `git mv openspec/specs/ios-background-upload openspec/specs/ios-photokit-upload`; updated the two cross-references in `deeplink-config` and `gallery-status`, and the base spec title
- [x] 1.3 Renamed this change's delta folder + narrative to match (`specs/ios-photokit-upload`; proposal Modified-Capabilities key; delta rename note)
- [x] 1.4 ≥26.1 qualifiers on the three appex-bound requirements — **carried by the delta** (`specs/ios-photokit-upload/spec.md`); applied to the base at archive. No hand-edit during apply.
- [x] 1.5 `validate --strict` passes — change valid; all 33 base specs pass (renamed `ios-photokit-upload` included)

## 2. Platform-free pump + scheduler (`:capability:upload`, JVM-tested)

- [x] 2.1 Add the `BackgroundScheduler` seam (`scheduleNext()` / `cancel()`) to `:capability:upload`
- [x] 2.2 Implement `BackgroundUploadPump`: four trigger entrypoints (foreground / bg-task / session-events / on-complete), single-flight with a trailing re-run, `PROCESSING` re-arm (foreground waits for a completion; background ensures a scheduled task)
- [x] 2.3 `commonTest` for the pump: single-flight coalescing, `PROCESSING`-in-foreground waits, `PROCESSING`-in-background schedules next, `COMPLETED` idles — against a fake `BackgroundScheduler` + fake `UploadCycle` (runs on JVM + `iosSimulatorArm64`)

## 3. Shared PhotoKit discovery module (`:app:ios:photokit-discovery`)

- [x] 3.1 Create the module (iosArm64 + iosSimulatorArm64, no `jvm()`; depends on `:capability:upload` + `:domain:gallery`)
- [x] 3.2 Extract `IosDiscovery` from `IosUploadJobPlatform`: the change-token walk (`discoverResources`), the request builder (`setAssumesHTTP3Capable(false)`), and token archive/unarchive — as a shared **object** (composition, not inheritance). Also moved `IosDiscoveryStore` + the byte↔NSData helpers into the module (package `app.snapsync.ios.discovery`). `discover()` is `suspend` (the gallery enumerator is)
- [x] 3.3 Recompose `:app:ios:photokit-extension` onto `IosDiscovery`; rename `IosUploadJobPlatform` → `IosPhotoKitUploadPlatform`; confirmed behavior-preserving — `:app:ios:photokit-extension:iosSimulatorArm64Test` (PhotoKitSmokeTest 2/2) green on the runner

## 4. App-driven adapter (`:app:ios:url-session-upload`)

> Compile/link-verified on `iosSimulatorArm64` (all cinterop bindings resolve). Runtime behavior
> (background transfers, BGTask scheduling, delegate delivery) is device-only — no sub-26 runtime here.

- [x] 4.1 Create the module (iosMain, main-app-composed, not a separate target; depends on `:capability:upload` + `:app:ios:photokit-discovery`)
- [x] 4.2 Implement `IosUrlSessionUploadPlatform : UploadJobPlatform` over a background `URLSession`: `createJob` (`uploadTask(fromFile:)`, tag `taskDescription = key`, own cap → `LIMIT_EXCEEDED`, unusable payload/file → `FAILED`); `fetchRetryJobs` → empty; `fetchAckJobs` (drain delegate completions, key by `taskDescription`); `retryJob` = cancel+recreate; `acknowledge` = drop record + delete temp file; `discoverResources` delegates to `IosDiscovery`. Delegate is a separate `NSObject` `SessionDelegate` (a Kotlin-interface class can't also be an ObjC supertype); shared state guarded by `NSLock`
- [x] 4.3 Per-slot temp staging (`PHAssetResourceManager.writeData` to the App-Group `upload-staging` dir, only when a slot frees; delete on terminal); launch orphan-sweep (`sweepStaging()` skips files still referenced by live tasks)
- [x] 4.4 Precise reconciliation: `fetchAckJobs` matches `getAllTasks` to the ledger's `pendingKeys()` by `taskDescription == key`; a `REQUESTED` key with no live task + no completion → surfaced terminal `FAILED` (flips row `REQUESTED`→`FAILED`, retried on next full enum); no `clearRequested`
- [x] 4.5 Implement `IosBackgroundScheduler : BackgroundScheduler` over `BGProcessingTaskRequest` (network required, external power not; `scheduleNext` (re)submits, `cancel` cancels)

## 5. App wiring + Swift shell (`:app:ios`)

- [ ] 5.1 Tier branch in `SnapSyncRoot` at `backgroundUploadSupported()`: true → existing PhotoKit registration; false → construct the pump + `IosUrlSessionUploadPlatform` + `IosBackgroundScheduler` and hold the `LedgerWriter` in the app
- [ ] 5.2 App-driven lifecycle: enable (immediate cycle + schedule first task), disable (invalidate session + cancel task), re-provision (cancel old-event tasks + temp files → update event → `:capability:rejoin` reconcile → run cycle), leave (cancel all + wipe ledger/cursor/eventId)
- [ ] 5.3 Thin Swift shell: `BGTaskScheduler` registration, `URLSession` background-config + delegate, `handleEventsForBackgroundURLSession` → forward all into the Kotlin pump; `Info.plist` `BGTaskSchedulerPermittedIdentifiers` + `processing` background mode
- [ ] 5.4 `compileIosMainKotlinMetadata` (Linux proxy) + `./gradlew build` green

## 6. Docs

- [x] 6.1 `docs/design.md` §1: minimum iOS 27 → **18**, documented as two upload tiers (PhotoKit ≥26.1 / URLSession 18–26.0)
- [x] 6.2 `docs/design.md` §6: noted the app-driven transport is **simulator-testable** (unlike the PhotoKit extension); `BGProcessingTask` timing stays device-only
- [x] 6.3 `CLAUDE.md`: module table (+`:app:ios:photokit-discovery`, +`:app:ios:url-session-upload`; pump/scheduler in `:capability:upload`), min-iOS line, version-tier note; `app/ios/CLAUDE.md` two-tier deviation section + renamed adapter ref (the single-writer/enable wiring notes there update with task 5)

## 7. On-device verification (sub-26 device)

- [ ] 7.1 Sideload onto the sub-26 device; grant full photo access; confirm the app-driven pump starts a foreground cycle and uploads land in the bunny storage zone (authoritative check, not the status screen)
- [ ] 7.2 Background drain: background the app mid-upload; confirm transfers continue and the relaunch ping-pong tops up; confirm a `BGProcessingTask` fires and enqueues new captures taken while closed
- [ ] 7.3 Lifecycle: re-provision against a fresh event (reconcile seeds stored as `COMPLETED`, uploads only the gap) and leave (transfers cancelled, local state wiped)
