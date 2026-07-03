## Why

Today the desktop harness only ever **forges** display state. The forge harness (`:app:desktop:ui`,
capability `desktop-test-harness`) drives the real `StatusScreen` through a `PanelController` that
writes `SyncStatus`/`CreationStatus`/permission cells **by hand** — the counts are typed in, never
computed. Change 2 (`add-harness-world-model`) landed `:test:world`: a controllable in-memory
**world** the REAL platform-agnostic stack (`SyncEngine` + `UploadCycle`, `ExtensionReconciler`,
`DeviceManifestProducer`, `DownloadController`, `OwnDeviceCompletedAssetsSource` +
`ListingSyncStatusSource`, `CreateEvent`) runs against — but its only consumer so far is
`:test:integration` (assertions, no UI). Nothing lets a human **watch** the real stack behave: add a
photo, invoke the extension, and see the status screen's counts move because an object actually
landed in the backend store.

This is change **3 of 3** of the "full-stack world" design. It builds the app on top of the world:
a full-stack desktop harness where the left pane is the **real** status screen fed by the world's
**real** `ListingSyncStatusSource` — so its counts **emerge** from the world, never forged — and the
right pane is a "world inspector" that drives `:test:world`'s control surface. The operator plays the
OS: nothing auto-runs; an **Invoke extension** button runs one `process()`-shaped cycle by hand.

## What Changes

- **Claim `:app:desktop:run` for the full-stack harness.** Change 1 turned `:app:desktop` into a
  shared harness **library** (`PhoneFrame` + `StatusPane`) and deliberately left it with **no**
  application block, reserving `:app:desktop:run`. This change adds a `compose.desktop.application`
  block **directly to `:app:desktop`** with a full-stack `main()`, so `./gradlew :app:desktop:run`
  launches the new harness. `:app:desktop` becomes both the shared library (still consumed by
  `:app:desktop:ui`) **and** the full-stack application. The forge harness (`:app:desktop:ui:run`,
  capability `desktop-test-harness`) is **unchanged**.
- **Left pane — real status emerges.** Reuse `:app:desktop`'s in-module `StatusPane` + `PhoneFrame`,
  wiring them to the world's **real** seams: `world.syncStatusSource(scope)` (the real
  `ListingSyncStatusSource`), `StoreDownloadStatusSource(world.downloadStore)` (the real download
  status), `world.createEvent(scope)` (the real `CreateEvent`), `world.creationStatus`,
  `world.permission`, and `world.configSource`. No forged `SyncStatus` cell exists — every count on
  the phone frame is computed by the real projection over real world state.
