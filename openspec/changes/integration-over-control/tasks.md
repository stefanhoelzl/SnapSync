## 1. One shared host composition (D1, D2)

- [x] 1.1 Add `:app:composition` (jvm + iosArm64 + iosSimulatorArm64; depends on `:domain:compose` and `:ui:presentation`; no test source set). Enrol it in `ModuleSetTest`'s expected set and the detekt tier coverage.
- [x] 1.2 Add the inbound port `BackendVerdicts` (credential rejected / version refused / served), have `AppCore` expose `backendVerdicts`, and add a `withCredentialInterceptor(token, verdicts, appVersion)` overload. Wire `SnapSyncRoot` and `World` through it.
- [x] 1.3 Add `appStoreUrl`, `timeZone` and `displayClock` to `AppPorts`.
- [x] 1.4 Implement `snapSyncHost(scope, ports)`: `snapSyncApp`, then on first `host` touch `installPermissionSubscriptions` + `installPushRegistration` → `StatusSources` from every core read-model → `StatusContainerHost`.
- [x] 1.5 Make `SnapSyncRoot` delegate to `snapSyncHost` without changing behaviour. Update the `KotlinShellGuardTest`/`SwiftShellGuardTest` pins, and run `detektAppShell` and `compileIosMainKotlinMetadata`.
- [x] 1.6 Make `World` compose through `snapSyncHost` and expose `host`. Point the desktop `StatusPane`/world harness at `world.host`, and delete their hand assembly.
- [x] 1.7 Shrink `JvmRigHost.compose` to building a world with the rig's `onEventMinted` routing. Use `attests = true` on the mini-edge and off on deno.
- [x] 1.8 Add JVM host tests (`:test:control`): `UpdateRequired` is reachable after a minimum-version refusal, and a delivered push token lands as device config.
- [ ] 1.9 Regenerate `architecture/` (`./gradlew architectureDiagrams`) and run `./gradlew build` green.

## 2. World levers and reads (D5, D6, D7)

- [x] 2.1 Classify every `World` cell as durable or process memory in one place, and implement `World.relaunch()` over that list, with the cold-start sequence. Add a `:test:world` test that pins each durable cell surviving and each memory cell starting fresh.
- [x] 2.2 Add the APNs record to the mini-edge (a push per registered token when the event changes) and the neutral read, which answers unavailable on deno. Add a `:test:world` test for it.
- [x] 2.3 Add the neutral backend reads the mini-edge lacks: departed, device config, event name (publish count already existed). Each answers unavailable on deno where the public surface cannot serve it. The attest-mint count was not added: the world's attestation client is its own mock, not the backend, so PushRegistration `:146` is reshaped instead.
- [x] 2.4 Add backend levers on the neutral surface: hold-leave (+ release), fail the device-files listing route, deposit bytes without an ack, register a legacy event with no `startsAt`, refuse the next credential. Each answers unavailable on deno.
- [x] 2.5 Add world levers: `failNextEnumeration`, seeding with chosen id/date/kind (screenshot, HD video, Live Photo), a filename on the foreign-device helper, appending to the app and extension log cells the dump reads, and an await-parked-import signal.
- [x] 2.6 Determine whether the StagedByteReclaim backlog is reachable through the import levers plus `relaunch` (design, Open Questions). If it is not, add the `staging/seed-backlog` lever and flag it in the PR.

## 3. The protocol (D5)

