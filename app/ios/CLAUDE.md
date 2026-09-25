# app/ios — iOS specifics

The iOS platform layer. **Wiring-only and untested** (root `CLAUDE.md` hard rule): all testable
logic — shared *or* iOS-specific — lives in `domain`/`capability` modules under test; nothing
testable is parked here. This doc covers what is specific to the iOS surface — the **structure**, not
the operator procedure. For on-device testing load the **`snapsync-device`** skill, for builds and signing
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
  (capability `sync-status`; mitigation for CMP-5978 — delete when fixed upstream). ⚠️ Key on the
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
  (background-`URLSession` platform and scheduler stay tier-local mechanism; the app's `TailRunner` drives it).

**Neither is the direction gate** (capability `background-upload`). Whether a membership uploads **at all** is
decided inside `UploadCycle`, from a required `Contribution` (`:domain` `model/`) carrying the membership's
direction *and* its cutoff: `None` → the cycle returns `CycleResult.SKIPPED` before any walk, job, manifest, or
notify, and the tail then re-arms no heartbeat `BGProcessingTask`. The roots only pass **facts** —
`Contribution.of(direction.includesUpload, minPhotoDate)` — and never the branch.
That gate sits at the **choke point every trigger funnels through**, not at the arm's invoker, because an
invoker-gate is only as good as its enumeration of invokers. It used to be one: a download-only membership was
handled by simply not enabling the producer, on the reasoning that "the OS never invokes the extension" — true
on ≥26.1, false here, where the *app* invokes its own cycle. `onForeground` walked straight past it and
uploaded the camera roll of a member who had been promised "you won't share yours".

**The upload lifecycle is NOT decided here** either (same capability). `SnapSyncRoot` supplies only
**facts** — the app-driven engine, the OS-driven registration where this OS carries it, whether it does, and
the rig's per-uploader switch source. What each membership transition does (join · re-provision · reconfigure ·
permission change · launch · leave) is decided by the tested, stateless `UploadTransitions` in `:domain`'s
feature/upload, from `model/`'s `extensionRegistrable` read fresh at that moment. It calls five verbs: the
extension's `register()` (the disable → enable ritual — never a bare enable) and `deregister()`, and the app
engine's `arm()`, `disarm()` (the heartbeat only) and `cancelTransfers()` (a leave only). **None of them clears
the ledger**: only a leave clears it and only a join loads it, as the membership use-cases' own steps
(`background-upload`, `join-event`). And **no transition but a leave stops in-flight work**: the registration spans
the membership (never removed by a reconfigure or a permission change), a revoke stops only new creation, and a
re-provision of the joined event does nothing — so nothing is ever orphaned, and nothing needs repair
(decision record `openspec/changes/both-uploaders-active`).

**Both uploaders run, and that is by design.** Every app-side trigger goes to the app engine unconditionally; its
cycle's entry gate withholds only without usable access (or when the rig switched it off), and the extension's
withholds without a full grant. On ≥26.1 under a full grant both create, over the one App-Group ledger: a cycle
picks only `DISCOVERED` rows and records `REQUESTED` only after its job exists, so an overlap is at worst a
duplicate upload of the same object, and the guarded terminal write converges the row. The launch reconcile is
called explicitly from host assembly — the permission `StateFlow`'s replay is no longer a transition — and it
*compares* against the OS's registration, registering only a record the OS reads absent, so a launch never
wipes the extension's in-flight jobs; only a join forces the ritual.

This structure is load-bearing, not tidiness. The lifecycle *used* to live here as a pile of
`if (useAppDrivenUpload)` branches, and because this module is wiring-only and untested, nothing caught
that `provisionEvent` → `enableBackgroundUpload()` → `disableExtension()` resolved, on the app-driven tier,
to a **full leave** (cancel transfers, cancel the heartbeat, wipe ledger + cursor) followed by a no-op
enable. Joining an event tore the upload arm down and started nothing. `ProducerExclusivityTest` guards what
the compiler cannot — that the extension is never registrable below 26.1, and that no transition sequence
deregisters or cancels anywhere but at a leave.

**Ledger writers are owned by code, not by a process** (`photo-sharing`). The app holds a `LedgerWriter` on every
OS version — constructed in `UrlSessionUploadController` through `uploadCore` — and on **iOS ≥26.1** the
**extension** holds one too. Every write is one guarded transaction owned by named code: the cycle's record
family, a transport's guarded terminal write, and the membership reset family. Outside that controller,
`:app:ios` constructs no writer.

## Entitlements & Info.plist (the cross-process glue)

- **App Group `group.app.snapsync`** (both `*.entitlements`): the shared on-disk container for the
  ledger DB the extension writes and the app reads (`LedgerService` over `IosDatabases`) — and for the config file of
  record (`eventconfig.json`, `ConfigService` over `IosFiles`; save, clear **and read** are file-only — the
  legacy Keychain write-through ended with the migration and the Stage-2 change deleted the
  read-only legacy-item fallback behind the read, so the container's lifetime IS the membership's:
  **reinstall = left the event**, and the not-found error classification (`isFileAbsence`, behind `IosFiles`) is
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
  `$(ASSOCIATED_DOMAIN)`): claims the event link's Universal Link (capability `join-event`), which is
  how a Camera-scanned QR opens the app. Like App Groups (and unlike keychain groups) **it must be
  enabled on the app.snapsync App ID in the portal**, or signed builds fail to provision — and
  enabling it *invalidates existing profiles*, so the dev build loop's baked secret needs refreshing
  (root `CLAUDE.md`). The **extension declares none**: it never handles URLs.
