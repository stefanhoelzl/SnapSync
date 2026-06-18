## Why

The iOS app currently renders a single **static** `UiState` — no live data source, no real platform code. Meanwhile the full live stack (`LedgerSyncStatusSource`, `StatusContainerHost`) exists in shared code but has never been assembled at runtime anywhere (desktop drives it from harness fakes). This change makes iOS the first place the **real** stack is assembled, delivering the first genuine iOS platform implementation — a **PhotoKit permission adapter** — driving the real container over a real, on-disk (initially empty) ledger. It is the on-device analog of the long-planned status↔screen bridge.

Assembling a real backend forces a contract honesty we have been able to dodge with synchronous fakes: a SQLite-backed source **cannot** produce a real value synchronously at construction (the first read of persisted state is inherently async). The current seam papers over this with a `suspend` factory; this change replaces that with an explicit, first-class **`Loading`** state — the honest answer to "I am reading persisted state and don't know the result yet." This matters most precisely on iOS, where the app is a *projection of ledger state written by a background extension while the app process was dead*: a cold launch over a populated ledger must never flash `NeverSynced → Complete`.

## What Changes

- **BREAKING** — The status seam stops promising a synchronous-real value. `SyncStatusSource.status` becomes `StateFlow<SyncStatus>` where `SyncStatus` is a sealed type `{ Loading | Ready(SyncProgress) }`. `Loading` is the seeded initial value; the source emits `Ready(...)` once the ledger is read.
- **BREAKING** — `LedgerSyncStatusSource` is **no longer a `suspend` factory**. It is constructed synchronously, seeds `Loading`, and fills `Ready` via its collector. (The suspend existed only to honor the old always-real contract, which `Loading` now replaces honestly.)
- Presentation gains a `UiState.Loading`, reduced from `SyncStatus.Loading` and reachable only under `GRANTED` (permission-first precedence still short-circuits to the gate otherwise).
- The design system gains an indeterminate `StatusIndicator.Loading`; the status screen renders `UiState.Loading` as a hero with copy **"Loading …"**, no detail and no button.
- New iOS platform code: a **PhotoKit permission adapter** (`:domain:permission` `iosMain`) implementing both permission ports — synchronous initial read, request/openSettings commands, and a **foreground-refresh ding** (`UIApplication.didBecomeActiveNotification`) so a permission change made in system Settings is reflected on return to foreground. Maps `.authorized → GRANTED`, `.notDetermined → NOT_DETERMINED`, `.limited/.denied/.restricted → DENIED` (the existing contract).
- New iOS ledger backend: **`iosLedgerBackend()`** (`:domain:engine` `iosMain`) over a `NativeSqliteDriver`, persisting **on-disk in the app sandbox** (App-Group container deferred to the background-extension slice; the factory is the single migration site).
- The iOS app shell goes **live**: a `SnapSyncRoot` composition-root singleton (`:app:ios` `iosMain`) owning an app-lifetime `CoroutineScope`, assembling `backend → watcher → LedgerSyncStatusSource × permission → StatusContainerHost`; `MainViewController` renders `host.container.stateFlow` instead of a static `UiState`. The Swift entry point is unchanged.
- New dependency: `sqldelight-native-driver` in the version catalog.
- Tests: the `Loading` path is covered in the existing `commonTest` suites (which the `ios-test` CI job now also runs on Kotlin/Native) and the `StatusScreen` UI test; a new `:domain:engine` `iosTest` runs `LedgerBackendContract` against the native driver. The PhotoKit adapter's request/foreground behavior is verified by a manual simulator walk-through (the system permission dialog cannot be driven by a unit test).

## Capabilities

### New Capabilities
<!-- None — all changes modify existing capabilities. -->

### Modified Capabilities
- `sync-status`: the `SyncStatusSource` seam changes from `StateFlow<SyncProgress>` to `StateFlow<SyncStatus>` (sealed `Loading | Ready(SyncProgress)`); `LedgerSyncStatusSource` is no longer a suspend factory and seeds `Loading` rather than reading current truth before construction.
- `sync-status-screen`: a new `UiState.Loading` state and its rendering ("Loading …", indeterminate indicator); the existing "no cold-start guess" requirement is reconciled — `Loading` is a legitimate *source-derived* first state, distinct from a placeholder not derived from source values.
- `permission-gate`: a new requirement for the **iOS platform adapter** — a single object implementing both ports against PhotoKit, keeping its `StateFlow` current across the system-Settings round-trip via an app-foreground refresh.
- `ios-app-shell`: the shell renders **live** `UiState` from an assembled real stack (composition root, on-disk native ledger, real permission adapter) instead of a single static `UiState`.

## Impact

- **Shared domain (affects desktop too):** `:domain:status` (`SyncStatus`, non-suspend `LedgerSyncStatusSource`), `:domain:presentation` (`UiState.Loading`, container Loading arm), `:domain:ui` + `:domain:ui:components` (`StatusIndicator.Loading`, screen rendering). Desktop behavior is unchanged at runtime — its fake source emits a `Ready` value immediately, so it never displays `Loading`.
- **iOS-only:** new `iosMain` source sets in `:domain:permission` (PhotoKit adapter) and `:domain:engine` (`iosLedgerBackend()`); new composition root + live wiring in `:app:ios`; new module deps from `:app:ios` onto `:domain:engine`, `:domain:status`, `:domain:permission`, `:domain:presentation`.
- **Build:** `sqldelight-native-driver` added to `gradle/libs.versions.toml`.
- **Tests:** updated `StatusContainerHostTest`, `LedgerSyncStatusSourceTest`, `StatusScreenTest`; new `:domain:engine` `iosTest`. No CI config change — the existing `ios-test` job already runs the `iosSimulatorArm64Test` aggregate.
- **Out of scope:** any ledger producer on iOS (no engine/gallery/uploader — the ledger stays empty, so live status is the permission flow plus `Loading → NeverSynced`); code signing / TestFlight (`ios-testflight-delivery`); the App-Group ledger path (background-extension slice).
- **Manual verification:** simulator walk-through — launch → `PermissionAsk`; Allow → PhotoKit dialog → grant → brief `Loading …` → `NeverSynced`; background → revoke in Settings → foreground → `PermissionDenied`.
