# Tasks

## 1. The device setting as a service

- [x] 1.1 Add `MobileDataSetting` in `:domain:services` (`services.settings`) over `Preferences` (D1): it has `transferNetwork()` (absent/`on` → `ANY`, `off` → `UNRESTRICTED_ONLY`, unavailable or malformed → `UNRESTRICTED_ONLY`, logged), `allowed: StateFlow<Boolean>`, `set(on): Boolean` (publishes only on `WriteOutcome.Ok`) and `clear()`. Verify with a mock-driven test beside `AlbumMapServiceTest` (`:adapter:generic:mock` commonTest) covering each read branch, a refused write leaving `allowed` unchanged, and `clear()` returning to `ANY`.
- [x] 1.2 Register the key in whatever `RuntimeIdentityTest` pins for preferences keys, if it pins them. Verify with `./gradlew :test:architecture:test`.

## 2. Transfers read the device setting; the membership loses the field

- [x] 2.1 Build the service in `AppServices` and in `extensionServices()` (over `ExtensionPorts.preferences`), and add it to `UploadServices` (D2). Verify with `./gradlew compileIosMainKotlinMetadata` and the JVM compile.
- [x] 2.2 Replace the four `transferNetworkOf(config…)` call sites (`UploadCore.uploadCycle`, `TransferEntries`, `SnapSyncApp`'s `DownloadJobs`, `TailComposition`'s `Heartbeat`) with `mobileData::transferNetwork`. Delete `transferNetworkOf(EventConfig?)` and `EventConfig.mobileData`/`transferNetwork`, and keep `transferNetworkOf(Boolean)` only if it is still used. Update `EventConfigTest` (drop the two mobile-data tests, and add one showing a config JSON that still carries `mobileData` decodes) and `TransferNetworkTest`. Verify with `./gradlew :domain:model:jvmTest :domain:services:jvmTest`, plus `UploadTransferServiceTest`, `DownloadJobsTest` and `HeartbeatTest` passing unchanged.
- [x] 2.3 Drop `mobileData` from `JoinChoice`, `JoinEvent`, `ReconfigureEvent`, the `reconfigure` command in `UserCommands`/`UserCommandsComposition`, and `TailRunnerTest`/`DownloadSupport` fixtures as needed. Remove "join persists the mobile-data choice" and "saves the mobile-data choice", and the `mobileData = true` arguments in `ReconfigureEventTest`. Verify with `./gradlew :test:feature:jvmTest`.
- [x] 2.4 Add a best-effort `mobileData.clear()` step to `ResetDeviceState` (D5), wired in `SnapSyncApp`. Verify with a case in `ResetDeviceStateTest` showing a reset returns the setting to on.
- [x] 2.5 Move `mobile_data` in `CollectDiagnosticDump` to the device block (`on` | `off` | `unreadable`), wired in `DiagnosticsComposition`. Verify with a `CollectDiagnosticDumpTest` case that asserts it with no event joined.
- [x] 2.6 Update `docs/architecture.md` §"A photo transfer carries the member's network rule" so the rule is read from the device setting at creation, citing this change. Verify by reading it back against D1/D2.

## 3. Presentation: the device fact, the command and the waiting line

- [x] 3.1 Add `UiState.mobileData: MobileDataState(on, notSaved)`, `StatusSources.mobileData` (wired in `ComposedApp.statusSourcesOf`) and `UserCommands.setMobileData(on): Boolean` (D3).
- [x] 3.2 Route `UiIntent.MobileData` to the command in `UiIntents`/`StatusContainerHost`: set `notSaved` on a refused write, and clear it on the next flip or when the menu is dismissed. Remove `RangeForm.mobileData`, `SettingChange.mobileData`, `onMobileData`'s form branch and `RangeResolution`'s seeding. Switch `heldForWifi` to the device fact. Update `StatusContainerHostNetworkTest` (the waiting line follows the device setting), replace `StatusContainerHostSurfacesTest`'s settings mobile-data test with a menu one (applies on flip, a refused write reverts and says so), and fix the `:528` call and the reconfigure fakes. Verify with `./gradlew :domain:presentation:jvmTest`.

## 4. UI: the menu row, and the row's removal from the card

- [x] 4.1 Add `AppMenuSwitch` and a mobile-data `AppMenuIcon` to `:ui:components` (D4).
- [x] 4.2 In `StatusScreen.AppMenu`, put the switch first with its on/off note and the not-saved line, then a divider, then the existing rows. Add `MenuActions.onMobileData`, wired in `HostStatusActions`. Add a `mobile_data_not_saved` string (English and German). Extend `AppMenuScreenTest` with: the open menu shows the switch first with its note, with and without an event, a tap asks for the change, and the not-saved line shows. Update `TestActions.testMenuActions`. Verify with `./gradlew :ui:screens:jvmTest`.
- [x] 4.3 Remove the row from `ParticipationSections` (state, actions, divider) and the "mobile data" mention in `ReconfigureScreen`'s KDoc. Update `JoinScreenTest` (drop :777/:792, and assert no mobile-data choice on the join card), `StatusScreenTest` "the settings show every choice…", and `HostStatusActionsTest:422` (no mobile-data tap). Verify with `./gradlew :ui:screens:jvmTest`, and confirm `HardCodedUiText` passes.

## 5. Control channel, harness and integration

- [x] 5.1 In `:test:rig`, add the `/user mobileData?on=` verb to `RigVocabulary`, and drop `mobileData` from `rangeChoices()`. Update the `rig-channel` skill's verb list if it lists `/user` params. Rewrite `JvmHostProtocolTest`'s mobile-data test to drive the menu verb and read `UiState.mobileData`. Verify with `./gradlew :test:control:test`.
- [x] 5.2 Wire `MenuActions.onMobileData` in `MirrorHarness` to post the new verb. Verify by compiling `:app:desktop`, and by flipping the switch through the harness driver (`ui-harness` skill) and reading the screenshot.
- [x] 5.3 Rewrite `MobileDataIntegrationTest` to set the choice through the menu verb instead of `createAndJoin("mobileData" to "false")`/`reconfigure`. Add `the_choice_carries_to_the_next_event` (off, leave, join another → its photos wait for Wi-Fi) and `chosen_before_joining` (off with no event, then join on mobile data). Verify with `./gradlew :test:integration:test`.

## 6. Whole-tree checks

- [x] 6.1 Run `./gradlew build` and `./gradlew architectureDiagrams`, then commit any regenerated `architecture/` (the `UiIntent`/`UiState` rows move). Verify both are green and `git status` is clean after the commit.
- [ ] 6.2 Verify on the iOS proxy and both platforms' journeys: `./gradlew compileIosMainKotlinMetadata`, and the `journeys (ios|android)` CI gates green on the PR.
- [x] 6.3 At archive, rewrite `openspec/specs/mobile-data/spec.md`'s `## Purpose` to the device-wide choice (one paragraph, the decision-record cite set to this change), and run the two archive gates in `openspec/config.yaml` over the four touched specs. Verify with `npx --yes @fission-ai/openspec@1.13.2 validate --specs --strict` green and the identifier grep printing nothing.
