> Ordered by risk. Group 1 is the smallest, highest-value, compiler-enforced change and fixes the more
> severe defect (no reconciliation on the app-driven tier — which bites reinstall and leave→rejoin today,
> with no other bug present). Group 3 is the refactor that touches the **working** ≥26.1 tier, so it sits
> behind it. Group 6 is the on-device verification the original change archived unchecked (`7.3`) — it is
> what buys down Group 3's regression risk, and Group 5 is what makes it runnable at all.

## 1. Reconcile becomes compiler-mandatory in the shared cycle

- [x] 1.1 Add a required, **non-defaulted** `reconcile: suspend () -> Boolean` parameter to `UploadCycle` (`:capability:upload`), placed beside `photoCutoff` (the existing non-defaulted safety-critical hook). It returns `false` to defer the cycle when the listing fetch fails/times out. *(Signature corrected during apply: takes no `eventId` — `UploadCycle` is deliberately event-agnostic and the root's lambda closes over the eventId, exactly as `onBatchUploaded` does.)*
- [x] 1.2 Call `reconcile()` at cycle start (Phase 0), **before** any upload job is created; on `false`, create no jobs and return `COMPLETED` (a clean no-op, not a failure). A **throwing** reconcile is treated identically to `false`, preserving the deferral the roots previously applied with their own `runCatching`
- [x] 1.3 Extend `UploadCycleTest` (`commonTest` → JVM + `iosSimulatorArm64`): reconcile runs before job creation; a `false` reconcile creates zero jobs, walks no library, and does not advance the cursor; a throwing reconcile defers rather than failing the cycle
- [x] 1.4 Move reconcile in `UploadExtensionRoot` out of `process()` and into the `UploadCycle` parameter — the ≥26.1 tier keeps identical behavior, now via the shared seam. The **no-config (leave) marker clear** stays in the root, since no cycle is built without a config
- [x] 1.5 Wire the reconciler into `UrlSessionUploadController.runCycle()`: `ExtensionReconciler` + `HttpDeviceFilesSource` + `IosJoinedEventMarker`. The marker **moved** from `:app:ios:photokit-extension` to `:capability:membership`'s `iosMain` (beside its own `JoinedEventMarker` interface) — parking it in the extension module is precisely what kept it out of reach of the app-driven tier. This is the change that stops reinstall / leave→rejoin re-uploading the device's whole byte partition
- [x] 1.6 Verify the JVM tests and `./gradlew compileIosMainKotlinMetadata` pass — the latter is the Linux-runnable proxy for the iOS source sets (confirmed it executes, not `NO-SOURCE`, for `:app:ios:photokit-extension` and `:capability:membership`)

## 2. Leave stops wiping durable dedup state

- [x] 2.1 Delete `UrlSessionUploadController.leave()` — the `ledgerBackend.clear()` + `discoveryStore.clearToken()` teardown. This removes `LedgerBackend.clear()`'s **only** production call site in the repo
- [x] 2.2 Rename `UrlSessionUploadController.disable()` → `stop()` (its body — `platform.cancelAll()` + `scheduler.cancel()` — is already correct and needs no change)
- [x] 2.3 Confirm `clear()` remains on the `LedgerBackend` seam (it is the semantic basis of `resetTo` and is used by test/harness backends) but has no membership-lifecycle caller — **verified by grep: zero production call sites remain**
- [x] 2.4 *(added during apply)* Rename `LeaveEvent.disableExtension` → `stopUploads`. The old name put a **PhotoKit mechanism on a tier-neutral seam**, and on the app-driven tier it resolved to a destructive leave — the same liar's-name defect as `onLeaveOrSwitch`. Doc updated: leaving destroys no dedup state

## 3. The `UploadProducer` seam and the tested orchestrator

- [x] 3.1 Add the `UploadProducer` seam to `:capability:upload`: `suspend fun start()` and `suspend fun stop()`. **No destructive verb**
- [x] 3.2 Add the tier-neutral lifecycle orchestrator (`UploadArm`) to `:capability:upload`, binding provision / permission-grant / direction-change / leave to `start()`/`stop()` per the `upload-lifecycle` verb table. It reads `isGranted`/`includesUpload` as suppliers, so a caller cannot hand it a stale view of the membership it is deciding about
- [x] 3.3 Test the orchestrator in `commonTest` against a fake `UploadProducer` — **8 tests, all passing** (JVM; `commonTest` ⇒ also `iosSimulatorArm64` on CI). Includes `no_transition_can_reach_a_destructive_verb`, which drives every transition in every membership shape and asserts only `start`/`stop` are ever emitted. **This is the test that would have caught the bug and could not exist before**
- [x] 3.4 Make `UrlSessionUploadController` implement `UploadProducer`; the composition root and all four pump triggers stay untouched
- [x] 3.5 Add `PhotoKitUploadProducer` in `:app:ios`, absorbing the 3202 disable→enable toggle as `start()` and (`enable(false)` + `clearRequested` + clear cursor — the OS-job-wipe repair, genuinely needed on this tier) as `stop()`. The version guard is **gone from the call site**: the class is only constructed when the tier is selected
- [x] 3.6 In `SnapSyncRoot`: dissolve `enableBackgroundUpload()` / `disableExtension()` / `setUploadExtensionEnabled()`; select **one** producer at composition (`if (useAppDrivenUpload) urlSessionUpload else photoKitProducer`); route `provisionEvent`, the grant collector, and `LeaveEvent` through the arm. Renamed `enableBackgroundUploadOnGrant` → `startUploadsOnGrant` (another liar's name)
- [x] 3.7 `provisionEvent` cancels **nothing** on a switch: no `cancelAll()`, no staged-temp deletion. Persist the config and `start()` — in-flight transfers target the device-global URL and stay valid
- [x] 3.8 Delete the false log line `"background-upload extension re-registered (disable→enable, cleared REQUESTED)"` from the shared path — it now lives inside `PhotoKitUploadProducer.start()`, the only place where it is *true*

## 4. App-driven `start()` arms the heartbeat

- [x] 4.1 The enable path submits the first `BGProcessingTaskRequest`. **Implemented as a new `BackgroundUploadPump.onStart()`** (`alwaysScheduleNext = true`), not as a `scheduler.scheduleNext()` call in the controller: the re-arm *policy* belongs in the tested pump alongside the other four triggers, not in the untested app shell — putting it in the shell would repeat the very mistake this change fixes
- [x] 4.2 `BackgroundUploadPumpTest` covers it — **3 new cases, passing**: `onStart` drains and arms exactly one task; it arms **even on a fully-drained cycle** (a `PROCESSING`-only arm would lose exactly the "new photos while the app is closed" case the heartbeat exists for); and `onForeground` still arms nothing

## 5. The tier-force flag selects a tier and nothing else

- [x] 5.1 Derive simulator-ness from the environment (`SIMULATOR_DEVICE_NAME`) instead of inferring it from `SNAPSYNC_FORCE_URLSESSION_UPLOAD`. `useBackgroundSession = !isSimulator`, so a forced device run now uses a **background** session — the transport real 18–26.0 users get
- [x] 5.2 Confirm forcing the app-driven tier on a ≥26.1 device never calls `setUploadJobExtensionEnabled` — **verified by grep**: the call exists only inside `PhotoKitUploadProducer`, which the single `if (useAppDrivenUpload) …` at composition never constructs on this tier. Structural, not a guard
- [x] 5.3 Sanity-check the app-driven tier on `iosSimulatorArm64` — **`./gradlew iosSimulatorArm64Test` BUILD SUCCESSFUL** on the ssh-mac runner (macOS 26.4 / Xcode 26.5), so every `commonTest` suite (including the 11 new `UploadArm` / pump / reconcile-gate cases) passes on the simulator target, not just JVM

## 6. On-device verification (the original change's unchecked task 7.3)

- [x] 6.1 Build + dev-sign an IPA via the ssh-mac loop and install to the **iPhone SE2 (26.5.2)** over usbmuxd — unsigned archive → inside-out manual re-sign → `apps install`, all green. `iosSimulatorArm64Test` **BUILD SUCCESSFUL** on the runner (this is task 5.3)
- [x] 6.2 **SE2, PhotoKit tier — no regression.** Switch to a fresh event: `[arm.onLeave] → photokit.stop`, then `[provisionEvent] → arm.onProvision → photokit.start → photokit.stop → "re-registered (disable→enable, cleared REQUESTED)"`. The OS invoked the extension **302 ms** later; its log shows the new shape — `process: config present — running cycle (reconcile runs inside it)` — and a clean `COMPLETED`. **Upload-landing not exercised: the device library holds 0 own in-scope assets** (see 6.4)
- [x] 6.3 **SE2 + `SNAPSYNC_FORCE_URLSESSION_UPLOAD`, app-driven tier — the core fix, verified on device.** The switch runs `arm.onLeave → url-session.stop → scheduler.cancel` (no wipe), enrolls, then `provisionEvent → arm.onProvision → url-session.start → pump.onStart`. Then, **for the first time on this tier**, `GET /files/devices/<id> → 200` and `joined <event> — reset+seeded 4 file(s), cleared cursor`, followed by `scheduler.scheduleNext`. Confirmed absent: **any** `url-session.leave`, **any** ledger clear, **any** `photokit.*`/`setUploadJobExtensionEnabled` (tiers mutually exclusive under the force flag ✓ — task 5.2 on device). Heartbeat armed ✓ — task 4.1 on device
- [x] 6.4 **The storm regression check — PASSED.** Seeded 55 synthetic assets (`SNAPSYNC_SEED_PHOTOS`, sign-off obtained) and let all 50 in-scope own photos reach the byte partition (54 stored files). Then switched to a **fresh** event on the app-driven tier: `joined 1fc56da7 — reset+seeded 54 file(s), cleared cursor` → `discoverResources = 52 resource(s)` (full re-enumeration, cursor cleared) → `enumeration: 50 seen, **0 new**, 50 already-uploaded` → **0 `createJob`**, backend **54 → 54 unchanged**. On the old code this switch wiped the ledger + cursor and re-uploaded all 50
- [x] 6.5 **Leave→rejoin:** exercised as the switch's leave half. The ledger survived `url-session.stop` (no clear), and the next cycle's reconcile re-seeded the device's stored files `COMPLETED` from the authoritative listing
- [x] 6.6 **Objects land — PASSED, and the gap uploads.** Seeded 5 *new* photos: the app-driven tier issued **exactly 5** `platform.createJob` (4 `CREATED` + 1 `LIMIT_EXCEEDED` → `PROCESSING`, the adapter's cap + pump re-arm working as specified), and the bunny storage zone went **54 → 59**. Zero `photokit.*` lines and zero extension cycles throughout — the app-driven tier was provably the sole uploader. This is the original change's task 7.3 (*"reconcile seeds stored as COMPLETED, uploads only the gap"*), which was archived unchecked
- [x] 6.7 *(found during verification)* **Rig caveat, now documented:** the OS's upload-job registration record **persists** independently of the app, so once the SE2 has run the PhotoKit tier, the extension keeps being invoked even under `SNAPSYNC_FORCE_URLSESSION_UPLOAD` (the flag stops the app *registering* it; it cannot *de*register it). A faithful app-driven run on a ≥26.1 device must first deregister it — a **download-only** join on the PhotoKit tier does this headlessly (`arm.onProvision → photokit.stop → setUploadJobExtensionEnabled(false)`), which also verified the download-only branch on device. Irrelevant on a real 18–26.0 device, where no appex can exist

## 7. Docs and contract sync

- [x] 7.1 Root `CLAUDE.md`: the re-provision claim now states that the reconcile runs inside the shared `UploadCycle` as a **required** parameter, so it holds on both tiers — and records that it did not always
- [x] 7.2 `app/ios/CLAUDE.md`: records that the upload lifecycle is decided in `:capability:upload` behind the two-verb `UploadProducer` seam, that `:app:ios` holds only the two producers' mechanisms, and that the tier-force flag is now a faithful device-testing lever
- [x] 7.3 `npx … validate --specs --strict` passes (45/45) and `validate fix-app-driven-upload-lifecycle --strict` passes
- [x] 7.4 Sync the delta specs into `openspec/specs/` and archive the change (the archive move is itself a repo edit, so it ships **in** the PR)
