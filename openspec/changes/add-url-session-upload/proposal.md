## Why

SnapSync's only upload path is PhotoKit's OS-driven background-upload subsystem
(`PHBackgroundResourceUploadExtension`), which exists **only on iOS 26.1+**. The host app already
installs and runs on **iOS 18** (the app targets deploy to `18.0`; only the extension target is
pinned to `26.1`), so on an iOS 18–26.0 device the app joins events and shows status but has **no way
to upload** — the `backgroundUploadSupported()` guard silently disables everything. A real sub-26
device needs to back up its photos. This adds a second, app-driven upload path for iOS 18–26.0,
selected per OS version, so the same app backs up photos on both tiers.

## What Changes

- **New app-driven upload tier for iOS 18–26.0.** Where the OS provides no background resource
  upload, the **main app** performs it: a background `URLSession` (transfers continue across
  suspension and relaunch the app on completion) primed in the foreground, topped up by a
  `BGProcessingTask` heartbeat (network required, external power **not** required).
- **A second implementation of the existing `UploadJobPlatform` seam**, not a new seam. The
  background `URLSession` is structurally the same cross-process durable job queue as the PhotoKit
  OS-job queue, so `UploadCycle` is reused **unchanged**. The adapter returns **empty**
  `fetchRetryJobs` (no OS-granted free retry), makes `acknowledge` a real temp-file cleanup, enforces
  its **own** concurrency cap (surfaced as `LIMIT_EXCEEDED`), and reconciles in-flight work precisely
  via `URLSession.getAllTasks` instead of the blanket `clearRequested` recovery the OS path needs.
- **A new `BackgroundUploadPump` + `BackgroundScheduler` seam** in the platform-free capability: the
  in-app reimplementation of the OS scheduler (four triggers — foreground, `BGProcessingTask`,
  background-session relaunch, per-completion — single-flight-serialized, re-arming on `PROCESSING`).
  JVM/simulator-testable against a fake scheduler + fake cycle.
- **App-driven lifecycle on <26.1.** enable/disable/re-provision/leave become in-process, ordered
  operations (no `setUploadJobExtensionEnabled` toggle, no cross-process race — the pre-existing
  toggle/`clearRequested` hazards cannot occur when a single process owns the ledger).
- **Rename `ios-background-upload` → `ios-photokit-upload`** (RESTRUCTURE). Both tiers do background
  upload; the distinguishing axis is the *mechanism*, mirroring the platform classes
  (`IosPhotoKitUploadPlatform` / `IosUrlSessionUploadPlatform`). Three appex-bound requirements are
  qualified to **"on ≥26.1."**
- **Generalize the ledger's single-writer invariant** to be platform-neutral: exactly one
  **record-writer** exists at a time; *which process* holds it is a platform binding, not a ledger
  concern. On <26.1 the **app** is that writer (no extension exists).
- **BREAKING (declared floor):** minimum supported iOS lowered from 27.0 to **18.0**, documented as
  two upload tiers. No code floor changes (app targets are already 18.0); this aligns the stated
  contract with reality.
- **New Gradle modules:** `:app:ios:url-session-upload` (the app-driven adapters, own module) and
  `:app:ios:photokit-discovery` (shared `IosDiscovery` — the change-token walk + request builder +
  token archiver, reused by both adapters; forced by keeping PhotoKit out of the platform-free
  `:capability:upload`).

## Capabilities

### New Capabilities
- `ios-url-session-upload`: the iOS 18–26.0 app-driven upload path — the background-`URLSession`
  `UploadJobPlatform` implementation, the `BackgroundUploadPump` and `BackgroundScheduler`, the
  `BGProcessingTask` heartbeat + relaunch ping-pong, per-slot temp-file staging, app-driven
  lifecycle, tier selection at `backgroundUploadSupported()`, and the app-as-ledger-writer binding.

### Modified Capabilities
- `ios-photokit-upload`: **renamed from `ios-background-upload`**; three requirements ("Background
  upload extension target", "Extension owns the single ledger writer", "Extension registration is a
  disable→enable toggle") qualified to **on ≥26.1**, so they no longer read as the *only* upload host.
- `sync-ledger`: the single-record-writer invariant restated platform-neutrally; the
  extension-specific language in `clearRequested` (the "…when the extension was disabled" recovery
  narrative) removed so the neutral ledger spec stops assuming the 26.1 process model.

## Impact

- **Specs:** new `ios-url-session-upload`; rename + qualify `ios-photokit-upload`
  (was `ios-background-upload`); delta `sync-ledger`. Docs: `design.md` §1/§6 (min iOS 18, two tiers,
  <26.1 transport is simulator-testable), `CLAUDE.md` (module table +2, min-iOS line).
- **Code — new:** `:capability:upload` gains `BackgroundScheduler` + `BackgroundUploadPump`
  (jvm-tested); `:app:ios:photokit-discovery` (extracted `IosDiscovery`); `:app:ios:url-session-upload`
  (`IosUrlSessionUploadPlatform`, `IosBackgroundScheduler`).
- **Code — modified:** `:app:ios:photokit-extension` recomposed onto the shared `IosDiscovery`
  (`IosUploadJobPlatform` → `IosPhotoKitUploadPlatform`); `:app:ios` `SnapSyncRoot` tier branch at
  `backgroundUploadSupported()`; thin Swift shell wiring (BGTask registration, `URLSession` delegate,
  `handleEventsForBackgroundURLSession`). `:capability:upload` seam (`UploadJobPlatform`,
  `UploadCycle`, `DiscoveryStore`, `UploadConfig`) unchanged.
- **Verification gate (front-run):** confirm on the real sub-26 device that the 26.1 appex embedded
  in an iOS-18-installable app does **not** block install/launch; if it does, the fix is
  conditional/weak embedding of the appex. Everything else is downstream of this.
- **Testing:** the pump + scheduler are JVM/`iosSimulatorArm64`-covered; the `URLSession` adapter is
  faked in the harness (like `IosPhotoKitUploadPlatform`); background `URLSession` runs in the
  simulator, so the transport is simulator-drivable end-to-end. `BGProcessingTask` *timing* stays
  device-only.
