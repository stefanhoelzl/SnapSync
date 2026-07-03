# Design: add-full-stack-harness

The full-stack world harness — the app on top of `:test:world`. This is **thin wiring + a Compose
panel**: no new domain logic. Everything that could be tested already lives in `:test:world`
(self-tested) and the presentation/status modules; the inspector panel and its controller are test
equipment, treated exactly like the forge's `ControlPanel`/`PanelController` (no tests).

## 1. Where the app lives: `:app:desktop:run`

Change 1 turned `:app:desktop` into a shared harness **library** (`PhoneFrame` + `StatusPane`) with
**no** `compose.desktop.application` block, and its `build.gradle.kts` header explicitly reserves
`:app:desktop:run` "for the full-stack world harness." This change realizes that: it adds the
application block + a full-stack `main()` **directly to `:app:desktop`**.

```
:app:desktop                      (kotlin.jvm + compose + NOW compose.desktop.application)
├── PhoneFrame.kt        ─┐  shared library surface (unchanged) — also used by :app:desktop:ui
├── StatusPane.kt        ─┘
├── FullStackHarness.kt      main() → :app:desktop:run     (NEW — the full-stack app)
├── WorldInspectorController.kt   the single mutation path  (NEW)
└── WorldInspector.kt            the right-pane composables  (NEW, raw Material 3)

:app:desktop:ui                   (unchanged forge harness)
└── depends on :app:desktop, provides its own main() → :app:desktop:ui:run
```

**Why on `:app:desktop` and not a new child module.** The reservation is deliberate and the module
table already advertises `:app:desktop:run` as the full-stack harness. A module can be both a
consumed library and an application; `:app:desktop:ui` already depends on the library surface and is
unaffected by the parent gaining a `main()`. Adding a `:app:desktop:fullstack` child would fork the
`run` task name away from the reserved `:app:desktop:run` for no benefit.

**Entry-point class-name collision (must-fix).** A Kotlin file with a top-level `main()` compiles to
`<FileName>Kt`. The forge's `Main.kt` compiles to `app.snapsync.desktop.MainKt`, and because
`:app:desktop:ui` depends on `:app:desktop`, any `MainKt` in the parent's `app.snapsync.desktop`
package would collide on the child's classpath. The full-stack entry file is therefore **named to
compile to a distinct class** — `FullStackHarness.kt` → `app.snapsync.desktop.FullStackHarnessKt` —
and the application block points `mainClass` at it. (The forge keeps `MainKt`; no rename there.)

**Gradle block** mirrors `:app:desktop:ui`'s proven setup — the run task does not inherit
`jvmToolchain`, so it needs an explicit `javaHome` from a `javaToolchains.launcherFor { … }`, plus
the Skiko `--enable-native-access=ALL-UNNAMED` and the `-Dsun.java2d.uiScale` HiDPI arg (same
`-PuiScale` escape hatch).

**`:app:desktop:run` is not literally free today — it's the application block that creates it.** The
`org.jetbrains.compose` 1.11.1 plugin auto-applies **Compose Hot Reload**, which registers
`hotRun` / `runHot` (deprecated) / `hotRunAsync` on **every** module that applies the compose plugin,
regardless of an application block. So on today's `:app:desktop` (library only) there is **no plain
`run` task**, and `./gradlew :app:desktop:run` abbreviation-matches Gradle to the hot-reload
`runHot`/`hotRun` chain — which fails with "Deprecated task 'runHot'" and "Missing 'mainClass'
property". Adding `compose.desktop.application { mainClass = … }` is precisely what mints the real
`run` JavaExec task (as it already does on `:app:desktop:ui`, whose `:app:desktop:ui:run` works); the
hot-reload `hotRun`/`runHot` tasks keep coexisting harmlessly (they already do on `:app:desktop:ui`).
This refines the change-1 framing that "`:app:desktop` declares no application block, so it has no
`run` task": there is no *application* `run` task, but the compose plugin's hot-reload `run` variants
are present — the reservation of `:app:desktop:run` for the full-stack app still holds, and the
application block claims it as a proper JavaExec run.

New dependencies on `:app:desktop`:

