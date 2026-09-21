## 1. Entry gate: admission and the two new outcomes

- [x] 1.1 Add `UploadAdmission` (`Admit` / `NotResolved` / `Withheld`) and a required `admission` input to `cycleGate`; add `CycleGate.NotResolved` and `CycleGate.Withheld(config)`, decided after `Skip` / `NotJoined` and before `Run`
- [x] 1.2 In `UploadCycle.settle()`, short-circuit both before `membership.policy()`: `NotResolved` → `CycleOutcome.NotResolved` with no settle; `Withheld` → acknowledge-only settle (drain terminals, adjudicate failures, no `createJob` / `retryJob`, no `reconcileStranded`) → `CycleOutcome.Withheld`
- [x] 1.3 Add both outcomes to `CycleOutcome` (result `SKIPPED`) and to the exhaustive `publish()` publishing nothing; routine-severity log lines
- [x] 1.4 Leave `signalRestart`'s pending flag untouched on a `NotResolved` cycle (it never reaches the stranded pass)
- [x] 1.5 `commonTest`: gate outcomes over every input; a not-resolved cycle drains nothing and leaves `REQUESTED` rows; a withheld cycle acknowledges, creates nothing, demotes nothing, writes no manifest; the policy supplier is never invoked for either (a supplier that fails the test if called)

## 2. Per-process admission wiring

- [x] 2.1 Add a required `admission: () -> UploadAdmission` to `UploadPorts` (no default) and pass it through `uploadCore`'s `readGate`
- [x] 2.2 Move the `PHAuthorizationStatus` → `PermissionStatus` mapping into `:adapter:ios:ext-safe`; make `PhotoLibraryPermission` delegate to it
- [x] 2.3 Extension root: admission = `GRANTED` → `Admit`, else `Withheld`, read in-process via the ext-safe mapping
- [x] 2.4 App-driven root (`UrlSessionUploadController`): admission = resolved kind (OS fact, current permission, override) == `URL_SESSION` → `Admit`, else `NotResolved`
- [x] 2.5 `:test:world`: its one cycle takes the app graph's admission (the world composes an OS without the OS-driven mechanism); world tests that a revoked grant withholds without blanking the union and a restored grant resumes. (The ≥26.1 two-process cases — app declines while the extension runs; the extension withholds under `LIMITED` — are covered by the domain gate tests and `ProducerExclusivityTest`, since the world models one process.)

## 3. Transitions replace the arm

- [x] 3.1 Reshape `OsDrivenUploadMechanism` into `ExtensionRegistration` (`register()` = the unchanged disable → demote → enable ritual, `deregister()`, `isRegistered()`); delete its trigger declines
- [x] 3.2 Define `AppUploadEngine` (the four triggers + `arm()` = `signalRestart()` + `pump.onStart()`, `disarm()` = `cancelAll()` + `scheduler.cancel()`) and implement it on `UrlSessionUploadController`
- [x] 3.3 Add the stateless `UploadTransitions` in `feature/upload` with `onJoin` / `onReconfigure` / `onPermissionChanged` / `onLaunch` / `onLeave` per design D1 (forced vs compared; compare reads `isRegistered()` only under `GRANTED`; every enable through the ritual; disarm whenever not wanted)
- [x] 3.4 `commonTest` for `UploadTransitions`: every row of the desired-state and transition tables, the download-only-join-then-reconfigure registration, `LIMITED` → `GRANTED` disarm-before-register, revocation disarms, no-membership arms nothing, no compared write under a non-`GRANTED` grant
- [x] 3.5 Delete `UploadArm`, `UploadProducer`, `UploadMechanismRuntime`, `IdleUploadMechanism`, `RelinquishThenRun`, `uploadMechanismTable`, `requireConsistent`, and their tests (`UploadArmTest`, `IdleUploadMechanismTest`, `UploadMechanismTableTest`)

## 4. Composition and call sites

