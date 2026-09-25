## Context

This is phase 11a of the thin-ports re-cut. The phases after it re-cut the port layer: one external system per port,
every decision in a core zone. Those phases need places to land, and today's tree lacks them in four ways:

- **Presentation and host sit outside the core's zone chain.** `:ui:presentation` depends on `:domain:model` and
  `:domain:feature` through `api()`. So every consumer (`:ui:screens`, `:test:control`, `:test:integration`) reaches all
  of `feature/` transitively. `:app:composition` exports compose and presentation the same way.
- **The claimed read-model restriction is not enforced.** architecture-guards "The zone gates" states that compilation
  confines presentation to model, feature read-model types and the command bundle. No gate implements that. With
  `api(:domain:feature)` every feature type resolves, and "read-model" has no mechanical definition. CLAUDE.md
  describes the same non-existent "presentation-imports gate".
- **Pure data sits in the wrong zones.** The data types ports carry sit in `ports/` beside the interfaces. `UiState` sits
  in presentation.
- **Target lists are repeated.** Every `:domain:*` and `:ui:*` module declares the same three targets by hand.

The whole change is a structural refactor. The shipped app's behaviour, every port signature, the adapters, Swift,
`api/` and the contract recordings stay as they are.

## Goals / Non-Goals

**Goals:**

- Make presentation (`:domain:presentation`) and the host (`:domain:host`) real core zones, depending only through
  `implementation()`, so the permitted-edge gate governs them like every other zone.
- Give "read-model" a mechanical definition, the `feature/<feature>/readmodel/` package, and enforce it with a gate.
- Put all pure data in `model/`: `UiState` and the pure-data types ports carry.
- Declare the allowed targets once, in a convention plugin.
- Make every law this phase touches true of the tree after it, and no law truer than the tree.

**Non-Goals:**

