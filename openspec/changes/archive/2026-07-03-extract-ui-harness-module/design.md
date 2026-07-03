## Context

Change 1 of three that stand up a **second** desktop harness. The final, settled layout names each
harness by its Gradle run task:

- `:app:desktop:run`     → full-stack world harness (change 3)
- `:app:desktop:ui:run`  → today's forge harness, this change relocates it here

This change is **behavior-preserving**: it only moves where the forge harness lives and frees the
parent `run` task. The one spec delta exists because `desktop-test-harness`'s "Dual-pane harness
layout" requirement names `:app:desktop` as the harness home.

Verified current state (2026-07-03):

- `:app:desktop` is a **Compose application** (`kotlin.jvm` + `kotlin.compose` + `compose`), run task
  `:app:desktop:run`, `mainClass = "app.snapsync.desktop.MainKt"`, with an explicit toolchain
  `javaHome` and `--enable-native-access=ALL-UNNAMED`.
- Its four files all share `package app.snapsync.desktop`: `Main.kt` (entry + inline host wiring +
  clipboard/log `share` stub), `ControlPanel.kt` (raw Material 3, forge-only), `PanelController.kt`
  (the stand-in seams + cells, forge-only), `PhoneFrame.kt` (the ~390×844 bezel frame, shared).
- `StatusContainerHost` already lives in `:domain:presentation`; the only desktop-side "wiring" to
  share is the composition glue inline in `Main.kt` (construct the host from seams → collect state →
  render `StatusScreen` in `PhoneFrame`).
- The **parent-with-source-plus-child** Gradle shape is already used twice in this repo: `:app:ios`
  (own `build.gradle.kts` + `src/` + child `:photokit-extension`) and `:domain:ui` (own build + `src/`
  + child `:components`). No new Gradle idiom is required.
- `:capability:event-creation-ui` is not an explicit `:app:desktop` dependency today — `PanelController`
  reaches `app.snapsync.eventcreation.*` transitively (via `:domain:ui`/`:domain:presentation`).

## Goals / Non-Goals

**Goals:**
- Relocate the forge harness into `:app:desktop:ui` so its run task is `:app:desktop:ui:run`.
- Free `:app:desktop:run` for change 3 (parent becomes a plain library — no `application` block, so
  no run task exists yet).
- Keep the pieces change 3 reuses — `PhoneFrame` + the host-construction glue — in the parent.
- Zero behavior change: the diff is file moves + a verbatim glue extraction + Gradle re-shaping.

**Non-Goals:**
- No full-stack world harness (change 3), no right-pane world-control panel, no engine/seam wiring.
- No new or altered UI state, preset, or scenario.
- No package rename (keeping `app.snapsync.desktop` keeps `mainClass` and every import stable).

## Decisions

### Parent = Compose library, child = Compose application

`:app:desktop` drops its `application` block and becomes a **Compose library** holding `PhoneFrame.kt`
+ the new `StatusPane.kt`. With no `application` block, **`:app:desktop:run` does not exist** — it is
free for change 3 to define. `:app:desktop:ui` applies the Compose plugins **and** the
`compose.desktop.application` block (moved verbatim from the old parent: same `mainClass`, `javaHome`
toolchain launcher, `--enable-native-access` arg), so `:app:desktop:ui:run` is the forge harness.

```
:app:desktop            (library)          :app:desktop:ui        (application)
  PhoneFrame.kt   ── shared ──┐              Main.kt          (MainKt: PanelController +
  StatusPane.kt   ── shared ──┤                              StatusPane left / ControlPanel right)
    (construct StatusContainerHost,          ControlPanel.kt  (raw M3, forge-only)
     render StatusScreen in PhoneFrame)      PanelController.kt (stand-in seams + cells)
  no application block → no run task         compose.desktop.application → :app:desktop:ui:run
```

### `StatusPane` extraction is the one refactor, and it is verbatim

Both harnesses build a `StatusContainerHost` from a set of seams and render its `StatusScreen` inside
`PhoneFrame`; only the **right pane** and the **source of the seams** differ (forge cells now vs the
real stack in change 3). So the left-pane glue currently inline in `Main.kt` is lifted, unchanged,
into `StatusPane.kt` in the parent — a composable that takes the seams (`SyncStatusSource`,
`PermissionStatusSource`, `PermissionRequester`, `ConfigSource`, `ConfigStore`, `CreationStatusSource`,
`EventCreator`, download source), the `share: (String) -> Unit` lambda, and a `CoroutineScope`, and
renders the left pane. The forge's `share` stub (clipboard + `println`, using `java.awt`) stays in the
**child** and is passed **into** `StatusPane` as the lambda — so the parent needs no `java.awt` and no
harness-specific behavior. This is the only edit beyond a pure move; it changes no runtime behavior.

### Keep the package `app.snapsync.desktop` across both modules

The moved files and the extracted `StatusPane` all keep `package app.snapsync.desktop`. Benefits:
`mainClass` is unchanged, and there is zero import churn. The two Gradle modules then share one package
across the child→parent dependency edge — benign on the JVM (no JPMS `module-info` in this repo, so no
split-package enforcement). Rejected alternative: renaming the child to `app.snapsync.desktop.ui` would
force a `mainClass` change and import edits for no behavioral gain, working against the
"behavior-preserving" goal.

### Dependency split

- **Parent `:app:desktop` (library):** `:domain:ui`, `:domain:presentation`, `:domain:status`,
  `:domain:permission`, `:capability:config`, `compose.runtime`, `compose.foundation` — what
  `PhoneFrame` + `StatusPane` need. (Add `:capability:event-creation-ui` explicitly if
  `StatusContainerHost`'s creation-seam params no longer resolve transitively.) **No** `material3`,
  **no** `compose.desktop.application`.
- **Child `:app:desktop:ui` (application):** `project(":app:desktop")` (shared `PhoneFrame`/`StatusPane`),
  `:domain:permission`, `:domain:status`, `:capability:config`, `:capability:event-creation-ui`
  (the modules `PanelController` constructs cells from), `compose.material3` (raw `ControlPanel`),
  `compose.desktop.currentOs` (app entry + the clipboard share stub).

## Risks / Trade-offs

- **Split-package across two modules** — accepted (no JPMS here); documented above so it is a
  deliberate choice, not an accident.
- **Verifying "behavior-preserving"** — the harness has no unit tests (it is test equipment). The
  safety net is: `./gradlew :app:desktop:ui:run` launches an identical two-pane window, and
  `./gradlew build` + `compileIosMainKotlinMetadata` stay green. A diff review confirms `StatusPane`
  is a verbatim lift.
