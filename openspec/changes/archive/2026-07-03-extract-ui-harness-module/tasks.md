# Tasks — extract-ui-harness-module

Behavior-preserving relocation of the forge harness into `:app:desktop:ui`, freeing `:app:desktop:run`
for change 3. No new behavior. The launched harness must look and behave identically before and after.

## 1. Add the `:app:desktop:ui` child module

- [x] 1.1 Register `include(":app:desktop:ui")` in `settings.gradle.kts`
- [x] 1.2 Add `app/desktop/ui/build.gradle.kts`: plugins `kotlin.jvm` + `kotlin.compose` + `compose`; `jvmToolchain(libs.versions.jdk.get())`; the `compose.desktop.application` block moved **verbatim** from the old parent (`mainClass = "app.snapsync.desktop.MainKt"`, toolchain `javaHome`, `--enable-native-access=ALL-UNNAMED`)
- [x] 1.3 Child deps: `implementation(project(":app:desktop"))`, `:domain:permission`, `:domain:status`, `:capability:config`, `:capability:event-creation-ui`, `compose.material3`, `compose.desktop.currentOs`

## 2. Re-shape `:app:desktop` into a shared library

- [x] 2.1 In `app/desktop/build.gradle.kts` remove the `compose.desktop.application` block and the toolchain-launcher `javaHome` plumbing (no `application` block ⇒ no `:app:desktop:run` task — freed for change 3)
- [x] 2.2 Drop `implementation(compose.material3)` (only the forge `ControlPanel` uses M3); keep/settle the library deps: `:domain:ui`, `:domain:presentation`, `:domain:status`, `:domain:permission`, `:capability:config`, `:capability:event-creation-ui`, `compose.runtime`, `compose.foundation`
- [x] 2.3 `PhoneFrame.kt` stays in `:app:desktop` unchanged

## 3. Extract the shared `StatusPane` (verbatim host wiring)

- [x] 3.1 Add `app/desktop/src/main/kotlin/app/snapsync/desktop/StatusPane.kt` (package `app.snapsync.desktop`): a `@Composable` lifting the `StatusContainerHost` construction + state collection + `StatusScreen`-inside-`PhoneFrame` render out of today's `Main.kt`, taking the seams (`SyncStatusSource`, `PermissionStatusSource`, `PermissionRequester`, `ConfigSource`, `ConfigStore`, `CreationStatusSource`, `EventCreator`, download source), the `share: (String) -> Unit` lambda, and a `CoroutineScope` as params
- [x] 3.2 Confirm the lift is verbatim — no logic delta, same `StatusScreen` args (leave no-op default, share stub passed in, invite URL from the host, download counts)

## 4. Move the forge-only files into `:app:desktop:ui`

- [x] 4.1 `git mv` `ControlPanel.kt` and `PanelController.kt` from `app/desktop/src/` to `app/desktop/ui/src/main/kotlin/app/snapsync/desktop/` (package unchanged)
- [x] 4.2 `git mv` `Main.kt` into the child and slim it: `main()`/`application {}` builds `PanelController`, renders the left pane via the shared `StatusPane` (passing the controller's seams + the clipboard/log `share` stub + scope) and the right pane via `ControlPanel(controller)`
- [x] 4.3 Confirm the child's clipboard share stub (`java.awt` + `println`) is the lambda passed into `StatusPane` — the `java.awt` import lives only in the child

## 5. Build & verify (no behavior change)

- [x] 5.1 `./gradlew :app:desktop:build` (library compiles) and `./gradlew :app:desktop:ui:build` green
- [x] 5.2 `./gradlew build` green; confirmed `:app:desktop:run` no longer exists and `:app:desktop:ui:run` does (`./gradlew :app:desktop:ui:tasks --all` lists `run`; `:app:desktop:tasks --all` lists none)
- [x] 5.3 `./gradlew compileIosMainKotlinMetadata` green (unaffected, but confirms no accidental commonMain breakage)
- [~] 5.4 GUI launch (`./gradlew :app:desktop:ui:run`) opens a real window and **needs a display** (per CLAUDE.md) — not runnable in this headless environment. Behavior-preservation rests on the verbatim `StatusPane` lift (5.5) + the green compile; the run task exists and is correctly named
- [x] 5.5 Diff review: the change is module add + file moves + a verbatim `StatusPane` lift + Gradle re-shape — no logic delta

## 6. Docs

- [x] 6.1 Updated the `CLAUDE.md` module table: split the `:app:desktop` row into the parent (shared `PhoneFrame` + `StatusPane` library) and a new `:app:desktop:ui` row (the forge harness); updated the `Test UI` section + the build-note to `:app:desktop:ui:run`
- [x] 6.2 Updated `docs/design.md §5.1` (and the design module table) for the run-task rename (`:app:desktop:run` → `:app:desktop:ui:run`) and noted the parent `run` is reserved for the full-stack harness
- [x] 6.3 `npx --yes openspec validate extract-ui-harness-module --strict` → "Change 'extract-ui-harness-module' is valid" (openspec 1.4.1 via npx)
