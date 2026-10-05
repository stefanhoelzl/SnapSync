# Tasks

## 1. Model vocabulary

- [x] 1.1 Add `Fact<T>` (`Known` / `Unsupported` / `Failed(reason)`), `DeviceConditionsReading`, `AppFacts` and `ReportContext` to `:domain:model`, plus the new state-key constants in `DiagnosticDump.kt` (design D2, D5–D7); verify with `./gradlew :domain:model:jvmTest compileIosMainKotlinMetadata`
- [x] 1.2 Update the whole-event table in `DiagnosticDump.kt`'s KDoc for the larger state section (D8); verify that the numbers in the row still sum to ≤ 4,000 B

## 2. The DeviceConditions port, its mock and its contract

- [x] 2.1 Add `ports/DeviceConditions` (one `suspend fun read()`), and the `services.device.DeviceConditionsReadings` pass-through service (D2); verify that `ModuleSetTest`, `MixedPortImplTest` and the zone gates in `:test:architecture` pass
- [x] 2.2 Add `DeviceConditionsMock` to `:adapter:generic:mock` (durable state, port face, operator face that sets each fact, including `Unsupported`, `Failed` and a hanging read), and a `DevicePorts`/`JvmMocks` slot for it; verify with `:adapter:generic:mock:jvmTest`
- [x] 2.3 Add `DeviceConditionsContract` to `:test:contracts` (clauses: every field answers `Known` or `Unsupported`, never throws; the platform's unsupported set is exactly the declared one — the "battery monitoring left as found" clause was dropped: it is unobservable through the port, so it is stated on `IosDeviceConditions` instead), bound to the mock; verify that `ContractCoverageTest` lists the real hosts as owed until 3.x/4.x land

## 3. iOS adapter

- [x] 3.1 Implement `IosDeviceConditions` in `:adapter:ios:app-only`: `NSProcessInfo` Low Power + thermal; `UIDevice` battery with monitoring enabled and then restored in one main-thread hop; `UIApplication.backgroundRefreshStatus` on main; the standby bucket and optimisation exemption are `Unsupported` (D2); verify with `./gradlew compileIosMainKotlinMetadata`
- [ ] 3.2 Bind the contract live on `IOS_SIM_APP` in the rig source set (`SimulatorAppContracts`), and wire the adapter into `:app:ios`'s `DevicePorts`; verify that `scripts/sim-contracts` passes the clauses on the simulator (journeys (ios) job) and that `SwiftShellGuardTest`/`KotlinShellGuardTest` stay green

## 4. Android adapter

- [x] 4.1 Implement `AndroidDeviceConditions` in `:adapter:android`: `PowerManager` (`isPowerSaveMode`, `isIgnoringBatteryOptimizations`, `currentThermalStatus`), `UsageStatsManager.appStandbyBucket`, the sticky `ACTION_BATTERY_CHANGED`; background refresh is `Unsupported` (D2); verify that `./gradlew :adapter:android:compileDebugKotlinAndroid` passes, and that the merged manifest gains no permission
- [x] 4.2 Bind the contract as an `androidDeviceTest` (`ANDROID_EMU`), and wire the adapter into `:app:android`'s `platformAdapters()`; verify with `./gradlew androidPlatformTest`

## 5. Dump assembly, wiring and presentation

- [x] 5.1 Add the non-minting `PersistedDeviceIdentity.current()` (D6), with a test proving it never writes the SecureStore when nothing is stored; verify with `:adapter:generic:mock:jvmTest`
- [x] 5.2 Extend `CollectDiagnosticDump`: take `NetworkReadings`, `DeviceConditionsReadings`, `appFacts` and the `ReportContext`; read the device facts concurrently, each under a 2 s timeout; render `Known` / omit `Unsupported` / `failed (<≤80-char reason>)`; rewrite the KDoc to state the content bound in place of the dropped rule (D1, D3, D4, D7). In `CollectDiagnosticDumpTest`, cover each rendering, a hanging device read, a fresh `offline` network read, selection photos counted as distinct assets, and the worst-case state section ≤ 4,000 B (D8); verify with `./gradlew :test:feature:jvmTest`
- [x] 5.3 Wire it in `compose/` (`collectDiagnosticDump`, `appFacts` over identity, clock zone, process footprint, the selection snapshot under LIMITED) and register `CollectDiagnosticDump.appFacts` in `CompositionSeamTest`; change `UserCommands.sendDiagnostics` to take `ReportContext` and update its implementers and test doubles; verify with `./gradlew :test:architecture:test :domain:compose:jvmTest`
- [x] 5.4 In `StatusContainerHost.onIntent`, build `ReportContext.shown` from the current `UiState`'s `SyncCounts` (D5), with a presentation test for `3/10`, `off` and not-joined (no keys); verify with `./gradlew :domain:presentation:jvmTest`
- [x] 5.5 Extend `DiagnosticDumpIntegrationTest` (`:test:integration`, through the rig's JVM host): the device facts set on the mocks appear in the dump, an unsupported fact is absent, a failed one reads `failed (…)`, and the shown counts match the rendered `UiState`; verify with `./gradlew :test:integration:test`

## 6. Sheet copy and disclosures

- [x] 6.1 Change both sheet bodies in `StatusScreen.kt` to name the device's state ("…recent activity log, sync state and device state…"), and update any UI test that pins the copy; verify with `./gradlew :ui:screens:jvmTest` and a harness look at the sheet (`ui-harness`)
- [x] 6.2 Update the Privacy Policy's "Bug reports you send" entry in `site/src/pages/index.astro` to name the device's state (network, power saving, background allowance, battery, temperature, time zone, device identifier) and the screen's counts; re-check `metadata/play/declarations.md` against Play's *App info and performance → Diagnostics* type and record the outcome there; verify with the `site-build` gate (`cd site && npm run build`)

## 7. Integration

- [x] 7.1 Run `./gradlew build`, regenerate `architecture/` (`./gradlew architectureDiagrams`) and commit it, and run `npx --yes @fission-ai/openspec@1.13.2 validate device-state-in-bug-reports --strict`; verify that all three pass
- [x] 7.2 On a device or the simulator rig build, send one report with no DSN (it is saved as `diagnostic-report.json`), pull it, and check every new key against the spec's bound; verify by reading the pulled file
