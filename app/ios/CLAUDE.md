# app/ios — iOS specifics

The iOS platform layer. **Wiring-only and untested** (root `CLAUDE.md` hard rule): all testable
logic — shared *or* iOS-specific — lives in `domain`/`capability` modules under test; nothing
testable is parked here. This doc covers what is specific to the iOS surface — the **structure**, not
the operator procedure. For on-device testing load the **`ios-device`** skill, for builds and signing
**`ssh-mac-build`**, for portal chores **`asc-portal`** (root `CLAUDE.md` → *Runbooks*); for
architecture and resolved decisions see the `openspec/specs/` contracts and their `Decision record:`
pointers into `openspec/changes/archive/`.

## Two processes, two frameworks

The app and the background-upload extension are **separate iOS processes**, each a separate Gradle
module exporting its own **static** framework that the Xcode project links:

```
:app:ios            → framework "SnapSyncKit"        ← app process (UI + ledger reader)
:app:ios:extension  → framework "SnapSyncUploadKit"  ← extension process (discover→upload)
:app:ios:forge      → framework "SnapSyncForgeKit"   ← marketing-screenshot binary, build-gated
```

The third is **built only under `-Psnapsync.forge=true`** and links neither `:app:ios` nor any adapter: it
renders the real `StatusScreen` over forged sources and has no `SnapSyncRoot`, no live graph, no backend
client. Forge used to be a *mode* of the app — a `CompositionMode.Forge` case and a `ForgeShell`
implementing ~15 `Shell` members whose only job was to keep every entry point inert — and all of that
shipped. Now the inertness is a property of which binary is running rather than something a delegate has to
keep performing correctly.

Two frameworks, not one, for two real reasons: the **extension-safety line** (app-only API —
UIKit/BGTask/URLSession adapters — must be structurally un-linkable from the appex, and
Kotlin/Native links whole modules) and the **appex footprint** (Compose/Skiko has no business in a
memory-capped extension). Both images DO embed the shared domain code, each privately — that is
fine; no Kotlin type ever crosses the process boundary. The app framework carries Compose/UI + the
full `domain` stack; the extension framework is lean (`:domain`'s feature/upload UploadCycle
orchestration over the extension-safe adapters `:adapter:ios:ext-safe` + `:adapter:generic:app`). Both are
`isStatic = true` — the Compose-iOS norm (avoids dynamic-linking issues with the bundled
Skiko/Compose native libs).

## The Gradle ↔ Xcode boundary

`iosApp/` (repo root, **not** under this module, **not** a Gradle project) is the Xcode host: app
target + `BackgroundUploadExtension` target. Each target has a run-script phase that calls
`./gradlew :app:ios[:extension]:embedAndSignAppleFrameworkForXcode` to build + embed the
Kotlin framework, and each links `-lsqlite3` in `OTHER_LDFLAGS` (SQLDelight's native driver needs
the system SQLite — without it the device app fails to link). Shared build settings live in
`iosApp/Configuration/Config.xcconfig`; export configs in `iosApp/ExportOptions*.plist`.

You cannot build or run any of this on Linux. Use the proxy:

- `./gradlew compileIosMainKotlinMetadata` — **Linux-runnable** compile of `iosMain`/`commonMain`
  (+ cinterop) for both modules; catches iOS-only Kotlin breakage without a Mac.
- The Swift shells and the Xcode project compile **only on macOS CI** (`macos-26`); there is no
  Swift toolchain locally, so they cannot be built or run on this machine. The extension shell is
  verified on device (real-s3-upload, build 70) — keep edits to it minimal and lean on CI.

## The Swift ↔ Kotlin seam (Swift is a pure transcriber)

Swift shells forward raw, ObjC-visible OS inputs **whole**; every decision is Kotlin (migration
step 12; `SwiftShellGuardTest` pins the decision keywords — `if`/`guard`/`switch` at zero, one `??`).

