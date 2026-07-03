# Tasks: add-full-stack-harness

## 1. Gradle — claim `:app:desktop:run`

- [x] 1.1 Add a `compose.desktop.application` block to `app/desktop/build.gradle.kts` mirroring
      `:app:desktop:ui` (toolchain `javaHome` from `javaToolchains.launcherFor`, Skiko
      `--enable-native-access=ALL-UNNAMED`, `-Dsun.java2d.uiScale=$uiScale` with the `-PuiScale`
      escape hatch), `mainClass = "app.snapsync.desktop.FullStackHarnessKt"`.
- [x] 1.2 Add deps: `project(":test:world")`, `project(":capability:download")`, `compose.material3`,
      `compose.desktop.currentOs`. Keep `compose.runtime`/`compose.foundation`; leave the existing
      `:domain:*` / `:capability:config` / `:capability:event-creation-ui` edges.
- [x] 1.3 Verify `./gradlew :app:desktop:ui:run` still resolves (no `MainKt` collision — the
      full-stack entry file compiles to `FullStackHarnessKt`, distinct from the forge's `MainKt`) and
      `./gradlew build` stays green.
- [x] 1.4 Confirm the application block minted a real `:app:desktop:run` JavaExec — `./gradlew
      :app:desktop:run --dry-run` now runs the application run task, **not** the Compose Hot Reload
      `runHot`/`hotRun` chain it abbreviation-matched before the block existed (see design §1).

## 2. Composition root (`FullStackHarness.kt` → `main()`)

- [x] 2.1 Construct one `World`; render `StatusPane` (left) + `WorldInspector` (right) in a `Row`,
      as the forge's `Main.kt` does, but feed `StatusPane` the world's REAL seams:
      `world.syncStatusSource(scope)`, `world.permission`, `world.configSource`,
      `StoreDownloadStatusSource(world.downloadStore)`, `world.creationStatus`,
      `world.createEvent(scope)`.
- [x] 2.2 Supply the inspector `PermissionRequester` (flips `world.permission` to the armed outcome;
      `openSettings()` logs), the `ConfigStore` adapter (`save` → `world.provision(eventId)`;
      `clear` → leave), and the clipboard `share` stub (copy + `println`, as the forge).
- [x] 2.3 Hold the world in a Compose `MutableState<World>` + a generation counter; wrap the
      `StatusPane` call in `key(generation) { … }` so a preset (fresh world) rebuilds its
      `StatusContainerHost` against the new sources.
- [x] 2.4 **`:test:world`:** add `suspend fun World.leave()` — real `downloadController.onLeaveOrSwitch()`
      + `configCell.value = null` + `marker.clear()`, retaining gallery/store/ledger/imported photos.
      Covered by the `harness-world-model` spec delta; add a `:test:world` self-test (leave keeps an
      imported asset + re-provision still suppresses it).
- [x] 2.5 **`:app:desktop` `StatusPane`:** add an optional `leave: () -> Unit = {}` param passed into
      `StatusContainerHost`; the full-stack `main()` passes `leave = { scope.launch { world.leave() } }`.
      Default no-op keeps the forge's Leave button inert (`:app:desktop:ui` passes nothing — unchanged).
      Wire the `ConfigStore` adapter's `clear()` to `world.leave()` too.

## 3. `WorldInspectorController` (single mutation path — test equipment, no tests)

- [x] 3.1 One named method per control per the design's mapping table (Invoke extension, Expire
      token, permission set, armed request, Re-provision, Leave, add/remove asset, inject device,
      complete/fail job, job-limit, stage, offline, import-failure). No inline mutation in composables.
- [x] 3.2 **Invoke extension** = one OS invocation: `world.runUploadCycle()` then
      `world.downloadController.reconcile(joinedEventId)`.
- [x] 3.3 End **every** mutating method with `world.completed.refresh()` + `world.inFlight.refresh()`
      + the download source `refresh()` so the real projection re-emits (permission needs none).
- [x] 3.4 Presets construct a fresh `World`, apply the setup script (Clean / Enrolled / Fresh join /
      Re-provision-dedup / Foreign download per the design), and bump the generation.

## 4. `WorldInspector` composables (raw Material 3, never `App*`)

- [x] 4.1 Presets row + primary **Invoke extension** + **Expire change token**.
- [x] 4.2 **Enrollment**: 3-state permission segment, armed-next-request control, joined-event-id
      readout, Re-provision / Create event / Leave (Leave → `world.leave()`, same edge as the
      phone-frame button).
- [x] 4.3 **Gallery ▏ Backend** two-up: Gallery editable own-asset rows (add/remove; imported rows
      badged upload-suppressed via `suppressedLocalIds()`); Backend objects grouped by device
      (`world.store.objectsOf`) with "+ Inject device" (one-click canned: `foreign-$n` device + one
      `World.foreignAsset("foreign-$n-a1")` into the joined event via a monotonic counter; disabled
      when no event is joined).
- [x] 4.4 **Upload jobs ▏ Downloads** two-up: upload queue rows (pending/retry) with per-job Complete
      / Fail(`UploadError` picker: Network/Http/Cancelled/Unknown) + job-limit indicator; Downloads
      pending foreign resources (`world.downloadJobs.pending()`) with a Stage action (no
      `DownloadError` picker — a non-staged download stays PENDING).
- [x] 4.5 **Failure levers** 2-up: Backend-offline toggle, Job-limit control, Import-failure arm.
- [x] 4.6 Two-column paired-section layout filling the width to cut scroll (still vertically
      scrollable, `FlowRow` groups, per the forge's pattern).
- [x] 4.7 **Engine console footer**: install a Kermit `LogWriter` in `main()`
      (`Logger.setLogWriters`) funnelling the stack's log lines into a bounded (~200-line) ring
      `MutableStateFlow<List<String>>`; render a scrollable footer auto-scrolled to the tail with a
      **Clear** action. The controller appends an explicit `CycleResult` line at the end of
      `invokeExtension()`. No change to `:test:world`/production (pure read of existing Kermit output).

## 5. Verify & document

- [x] 5.1 `./gradlew :app:desktop:run` opens the dual pane; walk each preset and confirm the LEFT
      pane counts move only from real world state: add asset → Invoke → Complete job → Invoke →
      status advances toward Complete; Foreign download → Invoke → Stage → imported asset badged
      suppressed and not re-uploaded; Backend-offline keeps last-good counts; **Leave** keeps the
      imported photo in the gallery and, on re-provision, still suppresses it (faithful edge).
- [x] 5.2 Point the `CLAUDE.md` `:app:desktop` module-table row at the now-live `:app:desktop:run`
      full-stack harness (and note `:app:desktop:ui:run` remains the forge harness).
- [x] 5.3 `npx --yes @fission-ai/openspec@1.4.1 validate --specs --strict` (and the change) passes.