- **App `Info.plist`**: registers **no** `CFBundleURLTypes` — the `snapsync` scheme is retired, and a
  scheme re-added here would route links the one authoritative codec no longer accepts.
  `CADisableMinimumFrameDurationOnPhone = true` is **mandatory** — Compose MP ≥1.7
  hard-aborts at launch without it. Portrait-only, iPhone-only.
- **`BackgroundUploadURLBase` — in BOTH `Info.plist`s, and it is the one deployment value that lives
  there.** `assetsd` reads it out of a **bundle's own `Info.plist`** to validate the background-upload
  registration insert; it can see no resource we bundle, so `Deployment.plist` cannot stand in. Absent,
  `setUploadJobExtensionEnabled(true)` fails with a bare `PHPhotosErrorDomain -1` and **empty**
  `userInfo`, the OS launches the extension never, and nothing uploads on that tier — which is exactly
  what shipped in builds 675/687 when a config change moved the key out (measured A/B on device,
  SE2/26.6, 2026-08-28; Bugsink `SNAPSYNC-37`). The daemon's **matching rule is not established** — do
  not assert one. ⏰ Re-measure at the next iOS major.
  Written as `$(UPLOAD_SCHEME)://$(UPLOAD_HOST)/api/v1`, **composed** rather than carried: an
  `Info.plist` substitution can only read a build setting and `//` opens a comment anywhere on an
  xcconfig line, so the resolver emits a scheme enum and a bare host and the URL is assembled in the
  plist, where `//` is data. `ios.yml` asserts it equals that bundle's `Deployment.plist` `uploadBase`.
  It must be an HTTPS endpoint: default ATS (HTTPS-only) applies, no `NSAllowsLocalNetworking`
  exception. (A user-configurable upload host is impossible with this API.)
- **Extension `Info.plist`** also declares `EXExtensionPointIdentifier =
  com.apple.photos.background-upload` (under `EXAppExtensionAttributes`, the ExtensionKit iOS 26
  model).

## iOS-version deviation & the two uploaders

App deploys **min iOS 18**. There are two uploaders, and **both run** where both exist (decision record
`openspec/changes/both-uploaders-active`): each decides at its own entry gate whether it may create, and an
overlap is a duplicate upload of the same object, never a loss. `SnapSyncRoot`'s one switch decides only
**presence** — whether this OS carries the OS-driven uploader at all (`isOperatingSystemAtLeastVersion(26.1)`).
Whether the extension may be **registered** is `model/`'s pure `extensionRegistrable` over that fact, the current
photo permission, and the rig's per-uploader switch (always `null` on a production build — its only writer is the
rig's boot hook, not compiled in without `-Psnapsync.rig=true`).

- **iOS ≥26.1 — PhotoKit (`background-upload`).** The OS-driven upload extension, using the
  **deprecated 26.1** `PHBackgroundResourceUploadExtension` (the only protocol runnable on current GM
  devices). `setUploadJobExtensionEnabled` is confined to `PhotoKitExtensionRegistry` (`:adapter:ios:app-only`), the
  sole caller of that selector and of its read-back, reached through the `UploadExtensionRegistry` port by
  `OsDrivenRegistration` (`:domain` `feature/upload`), which is only
  constructed where the OS carries this uploader (≥26.1) — so it can never trap on a lower system. The
  registration spans the membership: registered at the join wherever the OS allows it (a full grant),
  download-only included, and removed only at a leave. Under a partial grant no registration write is
  attempted (every one is refused — 3311); a surviving record is invoked by the OS, and its extension withholds
  at its own gate — it records and acknowledges, and creates nothing. A later move to the iOS 27 async
  `PHBackgroundResourceUploadJobExtension` is confined to the Swift shell + deployment target.
- **app-driven `URLSession` (`background-upload`) — every iOS version, whenever access is usable.** The
  **main app process** uploads over a background `URLSession` + `BGProcessingTask`, via
  `IosUrlSessionUploadPlatform` / `IosBackgroundScheduler` (`:adapter:ios:app-only`) driving the same `:domain`
  feature/upload `UploadCycle`, whose units (`topUp`, `walkAndPublish`) run in the app's one `TailRunner`. Below 26.1 it is the only uploader; from 26.1
  it runs beside the extension, both writing the one App-Group ledger (every write a guarded single transaction).

**Exercising one uploader alone on a device works through the rig**: `POST /device/uploaders?app=on|off&extension=on|off`
(`rig-channel`). `extension=off` deregisters the extension; `app=off` makes the app's uploader withhold.

There is **no host axis** any more: nothing reads `SIMULATOR_DEVICE_NAME`, and there is no
simulator-specific session. The transport used to be downgraded to a foreground session on the
simulator, on an unmeasured belief that a background one could not run there; measured 2026-08-09 on
`iosSimulatorArm64`, it runs — `getAllTasks` answers and an upload task completes. ⚠️ That covers the
**transport** only: whether the OS relaunches a terminated app to deliver
`handleEventsForBackgroundURLSession` on a simulator is still unproven.

⚠️ The OS's upload-job registration lives in the **system**, not the app, and survives relaunch and
reinstall. To exercise the app's uploader alone on a ≥26.1 device, switch the extension off with `rig-channel`'s
`POST /device/uploaders?extension=off`. The on-device loop is in the **`snapsync-device`** skill (root
`CLAUDE.md` → *Runbooks*). Irrelevant on a real 18–26.0 device, where no appex can exist at all.

## Gotchas

- **Device logs:** both composition roots set `Logger.setLogWriters(PublicNSLogWriter(),
  FileLogWriter(<destination>))` — the writers live in `:adapter:ios:ext-safe` (capability `privacy-security`). The writer takes its **destination**: the app passes `appLogDestination()`
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
