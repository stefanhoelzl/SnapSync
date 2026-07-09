# app/ios — iOS specifics

The iOS platform layer. **Wiring-only and untested** (root `CLAUDE.md` hard rule): all testable
logic — shared *or* iOS-specific — lives in `domain`/`capability` modules under test; nothing
testable is parked here. This doc covers what is specific to the iOS surface. For on-device
testing, sideloading, and App Store Connect chores see the **root `CLAUDE.md`**; for architecture
and resolved decisions see the `openspec/specs/` contracts and their `Decision record:` pointers into `openspec/changes/archive/`.

## Two processes, two frameworks

The app and the background-upload extension are **separate iOS processes**, each a separate Gradle
module exporting its own **static** framework that the Xcode project links:

```
:app:ios                     → framework "SnapSyncKit"        ← app process (UI + ledger reader)
:app:ios:photokit-extension  → framework "SnapSyncUploadKit"  ← extension process (discover→upload)
```

Two frameworks, not one, so the two binaries never both statically pull `:domain:engine` into a
single image. The app framework carries Compose/UI + the full `domain` stack; the extension
framework is lean (`:capability:upload` — the UploadCycle orchestration — over `:domain:engine` +
`:domain:gallery`, plus `:capability:upload-url` + `:capability:config`). Both are
`isStatic = true` — the Compose-iOS norm (avoids dynamic-linking issues with the bundled
Skiko/Compose native libs).

## The Gradle ↔ Xcode boundary

`iosApp/` (repo root, **not** under this module, **not** a Gradle project) is the Xcode host: app
target + `BackgroundUploadExtension` target. Each target has a run-script phase that calls
`./gradlew :app:ios[:photokit-extension]:embedAndSignAppleFrameworkForXcode` to build + embed the
Kotlin framework, and each links `-lsqlite3` in `OTHER_LDFLAGS` (SQLDelight's native driver needs
the system SQLite — without it the device app fails to link). Shared build settings live in
`iosApp/Configuration/Config.xcconfig`; export configs in `iosApp/ExportOptions*.plist`.

You cannot build or run any of this on Linux. Use the proxy:

- `./gradlew compileIosMainKotlinMetadata` — **Linux-runnable** compile of `iosMain`/`commonMain`
  (+ cinterop) for both modules; catches iOS-only Kotlin breakage without a Mac.
- The Swift shells and the Xcode project compile **only on macOS CI** (`macos-26`); there is no
  Swift toolchain locally, so they cannot be built or run on this machine. The extension shell is
  verified on device (real-s3-upload, build 70) — keep edits to it minimal and lean on CI.

## The Swift ↔ Kotlin seam (keep Swift thin)

Swift shells are pass-throughs; all logic is Kotlin. Do not add parsing or decisions in Swift.

- **App entry** (`iosApp/iosApp/iOSApp.swift`): SwiftUI `@main` scene. `.onOpenURL` forwards the raw
  `snapsync://` deeplink string to `SnapSyncRoot.shared.onOpenUrl(...)` — Kotlin decodes/validates/
  persists. `ContentView.swift` bridges `MainViewControllerKt.MainViewController()` (Compose) into
  SwiftUI.
- **Extension principal** (`iosApp/BackgroundUploadExtension/BackgroundUploadExtension.swift`):
  `@main` class conforming to the iOS 26.1 `PHBackgroundResourceUploadExtension`; its `process()`
  just calls `UploadExtensionRoot.shared.process()` and maps the `Bool` to the system result.

## Composition roots (manual DI — no expect/actual)

- **App**: `app/ios/src/iosMain/.../SnapSyncRoot.kt` — app-lifetime singleton owning a
  `SupervisorJob` scope on `Dispatchers.Main` (outlives Compose recomposition). Assembles the real
  live stack lazily: `iosLedgerBackend` → `LedgerWatcher` → `LedgerSyncStatusSource` ×
  `PhotoLibraryPermission` × `KeychainConfigStore` → `StatusContainerHost`. The app **reads** the
  ledger and, on a full photo grant, enables the extension (`setUploadJobExtensionEnabled`).
- **Extension**: `app/ios/photokit-extension/src/iosMain/.../UploadExtensionRoot.kt` — assembles the
  App-Group `LedgerWriter`, the `SyncEngine`, the upload provider, the `IosPhotoKitUploadPlatform`
  (composing the shared `IosDiscovery` from `:app:ios:photokit-discovery`), and the `UploadCycle`;
  `process()` runs one blocking discover→engine→job→drain cycle.

