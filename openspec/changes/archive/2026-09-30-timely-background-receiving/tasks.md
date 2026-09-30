# Tasks

## 1. The OpenSpec change

- [x] 1.1 Verify `npx --yes @fission-ai/openspec@1.13.2 validate timely-background-receiving --strict` and `… validate --specs --strict` pass (the change ships in the phase's one PR)

## 2. The selection is read on every start under limited access (D6)

- [x] 2.1 Move the selection subscription and `gallery.observeChanges(true)` out of `installPermissionSubscriptions` into an idempotent `installSelectionObserver()` that `snapSyncHost` calls on composition, leaving the grant collectors, the album grant subscription and the interrupted-import sweep at host assembly; verify with a `:test:integration` scenario on the JVM host — `relaunch?scene=false` under `LIMITED` with a staged backlog → `device/os-record` shows the selection observed and the backend receives the backlog's bytes without an `onForeground` — and that the existing "a cold background wake assembles no host" assertions still hold
- [x] 2.2 Correct `CLAUDE.md` fact ① ("the observer opens only at host assembly"), the KDoc of `installPermissionSubscriptions` / `PhotoKitSelection` / `ComposedApp`, and `docs/architecture.md` where it states the read discipline, citing this change and the 2026-09-30 SE2 measurement; verify by grep that no text still claims a background start reads no selection

## 3. The wake port carries a cadence (D2, D8 contract)

- [x] 3.1 Add the cadence to `WakeTrigger.After` (busy / idle) and let `Heartbeat` request busy = 60 s, idle = 1 h; verify `HeartbeatTest` covers both triggers and the unchanged network requirement
- [x] 3.2 Add `IDLE_ARMS_ONE`, `IDLE_REPLACES_BUSY`, `BUSY_REPLACES_IDLE`, `CANCEL_CLEARS_IDLE` to `WakeContract`, and make every binding's `pendingWakes` count all of its identifiers; verify the mock binding (`WakeContractBindingTest`) passes and `ContractCoverageTest` lists the new clauses
- [x] 3.3 Implement the iOS side in `IosWake`: idle → `BGAppRefreshTaskRequest` on `app.snapsync.heartbeat.idle` (earliest 1 h), each submit cancels the other identifier, `listen` registers both launch handlers routed to `WakeId.Heartbeat`, `cancel` withdraws both, a refused refresh is reported (no fallback — D2, measured); verify `IosWakeTest` over the `BackgroundTaskApi` seam covers mutual replacement, both registrations, cancel-both and the refusal, and `./gradlew compileIosMainKotlinMetadata` is green
- [x] 3.4 Add `fetch` to `UIBackgroundModes` and the new identifier to `BGTaskSchedulerPermittedIdentifiers` in `iosApp/iosApp/Info.plist`, and teach `RuntimeIdentityTest` and the rig's `MockEntryDriver` / `RigVocabulary` the identifier; verify `:test:architecture` passes and `/os/app/onBackgroundTask?arg=app.snapsync.heartbeat.idle` fires the wake on the JVM host
- [x] 3.5 Implement the Android side in `AndroidWake`: idle → the same unique work with `setInitialDelay(1 h)`; verify the new clauses in `AndroidWorkContractTest` pass on `ANDROID_EMU` (`scripts/android-device-tests`)
- [x] 3.6 Re-record the iOS contract on the SE2 (rig build, `POST /contract/BackgroundScheduler` → `BackgroundScheduler@IOS_DEVICE_APP.rec`), committed unedited — with Background App Refresh on: with it off iOS keeps no task at all, so the recorder refuses (D2); verify the recording replays green in `:adapter:ios:app-only`'s iOS tests on the macOS CI job
- [x] 3.7 Update `docs/testing.md` / `docs/architecture.md` where they describe the heartbeat, its task identifier and the wake contract's recordings; verify by grep that each mention of the heartbeat identifier names both

## 4. The cadence rule (D1, D3)

- [x] 4.1 Replace `rearmFor` + `shouldSchedule` with one pure rule returning none / busy / idle over: joined, event ended, uploads remaining, imports remaining, full grant, OS library wake — keeping `Rearm.NEVER` triggers inert and the exhaustive `when` over `CycleResult`; verify with unit tests covering every row of D1's table
- [x] 4.2 Supply the OS-library-wake input from the composition: Android = the library watch stands; iOS = `osSupportsOsDrivenUpload && permission == GRANTED && extensionRegistration.isRegistered() == true` and the uploader pin not off; verify with `TailRunnerTest` cases (confirmed → idle when caught up; `null`/`false` → busy; limited → idle; denied → idle; receive-only → idle; after the end → idle; not joined → none)
- [x] 4.3 Add `pendingWake` (`{cadence, earliestSeconds}` or `null`) to `/device/os-record` from the wake mock's pending map; verify `:test:control`'s client reads it and the existing `heartbeatsScheduled` assertions still pass
- [x] 4.4 Add the cadence scenarios to `:test:integration` (receive-only caught up → idle; full-access sharer without a library wake → busy; the same after `clock/advance` past the end → idle; `device/permission?status=DENIED` → idle; a partial grant → idle; leave → `null`); verify they pass on the JVM host. The JVM root carries no OS uploader and no staged-import lever reaches a tail's end, so the confirmed-uploader and staged-imports rows are pinned by `TailRunnerTest` / `HeartbeatCadenceTest` instead

## 5. The bounded photo and close checks (D4, D5)

- [x] 5.1 Add the per-event last-checked service over `Preferences` (`reconcile.lastAt.<eventId>`, `close.lastAt.<eventId>`; every attempt stamps; a future time is due; clear on leave and reset) in `:domain:services`; verify with service tests over the `Preferences` mock
- [x] 5.2 Add `DownloadController.reconcileIfDue(eventId)` and stamp every unthrottled `reconcile` too; run `reconcileIfDue` in the end-of-wake step of every full-scope tail; verify with `:test:feature` tests (due → one union read; within the hour → none; upload-only → none; failed read still stamps)
- [x] 5.3 Put `EventCompletion.finish`'s event read behind the close bound, bypassed after `SILENT_PUSH`, `FOREGROUND` and `ARM` (the trigger reaches the step through `WakeHold.finishAfter` and `heartbeatThenFinish`); verify with `EventCompletion` tests (throttled wake within the hour → no read; push → read; foreground → read)
- [x] 5.4 Expose union and event read counts on the backend mock's operator face and the rig (`backend/requests?route=…`) if they are not readable yet; verify through `RigClient`
- [x] 5.5 Add the bound scenarios to `:test:integration`: two heartbeat wakes 10 min apart → 1 union read, `clock/advance` +1 h → 2; a silent push between wakes → the push reads, the next wake does not; after the end, two wakes within the hour → 1 event read, a close push → one more; verify they pass on the JVM host
- [x] 5.6 Update the KDoc of `EventCompletion`, `WakeEntry`, `Heartbeat` and `TailRunner` (the "download backstop went" and "no periodic wake" wording) to the new rule; verify by grep that no KDoc still says the app has no timed wake for a member who does not upload

## 6. Force-stop on Android

- [x] 6.1 Add a non-gating script beside `scripts/android-device-tests` that, on the emulator, leaves an idle wake pending, runs `adb shell am force-stop`, asserts no job is pending (`dumpsys jobscheduler`), starts the app and asserts the job is back; verify it passes on a local emulator run and document it in `docs/testing.md`

## 7. Integration

- [ ] 7.1 Verify `./gradlew build` (no display), `./gradlew compileIosMainKotlinMetadata`, `./gradlew architectureDiagrams` committed (the diagrams check is required), and the macOS `ios-test` / `ios-contracts` jobs green on the PR
- [x] 7.2 Verify on the SE2 with a rig build (device lock): a joined receive-only member caught up submits the idle refresh request (read back through `getPendingTaskRequests` in the app log), and a full-access sharer on 26.6 with the extension registered drops to idle once caught up