- **App entry** (`iosApp/iosApp/iOSApp.swift`): UIKit `@main` app delegate + scene delegate. The scene
  delegate forwards every delivered `NSUserActivity` whole, under its own entry name per half —
  `onLaunchActivity` (cold) / `onSceneContinueActivity` (warm), so a dump says WHICH hook the platform
  invoked — the browsing-web filter and the raw `absoluteString` (fragment included) are Kotlin's tested
  `model/` codec, routed on to `onOpenUrl`. A silent push forwards its `userInfo` dictionary whole
  (`onSilentPush(userInfo:completion:)`; the `eventId` extraction is the tested payload codec).
  Foreground/background are **not** a Swift split any more: `SnapSyncRoot.onLaunch()` (called from
  `didFinishLaunchingWithOptions`) installs Kotlin-side `NSNotificationCenter` observers for
  `didBecomeActive`/`willResignActive`. `ContentView.swift` bridges
  `MainViewControllerKt.MainViewController()` (Compose) into SwiftUI — **gated on activation**: it binds
  Kotlin's scene generation (0 before any activation, 1 after) to `.id(…)`, so the Compose view is built
  once, at the first `didBecomeActive`, and never rebuilt. Until then `MainViewController()` returns a bare
  placeholder. WHICH it returns is Kotlin's tested decision (`resolveScene`), never Swift's. Why: iOS
  connects UI scenes in the BACKGROUND, so a silent-push wake would otherwise stand up a Compose runtime
  and Metal renderer in a process that cannot draw and present it hours later drawing dead textures
  (capability `ios-app-shell`; mitigation for CMP-5978 — delete when fixed upstream). ⚠️ Key on the
  APP-level notification, not `sceneDidBecomeActive`: `dvt launch` foregrounds the process WITHOUT
  connecting a scene session, so a scene-level hook gives a black screen and kills the headless
  screenshot loop (measured 2026-08-06).
- **Extension principal** (`iosApp/BackgroundUploadExtension/BackgroundUploadExtension.swift`):
  `@main` class conforming to the iOS 26.1 `PHBackgroundResourceUploadExtension`; its `process()`
  constructs `PHBackgroundResourceUploadProcessingResult(rawValue:)` from the raw `Int` Kotlin's
  tested `CycleResult.processingResultRawValue()` decided (`?? .failure` — the one remaining Swift
  pin; the system type is Swift-only, so the construction cannot leave the shell).

## Composition roots (manual DI — no expect/actual; both call the SHARED composition)

Both roots are wiring only: each constructs its process's platform adapters and hands them to the
shared composition functions in `:domain`'s `compose/` zone (law "One shared composition") — there
is no per-root cycle or feature assembly any more.

- **App**: `app/ios/src/iosMain/.../SnapSyncRoot.kt` — app-lifetime singleton owning a
  `SupervisorJob` scope on `Dispatchers.Main` (outlives Compose recomposition). Builds `AppPorts`
  (file-backed config store, PhotoKit permission, ledger/download stores, generic HTTP adapters,
  coordination lambdas) and calls `snapSyncApp(scope, ports)`; the returned `AppCore`'s lazily
  composed graph (status sources, attestation, join/leave/create, downloads, upload arm) is wired
  into `StatusContainerHost`. **The scope carries a `CoroutineExceptionHandler`** — its one
  non-negotiable member: a `SupervisorJob` isolates siblings from each other but does **nothing**
  for a throwable no child handles, which on Kotlin/Native hits the default terminate → `SIGABRT`.
  Without the handler an uncaught launch-path failure (a platform-API call, an App-Group read, a
  deprecated PhotoKit selector on a newer iOS) aborts the whole app before first paint. The handler
  logs the throwable to `debug.log` (the un-redacted channel) and lets the app live — the
  "errors reduce into state, never crash the shell" rule, applied to the one seam Compose reduction
  cannot reach. Every feature still reduces its own domain errors into `UiState`; this is the last
  resort for what nothing else did, and it is what makes an otherwise-invisible launch crash
  self-diagnosing (the exception text lands in `debug.log` instead of an opaque abort).
- **Extension**: `app/ios/extension/src/iosMain/.../UploadExtensionRoot.kt` — builds
  `UploadPorts` (the file-backed `ConfigReader`, the PhotoKit `IosPhotoKitUploadPlatform` +
  `IosDiscovery` — both from `:adapter:ios:ext-safe`, where the platform adapter lives — App-Group
  stores, `:adapter:generic:app` HTTP adapters) and calls `uploadCore(scope, ports)`; `process()` runs
  one blocking cycle of the composed `UploadCycle`, then maps the pending→`PROCESSING` requeue and
  the raw-value handoff through the tested `ports/` rules.
- The app-driven tier's `UrlSessionUploadController` calls the same `uploadCore` over its own ports
  (background-`URLSession` platform, pump, scheduler stay tier-local mechanism).

**Neither is the direction gate** (capability `upload-lifecycle`). Whether a membership uploads **at all** is
decided inside `UploadCycle`, from a required `Contribution` (`:domain` `model/`) carrying the membership's
direction *and* its cutoff: `None` → the cycle returns `CycleResult.SKIPPED` before any walk, job, manifest, or
notify, and the pump then schedules no `BGProcessingTask`. The roots only pass **facts** —
`Contribution.of(direction.includesUpload, minPhotoDate)` — and never the branch.
That gate sits at the **choke point every trigger funnels through**, not at the arm's invoker, because an
invoker-gate is only as good as its enumeration of invokers. It used to be one: a download-only membership was
handled by simply not enabling the producer, on the reasoning that "the OS never invokes the extension" — true
on ≥26.1, false here, where the *app* invokes its own cycle. `onForeground` walked straight past it and
uploaded the camera roll of a member who had been promised "you won't share yours".

