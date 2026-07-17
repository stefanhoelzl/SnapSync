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

Two frameworks, not one, for two real reasons: the **extension-safety line** (app-only API —
UIKit/BGTask/URLSession adapters — must be structurally un-linkable from the appex, and
Kotlin/Native links whole modules) and the **appex footprint** (Compose/Skiko has no business in a
memory-capped extension). Both images DO embed the shared domain code, each privately — that is
fine; no Kotlin type ever crosses the process boundary. The app framework carries Compose/UI + the
full `domain` stack; the extension framework is lean (`:domain`'s feature/upload UploadCycle
orchestration over the extension-safe adapters `:adapter:ios:ext-safe` + `:adapter:generic`,
plus the receive seam left in `:capability:upload`). Both are
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
  event-link URL string (fragment included) to `SnapSyncRoot.shared.onOpenUrl(...)` — Kotlin decodes/validates/
  persists. `ContentView.swift` bridges `MainViewControllerKt.MainViewController()` (Compose) into
  SwiftUI.
- **Extension principal** (`iosApp/BackgroundUploadExtension/BackgroundUploadExtension.swift`):
  `@main` class conforming to the iOS 26.1 `PHBackgroundResourceUploadExtension`; its `process()`
  just calls `UploadExtensionRoot.shared.process()` and maps the `Bool` to the system result.

## Composition roots (manual DI — no expect/actual)

- **App**: `app/ios/src/iosMain/.../SnapSyncRoot.kt` — app-lifetime singleton owning a
  `SupervisorJob` scope on `Dispatchers.Main` (outlives Compose recomposition). Assembles the real
  live stack lazily: `iosLedgerStore` → `LedgerWatcher` → `LedgerSyncStatusSource` ×
  `PhotoLibraryPermission` × `KeychainConfigStore` → `StatusContainerHost`.
- **Extension**: `app/ios/photokit-extension/src/iosMain/.../UploadExtensionRoot.kt` — assembles the
  App-Group `LedgerWriter`, the `SyncEngine`, the upload provider, the `IosPhotoKitUploadPlatform`
  (composing the shared `IosDiscovery` from `:adapter:ios:ext-safe`), and the `UploadCycle`;
  `process()` runs one blocking discover→engine→job→drain cycle.

**Neither is the direction gate** (capability `upload-lifecycle`). Whether a membership uploads **at all** is
decided inside `UploadCycle`, from a required `Contribution` (`:domain:gallery`) carrying the membership's
direction *and* its cutoff: `None` → the cycle returns `CycleResult.SKIPPED` before any walk, job, manifest, or
notify, and the pump then schedules no `BGProcessingTask`. The roots only pass **facts** —
`Contribution.of(direction.includesUpload, minPhotoDate)` — and never the branch.
That gate sits at the **choke point every trigger funnels through**, not at the arm's invoker, because an
invoker-gate is only as good as its enumeration of invokers. It used to be one: a download-only membership was
handled by simply not enabling the producer, on the reasoning that "the OS never invokes the extension" — true
on ≥26.1, false here, where the *app* invokes its own cycle. `onForeground` walked straight past it and
uploaded the camera roll of a member who had been promised "you won't share yours".

**The upload lifecycle is NOT decided here** either (same capability). `SnapSyncRoot` selects
**exactly one** `UploadProducer` for the process — `PhotoKitUploadProducer` (≥26.1) or
`UrlSessionUploadController` (18–26.0) — and forwards membership transitions to the tested, tier-neutral
`UploadArm` in `:capability:upload`. The seam has **two** verbs, `start()` and `stop()`, and **no
destructive one**: no lifecycle transition (provision, switch, grant, direction change, leave) may clear
the **ledger**. That is device-global dedup — it stays valid across events, and only a triggered
reconciliation's `resetTo` re-baselines it. The **discovery cursor** is not dedup state and is not covered:
a tier's `stop()` may clear it to repair damage its own mechanism causes, and PhotoKit's does — the OS's
extension-disable wipes every in-flight job, and `clearRequested()` alone would leave those photos behind a
settled cursor that never re-surfaces them. That costs a re-enumeration, not a re-upload, because the ledger
it does not touch still knows what is stored (`upload-lifecycle`, `ios-photokit-upload`).

This structure is load-bearing, not tidiness. The lifecycle *used* to live here as a pile of
`if (useAppDrivenUpload)` branches, and because this module is wiring-only and untested, nothing caught
that `provisionEvent` → `enableBackgroundUpload()` → `disableExtension()` resolved, on the app-driven tier,
to a **full leave** (cancel transfers, cancel the heartbeat, wipe ledger + cursor) followed by a no-op
enable. Joining an event tore the upload arm down and started nothing. Selecting one producer also makes
the two tiers mutually exclusive *structurally* — `setUploadJobExtensionEnabled` lives inside the PhotoKit
producer, which is simply not constructed on the other tier, so not even the dev force flag can enable both.

**Single-writer invariant — the writer's process depends on the tier** (`sync-ledger`: exactly one
record-writer; its process placement is a platform binding). On **iOS ≥26.1** the **extension** is the
only `LedgerWriter` and the app constructs only reader/watcher (never a writer). On **iOS 18–26.0**
there is no extension, so the **app** holds the single `LedgerWriter` — constructed in
`UrlSessionUploadController` (the app-driven tier). Outside that controller, `:app:ios` still
constructs no writer.

## Entitlements & Info.plist (the cross-process glue)

- **App Group `group.app.snapsync`** (both `*.entitlements`): the shared on-disk container for the
  ledger DB the extension writes and the app reads (`iosLedgerStore`). **Must be registered in
  the Developer portal** and enabled on both App IDs, or signed builds fail to provision.
