## Why

SnapSync is adding a **second** desktop harness. Today's `:app:desktop` is a **UI-state forge**:
its right pane (`ControlPanel` + `PanelController`) forges `SyncStatus`/`UiState` directly and its
left pane is the real `StatusScreen` inside a `PhoneFrame` — it does **not** run the engine. A later
change (change 3) adds a **full-stack world** harness that drives the real platform-agnostic stack
(`SyncEngine`, `UploadCycle`, `DownloadController`, `ListingSyncStatusSource`) against controllable
in-memory fakes. The settled layout **names each harness by its run task**:

- `:app:desktop:run`     → the new full-stack world harness (built in change 3)
- `:app:desktop:ui:run`  → today's forge harness, relocated into a new `:app:desktop:ui` child module

This change does **only** the relocation that frees the parent's `run` task for change 3. It is a
**behavior-preserving refactor** — no new UI states, no new features, no engine wiring. Per
`CLAUDE.md` a pure refactor may skip OpenSpec, but the `desktop-test-harness` spec's
"Dual-pane harness layout" requirement **pins the harness to `:app:desktop`**, so keeping specs the
contract of record requires a module-placement delta. That one delta is this change's entire spec
footprint.

## What Changes

- **Add a `:app:desktop:ui` child module** (Compose **application**, `kotlin.jvm` + Compose plugins),
  registered in `settings.gradle.kts`. Its `compose.desktop.application` block owns
  `mainClass = "app.snapsync.desktop.MainKt"`, the toolchain `javaHome`, and the
  `--enable-native-access=ALL-UNNAMED` JVM arg — so the forge harness's run task becomes
  **`:app:desktop:ui:run`**.
- **Move the forge-only files** `Main.kt`, `ControlPanel.kt`, `PanelController.kt` from `:app:desktop`
  into `:app:desktop:ui`. **Package unchanged** (`app.snapsync.desktop`), so `mainClass` and all
  imports are stable. No logic change.
- **Keep `:app:desktop` as a Compose library** holding the pieces both harnesses reuse: `PhoneFrame.kt`
  (unchanged) plus a new `StatusPane.kt` — the composition glue **extracted verbatim** from today's
  `Main.kt` (construct `StatusContainerHost` from the injected seams + `share` lambda + scope, collect
  its state, render `StatusScreen` inside `PhoneFrame`). The parent has **no** `application` block, so
  **`:app:desktop:run` does not exist yet** — it is left free for change 3 to claim.
- **Re-point dependencies**: the parent keeps the seam/UI deps its shared code needs
  (`:domain:ui`, `:domain:presentation`, `:domain:status`, `:domain:permission`, `:capability:config`,
  Compose runtime/foundation); the child depends on `project(":app:desktop")` plus the seam-providing
  modules its `PanelController` cells construct (`:domain:permission`, `:domain:status`,
  `:capability:config`, `:capability:event-creation-ui`) and the harness-only edges
  (`compose.material3` for the raw control panel, `compose.desktop.currentOs` for the app entry +
  clipboard share stub).
- **Update docs**: the `CLAUDE.md` module table (split `:app:desktop` into the parent library + the
  `:app:desktop:ui` forge child) and `docs/design.md §5.1` (the harness's run task rename).

## Capabilities

### New Capabilities

_None — no new behavior. This is a behavior-preserving relocation; the only spec impact is the
module-placement contract in `desktop-test-harness`._

### Modified Capabilities

- `desktop-test-harness`: the **"Dual-pane harness layout"** requirement currently pins the two-pane
  harness to `:app:desktop`. It changes to place the forge harness in **`:app:desktop:ui`** (run task
  `:app:desktop:ui:run`), with the shared `PhoneFrame` + `StatusPane` composition glue living in the
  parent `:app:desktop` library. Every other requirement and scenario (config toggle, display-override
  presets, leave/invite UI-only, creation-state overrides) carries over **unchanged** except its module
  home.

## Impact

- **New module:** `app/desktop/ui/` (`build.gradle.kts`, `src/main/kotlin/app/snapsync/desktop/`); add
  `include(":app:desktop:ui")` to `settings.gradle.kts`.
- **Moved code:** `Main.kt`, `ControlPanel.kt`, `PanelController.kt` out of `app/desktop/src/` into
  `app/desktop/ui/src/`.
- **Extracted (behavior-preserving):** the host-construction + `StatusScreen`-in-`PhoneFrame` block
  lifted from `Main.kt` into a new `StatusPane.kt` in the parent; `PhoneFrame.kt` stays in the parent.
- **Edited wiring:** `app/desktop/build.gradle.kts` (drop the `application` block + `material3`; keep it
  a library), new `app/desktop/ui/build.gradle.kts` (the application block, moved verbatim), and the
  child's slimmed `Main.kt` (left pane now calls the shared `StatusPane`).
- **Run-task rename:** `:app:desktop:run` → `:app:desktop:ui:run` is the sole invocation-surface change
  (the `Test UI` line in `CLAUDE.md` + `docs/design.md §5.1`).
- **Docs:** `CLAUDE.md` module table, `docs/design.md §5.1`.
- **Depends on:** nothing new (base branch already has Move A / Move B). Independent of change 2
  (`add-harness-world-model`).
- **Prerequisite for:** change 3 — the full-stack world harness that claims the freed `:app:desktop:run`.
- **Not in scope:** the full-stack world harness itself, any new UI state, any engine/seam wiring, the
  right-pane "world control" panel (all change 3).