**Single-writer invariant — the writer's process depends on the tier** (`sync-ledger`: exactly one
record-writer; its process placement is a platform binding). On **iOS ≥26.1** the **extension** is the
only `LedgerWriter` and the app constructs only reader/watcher (never a writer). On **iOS 18–26.0**
there is no extension, so the **app** holds the single `LedgerWriter` — constructed in
`UrlSessionUploadController` (the app-driven tier). Outside that controller, `:app:ios` still
constructs no writer.

## Entitlements & Info.plist (the cross-process glue)

- **App Group `group.app.snapsync`** (both `*.entitlements`): the shared on-disk container for the
  ledger DB the extension writes and the app reads (`iosLedgerBackend`). **Must be registered in
  the Developer portal** and enabled on both App IDs, or signed builds fail to provision.
- **Keychain group `$(AppIdentifierPrefix)app.snapsync.shared`** (both `*.entitlements`): lets the
  extension read the event config (the `eventId`) the app stores (`KeychainConfigStore`, which omits
  `kSecAttrAccessGroup` and relies on this default group). Keychain groups need **no** portal step.
- **App `Info.plist`**: `CFBundleURLTypes` registers the `snapsync` scheme (Camera-scanned QR
  deeplink); `CADisableMinimumFrameDurationOnPhone = true` is **mandatory** — Compose MP ≥1.7
  hard-aborts at launch without it. Portrait-only, iPhone-only.
- **Extension `Info.plist`**: `EXExtensionPointIdentifier = com.apple.photos.background-upload` and
  `BackgroundUploadURLBase = $(BACKGROUND_UPLOAD_URL_BASE)` — the compile-time host the system
  permits uploads to (a user-configurable upload host is impossible with this API). It must be an
  HTTPS endpoint: default ATS (HTTPS-only) applies, no `NSAllowsLocalNetworking` exception.

## iOS-version deviation & the two upload tiers

App deploys **min iOS 18**. Upload runs on one of two tiers, selected at
`SnapSyncRoot.backgroundUploadSupported()` (`isOperatingSystemAtLeastVersion(26.1)`):

- **iOS ≥26.1 — PhotoKit (`ios-photokit-upload`).** The OS-driven upload extension, using the
  **deprecated 26.1** `PHBackgroundResourceUploadExtension` (the only protocol runnable on current GM
  devices). The runtime guard keeps the `setUploadJobExtensionEnabled` call from trapping on lower
  systems. A later move to the iOS 27 async `PHBackgroundResourceUploadJobExtension` is confined to the
  Swift shell + deployment target.
- **iOS 18–26.0 — app-driven `URLSession` (`ios-url-session-upload`).** No appex exists; the **main
  app process** performs uploads over a background `URLSession` + `BGProcessingTask`, via
  `IosUrlSessionUploadPlatform` / `IosBackgroundScheduler` (`:app:ios:url-session-upload`) driving the
  same `:capability:upload` `UploadCycle` through the `BackgroundUploadPump`. On this tier the **app**
  is the single `LedgerWriter` (no extension process exists).

## Gotchas

- **Device logs:** both composition roots set `Logger.setLogWriters(PublicNSLogWriter(),
  FileLogWriter())` — the writers now live in `:domain:logging` (capability `diagnostic-logging`).
  `FileLogWriter` (verbatim `Documents/debug.log`, 10 MB roll) is the reliable channel; the os_log
  `PublicNSLogWriter` is redacted `<private>` on current iOS. Each root emits a boot banner and wraps
  its entry points with `logInvocation`, so every line carries a `[<entryPoint>]` prefix. Keep new
  entry points wrapped, or their downstream lines lose the trigger prefix.
- **`-lsqlite3`:** required in each target's `OTHER_LDFLAGS` (above). A new linked target needs it.
- **In-memory SQLite on Native:** `NativeSqliteDriver` shares an in-memory DB across connections via
  shared-cache — give each backend a **unique db name** to avoid cross-test/instance leakage.
- **Compose scope ownership:** `SnapSyncRoot` deliberately uses a process-lifetime `SupervisorJob`,
  not `rememberCoroutineScope` (which dies with the view). Move ownership to Swift only if
  scene-aware lifecycle (multi-window, reset/logout) is ever needed.