- **Keychain group `$(AppIdentifierPrefix)app.snapsync.shared`** (both `*.entitlements`): lets the
  extension read the event config (the `eventId`) the app stores (`KeychainConfigStore`, which omits
  `kSecAttrAccessGroup` and relies on this default group). Keychain groups need **no** portal step.
- **Associated domain `applinks:snapsync.stho.net`** (app entitlements only, via
  `$(ASSOCIATED_DOMAIN)`): claims the event link's Universal Link (capability `event-link`), which is
  how a Camera-scanned QR opens the app. Like App Groups (and unlike keychain groups) **it must be
  enabled on the app.snapsync App ID in the portal**, or signed builds fail to provision — and
  enabling it *invalidates existing profiles*, so the ssh-mac loop's baked secret needs refreshing
  (root `CLAUDE.md`). The **extension declares none**: it never handles URLs.
- **App `Info.plist`**: registers **no** `CFBundleURLTypes` — the `snapsync` scheme is retired, and a
  scheme re-added here would route links the one authoritative codec no longer accepts.
  `CADisableMinimumFrameDurationOnPhone = true` is **mandatory** — Compose MP ≥1.7
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
  devices). `setUploadJobExtensionEnabled` is confined to `PhotoKitUploadProducer`, which is only
  constructed when this tier is selected — so it can never trap on a lower system. A later move to the
  iOS 27 async `PHBackgroundResourceUploadJobExtension` is confined to the Swift shell + deployment
  target — and, on the Kotlin side, to a third `UploadProducer`.
- **iOS 18–26.0 — app-driven `URLSession` (`ios-url-session-upload`).** No appex exists; the **main
  app process** performs uploads over a background `URLSession` + `BGProcessingTask`, via
  `IosUrlSessionUploadPlatform` / `IosBackgroundScheduler` (`:adapter:ios:app-only`) driving the
  same `:capability:upload` `UploadCycle` through the `BackgroundUploadPump`. On this tier the **app**
  is the single `LedgerWriter` (no extension process exists).

**Forcing the app-driven tier on a device** (`SNAPSYNC_FORCE_URLSESSION_UPLOAD=1` as a launch env var, as
with `SNAPSYNC_EVENT_LINK`) is the **only way to exercise the 18–26.0 tier on the agent-driveable SE2**,
which runs iOS 26.5 and would otherwise take the PhotoKit path. It selects the **tier and nothing else**:
the transport stays a background `URLSession` (simulator-ness is read from `SIMULATOR_DEVICE_NAME`, not
inferred from this flag), and the PhotoKit extension is never registered. It previously did all three
wrong — foreground transport, *and* it still enabled the extension, giving two `LedgerWriter`s over one
App-Group ledger — which made the SE2 an unfaithful proxy that masked bugs rather than exposing them.

**Deregister the extension first** (≥26.1 devices only). The OS's upload-job registration record lives in
the **system**, not the app, and survives app relaunch/reinstall. So once a device has run the PhotoKit
tier, the OS keeps invoking the extension even under the force flag — the flag stops the app from
*registering* it, but nothing *de*registers it — and the extension will happily upload behind the
app-driven tier's back (two `LedgerWriter`s again, and it silently does the work you think you are
testing). Turn it off headlessly with a **download-only** join on the PhotoKit tier (no force flag), which
drives `arm.onProvision → photokit.stop → setUploadJobExtensionEnabled(false)`:

```
d=$(python3 -c "import json,base64;print(base64.urlsafe_b64encode(json.dumps(
  {'eventId':'<uuid>','autoJoin':True,'minPhotoDate':'2001-01-01T00:00:00Z','direction':'download'}
).encode()).decode().rstrip('='))")
$P developer dvt launch app.snapsync --env SNAPSYNC_EVENT_LINK="https://snapsync.stho.net/join#v=3&d=$d" --userspace
```

Then relaunch with the force flag **and a fresh deeplink for whatever you are actually testing** — the
download-only config above persists otherwise, and the app-driven tier will then correctly decline every
cycle (`cycle skipped — this membership contributes nothing`), which looks exactly like a broken test rig.
That decline is new: before the direction gate landed, this same sequence — deregister via a download-only
join, then relaunch forced — uploaded the device's whole post-cutoff library, because the app-driven tier
honoured no direction at all. If you are chasing a historical report from that era, that is the explanation.

Verify with `grep -c 'photokit\.'` on the app log (expect 0) and by checking the **extension's**
`debug.log` stops gaining `cycle finished` lines. Irrelevant on a real 18–26.0 device, where no appex can
exist at all.

## Gotchas

- **Device logs:** both composition roots set `Logger.setLogWriters(PublicNSLogWriter(),
  FileLogWriter())` — the writers live in `:adapter:ios:ext-safe` (capability `diagnostic-logging`).
  `FileLogWriter` (verbatim `Documents/debug.log`, 10 MB roll) is the reliable channel; the os_log
  `PublicNSLogWriter` is redacted `<private>` on current iOS. Each root emits a boot banner and wraps
  its entry points with `Logger.invocation`, so every line carries a `[<entryPoint>]` prefix. Keep new
  entry points wrapped, or their downstream lines lose the trigger prefix.
- **`-lsqlite3`:** required in each target's `OTHER_LDFLAGS` (above). A new linked target needs it.
- **In-memory SQLite on Native:** `NativeSqliteDriver` shares an in-memory DB across connections via
  shared-cache — give each backend a **unique db name** to avoid cross-test/instance leakage.
- **Compose scope ownership:** `SnapSyncRoot` deliberately uses a process-lifetime `SupervisorJob`,
  not `rememberCoroutineScope` (which dies with the view). Move ownership to Swift only if
  scene-aware lifecycle (multi-window, reset/logout) is ever needed.
