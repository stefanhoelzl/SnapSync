# Tasks

## 1. Compose the port into the app

- [x] 1.1 Add `NetworkMonitor` to `AppPorts` and `DevicePorts` (app process only, not `ExtensionPorts`); verify `PortBundleTest` and `ModuleSetTest` pass and `ExtensionPorts` is unchanged
- [x] 1.2 Build `IosNetworkMonitor` in the iOS root and `AndroidNetworkMonitor` in the Android root, and declare `ACCESS_NETWORK_STATE` in `app/android`'s manifest; verify with `./gradlew compileIosMainKotlinMetadata :app:android:assembleDebug` and `detektAppShell` (the shells stay decision-free)
- [x] 1.3 Add `network = NetworkMock()` to `MockDevice`, `NETWORK("network", …)` to `MockedSystem`, its value to `MockState`, and the mock to `JvmMocks.adapters`; verify the `MockState` round-trip test covers the new system and `:app:jvm` compiles
- [x] 1.4 Wire the network into the rig's launch-time adapters (`AdapterChoice` coherence, `LaunchMix`, iOS and Android hooks); verify `:test:launch-adapters` tests pass with `network=mock` and `network=real`

## 2. The watch: foreground-only, debounced

- [x] 2.1 Add the network watch feature in `feature/status` (`start()`/`stop()`, the asymmetric 5 s debounce of D2, reset to `ONLINE` on stop) and its read-model `NetworkStatusSource` in `feature/status/readmodel`, built over the port in `compose/`; verify feature tests in `:test:feature` over `NetworkMock`: a 2 s drop publishes nothing, a 6 s drop publishes `OFFLINE`, `ONLINE` clears at once, `OFFLINE`→`BLOCKED` after a shown warning is immediate, `stop()` resets
- [x] 2.2 Start the watch in the `Foreground` flow and stop it in the `Background` flow, beside the counts poller; verify the flow tests assert both and `architectureDiagrams` regenerates the flow transcripts (commit them)
- [x] 2.3 Document the watch in `docs/architecture.md` where the foreground-gated poller is explained; verify the doc names the read-model and the flows that start and stop it

## 3. The network's return is a trigger

- [x] 3.1 Add `TailTrigger.NETWORK` and an `onNetworkReturned` handler in `compose/` that, on a debounced non-`ONLINE`→`ONLINE` transition while foregrounded, runs the `Foreground` flow and then the tail under its own entry-point label; verify an integration test over the rig: with the network mocked offline past the grace period, switching it online makes received photos arrive and the counts update with no foreground event, and the device log carries `[onNetworkReturned]`
- [x] 3.2 Verify the transition does nothing in the background: an integration test with the app backgrounded while the network returns shows no `[onNetworkReturned]` line

## 4. Presentation and screens

- [x] 4.1 Add `SyncHealth.NoNetwork(blocked)` and `NetworkNotice` (`Offline`/`Blocked`) to `UiState`, the `network` field on `Layer.CreateEvent` and `Layer.JoiningEvent`; verify `UiState` serialization tests cover them
- [x] 4.2 Fold the read-model into `StatusContainerHost`: the rung second in the ladder (below `NeedsAccess`, above `NotStarted`, outranking `Unattested`), the create and join `network` fields, the sticky create `error` kept underneath; verify presentation tests for each `sync-status` priority scenario, "Offline with an expired verification", and the create failure message reappearing after the network returns
- [x] 4.3 Reload a pending join in `LoadFailed` on the debounced return to `ONLINE`, and only then; verify presentation tests: offline `LoadFailed` reloads on return; a `LoadFailed` while online (server unreachable) waits for Retry
- [x] 4.4 Render the notice in `:ui:screens`: create hint line + disabled Create; join notice + disabled Join, `LoadFailed` with Cancel only while offline; the joined status line, tappable only when blocked, dispatching the existing open-Settings intent; verify screen tests click the blocked line and the blocked notice's action (Settings opens) and assert the offline line has no action, and `statusActions` covers the tap
- [x] 4.5 Add the network lever to the world harness inspector and the rig's `MockLevers` (`POST /device/network?access=online|offline|blocked`, 409 where the network is real); verify a `:test:control` test drives each value and reads the `UiState` back, and the harness driver can reach all three screens' notices

## 5. Integration and docs

- [x] 5.1 Add `:test:integration` scenarios for each spec scenario reachable over the rig (create offline/blocked/return, join offline-load-then-return, joined priority and tap); verify they pass on the JVM host in `./gradlew build`
- [x] 5.2 Update `docs/testing.md` (the mocked system and lever) and the `rig-channel` skill (the new verb); verify `RunbookSkillsTest` passes
- [x] 5.3 Run `./gradlew build` and `npx --yes @fission-ai/openspec@1.13.2 validate --specs --strict`; verify both are green
- [x] 5.4 On the SE2 (rig build, real network): switch airplane mode on with the app open on the joined screen and on the create screen, and confirm the notice appears after the grace period and clears on return; record the result in the change's design (D2)
- [x] 5.5 At archive: update `sync-status`'s Purpose to name the network line among what the status line says plainly; verify the archive gates in `openspec/config.yaml` (no placeholder Purpose, no code identifiers in the touched specs)