- **Right pane — world inspector.** A right-hand control panel (raw Material 3, never `App*` — test
  equipment, exactly like the forge's `ControlPanel`) that drives the world through a **single**
  `WorldInspectorController` (mirroring `PanelController`: one named method per control, no inline
  mutation in composables). Two-column paired sections fill the width and cut scroll:
  - **Presets** — Clean · Enrolled · Fresh join · Re-provision (dedup) · Foreign download.
  - **Invoke extension** (primary) → one `world.runUploadCycle()` process cycle **plus** a
    `world.downloadController.reconcile(eventId)`, then a status/download refresh. Plus **Expire
    change token** (`world.platform.expireToken()`).
  - **Enrollment** — 3-state permission segment (+ armed next request), joined event id, Re-provision
    / Create event / Leave.
  - **Gallery ▏ Backend** side by side — Gallery: editable own-asset rows (add/remove; imported rows
    badged upload-suppressed). Backend: stored objects grouped by device (own + "+ Inject device"
    for a foreign contributor).
  - **Upload jobs ▏ Downloads** side by side — Upload queue: pending/retry rows with per-job Complete
    / Fail(`UploadError`: Network/Http/Cancelled/Unknown) + a job-limit indicator. Downloads: pending
    foreign resources with a Stage action (a non-staged download just stays PENDING — there is **no**
    `DownloadError`).
  - **Failure levers** (2-up) — Backend offline toggle · Job limit · Import failure (per-job
    `UploadError` lives on the queue).
- **Counts are pull-based, so the operator refreshes.** After every world mutation the controller
  calls `world.completed.refresh()` + `world.inFlight.refresh()` + the download source's `refresh()`,
  so the real projection re-emits — the operator plays the "foreground refresh" the iOS composition
  root performs. Permission is a live `StateFlow` and needs no refresh.
- **Presets rebuild a fresh world.** Unlike the forge's cell-resets, the world is a live stateful
  stack (backend byte store, ledger, gallery) that cannot be "un-deposited"; each preset swaps in a
  freshly-composed `World` and the left pane re-binds its `StatusContainerHost` to the new sources
  (keyed on a world-generation counter). Incremental controls mutate the current world in place.

Scope is **SHIPPED behavior**: whole-library, eventId-only, no date filter (the world's
`uploadCycle` already passes `startDate = null`).

## Capabilities

### New Capabilities

- **`full-stack-harness`** — the `:app:desktop:run` full-stack desktop harness: the dual-pane layout
  whose left status pane's counts **emerge** from the real stack over `:test:world`; the
  operator-driven **Invoke extension** cycle; the world-inspector controls
  (enrollment / gallery / backend / upload-job queue / downloads / failure levers) routed through a
  single controller; the presets that rebuild a fresh world; and the "panel is test equipment, all
  testable logic lives in `:test:world`/presentation" rule.

### Modified Capabilities

- **`harness-world-model`** — extended with a **`World.leave()`** composition helper that runs the
  real leave edge (`DownloadController.onLeaveOrSwitch()` + clear config cell + clear joined-event
  marker), retaining imported photos and stored objects. The harness needs a *faithful* leave (the
  real product keeps imported foreign photos on leave), and modelling it by rebuilding the world would
  forge the outcome instead of running the real path — so the world gains the helper rather than the
  panel faking it. A one-scenario delta; no existing world behavior changes.

_`desktop-test-harness` (the forge harness's spec) is **unchanged** — this is a distinct harness with
its own capability._

## Impact

- **Module `:app:desktop`** gains a `compose.desktop.application` block (`mainClass =
  "app.snapsync.desktop.FullStackHarnessKt"`, `javaHome` toolchain + Skiko `jvmArgs`, mirroring
  `:app:desktop:ui`) and a full-stack `main()` + `WorldInspectorController` + the inspector
  composables. **No new Gradle module or `settings.gradle.kts` entry** — `:app:desktop:run` is a task
  on the existing project.
- **New deps (`:app:desktop`):** `:test:world` (the world + fakes; brings `:domain:engine`,
  `:domain:gallery`, `:capability:upload`, … transitively for the types the inspector names),
  `:capability:download` (`StoreDownloadStatusSource`), `compose.material3` (raw-M3 inspector), and
  `compose.desktop.currentOs` (Window/application). `:domain:ui`, `:domain:presentation`,
  `:capability:config`, `:capability:event-creation-ui` are already deps.
- **`:test:world` gains `World.leave()`** (~4 lines: real `onLeaveOrSwitch()` + clear config cell +
  clear marker) so the harness's Leave runs the faithful edge; carried by the `harness-world-model`
  spec delta. No existing world behavior changes.
- **`:app:desktop` `StatusPane` gains an optional `leave: () -> Unit = {}` param** so the full-stack
  harness wires the real screen's Leave button to `world.leave()`. The default no-op keeps the forge
  harness's Leave button inert exactly as today (`:app:desktop:ui` passes nothing) — a purely additive
  shared-library signature change, no forge behavior change.
- **Entry-point class name.** The forge's `main()` compiles to `app.snapsync.desktop.MainKt`, which
  leaks transitively onto `:app:desktop:ui`'s classpath via its `:app:desktop` dependency. The
  full-stack `main()` therefore lives in a file that compiles to a **distinct** class
  (`FullStackHarnessKt`), so the two entry points never collide.
- **No tests on the panel/controller** — mirroring how the forge's `PanelController`/`ControlPanel`
  are treated (test equipment). All testable logic already lives in `:test:world` (self-tested) and
  the presentation/status modules; `:test:integration` covers the seam ↔ UiState surface. `:app:desktop`
  stays a JVM-only target (it consumes `:test:world`'s `jvm()` artifact).
- **Docs:** point the `CLAUDE.md` module table's `:app:desktop` row at the now-live
  `:app:desktop:run` full-stack harness; the `docs/design.md §5.1` world-model harness note is
  realized here.
- **Depends on:** landed change 1 (`extract-ui-harness-module` — `PhoneFrame`/`StatusPane` in
  `:app:desktop`, `:app:desktop:run` reserved) and change 2 (`add-harness-world-model` —
  `:test:world`).
- **Not in scope:** any production behavior change; a date-filtered or per-asset scope (SHIPPED
  behavior only); driving a real backend (the world is in-memory); tests for the inspector panel.