- [x] 3.1 Extend the `/user` table with `rename`, `renameStatusConsumed`, `confirmSwitch`, `retryLoad`, `sendDiagnostics` (`409` when the composed command is absent) and `setRange`. Correct or remove the stale exclusion reasons, and fix the KDoc's command count.
- [x] 3.2 Add the new `/device` entries to `RigVocabulary` (device reads, backend reads, backend levers, world/OS levers). Wire each on the JVM host over the world.
- [x] 3.3 Classify every new entry on the app host: world levers refused with the shared reason, and unwired device facts refused naming that. Add `originalFilename` to `GalleryView` on both hosts (app host: `PHAssetResource`).
- [x] 3.4 Add the new parameters: `gallery/seed` `id`/`date`/`kind` (the app host refuses `id` and the synthetic kinds with a reason), `foreign-device` `filename`, and `downloads/stage` returning while an import is parked.
- [x] 3.5 `:test:control` tests: every new entry is honoured on the JVM host and `GET /device` has no unclassified entry; the version refusal and push registration reach a JVM host. (The typed helpers live in the integration fixture, over `RigClient`'s generic `deviceVerb`, rather than as one `RigClient` method per verb.)
- [x] 3.6 Update the `rig-channel` skill's verb list.

## 4. Inbound-port bindings move (D10)

- [x] 4.1 Move `EntryContractFixtures.kt` (+ `quietDiagnostics`) to `:test:world` `commonTest`, `EntryContractsJvmTest` to `jvmTest` and `EntryContractsSimulatorTest` to `iosSimulatorArm64Test`, reading `world.host`.
- [x] 4.2 Confirm that `ContractCoverageTest` still counts `JVM` and `IOS_SIM_KEXE` for both inbound contracts, and that `:test:world:iosSimulatorArm64Test` runs the simulator binding.

## 5. Contract clause (D9)

- [x] 5.1 Add the publish-version-ordering clause to `ManifestPublisherContract`: an older publish is refused and changes nothing, and an equal one is accepted. Verify it passes on the mini-edge `Fake` and the live deno binding; fix the mini-edge if it diverges.

## 6. Integration suite onto the protocol (D3, D4, D8)

- [x] 6.1 Convert `:test:integration` to a JVM-only module (`src/test`). Set its dependencies to `:test:control` and `:test:rig`'s JVM entry point, with no `:test:world`, and state the forgone Kotlin/Native coverage in the build file.
- [x] 6.2 Write the `rigTest { rig -> }` fixture and the client-only helpers (create-and-join with a window containing the seed date, seed, cycle, gallery ids, await health). Delete `LedgerReads.kt` and `HostFixtures.kt`.
- [x] 6.3 Migrate FullStack (16) and JoinGate (15).
- [x] 6.4 Migrate ShareSet, SelectionPolicy, SelectionIsTheWalk, NarrowedScope, AdmittedSet, ShareableCount.
- [x] 6.5 Migrate Reconfigure, VersionGate, BoundedStatusStaleness, BoundedTopUp (`:23`), CapTruncatedPublish (`:24`), DeclaredIntent, LostUploadAck, ColdDownloadRelaunch.
- [x] 6.6 Migrate InterruptedImport, UnreadStatus, Rename, CycleEntryGate, DiagnosticDump, ManifestVersion (`:105`), PushRegistration (`:65`, `:112`, `:146` reshaped), StagedByteReclaim.
- [x] 6.7 Delete the nine tests in design D8 (BoundedTopUp `:51`, CapTruncatedPublish `:48`, InterruptedImport `:148`, ManifestVersion `:32` `:53` `:79`, UnreadStatus `:99`, Rename `:134`, PushRegistration `:35`), and list them with their remaining coverage in the PR description.
- [x] 6.8 Grep the suite and confirm that no test asserts `RigState.ledger`, and that no source names `World`, `app.snapsync.ports`, `app.snapsync.flow` or `app.snapsync.compose`.
- [x] 6.9 Port any integration test added on `main` meanwhile, e.g. the `os-recipe-timeouts` branch's `ExtensionCredentialRereadIntegrationTest`, when rebasing. (Rebased onto main at 4813b149: no new integration test had landed; `os-recipe-timeouts` has not merged.)

## 7. Journeys in `ios-contracts` (D11)

- [x] 7.1 Add the `:test:integration:journeys` task (outside `build`): the three journeys over `RigClient` and the adapter-layer Ktor clients against deno. It fails naming any missing `snapsync.journey.appA`/`appB`/`backend` property.
- [x] 7.2 Extend `scripts/sim-contracts` (or add `scripts/sim-journeys`): create, boot and grant a second simulator in parallel with the first, and launch both apps on their own rig ports.
- [x] 7.3 Have the same script read each app's `GET /device` and fail on `unclassified` or `outsideVocabulary`.
- [x] 7.4 In `ios.yml`'s `ios-contracts` job: add `denoland/setup-deno`, background `deno task dev:local` on 8080 with its output captured, warm it up with one request, run the journeys after the contracts, and upload the backend log and both app logs as evidence.
- [x] 7.5 Measure the job's wall clock on the PR's runs and record the result in design D11. If it exceeds +6 min, move the second simulator's boot ahead of the xcodebuild.

## 8. The mirror (D12)

- [x] 8.1 Add attach mode to `:app:desktop:run` (`-Psnapsync.attach=<url>`): the left pane renders `StatusScreen` from polled `RigState.ui`, taps post the matching `/user` intent, and taps with no intent are inert and logged.
- [x] 8.2 Add the right-pane mirror inspector (advertisement + latest state, raw M3). Verify it headlessly through `ui-harness` against a JVM host started with `:test:rig:runJvmHost` (driver gains `driveMirror`; verified: create and join tapped in the mirror reached the JVM host).

## 9. Docs and specs of record

- [x] 9.1 Update CLAUDE.md's module list: `:app:composition`, `:test:integration` JVM-only over the protocol, `:test:world` hosting the inbound bindings, `:app:ios`'s root delegating to `snapSyncHost`.
- [x] 9.2 Update the `ios-simulator` / `local-backend` skills where the journeys' deno-on-8080 and two-simulator setup changes what they say.
- [ ] 9.3 Run `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` and `./gradlew build`, and dispatch `gh workflow run ios.yml --ref integration-over-control` to smoke-check the `SnapSyncRoot` change on device (join, upload, version-refusal screen).