**The upload lifecycle is NOT decided here** either (same capability). `SnapSyncRoot` supplies only
**facts** — both mechanism thunks, whether this OS carries the OS-driven one, and any development
override — and forwards membership transitions to the tested, tier-neutral `UploadArm` in `:domain`'s
feature/upload, which holds **exactly one** `UploadMechanismRuntime` at a time, re-resolved per transition
by `model/`'s `resolveUploadMechanism`. The seam has **two** verbs, `start()` and `stop()`, and **no
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
enable. Joining an event tore the upload arm down and started nothing. Holding one mechanism *reference*
also makes the tiers mutually exclusive **structurally**: starting two has no expression, because the arm
can only name one. `ProducerExclusivityTest` guards what the compiler cannot — that no resolver cell yields
a mechanism its OS cannot run, and that every switch observed stop-before-start.

**Single-writer invariant — the writer's process depends on the tier** (`sync-ledger`: exactly one
record-writer; its process placement is a platform binding). On **iOS ≥26.1** the **extension** is the
only `LedgerWriter` and the app constructs only reader/watcher (never a writer). On **iOS 18–26.0**
there is no extension, so the **app** holds the single `LedgerWriter` — constructed in
`UrlSessionUploadController` (the app-driven tier). Outside that controller, `:app:ios` still
constructs no writer.

## Entitlements & Info.plist (the cross-process glue)

- **App Group `group.app.snapsync`** (both `*.entitlements`): the shared on-disk container for the
  ledger DB the extension writes and the app reads (`iosLedgerStore`) — and for the config file of
  record (`eventconfig.json`, `FileBackedConfigStore`; save, clear **and read** are file-only — the
  legacy Keychain write-through ended with the migration and the Stage-2 change deleted the
  read-only legacy-item fallback behind the read, so the container's lifetime IS the membership's:
  **reinstall = left the event**, and the not-found error classification (`isConfigFileAbsence`) is
  now the only thing between a misread error and a silent logout). **Must be registered in the
  Developer portal** and enabled on both App IDs, or signed builds fail to provision.
- **Keychain group `$(AppIdentifierPrefix)app.snapsync.shared`** (both `*.entitlements`): lets the
  extension read the shared Keychain items the app writes — the device id
  (`KeychainDeviceIdentity`) and the attestation token (`KeychainAttestStore`). The legacy config
  item used to be a third; the Stage-2 change deleted its reader, so nothing addresses it any more
  and an already-migrated device simply carries it as an inert orphan (purging it would mean keeping
  the seat, its runtime-identity pin, and a Keychain call on the leave path alive to delete data no
  code path can observe). Keychain groups need **no** portal step. ⚠️ The **device id names this group explicitly**
  (`kSecAttrAccessGroup`); declaring the entitlement is *not* sufficient. This entry used to read
  "…all of which omit `kSecAttrAccessGroup` and rely on this default group", and that was false: with
  no group named, the platform picks one **at write time** from the writing build's entitlements, so a
  dev-signed build (whose profile grants the wildcard `<TEAM>.*`) writes into each process's own
  `application-identifier` group instead. On 2026-07-20 the app and the extension therefore held two
  different device ids — both reads succeeding — and the app re-imported every photo it had uploaded
  as if a stranger had sent it. The attest pair and the album map remain unscoped deliberately (the
  token is demonstrably read cross-process; the map is a self-healing cache); the config reader was a
  third until Stage 2 deleted it, and its entry left the inventory with it. That inventory
  is pinned in `:test:architecture` — a *new* unscoped seat fails the build, including a
  reconstructed config one.
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