- [x] 4.1 `AppPorts`: replace `osDrivenUpload` + `relinquishOsRegistration` with `extensionRegistration: () -> ExtensionRegistration?`; retype `appDrivenUpload` to `() -> AppUploadEngine`; update `CompositionSeamTest` pins in the same commit; confirm 44 fields
- [x] 4.2 Build `UploadTransitions` in `SnapSyncApp`; rebind `LeaveEvent.stopUploads` and `MembershipEntry`'s leave to `onLeave`, `ReconfigureEvent.armUpload` to `onReconfigure`
- [x] 4.3 `flow/Provision`: replace the `uploadArm: UploadArm` parameter with a `reconcileUploads: suspend () -> Unit` effect bound to `onJoin` — same position (after refresh, after the share-set load), no new branch, parameter count unchanged
- [x] 4.4 Route the foreground pump, the silent-push fan-out, the selection-change collector and `SnapSyncRoot.runUploadHeartbeat` to the app engine unconditionally
- [x] 4.5 `installPermissionSubscriptions`: call `transitions.onLaunch()` explicitly; make the permission collector skip the replayed value (`drop(1)`) and call `onPermissionChanged()`
- [x] 4.6 `:app:ios` / `:app:ios:extension` roots: wire the registration and the engine; keep `detektAppShell` at threshold 2 (no decision in either shell)
- [x] 4.7 `:test:rig`: the upload-mechanism pin command runs the compared reconcile after setting or clearing the pin
- [x] 4.8 Update the KDoc that names the deleted types (`SnapSyncRoot`, `UrlSessionUploadController`, `DownloadController`, `BackgroundUploadPump`, `world/Fakes.kt`, `Provision`)

## 5. Guards and budgets

- [x] 5.1 Re-point `ProducerExclusivityTest` per design D8: resolver cells; both admission functions; verb sequences over a fake registration (3311 under `LIMITED`, grant-dependent read) and a fake engine, including override set/clear; assert no reachable two-writer state, no bare enable, no compared write under a non-`GRANTED` grant
- [x] 5.2 Check `KotlinShellGuardTest` / `SwiftShellGuardTest` pins and `DeletionLedgerTest` for the moved and removed sites
- [x] 5.3 `./gradlew architectureDiagrams` and commit the regenerated `architecture/`
- [x] 5.4 `./gradlew build` and `./gradlew compileIosMainKotlinMetadata` green; no detekt tier ceiling raised

## 6. Device verification (before ship)

- [x] 6.1 iOS < 26.1 (or ≥26.1 under `LIMITED`): kill the app, let a cold `BGProcessingTask` wake fire (rig `/os`), confirm a cycle ran and the next heartbeat was submitted — *verified on the SE2 via a url_session pin (arm scheduled the first heartbeat; `runUploadHeartbeat` ran a cycle and re-submitted). A truly cold background launch cannot be forced headlessly (`dvt launch` assembles the UI); the cold path is the trigger routing, covered by the shell wiring and the gate tests.*
- [x] 6.2 ≥26.1 `GRANTED`: foreground and a cold wake log `NotResolved`, write no ledger row; the extension keeps uploading; a relaunch with the record live makes no registration write
- [x] 6.3 `NOT_DETERMINED` cold wake (reset privacy on the simulator): no permission dialog (`tccd` shows no `AUTHREQ_PROMPTING`) — *verified on an iOS simulator: zero prompts across relaunch, heartbeat, foreground and silent push; the extension-side withheld path is not forceable there (the rig refuses unless PhotoKit is resolved) and is covered by the gate tests*
- [x] 6.4 Download-only join on ≥26.1 `GRANTED`, then reconfigure to upload: the extension is registered and uploads land
- [x] 6.5 `LIMITED` member: uploads go through the app engine; if the extension is invoked it logs `Withheld` and writes no manifest — *verified on the SE2 (iOS 26.6): a new selection photo uploaded through the app engine (ledger +1); the OS itself invoked the surviving extension under `LIMITED` 4 s later and the gate withheld it (nothing created, nothing published) — a new measurement, recorded in the specs and CLAUDE.md*

## 7. Ship

- [ ] 7.1 PR labelled `bug` (customer-visible: background uploads resume after cold wakes); `/ship --keep-workspace`
- [x] 7.2 At sync/archive: rewrite `upload-lifecycle`'s Purpose (no producer seam; the arm is the transitions set; exclusivity gated) and run the three archive gates
