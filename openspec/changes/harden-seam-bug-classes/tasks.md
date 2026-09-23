Each numbered group ships as its own PR, in order (design D1). Every group ends green under
`./gradlew build` plus `./gradlew compileIosMainKotlinMetadata`, and with `architecture/` regenerated.

## 1. G1: seam contracts (PR 1)

- [x] 1.1 Add the `DeviceIdentity` port to `ports/` with its throw contract. `KeychainDeviceIdentity` implements it, and the world gets a constant fake. Replace every `deviceId: () -> String` seam in `AppPorts`, `UploadPorts` and the six features (design D3).
- [x] 1.2 Add `refresh()` to the config port, bound to `FileBackedConfigStore.reload()`. Delete `AppPorts.reloadConfig`, and have the flows' `reloadConfig` collaborators built in `compose/` call the port.
- [x] 1.3 Replace `AppPorts.scheduleBackstop` with a backstop `BackgroundScheduler` bound to `IosBackgroundScheduler(DOWNLOAD_BACKSTOP_TASK_ID)`, and delete `SnapSyncRoot.scheduleDownloadBackstop` (fixes B8 for the backstop).
- [x] 1.4 Derive the extension's admission in `uploadCore` from a permission port plus a tier kind. Delete the inline `currentPhotoPermission()` lambda in `UploadExtensionRoot`.
- [x] 1.5 Carry `AlbumManager` in both bundles in place of `albumExcludedAssetIds`. Make `appVersion` and `host` plain `String` values.
- [ ] 1.6 Replace the feature lambdas for `now`, `permission`, `joined`, `activeEventId` and `activeConfig` with the existing ports (`Clock`, `PhotoAccessStatusSource`, `ConfigSource`), and change `SecureStore.resolveOrMint` to take `legacy: SecureStore?`.
- [ ] 1.7 Add the `UserQueries` bundle to `model/` (`loadJoinDetails`, `shareableCount`) and build it in `compose/` with the `awaitingOnCoreLane` decorator. `StatusContainerHost` takes it and runs the count as an intent reducing `Counting | Count | Unavailable`. Delete `ShareCountRow`'s effect query and `ParticipationActions.shareableCount` (fixes B6).
- [x] 1.8 Pass `QueuedPhotoDownloadJobs.onStaged` as a constructor parameter that resolves `downloadController` when invoked. Delete the `var` and the `?: return` (fixes B1), and do the same for `MetricKitProcessMetricSource.onReport` and `SnapSyncRoot.uploaderPinSource`.
- [ ] 1.9 Remove every default from function-typed parameters in production source (`UserCommands`, `StatusActions`, `BackgroundUploadPump`, `ShareableCountSource`, `Provision`, `candidatesFromFacts`, …) and fix every construction site.
- [ ] 1.10 Add the `StatusContainerHost.statusActions(…)` factory in `:ui:screens`, used by the iOS shell, the forge and the desktop pane. `StatusPane` takes the world's `UserCommands` instead of rebuilding them, which restores `choosePhotos` and `openLink`.
- [ ] 1.11 Widen `CompositionSeamTest` to discover every function-typed constructor parameter in `feature/` and `compose/`, including nullable function types. Pin each one with its binding per composition. Rewrite the stale pins (`deviceId`, `refreshAttestation`, `appVersion`, `UploadRecordPorts`), and add a nullable-function-type sample case.
- [ ] 1.12 Add the callback-slot gate and the lambda-default gate (content slots exempt; fail closed on an empty scan), and extend `CommandLaneTest` to cover `UserQueries`.
- [x] 1.13 Add a regression test that builds `AppCore`, touches only `downloadJobs`, stages a resource through the fake transport, and asserts that the controller marked it staged and imported it.

## 2. G2: the world matches production (PR 2)

- [ ] 2.1 Build `provision`, `refreshAttestation` and `registerPush` in `compose/` from the core, and remove them from `AppPorts`, `SnapSyncRoot` and `World`. `registerPush` gets `PushRegistration` and `PushTokenSource` from the core.
- [ ] 2.2 Make the world's operator `provision()` lever a separate operator edge (behaviour unchanged, per the harness-world-model spec), and point `onEventMinted`'s default at the composed Provision flow.
- [ ] 2.3 Delete the world's `init`-time touches of core lazies (for example `core.downloadController`). Add a test-only `initializedMembers()` accessor on `AppCore`, and a test asserting that a freshly built world has initialized only what the iOS root forces at process start.
- [ ] 2.4 Add a `@ParityFor("<entry>")` integration test in `:test:integration` for each OS entry point in `SnapSyncRoot`: foreground, background, open-url, push token, silent push, upload heartbeat, download backstop, and background-URLSession relaunch. Each starts from a cold core.
- [ ] 2.5 Add the entry-point parity gate: derive the entry points from `OsHandlerContainmentTest`'s scan and match them exactly against the tagged tests.
- [ ] 2.6 Remove the push-on-join test's manual `provisionFlow.run(...)` workaround, now that a join runs the real flow.