App deploys **min iOS 18**. Upload runs on one of two tiers, **re-resolved at every transition** by the
pure resolver (`model/`'s `resolveUploadMechanism`) from three inputs: `isOperatingSystemAtLeastVersion(26.1)`,
the current photo permission, and an optional development override. `SnapSyncRoot`'s one switch decides only
**presence** — whether this OS carries the OS-driven mechanism at all — and no entry point re-checks a flag.
A once-per-process answer could not express this: the OS refuses to register the extension under a partial
grant, so the resolved mechanism genuinely changes when permission does. On a production build the override
is always `null` (its only writer is the rig's boot hook, not compiled in without `-Psnapsync.rig=true`), so
a shipped process's tier is a function of the device it runs on **and** the grant the user gave.

- **iOS ≥26.1 — PhotoKit (`ios-photokit-upload`).** The OS-driven upload extension, using the
  **deprecated 26.1** `PHBackgroundResourceUploadExtension` (the only protocol runnable on current GM
  devices). `setUploadJobExtensionEnabled` is confined to `PhotoKitUploadProducer`, which is only
  constructed where the OS carries this mechanism (≥26.1) — so it can never trap on a lower system. On
  ≥26.1 under a partial grant it *is* constructed but never started, and the incoming app-driven mechanism
  deregisters it (`RelinquishThenRun`). A later move to the iOS 27 async
  `PHBackgroundResourceUploadJobExtension` is confined to the Swift shell + deployment target — and, on
  the Kotlin side, to a third `UploadProducer`.
- **app-driven `URLSession` (`ios-url-session-upload`) — all of iOS 18–26.0, and ≥26.1 under a partial
  grant.** No OS-driven upload runs here; the **main app process** performs uploads over a background
  `URLSession` + `BGProcessingTask`, via `IosUrlSessionUploadPlatform` / `IosBackgroundScheduler`
  (`:adapter:ios:app-only`) driving the same `:domain` feature/upload `UploadCycle` through the
  `BackgroundUploadPump`. On this tier the **app**
  is the single `LedgerWriter` — below 26.1 no extension process exists, and at ≥26.1 under a partial grant
  the extension is not registered, so the OS never launches it.

**Forcing the app-driven tier on a device works through the rig.** `SNAPSYNC_FORCE_URLSESSION_UPLOAD` was
deleted with the rest of the launch-trigger surface; its replacement is the `uploadMechanismOverride` input
to `resolveUploadMechanism`, pinned over the control channel (`:test:rig`'s boot hook assigns the thunk, so
a build made without `-Psnapsync.rig=true` cannot carry one). Without the rig, on the agent-driveable SE2
(iOS 26.5) the app-driven mechanism is reachable only under a **`LIMITED`** photo grant, where the OS
refuses to register the extension. That exercises the pump, the scheduler, the background `URLSession`,
staging and ledger writing, but **not** the full-library discovery walk: a partial grant feeds discovery
the in-memory selection snapshot instead of walking.

There is **no host axis** any more: nothing reads `SIMULATOR_DEVICE_NAME`, and there is no
simulator-specific session. The transport used to be downgraded to a foreground session on the
simulator, on an unmeasured belief that a background one could not run there; measured 2026-08-09 on
`iosSimulatorArm64`, it runs — `getAllTasks` answers and an upload task completes. ⚠️ That covers the
**transport** only: whether the OS relaunches a terminated app to deliver
`handleEventsForBackgroundURLSession` on a simulator is still unproven.

⚠️ The OS's upload-job registration lives in the **system**, not the app, and survives relaunch and
reinstall — so on a ≥26.1 device the extension must be **deregistered first** or it uploads behind the
app-driven tier's back. That procedure, its verification, and the rest of the on-device loop are in the
**`ios-device`** skill (root `CLAUDE.md` → *Runbooks*). Irrelevant on a real 18–26.0 device, where no
appex can exist at all.

## Gotchas

- **Device logs:** both composition roots set `Logger.setLogWriters(PublicNSLogWriter(),
  FileLogWriter(<destination>))` — the writers live in `:adapter:ios:ext-safe` (capability
  `diagnostic-logging`). The writer takes its **destination**: the app passes `appLogDestination()`
  (its own `Documents/debug.log`, pullable as before), the extension `extensionLogDestination()`
  (`ext-debug.log` in the **App Group**, so the app can read it for a diagnostic dump; it falls back
  to its own Documents when the container is unavailable and says so in the boot banner). Verbatim,
  10 MB roll. The os_log `PublicNSLogWriter` is redacted `<private>` on current iOS. The extension's log
  lives in the App Group, which is not USB-pullable; read it through the control channel
  (`GET /device/logs?process=extension`, load `rig-channel`) — the copy-into-Documents launch trigger that
  used to serve this is gone, along with every other one. Each root emits a boot banner and wraps
  its entry points with `Logger.invocation`, so every line carries a `[<entryPoint>]` prefix. Keep new
  entry points wrapped, or their downstream lines lose the trigger prefix.
- **`-lsqlite3`:** required in each target's `OTHER_LDFLAGS` (above) for any target linking SQLDelight's
  native driver. The forge target does **not** link it (no ledger, no download store), so it does not need
  the flag — but check before assuming that of any other new target; the symbol resolves at link time, not
  at call time, so the failure is a link error rather than a crash.
- **In-memory SQLite on Native:** `NativeSqliteDriver` shares an in-memory DB across connections via
  shared-cache — give each backend a **unique db name** to avoid cross-test/instance leakage.
- **Compose scope ownership:** `SnapSyncRoot` deliberately uses a process-lifetime `SupervisorJob`,
  not `rememberCoroutineScope` (which dies with the view). Move ownership to Swift only if
  scene-aware lifecycle (multi-window, reset/logout) is ever needed.
