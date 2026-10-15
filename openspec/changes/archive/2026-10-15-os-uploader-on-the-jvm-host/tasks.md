# Tasks

## 1. The OS fact (design D1)

- [x] 1.1 Give `ExtensionRegistryMock` a mutable `carried` fact and a record cell that always exists, with `port()` reading `carried` when it is built. Verify: a mock test shows that a port built before a toggle keeps its answer, a port built after it answers the new one, and the record survives off → on.
- [x] 1.2 Persist `carried` in `MockState` beside `REGISTERED`; `MockDevice(osDrivenUpload)` becomes the fact's starting value, which the iOS launch adapters fill from the real OS. Verify: `MockStateTest` round-trips the fact, and its existing `osDrivenUpload = true` cases still pass.
- [x] 1.3 Add `carried(on: Boolean)` to `ExtensionRegistryLevers` and its operator, with KDoc stating it takes effect at the next launch. Verify: `LeverFacesTest` is green and the face stays under `TooManyFunctions`.
- [x] 1.4 Make the JVM host's `uploadTier` (`JvmRigHost.kt`) read the fact at each launch instead of `uploadersCarried(false)`. Verify: a `:test:control` JVM-host test reads `host.state()`'s build map as `app` before and `app+extension` after `carried(true)` plus `relaunch`.
- [x] 1.5 Add the registry's lever to the behaviour DSL's `Acts` (`:test:control`) and a shared `given` step (`:test:integration`'s `Steps.kt`, where the shared steps live) that sets the fact and relaunches. Verify: a smoke behaviour test with the step sees `ExtensionRegistered(true)` after a full-grant join.

## 2. The registry follows the library's grant (D2)

- [x] 2.1 Hand `ExtensionRegistryMock` a read of `PhotoLibraryMock.grant` in `MockDevice`, replacing `partialGrant`, so that under `LIMITED` `setEnabled` answers `3311` both ways and leaves the record, and `isEnabled` answers the record. Verify: the mock's `ExtensionRegistryContract` binding passes both partial-grant clauses with the grant moved by the library mock, not a constructor flag.

## 3. The survival clause (D3)

- [x] 3.1 Add state `RECORD_PRESENT_UNDER_PARTIAL_GRANT` and clause `A_PARTIAL_GRANT_READS_NO_RECORD` (`isEnabled → NOT_REGISTERED`, D3/D9) to `ExtensionRegistryContract`, with KDoc giving the by-hand downgrade procedure. Make the device binding answer `NotRunHere` unless it finds a record under `.limited`. Bind the mock. Verify: `ClauseCoversTest` and the mock binding pass.
- [x] 3.2 Re-record `UploadExtensionRegistry@IOS_DEVICE_APP.LIMITED.rec` (and `…GRANTED.rec`, for iOS 27) on the SE2 under its lock (load `snapsync-device` and `rig-channel` first): join an event of our own under full access (which registers), narrow to limited in Settings, then run the contract. Diff the two refusal clauses against the committed recording, and stop and report if their answers changed. Verify: the new recording shows the clause `Passed`, and the build's replay of it passes. (Recorded on iOS 27.0.1, 2026-10-15; both refusals unchanged; the first clause failed on the device and was restated: D3, D9.)
- [x] 3.3 Run `./gradlew :test:architecture:test`. Verify: `ContractCoverageTest` and `AdapterCoverageTest` count the new clause through its recording.

## 4. The force-quit (D4, D5)

- [x] 4.1 Give `UploadSessionMock` a force-quit cancellation that takes live transfers out of flight and reports their ends, cancelled, only when the opened app brings the session up (D4, D8). Verify: a mock test shows the transfers gone from `view()` and the record kept; the force-quit integration test shows the cancelled photo sent again after the opening.
- [x] 4.2 Add `forceQuit()` to `PlayedOsLevers`, a `WorldEvent.ForceQuit`, and the hold where `MockEntryDriver` routes app deliveries. While held:
  - `backgroundTask`, `silentPush`, `backgroundTransfers` and played wakes are refused with a reason naming the force-quit;
  - `extension-host` answers;
  - pending wakes are kept;
  - `os.foreground()` ends the hold and builds the screen.

  Cite Apple's documented behaviour (the two decision records) in the KDoc. Verify: a `:test:control` JVM-host test asserts each app-launching verb is refused while held, `extension-host.process()` is not, and a pending heartbeat survives into the opening.
- [x] 4.3 Refuse `forceQuit` on a host that does not own the app's process. Verify: a `:test:rig` JVM test (`PlayedOsForceQuitTest`) shows the served `played-os` refusing only `forceQuit`, with the reason, without the process, and honouring it with one.

## 5. The tests (D6)

- [x] 5.1 Re-point `HeartbeatCadenceIntegrationTest.a_full_grant_sharer_keeps_looking_during_the_event`'s `@Verifies` to "No wake arrives". Verify: `TestsWaitOnEventsTest` is green.
- [x] 5.2 Write "Uploads work below iOS 26.1" as a background-upload test with the fact off (D6 row 1). Verify: it passes, and fails if the fact is turned on.
- [x] 5.3 Write "iOS's uploader follows a switch" (D6 row 2). Verify: it passes.
- [x] 5.4 Write "Reopening after a force-quit resumes uploading" (D6 row 3), with a transfer in flight at the force-quit. Verify: it passes, and fails if `forceQuit` is replaced by `relaunch(scene = false)` (run and reverted).
- [x] 5.5 Write "iOS's uploader keeps sharing after a force-quit" (D6 row 4). Verify: it passes.
- [x] 5.6 Write "A photo both uploaders pick up arrives once" (D6 row 5), reading `public-backend.union()` once settled. Verify: it passes.
- [x] 5.7 Write "Narrowing access after joining keeps unselected photos private" (D6 row 6, in `OsUploaderIntegrationTest` beside the other five, claiming `photo-access`), with KDoc citing the 2026-09-21 invocation measurement and its ⏰ note. Verify: it passes, and fails if `extensionAdmission` admits `LIMITED` (run and reverted).
- [x] 5.8 For any test above that fails, fix the defect in this change and add the fix as a decision to `design.md`. Verify: the test passes, and the design names the defect. (One failed, and the defect was the mock's: D8.)

## 6. Coverage and the gate (D7)

- [x] 6.1 Run integration coverage, against a baseline measured on `69f35436b`. Raise each moved module's `integration(…)` floor in the root `coverage { }` to its new measure, or delete the exception if it reaches 95/85. Verify: the coverage task passes at the raised floors. (`:domain:model` 76/46 → 77/47, `:domain:services` 82/60 → 83/61, `:domain:feature` 90/73 → 91/73; no module reached the goal.)
- [x] 6.2 Update `docs/testing.md` §6–7 where it names the JVM host's OS as "no OS uploader" or lists the played OS's acts. Verify: the docs name `carried` and `forceQuit`, and the `rig-channel` skill's lever list matches `rig.advertise()`.
- [x] 6.3 Run `./gradlew build`, `./gradlew compileIosMainKotlinMetadata`, `./gradlew architectureDiagrams` (commit any change) and `npx --yes @fission-ai/openspec@1.13.2 validate --specs --strict`. Verify: all pass.
