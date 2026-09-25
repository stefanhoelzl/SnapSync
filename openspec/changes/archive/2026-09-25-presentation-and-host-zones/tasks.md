## 1. Targets convention plugin (commit 1)

- [x] 1.1 Create the `build-logic/` included build: its own `settings.gradle.kts` (the version catalog from
      `../gradle/libs.versions.toml`), and a `build.gradle.kts` with `kotlin-dsl` and KGP as `compileOnly`.
- [x] 1.2 Write the `snapsync.targets` plugin. It requires `org.jetbrains.kotlin.multiplatform`, sets `jvmToolchain`
      from the catalog, and declares `jvm()`, `iosArm64()` and `iosSimulatorArm64()`, with a comment naming the
      "Zones inside the core" allowed-targets law.
- [x] 1.3 Register the included build in `settings.gradle.kts` (`pluginManagement { includeBuild("build-logic") }`).
- [x] 1.4 Apply the plugin in every `:domain:*` and `:ui:*` module. Delete their `jvmToolchain` and target lines, but
      keep `:ui:screens`' and `:ui:components`' `jvm { testRuns … }` configuration.
- [x] 1.5 Verify: `./gradlew build` and `./gradlew compileIosMainKotlinMetadata` are green, with no KGP classloader
      error.

## 2. Move the two modules (commit 2)

- [x] 2.1 `git mv ui/presentation domain/presentation`. Change `settings.gradle.kts` to `:domain:presentation`. Rename
      the kover filter and rule names in its build file.
- [x] 2.2 `git mv app/composition domain/host`, re-package `app.snapsync.composition` → `app.snapsync.host`
      (directory `app/snapsync/host`), and change `settings.gradle.kts` to `:domain:host`.
- [x] 2.3 Presentation's edges become `implementation()` only: model, feature, Orbit, datetime, coroutines,
      serialization-json.
- [x] 2.4 The host's edges become `implementation()` only: model, ports, feature, compose, presentation, Kermit,
      coroutines.
- [x] 2.5 Root `build.gradle.kts`:
      - the kover crediting edge becomes `:domain:presentation` → `:ui:screens`;
      - `appShellSources` changes `app/composition/src` → `domain/host/src`;
      - `detektTierOf` rekeys `:domain:presentation` (core) and `:domain:host` (shell), with comments updated.
- [x] 2.6 Update the `config/detekt/core.yml` and `_base.yml` comments. Change `.github/workflows/build.yml` to
      `:domain:presentation:jvmTest`, and update the `ios.yml` comment.

## 3. UiState and pure port data into model/ (commit 3)

- [x] 3.1 Move `UiState.kt`'s data types and public helpers, plus `RangeForm`/`ResolvedRange`, to `app.snapsync.model`
      (design D3), with `ShareCount`. Keep `Overlays.maskedFor` and the `resolve*` helpers in presentation.
- [x] 3.2 Rename ports' `EventDetails` → `EventLookup` (its adapters, fakes, mini-edge, contracts and feature users).
- [x] 3.3 Move the port pure-data types listed in design D3 from `app.snapsync.ports` to `app.snapsync.model`. Leave
      the port-adjacent functions in `ports/` and add imports there.
- [x] 3.4 Fix imports across `:domain:*`, the adapters, `:adapter:generic:fake`, `:test:*`, `:app:*` and
      `:tools:diagrams` where needed.
- [x] 3.5 Verify that no moved type retained logic or a port reference, by re-reading each moved file against the rule.

## 4. Read-model packages (commit 4)

- [x] 4.1 Move the read-model set in design D2 into `feature/<feature>/readmodel/`. Split files where a file mixes
      read-models with commands (`RenameStatus.kt` keeps `EventRenamer`/`ResetRename` and their no-ops in
      `feature/membership`).
- [x] 4.2 Extract `AppVersionGate.Refusal` → top-level `VersionRefusal` in `feature/version/readmodel` and update
      `AppVersionGate`.
- [x] 4.3 Move `EventCreator`/`NoOpEventCreator` to `model/` beside `UserCommands`.
- [x] 4.4 Update importers in presentation (main, test, forge, forgeTest), `:ui:screens`, the host, `:domain:compose`,
      `:app:desktop`, `:test:world` and `:test:rig`.

## 5. Rewire consumers (same commit as 2–4 as needed to stay compiling)

- [x] 5.1 `:ui:screens`: explicit `implementation` deps on presentation, model, feature, Orbit, datetime.
- [x] 5.2 `:test:control`: explicit deps on presentation, model, feature, for the integration surface too.
      `:test:integration` (incl. `journeys`): declare what it names explicitly.
- [x] 5.3 `:test:world` becomes the host; `:test:rig`, `:app:desktop`, `:app:ios` and `:app:ios:forge` get the new
      module paths and any dependency previously received transitively.
- [x] 5.4 Verify: `./gradlew build`; `./gradlew compileIosMainKotlinMetadata`;
      `./gradlew :domain:presentation:jvmTest -Psnapsync.forge=true`; a `-Psnapsync.rig=true` compile of the rig
      source sets (`compileKotlinIosSimulatorArm64` on `:app:ios`, `:adapter:ios:*` and `:test:rig`).

## 6. Gates (commit 5)

- [x] 6.1 ModuleSetTest `permitted`: add `presentation` → {model, feature} and `host` → {model, ports, feature,
      compose, presentation}.
- [x] 6.2 ZoneGateSupport: add `presentation` and `host` to `zoneTokens`, and rewrite the KDoc's `ui/presentation`
      scope note.
- [x] 6.3 Add `ReadModelImportsTest`:
      - it scans main and test sources of `domain/presentation`, `domain/host`, `ui/screens`, `ui/components` and
        `test/control`;
      - it strips comments and fails on an `app.snapsync.feature.` reference without a `.readmodel.` segment;
      - it names the `:app:desktop` exemption with its 11g end;
      - a non-vacuity twin requires at least one `readmodel` reference.
- [x] 6.4 Prove the gate fires: temporarily add a non-read-model feature import to a presentation file, see it fail,
      then revert.
- [x] 6.5 Run the whole `:test:architecture` suite. Fix any path-bound test that fires, and do not edit the ones that
      pass.

## 7. Specs, diagrams, docs (commit 6)

- [x] 7.1 `./gradlew architectureDiagrams` and commit `architecture/`.
- [x] 7.2 Update the CLAUDE.md module map:
      - the `:domain:presentation`/`:domain:host` entries;
      - the read-model packages;
      - pure port data in model/;
      - `build-logic/` in the repo layout;
      - drop the "presentation-imports gate" wording.
      No law digest.
- [x] 7.3 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` and `… validate presentation-and-host-zones
      --strict`.

## 8. Final verification and archive (only when asked)

- [x] 8.1 Full `./gradlew build`, `compileIosMainKotlinMetadata`, the forge `jvmTest`, the rig compile, all green.
      This comes after the spec sync, because ModuleSetTest reads the spec of record.
- [x] 8.2 Sync the deltas and archive the change in this PR (user's go-ahead). Then run the three archive gates in
      `openspec/config.yaml`. The dead-types gate must account for `EventDetails` (ports; renamed) and
      `AppVersionGate.Refusal`.
