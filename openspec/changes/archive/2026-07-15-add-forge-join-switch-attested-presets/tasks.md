## 1. Presentation seam (`:domain:presentation`)

- [x] 1.1 Make `PendingJoin` public (currently `private` in `StatusContainerHost.kt`); keep it in `:domain:presentation`.
- [x] 1.2 Add `MutablePendingJoinSource` (a `StateFlow<PendingJoin?>` + `set(PendingJoin?)`), mirroring `MutableAttestedSource`.
- [x] 1.3 Add a `pending` constructor param to `StatusContainerHost` typed `MutablePendingJoinSource`, defaulting to a fresh internal instance; replace the private `pending` field with it.
- [x] 1.4 Rewire the ~12 read/write sites: `pending.value` reads → `pendingSource.state.value` (incl. the `combine` input and the first-frame `reduceFrom` seed); `pending.value = …` writes → `pendingSource.set(…)`. No control-flow change.
- [x] 1.5 Run `:domain:presentation` `commonTest` (`StatusContainerHostTest`) — behavior must be unchanged. (Green under `./gradlew build`.)

## 2. Harness wiring (`:app:desktop`)

- [x] 2.1 Add `attestedSource: AttestedSource = AlwaysAttested` and a `pending: MutablePendingJoinSource = MutablePendingJoinSource()` param to `StatusPane`; forward both to the `StatusContainerHost` constructor.
- [x] 2.2 Confirm the full-stack harness (`FullStackHarness`/`WorldInspectorController`) call site compiles unchanged (defaults preserve current behavior).

## 3. Forge cells + presets (`:app:desktop:ui`)

- [x] 3.1 `PanelController`: add a `MutablePendingJoinSource` cell and a `MutableAttestedSource` cell; expose both sources; pass them into `StatusPane` from `Main.kt`.
- [x] 3.2 Add join-gate preset methods (config absent + set the phase) for all 7 phases with canned `name`/`startsAt` payloads: `Loading, ExplainAccess, Ready, NotFound, LoadFailed, Committing, CommitFailed`.
- [x] 3.3 Add switch-confirmation preset methods (config present + granted + settled sync + set the phase) for the 4 dialog phases: `Ready, NotFound, LoadFailed, CommitFailed`; use a canned new-event name distinct from the current config's name.
- [x] 3.4 Add an `showUnattested()` preset (config present + granted + attestation cell false).
- [x] 3.5 Make every other precondition-forcing preset (sync presets, not-started, join, switch) also force the attestation cell to attested, so `Unattested` can't stick and mask a later preset.

## 4. Control panel UI (`:app:desktop:ui`)

- [x] 4.1 `ControlPanel`: add a "Join event (config absent)" button group (7 buttons).
- [x] 4.2 Add a "Switch confirmation (joined)" button group (4 buttons).
- [x] 4.3 Add an "Attestation" group with the "Unattested" button.
- [x] 4.4 (discovered) Add `implementation(project(":domain:presentation"))` to `:app:desktop:ui` — it constructs the new cells directly and only had `:app:desktop` as an `implementation` (non-`api`) dep.

## 5. Spec + verification

- [x] 5.1 `npx --yes @fission-ai/openspec@1.5.0 validate add-forge-join-switch-attested-presets --strict`. (Valid.)
- [x] 5.2 `./gradlew build` (all targets + JVM/offscreen UI tests) and `./gradlew compileIosMainKotlinMetadata` (iOS proxy) both green. (BUILD SUCCESSFUL.)
- [x] 5.3 Preset→reducer mapping verified: harness launches and composes the new panel without a crash (foreground run blocked on the window until timeout, no exception); each preset's cell writes map to the intended `UiState` by construction (`CANNED_CONFIG.startsAt` confirmed past, so switch→`InSync`/unattested→`Unattested`), and the reduction itself is covered by the passing tests. Pixel-level eyeball of each of the 12 screens is a manual click-through the operator can run.
