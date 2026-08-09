## 1. Prove the two platform claims first

Both gate the design. Neither is settled by a symbol table (`module-architecture`, "A platform-capability
claim is settled by a compile"). Do these before writing anything else.

- [x] 1.1 Prove `ktor-server-cio` 3.2.0 **serves on a physical device**: a throwaway `embeddedServer(CIO)` in a dev IPA on the SE2, answering a `curl` over `pymobiledevice3 usbmux forward`. Only simulator execution has been measured. Record the result (and the iOS version) in `design.md`.
- [x] 1.2 Prove `@EagerInitialization` fires in a **static** Kotlin/Native framework linked into the iOS app, and record when it runs relative to `SnapSyncRoot`'s own initialization.
- [x] 1.3 **If 1.2 fails, stop.** Do not silently fall back to a `startRig { app }` line in `SnapSyncRoot` — that shape was considered and rejected during design; a failed measurement is a reason to re-decide with the user, not to take it.

## 2. The module and its containment

- [x] 2.1 Create `test/rig/` (`:test:rig`): `commonMain`, targets `iosArm64` + `iosSimulatorArm64`, depending on `:domain`, `ktor-server-cio`, `ktor-server-core`, and `kotlinx-serialization-json`. No `jvm()` target, no tests — record why in the build file (it holds no projection that could be wrong).
- [x] 2.2 Add `include(":test:rig")` to `settings.gradle.kts` and grow `ModuleSetTest`'s target set in the same commit.
- [x] 2.3 In `app/ios/build.gradle.kts`, read `snapsync.rig` and — only when set — add `test/rig/src/hook/` to `iosMain`'s source dirs **and** the `:test:rig` project dependency. Without the property, add neither.
- [x] 2.4 Change `SnapSyncRoot.app` from `private` to `internal`. Confirm the generated `SnapSyncKit` ObjC header is byte-identical before and after.
- [x] 2.5 Verify containment end-to-end: build without the property and confirm no rig symbol is in the binary; build with it and confirm the server starts.

## 3. The server, its lifetime and its transport

- [x] 3.1 `RigServer`, taking `() -> AppCore` (a **thunk** — binding must force nothing; `SnapSyncRoot.app` and `.host` are `by lazy` and touching `host` installs the grant subscriptions) plus an injected platform-hook object.
- [x] 3.2 Bind `127.0.0.1` on port `18099`, overridable by `SNAPSYNC_RIG_PORT` read **in the hook file**. Do not touch `LaunchDirectives`.
- [x] 3.3 On bind failure, log at `Error` naming address, port, and that the channel is not listening; let the app run on unchanged.
- [x] 3.4 Ensure no request timeout below `ReceiptDeadlines.BACKGROUND_TASK` (120 s) is imposed anywhere in the server, or `deadline-expired` becomes indistinguishable from a dead channel.
- [x] 3.5 Log every request through Kermit as a `[rig]` line (enter and exit), so a rig-driven trigger is attributable in `debug.log` and the marker serves as a log cursor.

## 4. `/health` and `/state`

- [x] 4.1 Add the `kotlin-serialization` plugin and `kotlinx-serialization-core` to `:ui:presentation`; annotate `UiState`, `SyncHealth`, `JoinPhase` and `PendingSwitch` with `@Serializable`. Annotations only — no shape changes, no DTO.
- [x] 4.2 `GET /health`: resolved `CompositionMode`, `UploadTier`, the baked upload base, and the bound port.
- [x] 4.3 `GET /state`: the serialized `UiState`, plus readiness (`configResolved` and the active `eventId`, from the `config` StateFlow the container observes), ledger aggregates from `AppCore.ledgerCounts`, and the screen-level read-models exposed beside `UiState`. Direct `.value` reads only — aggregation, never transformation.
- [x] 4.4 Confirm readiness actually closes the measured ordering gap: `onForeground` fires ~2.2 s before the config resolves, so a caller must be able to poll a stated fact instead of sleeping.

## 5. `/logs`

- [x] 5.1 `GET /logs?process=app|extension&bytes=N` as a pass-through to `DeviceLogSource.tail`.
- [x] 5.2 Surface the port's `null` as a stated reason with a non-200 status — never an empty `200`, which would re-collapse the absence the port deliberately keeps distinct.
- [x] 5.3 Confirm on device that `process=extension` returns the extension's log with **no** relaunch, replacing the `SNAPSYNC_EXPORT_LOGS=1` + `apps pull` dance.

## 6. `/trigger`

- [x] 6.1 Build the trigger map in the hook file, keyed by name, each entry invoking the real `SnapSyncRoot` member exactly as the Swift shell does. Keep it branch-free (a map literal plus constructor calls) so it passes the shell gate.
- [x] 6.2 For the four receipted entries (`onSilentPush`, `runDownloadBackstop`, `handleBackgroundUrlSession`, `runUploadHeartbeat`), pass the rig's own lambda as the OS completion handler and block until it fires. Bridge with a `CompletableDeferred` (safe from any thread; `releaseOnce` already guarantees at-most-once, but tolerate a double-complete rather than throwing).
- [x] 6.3 Return `heldMs` and `deadlineMs` as measured facts. Classify **nothing** — do not derive `settled`/`deadline-expired` from the numbers or from the log.
- [x] 6.4 For the non-receipted entries (`onForeground`, `onBackground`, `onPushToken`, `onPushTokenFailure`, `onSceneContinueActivity`), return `202` immediately, because the platform does not wait either.
- [x] 6.5 `onSceneContinueActivity` takes a URL and fabricates the `NSUserActivity(NSUserActivityTypeBrowsingWeb)` with `webpageURL` that iOS would deliver.
- [x] 6.6 Exclude `onLaunch`, `applyLaunchEnvMembership` and `applyLaunchEnvPhotoLibrary`, each with its reason recorded at the exclusion site.

## 7. Guards

- [x] 7.1 Trigger-coverage guard: derive the `@PlatformEntry` population as `PlatformEntryLoggingTest` does and assert it equals wired + excluded, exactly, in both directions, with every exclusion carrying a reason.
- [x] 7.2 Loopback-only guard: the rig's source names no bind address but the loopback constant.
- [x] 7.3 Receipt expiry-line pin, beside the existing literal pins, naming the consumers that read its presence as ground truth.
- [x] 7.4 Add `test/rig/src/hook/` to the root build's `appShellSources` **and** to `KotlinShellGuardTest`'s mirrored `shellSourceRoots` in the same commit (the guard has a non-vacuity floor; the lists must move together). Confirm the `@Suppress` pin table still balances.

## 8. Runbook and close-out

- [x] 8.1 Add the runbook to `CLAUDE.md`: the `-Psnapsync.rig=true` build, `usbmux forward 18099 18099`, the `curl` idiom with a uniform `--max-time` above 120 s, and the endpoint list.
- [x] 8.2 State plainly in the runbook that `onForeground` — likely the most-used trigger — is in the `202` group and requires polling `/state`.
- [x] 8.3 State that the `18099` default is **device-only** (all simulators share the host loopback) and that a simulator host must set `SNAPSYNC_RIG_PORT` per instance, passed as `SIMCTL_CHILD_SNAPSYNC_RIG_PORT` — `simctl launch <dev> <bundle> KEY=VAL` passes argv, not environment.
- [x] 8.4 Warn that a rig session must **not** force the URLSession tier on an iOS ≥26.1 device until the `ComposedProducers` follow-on lands: the OS extension registration survives relaunch and reinstall, so two `LedgerWriter`s would share one App-Group ledger.
- [x] 8.5 Run `./gradlew build` and `./gradlew compileIosMainKotlinMetadata` both with and without the property, and `./gradlew architectureDiagrams`, committing the regenerated `architecture/`.
- [ ] 8.6 Open the follow-on change for `ComposedProducers` distinguishing selectable-from-stoppable (`upload-lifecycle`, `ios-photokit-upload`), to be verified through this channel.