| Dep | For |
|---|---|
| `project(":test:world")` | `World` + all fakes; transitively brings `:domain:engine` (`UploadError`), `:domain:gallery` (`DeviceManifestAsset`, `RawResource`, `ResourceRole`), `:capability:upload`, … the types the inspector names |
| `project(":capability:download")` | `StoreDownloadStatusSource` (real download status over `world.downloadStore`) |
| `compose.material3` | raw-M3 inspector (never `App*`) |
| `compose.desktop.currentOs` | `Window` / `application` |

`:domain:ui`, `:domain:presentation`, `:capability:config`, `:capability:event-creation-ui`,
`:domain:status`, `:domain:permission` are already `:app:desktop` deps (they type `StatusPane`).
`:test:world` is JVM-consumable (its `jvm()` target); `:app:desktop` stays a JVM module.

## 2. Composition root — feeding the left pane from the world

`main()` constructs one `World`, derives the left-pane seams from it, and hands the same world to the
`WorldInspectorController`. `StatusPane`'s signature is unchanged; only the **sources** differ from
the forge (which passes `PanelController`'s stand-in cells).

```
val world = World()                         // fresh live stack (own device fixed)
StatusPane(
    syncSource          = world.syncStatusSource(scope),      // REAL ListingSyncStatusSource
    permissionSource    = world.permission,                   // MutablePermissionStatusSource
    requester           = inspectorRequester,                 // flips world.permission (armed)
    configSource        = world.configSource,                 // REAL config StateFlow
    configStore         = worldConfigStore,                   // adapter → world.provision / leave
    creationStatusSource= world.creationStatus,               // REAL MutableCreationStatusSource
    creator             = world.createEvent(scope),           // REAL CreateEvent (is an EventCreator)
    downloadSource      = StoreDownloadStatusSource(world.downloadStore), // REAL download status
    share               = clipboardShareStub,                 // copy+log (as forge)
    leave               = { scope.launch { world.leave() } }, // REAL leave edge (§5; new opt param)
    scope               = scope,
)
```

Every count on the phone frame is now computed by the real projection over real world state — the
harness cannot type a number in. `CreateEvent` already implements `EventCreator`, so the create input
on the real screen mints against the mini-edge (`POST /event` → `provision`).

**`ConfigStore` adapter.** `StatusContainerHost` requires a `ConfigStore`. The harness supplies a thin
one: `save(config)` → `world.provision(config.eventId)`; `clear()` → `world.leave()` (§5 — the new
faithful edge). The real screen's create flow provisions via `CreateEvent`'s own `provision` callback,
so `save` is rarely hit — the adapter mainly exists to satisfy the constructor, as in the forge.

### 2.1 Counts are pull-based → refresh after every mutation

`OwnDeviceCompletedAssetsSource.completed`/`.size` and `ReadingInFlightSource.inFlight` are
`MutableStateFlow`s updated **only** by `refresh()`; `ListingSyncStatusSource` merely `combine`s them.
So world state changing (an object deposited by a completed job, an asset added) does **not** move the
phone frame until someone refreshes — exactly the iOS "refresh the completed + in-flight source on
foreground entry" contract. The operator is that foreground: **every** controller method ends with

```
world.completed.refresh(); world.inFlight.refresh(); downloadSource.refresh()
```

Permission is a live `StateFlow`, so permission changes reflect without a refresh.

## 3. The single controller — control-surface mapping

`WorldInspectorController` mirrors `PanelController`: it holds the current world and exposes one named
method per control; composables never mutate world state inline. Each method maps to a public
`World` / `WorldRunner` / fake call, then refreshes (§2.1).

| Inspector control | Controller call → world surface |
|---|---|
| **Invoke extension** (primary) | `world.runUploadCycle()` then `world.downloadController.reconcile(joinedEventId)` |
| **Expire change token** | `world.platform.expireToken()` (next invoke → full enumeration) |
| **Permission segment** (3-state) | `world.permission.set(NOT_DETERMINED / DENIED / GRANTED)` |
| **Armed next request** | fake `PermissionRequester.request()` sets `world.permission` to the armed grant/deny; `openSettings()` logs |
| **Joined event id** (readout) | `world.configSource.config.value?.eventId` |
| **Re-provision (dedup)** | `world.provision(sameEventId)` (reconcile seeds already-stored assets `COMPLETED`) |
| **Create event** | the real screen's create input → `world.createEvent(scope).create(name)` |
| **Leave** | `world.leave()` — real `onLeaveOrSwitch()` + clear config/marker, imports retained (§5) |
| **Gallery: add row** | `world.addOwnAsset(assetId)` |
| **Gallery: remove row** | `world.removeAsset(assetId)` (next incremental discovery prunes it) |
| **Gallery: imported badge** | read: `assetId ∈ world.downloadStore.suppressedLocalIds()` |
| **Backend: objects per device** | read: `world.store.objectsOf(deviceId)` (own + injected) |
| **Backend: + Inject device** | `world.addForeignDevice("foreign-$n", joinedEventId, listOf(World.foreignAsset("foreign-$n-a1")))` — `n` a monotonic controller counter; disabled when no event is joined |
| **Upload queue rows** | read: `world.platform.liveJobKeys()` / `.created` (attempts via repeats) |
| **Upload row: Complete** | `world.platform.completeJob(key)` (deposits object store-direct) |
| **Upload row: Fail(UploadError)** | `world.platform.failJob(key, UploadError.Network/Http/Cancelled/Unknown)` |
| **Job-limit indicator / control** | `world.jobLimit` (get/set) |
| **Downloads: pending rows** | read: `world.downloadJobs.pending()` |
| **Downloads: Stage** | `world.stageAllDownloads()` (resolves url store-direct → import) |
| **Lever: Backend offline** | `world.backendOffline` (get/set → mini-edge 502) |
| **Lever: Import failure** | `world.failNextImport()` |

The one indirection worth calling out: the operator drives an **OS invocation**, not a single method.
**Invoke extension** models one full extension wake — the upload `process()` cycle (reload config →
reconcile → build config → run cycle, already assembled by `world.runUploadCycle()`) **and** the
download reconcile (`world.downloadController.reconcile(eventId)`, which enqueues foreign resources and
imports any already-staged). The **Stage** action is separate — it models the background `URLSession`
delegate completing a transfer between invocations — so the Foreign-download flow reads: Invoke (union
→ pending downloads) → Stage (→ import → suppression) → Invoke (own cycle skips the imported asset).

## 4. Presets rebuild a fresh world

The forge resets `MutableStateFlow` cells. The world is a **live stateful stack** — a completed job
has deposited real bytes into `world.store`, written a real ledger row, mutated the real gallery — so
"Clean" cannot be expressed by nudging a cell. A preset therefore **constructs a fresh `World`** and
applies a short setup script, and the controller swaps it in.

```
Clean            → World()                                   (nothing joined)
Enrolled         → World().apply { provision(E); addOwnAsset(a1); addOwnAsset(a2) }
Fresh join       → World().apply { provision(freshE); addOwnAsset(a1) }   (nothing stored yet)
Re-provision     → World().apply { addOwnAsset(a1); /* deposit a1's object */; provision(E) }
  (dedup)          → reconcile seeds a1 COMPLETED; a subsequent Invoke uploads nothing new
Foreign download → World().apply { provision(E); addForeignDevice(dev2, E, [foreignAsset(...)]) }
```

**Compose re-binding.** `StatusPane` does `remember { StatusContainerHost(sources…) }`, so swapping
the world must rebuild that host against the new sources. The controller holds the world in a Compose
`MutableState<World>` plus a monotonically increasing **generation** counter; `main()` wraps the
`StatusPane` call in `key(generation) { … }` so a preset (new world, bumped generation) tears down and
re-composes the host against the fresh sources. Incremental controls (add asset, complete job, inject
device, toggle a lever) mutate the **current** world in place and only refresh — no generation bump.

## 5. Leave / config-clear (faithful in-place clear)

Leave is modelled **faithfully**, not by rebuilding the world: the real product keeps imported foreign
photos on leave (they are real library assets now; the download store's imported rows are terminal and
delete-proof for cross-event dedup) and only cancels transfers + prunes *non-terminal* download rows.
A world-rebuild would wrongly wipe the imported photos and stored objects, and would *forge* the leave
outcome instead of running the real path — the opposite of this harness's purpose.

So `:test:world` gains a small **`World.leave()`** composition helper (this change touches change 2 —
see the `harness-world-model` delta) that runs the real edge:

```
suspend fun leave() {
    downloadController.onLeaveOrSwitch()   // real: cancel transfers, prune non-terminal rows
    configCell.value = null                // config absent → reduction leaves the joined layer
    marker.clear()                         // joined-event marker cleared
}                                          // gallery, store, ledger, imported photos RETAINED
```

Clearing `configCell` is reactive (`configSource` is its `StateFlow`), so the phone frame leaves the
joined layer with **no** generation bump / world rebuild — imported photos stay enumerable, and
re-provisioning the same event afterwards still finds them suppressed (real cross-event dedup). The
operator triggers leave through the **real screen's Leave affordance**: `StatusPane` gains an optional
`leave: () -> Unit = {}` param (default no-op keeps the forge's button inert, as it is today) and the
full-stack `main()` passes `leave = { scope.launch { world.leave() } }`. The inspector's Enrollment
**Leave** button routes to the same call for convenience (some states hide the phone-frame button).
The `ConfigStore` adapter's `clear()` (§2) also delegates to `world.leave()`, so every clear path is
the one faithful edge.

## 6. Engine console footer (resolved: included)

The inspector SHALL carry an **engine console** footer streaming the stack's own log lines, so the
operator sees the *narrative between snapshots* — the branch a cycle took, not just the job queue's
end state. This plugs the one blind spot the queue can't show: when a count doesn't move after Invoke,
was it a real no-op, or did `World.runUploadCycle()` short-circuit (`mayUpload == false`, or
`buildUploadConfig` returned null)?

Mechanism (the only new plumbing in the harness — still test equipment, no tests):

- `main()` installs a Kermit `LogWriter` via `Logger.setLogWriters(listOf(consoleWriter, …))` that
  formats each `(severity, tag, message)` into a line appended to a bounded
  `MutableStateFlow<List<String>>` (cap the ring at, say, 200 lines). The world stack already logs
  through Kermit tags (`DownloadController`, `CreateEvent`, engine/cycle), so nothing in `:test:world`
  or production changes — the console is a pure read of existing log output. Kermit is already a
  transitive dep via `:test:world` and the domain modules.
- The controller **also** appends an explicit line at the end of `invokeExtension()` carrying the
  `CycleResult` (`COMPLETED` / `PROCESSING` / `FAILED`) and the reconcile outcome, because that return
  value is not itself a Kermit log — this is the line that explains a surprising no-op.
- Rendered as a scrollable, monospace-ish footer spanning the inspector's width beneath the two-up
  sections, auto-scrolled to the tail. A **Clear** action empties the ring.

Because the writer is a process-global Kermit sink and the harness is the only thing running, there is
no cross-talk to filter. A preset's world-rebuild does not reset the console (the log is a session
timeline, not world state); Clear is the explicit reset.

## 7. What stays out of the panel

No `App*` in the inspector — raw Material 3 only, like the forge (`design.md §5.1`; the
`desktop-test-harness` "Material 3 containment" exemption applies identically here). No inline
mutation: composables call controller methods. No tests: the controller is a wiring shim over
`:test:world`'s already-tested surface; adding tests here would re-assert world behavior at the wrong
layer. The left pane is the shipped `StatusScreen` composable, never a copy.

## Open questions

- **O1 — Engine console footer. RESOLVED: included** (see §6). Ships as a Kermit `LogWriter` tap +
  an explicit `CycleResult` line per Invoke, rendered as a scrollable footer with Clear.
- **O2 — Leave fidelity. RESOLVED: faithful in-place clear** (see §5). `:test:world` gains a
  `World.leave()` running the real `onLeaveOrSwitch()` + clearing config/marker while retaining
  imported photos; the real screen's Leave button drives it via a new optional `StatusPane` param.
  This change carries the `harness-world-model` spec delta for the new helper.
- **O3 — Foreign-device inject affordance. RESOLVED: canned one-click.** Each "+ Inject device"
  click injects one `World.foreignAsset("foreign-$n-a1")` under a fresh `deviceId = "foreign-$n"`
  (monotonic controller counter) into the currently-joined event; the button is disabled when no
  event is joined. No form — the device's identity and filenames are irrelevant to every path it
  drives (union tags by id, controller skips by id-equality, suppression keys off the imported local
  id), so a canned asset fully exercises union → download → import → echo-suppression.
