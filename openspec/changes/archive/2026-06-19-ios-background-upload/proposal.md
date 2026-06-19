## Why

The iOS app is, by design, a projection of ledger state written by a background extension — but no such producer exists yet, so the ledger is always empty and the live status UI can only ever show `Loading → NeverSynced`. This change builds the first real producer: a `PHBackgroundResourceUploadExtension` that discovers photos and feeds the engine. It is deliberately scoped to a **vertical skeleton** — it emits **dummy** upload destinations and performs **no real upload** — so we can prove the extension runs on a real device, the App-Group ledger is written cross-process, and the UI lights up, before taking on S3, retries, and completions.

## What Changes

- **New iOS app-extension target** conforming to the iOS 26.1 `PHBackgroundResourceUploadExtension` (deprecated-but-runnable) protocol, hosted in a new lean Kotlin Multiplatform module. The Swift `@main` shell is a thin ExtensionKit principal class; all logic lives in Kotlin/Native (verified: K/N 2.4.0 binds the full job/change-request/enable API surface; only the `AppExtension` protocol itself is Swift-only).
- **Discovery inside the extension** (`process()`): enumerate the library via `fetchPersistentChanges(since:)` / `PHPersistentChangeToken` (full `PHAsset.fetchAssets` on first run), resolve `PHCloudIdentifier`s (defer `identifierNotFound`), fan each asset out to its `PHAssetResource`s, and drive the shared `SyncEngine`.
- **A `DummyUploadRequestProvider`** that mints/logs dummy destinations (e.g. `https://dummy.invalid/<key>`). This is the **only functional delta** from the real design — it is the swap-in twin of `S3UploadRequestProvider`.
- **Honest, REQUESTED-only ledger writes**: on a `Work` decision, create a system upload job with the dummy destination and `recordRequested`. `COMPLETED` is never recorded (nothing really uploads). All other system jobs are **acknowledged to drain** (no real retry/completion adjudication).
- **App-Group ledger sharing**: the ledger database moves into the `group.app.snapsync` container; the **extension owns the `LedgerWriter`**, the **app holds `LedgerReader`/`LedgerWatcher`**. Cross-process freshness via a **Darwin notification** posted on `put` and merged into the backend's `changes` flow.
- **App-side enablement**: on full (`.readWrite`) photo-access grant, the app calls `setUploadJobExtensionEnabled(true)`.
- **Build/signing**: new extension bundle id + App-Group capability on both targets; deployment target bumped to **iOS 26.1**; the simulator merge gate **compiles** the extension (it cannot run there); the device archive signs+bundles the extension.
- **Non-goals (explicit):** real HTTP upload / S3; retry & backoff adjudication; completion (`COMPLETED`) recording; iCloud-offloaded resource handling; `BackgroundUploadURLBase` pointing at a real endpoint; limited-access support; any asset-selection UI. Migration to the iOS 27 `PHBackgroundResourceUploadJobExtension` async API is deferred until iOS 27 is stable (the Kotlin-heavy split keeps that migration confined to the Swift shell + deployment target).

## Capabilities

### New Capabilities
- `ios-background-upload`: the PhotoKit background-upload extension — its target/module shape, the app-side enablement, the in-`process()` discovery → engine → dummy-destination job-creation → REQUESTED flow, the drain-all disposition, the deferred-cloud-id handling, and the iOS 26.1 deviation.

### Modified Capabilities
- `sync-ledger`: the `LedgerBackend.changes` flow on iOS is fed by a cross-process **Darwin** observer, and `put` posts a Darwin notification — so a ledger written by the extension process dings the app process.
- `ios-app-shell`: `iosLedgerBackend()` resolves the **App-Group container** path (no longer the app sandbox); the app composition root holds only a `LedgerReader`/`LedgerWatcher` (never a writer); and the app enables the extension (`setUploadJobExtensionEnabled(true)`) when photo access is granted.

## Impact

- **New module**: `:app:ios:photokit-extension` (KMP, iOS targets only) depending solely on `:domain:engine` — no Compose/UI. Produces a second small static framework.
- **New Xcode target**: an ExtensionKit "Generic Extension" (`app.snapsync.BackgroundUpload`) embedded in the app, with `NSExtensionPointIdentifier = com.apple.photos.background-upload`, a principal class, and `BackgroundUploadURLBase`.
- **`:domain:engine`** (`iosMain`): `iosLedgerBackend()` gains the App-Group path + Darwin wiring; WAL enabled for cross-process read/write.
- **`:app:ios`**: app-side `setUploadJobExtensionEnabled` call on grant; ledger reader over the App-Group DB.
- **Apple Developer portal / signing**: register `group.app.snapsync` App Group; new extension App ID; enable App Groups on both App IDs; cloud-managed signing must provision both bundle ids. No special Apple-approved entitlement is required (App Groups is the only capability).
- **CI**: merge-gate simulator build compiles the new target + framework; main-only device archive signs the extension. On-device manual verification is the runtime gate (the extension is unsupported in the simulator).
- **Risk**: the central unknown is whether `process()` is invoked when the job queue starts empty (the bootstrap question); the design carries an app-side ignition fallback.
