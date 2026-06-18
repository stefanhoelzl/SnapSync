## 1. Status seam — `SyncStatus` + non-suspend source

- [x] 1.1 Add sealed `SyncStatus { data object Loading; data class Ready(val status: SyncProgress) }` in `:domain:status` (package `app.snapsync.status`)
- [x] 1.2 Change `SyncStatusSource.status` from `StateFlow<SyncProgress>` to `StateFlow<SyncStatus>`; update its doc to state the current value is always real but may be `Loading`
- [x] 1.3 Rewrite `LedgerSyncStatusSource` as a **non-suspend** factory: seed `MutableStateFlow(SyncStatus.Loading)`, launch the `combine(watcher.aggregates, permission)` collector on `scope` emitting `Ready(mint(...))`; add `.flowOn(Dispatchers.Default)` on the aggregates stream so SQL leaves the main thread
- [x] 1.4 Update `LedgerSyncStatusSourceTest`: assert synchronous initial `Loading`, then `Ready` reflecting the ledger, plus the ledger-change / permission-flip / v1-constants scenarios on `Ready`

## 2. Presentation — `UiState.Loading`

- [x] 2.1 Add `data object Loading : UiState`
- [x] 2.2 Update `StatusContainerHost` to consume `StateFlow<SyncStatus>`: reduce `SyncStatus.Loading` → `UiState.Loading` under `GRANTED` only (permission-first precedence unchanged); fix the synchronous initial seed (`status.value` is now a `SyncStatus`)
- [x] 2.3 Update `StatusContainerHostTest`: Loading-under-GRANTED → `UiState.Loading`; gate still wins for non-GRANTED with a Loading snapshot; `Ready` reductions unchanged

## 3. Design system + screen rendering

- [x] 3.1 Add `data object Loading : StatusIndicator`, rendered as an **indeterminate** `CircularProgressIndicator()` in `StatusHero`'s `IndicatorIcon`
- [x] 3.2 Render `UiState.Loading` in `StatusScreen` as `StatusHero(StatusIndicator.Loading, "Loading …")` — no detail, no button
- [x] 3.3 Add the `Loading` case to `StatusScreenTest` (asserts "Loading …" present and `hasAnyProgressIndication()` present)

## 4. Desktop seam catch-up (keep desktop green, no behavior change)

- [x] 4.1 Update the harness fake sync source in `:app:desktop` (`PanelController`) to expose `StateFlow<SyncStatus>`, emitting `Ready(...)` immediately; confirm `Main.kt` wiring still compiles
- [x] 4.2 Add a `Loading` preset to the harness (`PanelController.showLoading()` forces Granted + `SyncStatus.Loading`; "Loading" button in `ControlPanel`) so the new state is explorable on desktop

## 5. iOS ledger backend (native, on-disk)

- [x] 5.1 Add `sqldelight-native-driver` to `gradle/libs.versions.toml` and wire it into `:domain:engine` `iosMain`
- [x] 5.2 Add `iosLedgerBackend()` in `:domain:engine` `iosMain`: `NativeSqliteDriver(LedgerDatabase.Schema, "ledger.db") → LedgerDatabase(driver) → SqlDelightLedgerBackend`, on-disk in the app sandbox (single site naming the path)
- [x] 5.3 Add `:domain:engine` `iosTest` running the existing `LedgerBackendContract` against a `NativeSqliteDriver` (in-memory, for isolation)

## 6. iOS PhotoKit permission adapter

- [x] 6.1 Add `:domain:permission` `iosMain` source set and `PhotoLibraryPermission` implementing both `PermissionStatusSource` and `PermissionRequester`: synchronous seed from `authorizationStatus(for: .readWrite)`, mapping `.authorized→GRANTED` / `.notDetermined→NOT_DETERMINED` / `.limited/.denied/.restricted→DENIED`
- [x] 6.2 Implement `request()` (`requestAuthorization(for: .readWrite)` + callback update) and `openSettings()` (`UIApplication.openSettingsURLString`)
- [x] 6.3 Register a `UIApplication.didBecomeActiveNotification` observer (app-lifetime) that re-reads `authorizationStatus` and emits the mapped status (the Settings round-trip refresh ding)

## 7. iOS live wiring

- [x] 7.1 Add `:app:ios` deps on `:domain:engine`, `:domain:status`, `:domain:permission`, `:domain:presentation`
- [x] 7.2 Add `SnapSyncRoot` (`iosMain`) singleton owning a `CoroutineScope(SupervisorJob() + Dispatchers.Main)`, assembling `iosLedgerBackend() → LedgerWatcher → LedgerSyncStatusSource × PhotoLibraryPermission → StatusContainerHost`
- [x] 7.3 Rewrite `MainViewController` to render `root.host.container.stateFlow` and route `onRequestPermission`/`onOpenSettings` to the host; leave the Swift entry point unchanged

## 8. Verify

- [x] 8.1 `./gradlew build` green on JVM (desktop + all `commonTest`/`jvmTest`)
- [ ] 8.2 `./gradlew iosSimulatorArm64Test` green (shared `commonTest` on Native + the new engine `iosTest`)
- [ ] 8.3 iOS device build green (`xcodebuild -sdk iphoneos … CODE_SIGNING_ALLOWED=NO build`)
- [ ] 8.4 Manual simulator walk-through: launch → `PermissionAsk`; Allow → dialog → grant → brief `Loading …` → `NeverSynced`; background → revoke in Settings → foreground → `PermissionDenied`