- No new or reshaped port, and no `:domain:services` (that is 11b).
- No re-homing of port-adjacent logic (resolveOrMint, requeueWhilePending, runProcessCycle, receipts, `PushTokenSource`,
  `Discovery`, `PlatformHandoff`, `PlatformUploadJob`, ConfigPorts' helpers, the `CycleResult` raw-value mapping).
- No rewiring of `:app:desktop` onto read-models (that is 11g). It gets only the import changes the moves force.
- No change to the forge beyond import paths (phase 12 deletes it).
- No `*Handlers` law text and no widened callback-slot gate. Those land with the first `*Handlers` class, and none
  exists in 11a.
- `:adapter:generic:fake` keeps its name (11g renames it).

## Decisions

### D1 — Presentation and host are core zones with `implementation()`-only edges

- **Edges.** `:domain:presentation` depends on model and feature. `:domain:host` depends on model, ports, feature,
  compose and presentation. Consumers declare what they use. `:ui:screens` gets presentation, model, feature, Orbit and
  datetime. `:test:control` and `:test:integration` get presentation, model and feature. `:test:world` gets the host
  plus whatever it names.
- **Rejected: withholding modules with `api()` exports, outside the zone chain.** That keeps `:test:control`'s
  read-model rule resting on what happens to be exported transitively. It also lets the host widen silently.
- **Placement and packages.** Both modules sit under `domain/`, the core's path grouping. The zone gates resolve a
  zone's sources as `domain/<zone>/src/commonMain/kotlin/app/snapsync/<zone>`. So the host's package becomes
  `app.snapsync.host`. Presentation's stays `app.snapsync.presentation`.
- **The host stays a shell.** It remains in `appShellSources` (`domain/host/src`) and in the `shell` detekt tier. It is
  wiring every root calls, and detektAppShell's threshold-2 proof is what keeps it decision-free.
- **Presentation's tier.** It stays in `core` (it is already there).
- **The host never imports services.** 11b's graph relies on that.

### D2 — "Read-model" is a package, held by a gate

- **The move.** A feature type consumed outside `feature/` moves to `feature/<feature>/readmodel/`. Verified against
  today's consumers, the set is:
  - `status/readmodel`: `SyncStatusSource`.
  - `creation/readmodel`: `CreationStatus`, `CreationFailureReason`, `CreationStatusSource`, `MutableCreationStatusSource`.
  - `membership/readmodel`: `RenameStatus`, `RenameFailureReason`, `RenameStatusSource`, `MutableRenameStatusSource`.
  - `download/readmodel`: `DownloadProgress`, `DownloadStatusSource`, `InMemoryDownloadStatusSource`.
  - `version/readmodel`: `VersionRefusal`. This was `AppVersionGate.Refusal`; it becomes top-level because a nested
    type cannot live in another package.
- **`EventCreator`/`NoOpEventCreator` go to `model/`, beside `UserCommands`.** They are a command, not a read-model.
  Presentation's tests and `:app:desktop` consume them.
- **`EventRenamer`, `ResetRename` and their no-ops stay in `feature/membership`.** They are the same kind of type, but
  nothing outside feature names them.
- **`StoreDownloadStatusSource` stays** in `feature/download`. It is an implementation over a port, not a read-model,
  and only `:app:desktop` names it.
- **The gate.** `ReadModelImportsTest` scans the main and test sources of `domain/presentation`, `domain/host`,
  `ui/screens`, `ui/components` and `test/control`. It fails on any `app.snapsync.feature.` reference whose path lacks
  a `.readmodel.` segment.
  - It reads code text with comments stripped (`ZoneGates.stripComments`), so KDoc links such as `[EventCreator]` do
    not count.
  - A twin asserts that the scan saw at least one `readmodel` reference.
  - `:app:desktop` is a named exemption until 11g.
- **Presentation's forge source sets fall inside the gate's scope.** Their one feature reference, `SyncStatusSource`,
  moves to `readmodel` anyway.
- **Why a text gate and not a module.** A module per read-model package would be one module per feature, and the
  module-set law rejects modules that withhold nothing but a package. This is exactly the "finer than a module" case the
  zone-gates requirement reserves for derived text gates.
- **Retiring the "presentation-imports gate".** Nothing is deleted in code: no such test exists. The spec text that
  claimed compile enforcement is corrected, and CLAUDE.md's module map stops naming it.

### D3 — Pure data into `model/`, by one rule

- **The rule.** A type moves if and only if it is a data or enum class, or a sealed class or interface, that references
  no port and carries no logic beyond its own members.
- **The list (by reading every declaration in `ports/`).** It is longer than the handoff's example list, which named
  only its first members.

  | From (`ports/`) | Types |
  |---|---|
  | `SecureStore.kt` | `SecureStoreRead`, `StoredProtection`, `SecureStoreResolution` |
  | `Handoff.kt` | `Handoff` |
  | `DownloadTransport.kt` | `TransferOutcome` |
  | `BackgroundTransfer.kt` | `CycleResult` (unchanged), `CreateResult` |
  | `DownloadStore.kt` | `DownloadState`, `DownloadCounts`, `AssetRef`, `PlannedResource`, `StagedResource`, `PendingDownload`, `ImportableAsset`, `UnconfirmedImport` |
  | `EventDirectory.kt` | `EventDetails`, **renamed `EventLookup`** |
  | `EventCreation.kt` | `CreateOutcome` |
  | `EventRename.kt` | `RenameOutcome` |
  | `JoinSeams.kt` | `JoinResult` |
  | `AttestSeams.kt` | `TokenOutcome` |
  | `DownloadSeams.kt` | `ImportResult` |
  | `ConfigPorts.kt` | `ConfigRead`, `ConfigFileRead`, `MembershipRead` |
  | `DeviceFilesSource.kt` | `StoredResource` |

- **`DownloadState` moves.** Its `isTerminal` getter is a member over its own values, not a port reference.
- **These stay in `ports/`:**
  - `UnionResource` and `UnionAsset` are plain classes, not data classes.
  - The exceptions (`DeviceListingShapeException`, `SecureStoreUnavailable`, `DeviceIdentityAbsent`) are not data
    types.
  - `DownloadTask` is an interface.
  - All the port-adjacent functions, among them `membershipAfterReload`, `configReadViaFile`, `configAfterReload`,
    `resolveOrMint`, `readExisting`, `needsMigration`, and `processingResultRawValue` over `CycleResult`. An extension
    function in `ports/` over a `model/` type is a legal edge.
- **The one rename.** Presentation's `EventDetails` comes to `model/` with `UiState`, so ports' `EventDetails` becomes
  `EventLookup`. No other moved name collides with a `model/` declaration; that was checked against every top-level
  type there.
- **Why the rule and not the handoff's short list.** A partial move would leave the law "a port's pure-data types are
  declared in `model/`" false on the day it is written.
- **The cost.** About 200 files change imports, including same-package users inside `ports/` that now need an import.
  The change is mechanical, and the compiler checks it.
- **`UiState` into `model/`.** The move carries:
  - `UiState.kt`: `UiState`, `Overlays`, `Layer`, `RenameState`, `JoinedSurface`, `PendingSwitch`, `EventDetails`,
    `JoinPhase`, `SyncHealth` and the public helpers `joinPhase`, `JoinPhase.details` and `JoinPhase.step`.
  - From `RangeForm.kt`: `RangeForm`, `ResolvedRange` and `ShareCount`. `ShareCount` has to move because
    `ResolvedRange` carries one; the handoff had placed it in presentation.

  These stay in presentation:
  - `internal fun Overlays.maskedFor` (now `OverlayMask.kt`): reduction logic, and `internal` to presentation.
  - `NO_CEILING_YEARS` and the `resolve*` helpers, along with `directionOf`, `nowWithinWindow` and
    `reconfigureForm`. The file keeping them is renamed `RangeResolution.kt`, since `RangeForm.kt` is now the
    model/ file.
- **Serialization and datetime.** `model/` already applies the serialization plugin and depends on kotlinx-datetime
  through `implementation`. `RangeForm` now puts `LocalDateTime` in `model/`'s public API, so every consumer that names
  it declares datetime itself. This follows the `implementation()`-only rule, and nothing is re-exported.

### D4 — The laws this phase adds, and the one it does not

- **Added, all true after 11a:**
  - A port's pure-data types live in `model/` ("Zones inside the core", "Ports are the I/O boundary named for the need").
  - Read-models are the `readmodel` packages.
  - The allowed targets and the plugin.
- **Not added: "ports never call ports".** The handoff listed it among the law texts true after 11a. It is not true of
  that tree:
  - `ports/`'s own `resolveOrMint` calls `SecureStore.read()`.
  - The HTTP adapters report through the `BackendVerdicts` port (`withCredentialInterceptor`).

  11a deliberately re-homes no logic. So the law lands with the phase that makes it true (11b's services for the
  former; 11c for the backend). Writing it now would repeat the exact failure the "presentation-imports gate" text is
  being corrected for: a law stated ahead of its enforcement.

### D5 — The targets convention plugin

- **What it is.** A `build-logic/` included build (`pluginManagement { includeBuild("build-logic") }`) with one plugin,
  `snapsync.targets`.
  - It applies to a project that already applies `org.jetbrains.kotlin.multiplatform`.
  - It sets `jvmToolchain` from the catalog and declares `jvm()`, `iosArm64()` and `iosSimulatorArm64()`.
- **Where it applies.** Every `:domain:*` and `:ui:*` module applies it and deletes its own target lines.
  - `:ui:screens` and `:ui:components` keep their `jvm { testRuns … }` block, which configures the target the plugin
    declared.
  - Adapters and `:app:*` do not use it yet.
- **KGP stays on one classloader.** The root build declares KGP `apply false` because separate KGP classloaders make the
  Apple targets' global build services fail to cast. To avoid a second copy, `build-logic` takes KGP as `compileOnly`,
  so the plugin runs against the KGP the root already loaded.
- **Rejected: a `buildSrc`.** An included build was the stated choice. It is also cache-friendlier: changing the plugin
  does not invalidate every build script.
- **Catalog access.** `build-logic` reads the version catalog through its own `settings.gradle.kts`
  (`versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }`). Inside the plugin, it reads
  the JDK version through the `VersionCatalogsExtension`.

### D6 — Gates and bookkeeping

- **ModuleSetTest.** `permitted` gains `"presentation" to {model, feature}` and `"host" to {model, ports, feature,
  compose, presentation}`. The build-file loop reads `domain/<zone>/build.gradle.kts`, so both new modules are covered
  as they stand.
- **ZoneGateSupport.** `zoneTokens` gains `presentation` and `host`, and its KDoc drops the `ui/presentation` scope
  note.
  - `domainMainFiles()` therefore scans both zones. I checked the three gates that use it against the moved files:
    PhotoKitAbiContainment, SelectionPolicyContainment and EventPhotoSetSource. None fires. The one near-hit, a
    `creationDate >= cutoff` in a `CutoffFormatter` KDoc, is a comment line, and that gate skips comments.
  - `zoneOf` resolves by the first zone-named segment, so feature-blindness is unaffected.
- **Other gates the handoff named.** CommandLaneTest, ScreensTakeNoSuspendSeamTest, MainLaneContainmentTest,
  CompositionSeamTest and the shell pins do not bind to either moved path. They are checked by running them, not
  edited. KotlinShellGuardTest derives its roots from `appShellSources`.
- **Root build.**
  - The kover crediting edge `":ui:presentation" to ":ui:screens"` becomes `":domain:presentation" to ":ui:screens"`.
  - `appShellSources` changes to `domain/host/src`.
  - `detektTierOf` rekeys both modules.
  - Presentation's kover rule names and its `projects.add` filter are renamed.
  - The `config/detekt/{core,_base}.yml` comments are updated.
- **CI.** `build.yml` changes to `:domain:presentation:jvmTest -Psnapsync.forge=true`, and the `ios.yml` comment is
  updated.
- **Generated and docs.** `architecture/` is regenerated: the zone graph now walks `domain/host`, which `app/` never
  was. The CLAUDE.md module map is updated.

### D7 — Model's coverage bound follows the code it now holds

- **What the move did.** `UiState` and `RangeForm` arrived in `model/` without their tests. Model's aggregate fell from
  above its floor to 59.6% of instructions (56.2% of branches). Those two files alone were 2,883 missed instructions.
- **Rejected: a crediting edge from presentation to model.** coverage-bounds "Coverage is measured over unit tests
  only" forbids crediting incidental coverage, and presentation's reduction tests are written for the reduction, not
  for `UiState`'s serializer.
- **Rejected: lowering model's floor.** No forcing proof exists: the missing coverage could be tested, and was.
- **Done instead.** `UiStateSerializationTest`, the one test written for these types, moved with them to model's
  `commonTest`. It had never round-tripped a `ResolvedRange`, a `ShareCount`, the reconfigure surface, a pending
  switch, a failed rename or `NotStarted`/`Syncing` health, all of which the control channel serves. It now does, along
  with each `RangeForm` field and each overlay off its default one at a time, and the `details`/`step`/`joinPhase`
  helpers. Model measures 90.7% of instructions and 81.6% of branches. Presentation's bounds still hold.

## Risks / Trade-offs

- **[KGP loaded twice through `build-logic`]** → `compileOnly` KGP in `build-logic`. The first full `./gradlew build`
  including iOS compilation (`compileIosMainKotlinMetadata`) proves it. If it still fails, the fallback is
  `buildscript` classpath alignment, and the plugin never moves KGP's version out of the catalog.
- **[The control-channel wire format changes]** `UiState` carries no `@SerialName`, so its polymorphic discriminators
  are fully qualified names and change prefix. The same-build client and host are unaffected, and no committed file
  holds those strings. The one exposure is `:app:desktop -Psnapsync.attach` pointed at a device that runs an older rig
  build: it fails to decode until that device is rebuilt. → Accepted, dev equipment only. Pinning `@SerialName`s now
  would freeze names 11g may want to change.
- **[The module-set gate reads the spec of record]** ModuleSetTest parses `openspec/specs/module-architecture/spec.md`,
  not this change's delta. So `./gradlew build` stays red between the settings rename and the spec sync. → The deltas
  are synced and the change archived in the same PR, before `/ship`. That is already this repo's practice.
- **[A large mechanical diff hides a real change]** → The only non-rename edits are the gates, the plugin and the
  build/spec text. Review can separate them by commit: plugin, then module moves, then type moves, then gates and
  specs.
- **[iOS linkage]** Swift names only `SnapSyncRoot`, `UploadExtensionRoot`, `MainViewControllerKt` and
  `ForgeViewControllerKt`, and no framework `export()`s a dependency. So no Swift-visible name changes. `CycleResult`
  crosses to Swift only as its raw `Int` (`processRawValue()`). → The iOS compile plus the Swift shell guard confirm it.
  No device session is needed.

## Migration Plan

This is a source-only refactor. Nothing is persisted under a moved name:

- No moved port type is `@Serializable`.
- SQLDelight stores enum names, which do not change.
- `UiState` crosses only the rig's wire.

Nothing is deployed besides the internal TestFlight build every merge produces. Rollback is `git revert` of the merge.

## Open Questions

- **An unenforced scenario, found and not fixed here.** module-architecture "Queries cross a lane-gated door" says that
  "the presentation gate fails" when a function-typed read is added to `StatusContainerHost`'s constructor outside
  `UserQueries`. No gate scans that constructor. After 11a, presentation cannot name a port type (no `ports/` edge),
  but a bare function type would still compile. This change renames that requirement's module path only. Whether to
  add a gate or reword the scenario is left to a later change.
- **11f's upload `CreateOutcome`.** Ports' event-creation `CreateOutcome` now lives in `model/`. The upload create
  result that 11f introduces must not take that simple name there (the design of record already calls it
  `UploadCreateOutcome`).

## Archive: delta completeness

Every module the diff touched, resolved to its capability, with its delta or the reason none is needed:

| Module(s) | Capability | Delta / reason |
|---|---|---|
| `domain/presentation` (was `ui/presentation`), `domain/host` (was `app/composition`), `domain/model`, `domain/ports`, `domain/feature`, `settings.gradle.kts`, `build-logic/`, `ui/screens`, `ui/components` (plugin only) | `module-architecture` | delta: the module set, the zones, allowed targets and the plugin, pure port data in `model/`, read-model packages, and the module paths in the remaining four requirements |
| `test/architecture` (`ModuleSetTest`, `ZoneGateSupport`, `ReadModelImportsTest`), root `build.gradle.kts` (`appShellSources`, tier map) | `architecture-guards` | delta: "The zone gates", "The shell gates" |
| `architecture/` (regenerated) | `architecture-diagrams` | delta: the module-path example; the regeneration itself is the requirement working |
| `domain/model` tests (`UiStateSerializationTest` moved in and extended), root kover edge rename, presentation's kover rule names | `coverage-bounds` | delta: the instrumented-module path. The test move changes no bound and adds no crediting edge (design D7) |
| `.github/workflows/build.yml` (forge test task path) | `testing-architecture` | delta: the forge test source set's path |
| `domain/compose`, `domain/flow` | `module-architecture` (compose/flow zones) | no delta: import paths only, behaviour-preserving |
| `adapter/generic/app`, `adapter/generic/fake`, `adapter/ios/ext-safe`, `adapter/ios/app-only` (incl. the `DownloadStore.sq` import) | `port-contracts` and each port's capability | no delta: import paths of the moved types only; no signature, behaviour or stored name changes (SQLDelight stores enum names) |
| `test/contracts` | `port-contracts` | no delta: import paths only |
| `test/world`, `test/rig`, `test/control`, `test/integration` | `harness-world-model`, `testing-architecture` | no delta: dependency declarations and import paths only; the wire discriminator prefix change is recorded in Risks |
| `app/ios`, `app/ios/extension`, `app/ios/forge` | `ios-app-shell` | delta for the host path ("iOS live composition root"); otherwise import paths only |
| `app/desktop` | `full-stack-harness`, `desktop-test-harness` | no delta: import paths only; exempt from the read-model gate until 11g |
| `config/detekt/{core,_base}.yml` | `complexity-budgets` | no delta: comments only, no ceiling changed |
| `.github/workflows/ios.yml` | — | comment only |
| `CLAUDE.md` | — | docs: the module map |

Gates run at archive: placeholder Purpose, none; dead types, one removed (`AppVersionGate.Refusal`, now
`VersionRefusal`), named by no spec.

## At merge: the laws moved to `docs/`, not to the specs

While this change waited to merge, `main` took in `spec-diet` (`changes/archive/2026-09-25-spec-diet`,
commit `c19b6c4b`): specs now define only what a user can observe, and the engineering laws moved to
`docs/architecture.md` and `docs/testing.md`. Seven of the eight capabilities this change carried deltas for
were retired (module-architecture, architecture-guards, architecture-diagrams, coverage-bounds,
gallery-status, ios-app-shell, testing-architecture), and `sync-status` was rewritten so that the
requirement this change modified no longer exists.

So the delta specs were never applied, and were removed from this record: this change has nothing a user can
observe, and under the new rule it earns no spec delta at all. What the deltas said was carried into the docs
instead:

- `docs/architecture.md`, the module set: `:domain:presentation` and `:domain:host` among the withholding
  modules; the host as the one module seeing both `compose/` and presentation; the integration surface's
  read-model rule (explicit dependencies plus `ReadModelImportsTest`).
- `docs/architecture.md`, the core and its zones: six zones and the host, their edges, pure port data in
  `model/`, read-model packages, and the allowed targets with the `snapsync.targets` plugin.
- `docs/architecture.md`, the laws: the read-model rule and its gate; the commands-door law now gated rather
  than review-only; the host among the shells; pure port data and the target list as review-held laws.
- `docs/architecture.md`, coverage, and `docs/testing.md`: the module paths.
- `ModuleSetTest` now holds the module set itself (`spec-diet` moved it there); its lists name the two new
  modules.

"Ports never call ports" stays out of the docs for the reason D4 gives: it is still not true of this tree.
