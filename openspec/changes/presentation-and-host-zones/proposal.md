## Why

The port layer is being re-cut into thin ports: one external system each, platform-neutral, with every decision in a
core zone (phases 11a–11i, then 12). This lets the app be tested honestly against mocks, and lets a later Android build
be adapters only. The later phases need places to land that do not exist yet:

- Presentation and the host composition sit outside the core's zone chain. `:ui:presentation` reaches the whole of
  `feature/` through `api()`.
- `UiState` lives in a module that tests have to take whole.
- The data types a port carries sit beside the port interfaces.
- Nothing mechanical says what a "read-model" is. The architecture-guards "The zone gates" requirement claims compilation
  already confines presentation to read-model types. It does not: no gate implements that claim, and presentation's
  `api(:domain:feature)` edge resolves every feature type.

This phase only restructures. It moves no behaviour and adds no port, so every later phase starts from a tree whose
laws are already true.

## What Changes

- **Two new core zones.**
  - `:ui:presentation` becomes `:domain:presentation` (`domain/presentation`, package unchanged, `app.snapsync.presentation`).
  - `:app:composition` becomes `:domain:host` (`domain/host`, package `app.snapsync.host`).
  - Both depend only through `implementation()`: presentation on model and feature; the host on model, ports, feature,
    compose and presentation.
  - Consumers that used to receive model, feature, Orbit or datetime transitively now declare them.
- **`UiState` into model/.**
  - Moves to `app.snapsync.model`: `UiState.kt` with its nested types (`JoinPhase`, `EventDetails`, `Overlays`, `Layer`,
    …), plus `RangeForm` and `ResolvedRange`.
  - Stays in presentation: the reduction logic, including `resolve*`, `Overlays.maskedFor` and `ShareCount`.
- **Pure port data into model/.** A type moves if and only if it is a data or enum class, or a sealed class or
  interface, with no port or logic reference.
  - Ports' `EventDetails` is renamed `EventLookup`, because presentation's `EventDetails` now lives in model/.
  - Port-adjacent logic stays in ports/ until a later phase re-homes it.
- **Read-model packages.**
  - Feature types consumed outside feature move to `feature/<feature>/readmodel/`.
  - `AppVersionGate.Refusal` becomes the top-level `VersionRefusal` in `feature/version/readmodel`.
  - `EventCreator` and `NoOpEventCreator` are a command, not a read-model, so they move to model/ beside `UserCommands`.
- **A targets convention plugin.** A `build-logic/` included build carries one plugin declaring the allowed targets
  (jvm, iosArm64, iosSimulatorArm64). It is applied to every `:domain:*` and `:ui:*` module, whose own target lists are
  deleted.
- **Gates.**
  - ModuleSetTest's permitted zone edges gain presentation and host.
  - `zoneTokens` and `domainMainFiles()` gain both zones.
  - A new `ReadModelImportsTest`, with a non-vacuity twin, holds presentation, the host, `:ui:*` and `:test:control` to
    `*.readmodel.*` imports from feature. `:app:desktop` is exempt until 11g.
  - The spec's "presentation-imports gate" is retired from the text. It was never implemented; the new gate is what now
    enforces its claim.
- **Build bookkeeping.**
  - The root build's kover crediting edge, shell roots (`appShellSources` → `domain/host/src`) and detekt tier map.
  - The detekt config comments.
  - `build.yml`'s forge test task path.
  - The regenerated `architecture/` diagrams and the CLAUDE.md module map.

No runtime behaviour, port signature, adapter behaviour, Swift source, `api/` file or contract recording changes. The
only observable difference is outside the shipped app: `UiState` carries no `@SerialName`, so the control channel's
wire discriminators change package prefix (`app.snapsync.presentation.*` → `app.snapsync.model.*`). Client and host are
built from one tree, and no committed file depends on those strings.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `module-architecture`: the module set, with presentation and the host as core zones; the zones with their edges, the
  allowed targets and the targets plugin; where pure port data lives; read-model packages; module paths in "One shared
  composition", "Commands cross one door", "Ports are the I/O boundary named for the need" and "Queries cross a
  lane-gated door".
- `architecture-guards`: "The zone gates" (the six zones plus host, the read-model import gate, and the presentation
  claim corrected from compile-enforced to gate-enforced); "The shell gates" (the host's shell root).
- `architecture-diagrams`: "The module graph counts architectural dependencies only" (its module-path example).
- `coverage-bounds`: "Coverage is measured over unit tests only" (the presentation module's path).
- `gallery-status`: "Module placement keeps the seam off presentation" (module path).
- `sync-status`: "Module placement plugs the engine leak" (module path; the retired presentation-imports gate).
- `ios-app-shell`: "iOS live composition root" (the host module's path).
- `testing-architecture`: "Build-property-gated source sets are compiled and run by CI, tests included" (the forge test
  task's path).

## Impact

- **Modules**:
  - Renamed: `:ui:presentation`, `:app:composition`.
  - Rewired: `:ui:screens`, `:ui:components` (plugin only), `:app:desktop`, `:app:ios`, `:app:ios:forge`, `:test:world`,
    `:test:rig`, `:test:control`, `:test:integration` (incl. `journeys`), `:test:architecture`.
  - Import changes: `:domain:ports`, `:domain:feature`, `:domain:flow`, `:domain:compose`, and every adapter and test
    module that imports a moved type (about 120 files).
- **Build**: a new `build-logic/` included build; `settings.gradle.kts`; the root `build.gradle.kts`;
  `config/detekt/{core,_base}.yml` comments; `.github/workflows/build.yml` (task path) and the `ios.yml` comment.
- **iOS linkage**: none. Swift names only `SnapSyncRoot`, `UploadExtensionRoot` and the two view-controller entry
  points, and no framework `export()` is declared, so no Swift-visible name changes. `CycleResult` crosses to Swift only
  as its raw `Int`.
- **Persistence and deployment**: none. No moved type is persisted, and none of the moved port types is
  `@Serializable`. SQLDelight stores enum names, which do not change. Rollback is a revert.
- **Label**: `internal`.