## 3. G3: failures (PR 3)

- [ ] 3.1 Add `runCatchingCancellable` and `bestEffort` to `model/` and migrate every `runCatching`/`catch (Throwable|Exception)` site in production source: the step helpers, `UploadCycle`, `SilentPush`, `LedgerCountsSource`, `DeviceAttestation`, and the ten HTTP adapters (fixes B12).
- [ ] 3.2 Wrap `PushRegistration.register`'s `deviceId()` read in its failure handling (fixes B11), and fix `DeviceAttestation`'s misattributed-throw comment and handling.
- [ ] 3.3 Add the catch gate, with an allowlist of the helpers, the ObjC-boundary helpers and `runProcessCycle`.
- [ ] 3.4 Add `fanOut` to `model/` and move `Foreground` and `Provision` onto it. Add a throwing-child test for each flow (fixes B4).
- [ ] 3.5 Teach the architecture-diagram flow transcriber the `fanOut` form, regenerate `architecture/`, and add the flow fan-out gate.
- [ ] 3.6 Add the `required`/`bestEffort` step receiver. Make the config save in `ReconfigureEvent` required, have it return `ReconfigureOutcome`, and have presentation reduce `SaveFailed` into the settings surface. Add a save-failure test (fixes B5). Apply the same receiver to `LeaveEvent` and `ResetDeviceState`.
- [ ] 3.7 Add `objcBoundary` and `checkedObjC` to `:adapter:ios:ext-safe`, and move the importer blocks, both URLSession delegates, `PhotoSelectionObserver`, MetricKit, every `performChangesAndWait(…, error = null)` call, and every `submitTaskRequest` call onto them (fixes B10, B8).
- [ ] 3.8 Add the ObjC-boundary gate (heuristic, stated in its source) with a positive self-test.

## 4. G4: collapsed states (PR 4)

- [ ] 4.1 Backend: move `/attest/token` and `/attest/renew` into per-version routers. v1 keeps its current handlers byte for byte; v2 answers a stale challenge with `409 stale challenge`. Add `api/` tests covering both versions. Pass `deno lint` and `deno task test`.
- [ ] 4.2 Have the credential interceptor attach and remember the token it sent, and report `onRejected(sentToken)` only for a token-bearing `401` on a gated path. Add a test pinning the ungated-path predicate to the backend's closed list (fixes B2).
- [ ] 4.3 Make `HttpAttestClient` return a sealed outcome (`Minted | ChallengeStale | NotAttested | Refused | Unreachable`). `DeviceAttestation` handles `ChallengeStale` with a single fresh challenge and never clears the token.
- [ ] 4.4 Add `AttestStore.clearTokenIf(expected)` and have `DeviceAttestation.onRejected(t)` compare-and-clear and trigger one refresh per rejected token. Wire it in both shells, keeping the token-rejection route guard green (fixes B3).
- [ ] 4.5 Add `MembershipRead` (`Member | NotMember | Unreadable`) on `ConfigSource`, backed by `FileBackedConfigStore`'s absence classifier. `UploadTransitions` and the push receivers log and defer on `Unreadable`.
- [ ] 4.6 Rename `isGranted` to `hasUsableAccess` in `Provision`, `AlbumGather` and `AlbumCoordinator`, and fix their KDoc.

## 5. G5: concurrency and re-entrancy (PR 5)

- [ ] 5.1 Make `PhotoSelectionSnapshotSource` emit serially: channel-backed emission on one lane, a baseline generation check, and changes queued behind the baseline. Add ordering tests for rapid changes and for a grant upgrade during the baseline (fixes B9).
- [ ] 5.2 Confine `QueuedPhotoDownloadJobs.outstandingImports` to a single-parallelism lane shared with `drained()`. Add a test that interleaves registration with a drain.
- [ ] 5.3 Add the `@ConfinedTo` annotation and the confinement gate (heuristic, stated in its source) with a positive self-test.
- [ ] 5.4 Add `guardedIntent` to `StatusContainerHost` and move create, rename and the switch's leave onto it. Add double-tap and cancel-mid-flight presentation tests (fixes B13 and the rename flag).
- [ ] 5.5 Key `reconfiguringState` and the rename status by the joined `eventId` in one `JoinedSurface` state that resets when the membership changes. Add a test covering an open Settings surface, a switch, and a fresh join (fixes B7).
- [ ] 5.6 Add the screens-take-no-suspend-seam gate to the presentation gate.

## 6. Close-out

- [ ] 6.1 Re-run the B1 device reproduction from workspace `repro-onstaged-drop` against the G1 build, and record the result.
- [ ] 6.2 Correct the stale spec text the review found outside this change's deltas: `leave-event` and `event-invite-qr` still describe `leave`/`share` as lambdas injected into `StatusContainerHost`.
- [ ] 6.3 Run the three archive gates in `openspec/config.yaml` before archiving.
