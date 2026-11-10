# SnapSync architecture

How the app and the backend are put together, which rules hold them in shape, what enforces each rule and why it
exists. This is an engineering doc, not a contract: what a user can observe is specified in `openspec/specs/`, and the
reasons behind a *behaviour* live in its decision record (`openspec/changes/archive/<id>`). The reasons behind an
*engineering* rule live here, beside the rule (§2), and in `docs/patterns.md`, which is how to write code in the
layers. Testing lives in `docs/testing.md`; build, deploy and release in `docs/deployment.md`.

**The gates are the authority, and this doc explains them.** Almost every rule below is a check that fails
`./gradlew build` (or `deno task lint` / `deno task test` for `api/`). Where the doc and a gate disagree, the gate
wins: fix the doc. A rule marked **review** has no gate, and only a reviewer who knows it can hold it.

**The standing principles.** These decide every choice the rules leave open:

- **A clean architecture is the goal**; behaving the same for a user is a constraint a change respects, never the goal.
  A compromise is not kept because it is behaviour-neutral.
- **It is kept clean continuously.** Propose, prefer and recommend the architecturally clean solution — never the
  smaller diff because it is smaller. When the clean one costs more, say so and recommend it anyway.
- **No dead production code.** What a change leaves unused is deleted in that change. A use only by a test, the rig or
  a mock keeps nothing alive.
- **Build logic lives in `build-logic/`**: a convention plugin, task, detekt or coverage wiring goes straight into the
  included build, never into a root build file.

Main decision record: `changes/archive/2026-07-17-establish-target-architecture`. Seam, failure, state and concurrency
rules: `changes/archive/2026-09-23-harden-seam-bug-classes`.

---

## 1. Overview

```
api/        the backend: Deno + Hono on bunny Edge Scripting, a relational store, a byte store (section 8)
site/       the Astro landing and no-app download pages (served by api/ from the storage zone)
iosApp/     the Xcode project: the app target and the upload-extension target (Swift shells only)
core/ adapter/ ui/ app/ test/      the Gradle modules (below)
build-logic/    the included build: every convention plugin, the project's detekt rules, the diagrams (section 6)
architecture/   GENERATED diagrams (section 6)
```

### The module set: three groups

Every module in `settings.gradle.kts` belongs to exactly one group. The group names the law that justifies the
module's existence. A structure that no law justifies is a **package**, not a module. The enumeration lives in
`ModuleSetTest` (`:test:architecture`). Adding a module means adding the `include` **and** placing the module in one
group, with the group's argument in the commit.

| group | why it exists | members |
|---|---|---|
| **Withholding** | withholds a dependency (third-party, platform, or another layer) from its consumers by compile error | the core's layers (below) · `:ui:state` `:ui:screens` `:ui:components` · `:adapter:ios:ext-safe` `:adapter:ios:app-only` `:adapter:ios:ui` `:adapter:generic:app` `:adapter:generic:mock` `:adapter:generic:sentry` `:adapter:generic:motionphoto` `:adapter:android` · `:test:levers` (the mocks withheld from the tests that call their typed faces) · `:app:ios` `:app:ios:extension` `:app:android` `:app:desktop` |
| **Contained** | exists so that something is **absent** from a production build, and is linked only under a build property | `:test:rig`, `:test:contracts`, `:test:launch-adapters` (`-Psnapsync.rig`) |
| **Support** | never linked into a shipped-format binary, exempt from production-module laws | `:app:jvm` `:test:integration` `:test:architecture` `:test:harness-driver` `:test:edge` `:test:client` `:test:control` `:test:cli` `:test:journeys` `:test:feature` `:test:data` `:test:partial-grant` `:test:no-grant` `:test:verifies` |

Key placements:

- `:ui:components` is the only module that may depend on Material 3.
- `:ui:state` is the screen's vocabulary — `UiState` (display values only) and `UiIntent` — and references nothing
  project-internal, so `:ui:screens` and `:ui:components`, which see it and nothing of the domain, cannot name a domain
  type. Presentation maps the domain into it.
- `:test:contracts` is the only module whose main code may assert (it withholds `kotlin-test`).

There is no per-module prose inventory: each module's build file and its code's KDoc say what it is. The live graph is
`architecture/modules.md`.

### The core and its layers

The core is the modules under `core/` (a path grouping, not a module): one module per **layer**, each with its
package at `app.snapsync.<layer>` — so `domain` names both the grouping and one layer of it, `:core:domain`
(`app.snapsync.domain`). Each layer declares only the edges its law permits, always with `implementation()`, so a
forbidden reference does not resolve — fully-qualified names, typealiases and generated source included, which a text
scan would miss. The permitted edges are `ModuleSetTest`'s `permitted` map; the one edge outside a layer's law is its
tests' `:test:verifies`, inside `commonTest.dependencies` only (an annotation that depends on nothing).

```
                 domain  ◀── every layer that names its values (it depends on nothing)
format ──▶ domain        wire (nothing)        inbound (nothing)
ports ──▶ inbound, domain
scheduling ──▶ inbound, ports, domain
data ──▶ domain, ports, wire, inbound, format
application, query ──▶ domain, data, inbound, format
presentation ──▶ domain, query, inbound, :ui:state
entrypoints ──▶ inbound, scheduling, domain, ports, application, data, query, presentation, :ui:state
composition/process ──▶ the layers below presentation · composition/extension ──▶ + composition/process
composition/app ──▶ composition/process and every layer, :ui:state
```

The layer table — what each holds, what it may see, the complexity it is held to (cyclomatic / cognitive per
function: the threshold of its detekt tier in `config/detekt/`, which flags a function at or above it), and what
proves it:

| layer | holds | may see | detekt threshold (cyclomatic / cognitive) | proof / budget |
|---|---|---|---|---|
| `domain` | values and verdicts, by concept (`time`, `event`, `sharing`, `asset`, `device`, `upload`, `receiving`, `album`, `report`…): the identities, the dates, the policies, the evidence types — pure, no I/O, no state, no format | nothing — no module, no library | 5 / 5 (`domain`) | `DomainPurity`, `NoTopLevelFunctions`; unit coverage zero-miss |
| `format` | the text a value takes on the wire and in a store, once per format (`TimestampText`, `InviteLinkText`, `EncryptedFileFormat`, `LegacyMembershipText`, `PushPayloadText`…) — total decoders: a typed library catch, never a throw | `domain` | 19 / 20 (`core`) | `OnlyInvalidProgramStateThrows`; unit coverage zero-miss |
| `wire` | the backend's HTTP shapes, generated from `api/openapi.yaml` | nothing | 19 / 20 (`core`) | generated; `BackendContract` |
| `inbound` | how work **enters**: the four inbound kinds, their handles, the `Trigger`, the structured `Log`, the opt-in markers (`@InboundImplementation`, `@CompositionOnly`, `@AppProcessOnly`) | nothing | 19 / 20 (`core`) | the opt-ins; unit coverage zero-miss |
| `ports` | every port interface, the event ports' `*Handlers` bundles, and the ports' data vocabulary — nothing else | `inbound`, `domain` | 19 / 20 (`core`) | `PortsHoldInterfacesOnlyTest`, `PortBundleTest`, the contracts (`docs/testing.md`) |
| `scheduling` | how work **runs**, not knowing the work: the `Runtime`, the entry-point bases, `Run`/`Steps`, single-flight `Sequence`s, `Observation`, `Exclusion`, `SessionDrains`, `ScreenDisplay`, `RootScopeFailures` | `inbound`, `ports`, `domain` | 7 / 9 (`scheduling`) | `CoroutinesInScheduling` (only this layer starts coroutines) |
| `data` | the repositories over the ports and the backend's protocol over the transport — translation, never a decision: every branch is a domain verdict's, as an exhaustive `when` used as an expression | `domain`, `ports`, `wire`, `inbound`, `format` | 4 / 4 (`data`) | `DataShape`, `StoreWritesAnnounceTest` |
| `application` | one use case per operation a trigger runs, called only by an entry point (`EntryPointCall`), never by another use case | `domain`, `data`, `inbound`, `format` | 4 / 4 (`application`) | the `EntryPointCall` opt-in |
| `query` | what the screen reads, never written: the query states, the lookups, the request statuses | `domain`, `data`, `inbound`, `format` | 5 / 5 (`query`) | `QueryReadOnlyTest` |
| `presentation` | the UI-state reduction: `StatusContainerHost` and its parts, mapping the domain into `UiState` | `domain`, `query`, `inbound`, `:ui:state` | 5 / 5 (`presentation`) | the compiler (no `application`, `entrypoints` or `data` edge) |
| `entrypoints` | what happens on a trigger: one entry point per operating-system trigger, a short list of steps that orders and never decides; the host's assembly (`AssembleHost`) | `inbound`, `scheduling`, `domain`, `ports` (vocabulary only), `application`, `data`, `query`, `presentation`, `:ui:state` | 4 / 4 (`entrypoints`) | `EntryPointShape`, `OneCallPerStep`, `EntryPointsCallNoPortTest` |
| `composition/*` | construction only: named graph classes of `val`s and one composing function per process — `process` (every process's), `extension`, `app` | the layers they wire (`ModuleSetTest`) | 2 / 1 (`composition`) | `CompositionIsWiringTest`, `PortBundleTest` |

Every core layer also holds the cross-cutting rules: no function-typed constructor parameter or property, no
`fun interface` (`NoFunctionTypes`), no top-level function but a process's composing function
(`NoTopLevelFunctions`). A constructor touches no port (`ConstructorsTouchNoPort`) in every layer that builds over
ports — `inbound`, `scheduling`, `domain`, `data`, `application`, `query`, `entrypoints`, `presentation` and the
composition — and `domain`, `format`, `inbound`, `scheduling`, `data`, `application`, `query` and `entrypoints` throw
only an `InvalidProgramState` (`OnlyInvalidProgramStateThrows`). Every number in a tier is a ceiling that only falls (§5).

### Where code goes — the placement guide

Ask in this order; the first yes is the layer.

| question | layer |
|---|---|
| Is it pure — a value, a rule over values, a verdict? | `domain` |
| Is it the text a value takes on the wire or in a store? | `format` |
| Does it translate a port, or own a store? | `data` |
| Is it one operation a trigger runs? | `application` |
| Is it read-only state the screen follows or asks? | `query` |
| Is it when or how work runs, without knowing the work? | `scheduling` |
| Is it what happens on a trigger — the steps, in order? | `entrypoints` |
| Does it map state into what a screen shows? | `presentation` |
| Is it construction only — which instance goes where? | `composition` |
| Does it talk to a platform API? | an adapter |

A type a port carries is the port's vocabulary (`ports`); a helper every adapter shares is the adapter base's
(`:adapter:generic:app` — `HandlerSlot`, `PlatformCalls`, `DateLocales`, the annotations). A use only by a test, the
rig or a mock keeps nothing in production: such a helper lives in the test equipment.

**Allowed targets.** The core's modules and the `:ui:*` modules compile for exactly `jvm`, `iosArm64`,
`iosSimulatorArm64` and `android`, declared once by the `snapsync.targets` convention plugin in the `build-logic/`
included build. No such module lists its own targets; it may configure one the plugin declared (its test
runtime, say). Adding a target is an edit to the plugin and this list, not to any law.

The `android` target is its own plugin, `snapsync.android` (AGP's `com.android.kotlin.multiplatform.library`), which
`snapsync.targets` applies and which every other multiplatform module the Android app links applies beside its own
`jvm`/iOS list (`:adapter:generic:*`, and the rig's `:test:rig`, `:test:launch-adapters`, `:test:contracts`). It
reads the SDK levels from `libs.versions.toml` (minSdk 30 — scoped storage enforced, one code path, and the level the device tests run at too) and **lowers the bytecode
to `android-jvmTarget` (17)** while the toolchain stays JDK 25: D8/R8 dexes what the Android compilations emit, and
R8 over the whole graph is built on every push (`ci.yml`'s `android-build`, the plain release) and run by every
Android journey (`journeys (android)`, the rig release). It declares no host tests —
`commonTest` runs on the JVM — and device tests only where a module has `src/androidDeviceTest` (the adapters'
contract bindings), run on a Gradle-managed emulator by `androidPlatformTest`. The Android app itself is ONE process with ONE composition
(`:app:android`'s `SnapSyncRoot`, built in `Application.onCreate`) and ONE adapter module (`:adapter:android`):
iOS splits its adapters because its upload extension is a second process with linkage limits, and Android has no
second process.

The layer split is the one place where withholding is done by an internal boundary. It was chosen because the text
gates it replaced had to enumerate violation forms, could not see generated code, and passed green when their
directory was renamed.

### Adapters

The adapter tree is two levels deep, `adapter:<platform-axis>:<linkage-leaf>`, and the prefixes are
paths, not modules. Each axis names the question that separates its leaves:

- **ios axis = process linkage.** `ext-safe` may link into the upload-extension process. `app-only`
  must not. It owns the app-process `URLSession` adapters, whose OS-reattached session ids make an
  extension-side link unsafe. `ui` is app-only too and holds what needs Compose and UIKit's UI: the `Ui`
  adapter (the Compose scene SwiftUI pulls, and the scene rule), the `Lifecycle` adapter, and the
  main-confined `SceneRecord` they share.
- **generic axis = shippability.** `app` ships in the app and extension binaries — the adapters every platform shares
  and the adapter base (above) — and so does `sentry`: the crash reporter, the `CrashReporter` port over the Sentry KMP
  SDK, ONE seat for iOS (both processes) and Android, because it is where "nothing unshaped leaves" is enforced. It
  reads no platform API: the build's facts arrive in `CrashOptions` from `BuildInfo`. It is its own module because it
  withholds the SDK. `mock` never ships. Each mock is a durable state, a per-process port face and a separate operator
  face (`docs/testing.md`, "Mocks"); the classes behind the port faces are all `internal` and every face is
  port-typed, so honesty is checked by the compiler: an app is only ever handed a port.

Adapters are named for the technology and hold implementations only. Finer structure is packages.

### Binaries

| binary | composition root | calls |
|---|---|---|
| iOS app (`SnapSyncKit`) | `:app:ios` `SnapSyncRoot` | `composeApp` (`:core:composition:app`), over `AppPorts` |
| iOS upload extension, iOS 26.1 and later (`SnapSyncUploadKit`) | `:app:ios:extension` `UploadExtensionRoot` | `composeExtension`, over `ExtensionPorts` |
| Android app | `:app:android` `SnapSyncRoot` | `composeApp`, over `AppPorts` |
| JVM root — the desktop world harness, the JVM rig host | `:app:jvm` `JvmApp`, over adapters its caller chooses (the mocks by default) | `composeApp`; `composeExtension` beside it, each over its own process ports (a process that supplies no log sinks installs no log writer: `Log`'s writers are JVM-global) |

---

## 2. The laws

Grouped by theme, one row each: the law, the gate that holds it, and why it exists — where it matters, the
alternative that was rejected. Gates live in `:test:architecture`
(`test/architecture/src/test/kotlin/app/snapsync/architecture/`) or are detekt rules (`build-logic/detekt-rules`,
configured in `config/detekt/`) unless noted. **review** = no gate. The rule inventory at the end of this section lists
every rule once, with its gate.

### Layers and edges

| law | gate | why |
|---|---|---|
| The build's module set equals the three groups, each module in exactly one | `ModuleSetTest` | a module no law justifies is a package; an unplaced one grows edges nobody argued for |
| A core layer references only its permitted layers, through `implementation()` edges | the compiler + `ModuleSetTest` | a forbidden reference must not resolve at all; an `api()` edge would republish a layer to every consumer |
| The core names no platform API, imports only its per-layer allowlisted libraries, and holds no main source set but `commonMain` | the compiler + `PlatformCodeContainmentTest` | platform code behind a port is code a mock can replace and a second platform need not re-make |
| Only `:ui:components` sees Material 3, its icons, or the QR library | the compiler | the design system is one module's |
| Core and `:ui:*` modules declare no target list of their own | **review** (`snapsync.targets` declares them) | one place to add a target |
| Every core module is scanned by exactly one detekt tier | `DetektTierCoverageTest` | a module no tier reads holds no rule |

### Ports

| law | gate | why |
|---|---|---|
| Anything touching an external system (time, files, network, platform) goes through a port, named for the **need**, never the technology; the process-level ports are one external system each (`CrashReporter`, `ProcessMetrics`, `LogSink`, `Clock`, `Crypto`, `ProcessInfo`, `SystemUi`, `BackgroundTime`, `ThreadLabel`, `EntryEvents`) | **review** (partly the compiler: `ports` cannot import Ktor, nor SQLDelight beyond its runtime interfaces) | a technology-named port leaks its library into the core |
| `ports` holds interfaces, the `*Handlers` bundles and data vocabulary only — no helper, no object, no decision | `PortsHoldInterfacesOnlyTest` | logic beside a port is code no adapter implements and no mock replaces |
| Every port extends `Port`; a bundle (`AppPorts`, `ExtensionPorts`, `ProcessPorts`, the device bundles) holds ports and nothing else | `PortBundleTest` | a root that hands the core a service, a constant or a lambda decides for it |
| A port answers every expected failure as a value — a sealed answer, never a throw; an adapter catches its library's failure once, typed, and translates it | `PortGridTest` (no `@Throws`) + each port's contract | a throw crosses the seam untyped, and a caller forgets it |
| Ports never call ports: no adapter's constructor takes a port | `PortsNeverCallPortsTest` | combining two systems is a decision, which belongs where a mock can see it |
| An adapter's constructor takes no function; an adapter declares, with `@NeverAnswers`, the answers its code cannot give | `AdapterConstructorTest`, `NeverAnswersCheck` + `AdapterCoverageTest` | a callback wired past `inbound` is a seam nobody tests; an unclaimed cell is an untested answer |
| Every port-grid cell is covered by a clause run against a real implementation, and recorded by its port's proxy | `ClauseCoversTest`, `ProxyCompletenessTest`, `ContractCoverageTest` | a mock that answers what no real adapter does tests nothing |
| A platform's magic values, ABI integers and error tables stay in adapters | `PhotoKitAbiContainmentTest` + **review** | a bare literal no gate can see collides with the next platform's |
| Production reads a development control only through `DevControls`, whose production adapter is inert — `NeverAnswers` declares what it never answers — and whose only other adapter is compiled into a rig build | `DevControlsContainmentTest` + `NeverAnswersCheck` | a test-only input with a declared constant production answer is a port's answer, not dead code; moving the uploader pin into a composition seam (rejected) would have hidden the same variability where nothing describes it |

### Inbound and entry

| law | gate | why |
|---|---|---|
| What the operating system tells a process arrives through **entry ports** (`EventPort<H>`), delivered raw through the inbound kind its handler is typed by; a root keeps one-line forwarders | `EntryIntegrationTest`, `OsCompletionIntegrationTest`, `ExtensionEntryTest`, `ListenDoorTest` + shell gates | a root that decides is untested; one door per system keeps every delivery labelled and contained |
| Only the runtime's entry-point bases implement an inbound kind | the compiler (`@SubclassOptInRequired(InboundImplementation)`) | every delivery must pass the code that labels, times and contains it |
| Only a composition builds a `*Handlers` and calls `inbound` | `ListenDoorTest` + `@CompositionOnly` | registration is wiring; a second site is a second, untested wiring |
| One entry point per operating-system trigger, named for it, ordering steps and deciding nothing; it calls no port | `EntryPointShape`, `EntryPointsCallNoPortTest`, the `entrypoints` tier | read top to bottom, the entry points are what the app does on every trigger |
| **Host-first:** only a person reaching the app assembles the screen host — `OnForeground` and the UI's `onLive`/intents run `AssembleHost`; a push, a wake or a relaunch builds no host and installs no subscription | `EntryIntegrationTest`, `OsCompletionIntegrationTest` | a cold background launch must not run the launch reconcile (it re-registers the extension) or follow the grant; an eager host in the graph (rejected) would have |
| Commands cross one door: presentation fires every command through `UserCommands`, each a `UserCommand` over an entry point, fired and never awaited | the compiler (presentation has no `application`/`entrypoints` edge) | a screen that calls work directly bypasses the entry point that labels it |

### Steps and scheduling

| law | gate | why |
|---|---|---|
| Only the runtime starts coroutines; work a run does not await is `detached` | `CoroutinesInScheduling` | a launch past the runtime escapes its label, its background-time hold and its containment |
| A step is one call: `required`, `isolated`, `child`, `ensure`, `detached` blocks each hold one statement | `OneCallPerStep` | the step's name is what it does, so the list reads as the trigger's overview |
| Work several triggers ask for runs single-flight (`Sequence`) | `SingleFlightTest` | one more pass however many join; a waiter whose time is up withdraws without cancelling others |
| Two runs that must not overlap name an `Exclusion`: the holder releases it in an `ensure` step, work that yields skips and says so | `ExclusionTest` + **review** | when work runs is scheduling's; a mutex in the old services (`MembershipTransition`, retired) hid the rule in a feature |
| The lanes are the app's only source of timers and threads; the main thread is named only by allowlisted platform-UI adapters | `TestsWaitOnEventsTest`, `MainLaneContainmentTest` | a host that owns the lanes owns the app's time, so tests wait on events, never on time |
| A delivery the platform makes once is persisted before its entry point returns | **review** | after the callback returns, the process's runtime is not ours to assume |
| No OS completion handler is stored in a mutable field: it rides its delivery as a `Completion`, answered once | `OsHandlerContainmentTest` + `EntryPointTest` | a stored handler is answered twice or never |
| Mutable state touched from an OS callback thread is confined to a named lane (`@ConfinedTo`) or is a thread-safe primitive | `ConfinementGateTest` (heuristic) | a callback arrives on threads it does not choose |

### Errors and containment

| law | gate | why |
|---|---|---|
| The layers throw nothing but an `InvalidProgramState`, whose `because` is a literal (`domain`, `format`, `inbound`, `scheduling`, `data`, `application`, `query`, `entrypoints`); nothing catches it | `OnlyInvalidProgramStateThrows`, `InvalidProgramStateBecause` | an expected failure is a value; a broken guarantee ends the process with its crash report rather than limping on |
| The core catches only typed third-party failures (in `data`, `format`, `wire`); a bug is contained at the runtime's three sites only — a run's containment, `isolated`, a pass's outcome | `CatchSitesTest` (the core's map empty) + `CatchGateTest` | a catch-all in the core turns a bug into a silently wrong answer |
| An adapter catches what its platform throws: typed where the platform documents it, else at a named `PlatformCalls.attempt` site that keeps cancellation | `CatchSitesTest` (named adapter sites, exact, shrink-only) | an undocumented platform throw becomes a value, never a crash; each catch-all states what it guards |
| No Kotlin throw escapes into ObjC; no ObjC `Boolean`/`NSError**` result is dropped | `ObjCBoundaryGateTest` (heuristic) | an exception unwinding into ObjC frames ends the process |
| A read that can be unknown returns a sealed result with an explicit *unreadable*; absence is never silent | **review** | "could not look" folded into "nothing" is how an unreadable membership once read as a leave |

### Types and evidence

| law | gate | why |
|---|---|---|
| The domain is values and verdicts: no suspension, no state, no behaviour as a value, no world, no top-level function | `DomainPurity` | a verdict that reads the world cannot be unit-tested as a value |
| A business rule is a type: parse, don't validate (`EventId.parse`, `CaptureRange.of`); a fact only a check can mint is evidence with its own `@RequiresOptIn(ERROR)` minting marker | the compiler + `OptInPinsTest` | a value that exists obeys its rule; evidence cannot be forged past its one mint |
| No function-typed constructor parameter or property, no `fun interface`, no function type alias, anywhere in the core | `NoFunctionTypes` | behaviour handed in as a lambda is a seam nobody names or tests; an interface names it |
| No top-level function in the core but a process's composing function (`composeApp`, `composeExtension`, `startProcess`) | `NoTopLevelFunctions` | a function lives on the type it belongs to, so "where is it?" has one answer |
| A constructor never touches a port: no initializer or `init` block reads or calls a port-typed parameter, reads a `.value` through a parameter, or starts a flow `Eagerly` | `ConstructorsTouchNoPort` | a graph built eagerly opens nothing, so a locked background launch is never forced into a read |
| A photo's identity is one canonical `AssetId`, minted from the platform's id by one reversible encoding (`AssetIdText`) | construction + `AssetIdTest`, `AssetIdTextTest` | the core, both stores, the manifest and the backend carry it unchanged |

### Data and cross-process

| law | gate | why |
|---|---|---|
| Data translates and never decides: every branch is a domain verdict's, as an exhaustive `when` used as an expression | `DataShape` | a decision in data is a decision no domain test sees |
| Storage by kind: event and membership state in the shared database (`ledger.db`); device-scoped values in `Preferences`; bytes in `Files`; secrets in `SecureStore`. A database that cannot be opened is unreadable, never "not joined" | **review**; `MembershipRepositoryTest`, `LegacyRecordsTest` | one consistency domain covers what must commit together |
| A store opens on first use and keeps only a successful open | `StoreOpenTest`, `OwnedDatabaseTest` | a locked launch's failure is retried at the next use |
| No raw statement: every write goes through the generated queries, so the change signal announces it | `StoreWritesAnnounceTest` | the screen follows the store's change signal, never a poll |
| What another process committed arrives through `Databases.inbound`; a process's own commits never come back to it | `DatabasesContract` | a missed notice costs freshness, never correctness |
| The download store has one writer, the app; the extension opens it read-only and pauses on an older schema | `ReceivedPhotosReaderTest`, `CycleVerdictsTest`, `PassGateTest` | one migrator per store |
| No transport adapter holds the upload rows; each kind of durable write has one owner, a guarded single transaction | `TransportLedgerGateTest` + **review** | two processes running the owner is fine; two owners is not |
| A shipped migration is never edited | `MigrationPinTest` | a phone that already ran it would never run the edit |
| Paths are area-relative; `Files` answers `NotFound` only for a definite absence | `FilesContract` (`DENIED_IS_NEVER_NOT_FOUND`) | an unreadable file read as absent was a false leave |

### Logging and secrets

| law | gate | why |
|---|---|---|
| Every line goes through `Log`: a literal event, its varying values as fields, its trigger from the coroutine context — or, unlabelled, from the thread's `ThreadLabel` | `EventNameLiteralTest` + the build (no logging library but `Log` resolves) | a line reads, and a failure report groups, by what happened; Kermit and its bridge (retired) carried free text with no fields |
| Every line reaches each process sink once and the crash channel once; the crash channel carries everything but an event key | `SinkLinesTest`, `CrashChannelTest` + the secret types | an event key in a crash report hands the event's photos to whoever reads it |
| A secret is its own type, rendered only where it is meant to leave | the secret types (`docs/patterns.md`, "Secret types and rendering") | a `String` secret is logged by the first `toString` |

### Composition and shells

| law | gate | why |
|---|---|---|
| **The composition layer is wiring only:** a root hands its composition ports and nothing else; named graph classes hold only `val`s built by constructor calls — no branch, no `lazy`, no lambda, no member function — and each process has one straight-line composing function whose first act is `startProcess` | `CompositionIsWiringTest`, `ExtensionSafetyTest`, `PortBundleTest`, the `composition` tier | the graph is not unit-tested; it can only be trusted if it cannot decide. A lazy graph (the old `compose/`, retired) let a root's touch change what a launch built |
| The upload's layers are one graph both processes build (`UploadGraph`); the app-only pieces (`@AppProcessOnly`) cannot be built from what the extension links | `ExtensionSafetyTest`, `OptInPinsTest` | the extension's linkage is a platform limit, held by types |
| Shells (`:app:ios`, `:app:ios:extension`, `:app:android`, `:app:jvm`, rig-contributed shell source) hold zero decisions | `detektAppShell` + `KotlinShellGuardTest` | every decision a root once held is unit-tested where it moved |
| Swift is a transcriber: decision keywords only at pinned occurrences | `SwiftShellGuardTest` | Swift cannot be tested from Linux |
| The build's adapter set is chosen at build time, by compilation: no flag is read, no inert stub ships, no shipped binary links a mock | the build scripts + `DevControlsContainmentTest`, `MockContainmentTest` | a remote-control affordance present in every binary is inert only by how it is started |
| A tap crosses as a `UiIntent`, decided by one exhaustive intent table | `HostStatusActionsTest` + the compiler | the table clicked is the table that ships |
| `:ui:screens` takes no `suspend` parameter; a function-typed `var` or a defaulted function parameter appears nowhere outside the core | `ScreensTakeNoSuspendSeamTest`, `LambdaSeamShapeTest` | the adapters, roots and screens are outside the core rules' scan |

### Naming

| law | gate | why |
|---|---|---|
| An answer's name says its kind: `XResult` (a port's operation), `XRead` (a read that can be unreadable), `XReply` (the backend's), `XOutcome` (what a use case did), `XVerdict` (a domain decision), `XQuery` / `XLookup` (what the screen follows / asks) | **review** | the suffix tells a reader which layer made it and whether it can be unknown |
| An entry point is named for its trigger (`OnSilentPush`); a port for its need, an adapter for its technology | **review** | the trigger names read as the app's behaviour; the need names keep technology out of the core |
| A boolean that stands for a choice is an enum | **review** | `true` at a call site says nothing |

### Platform, runtime identity and containment

| law | gate | why |
|---|---|---|
| All Keychain API use lives in `:adapter:ios:ext-safe` | `KeychainContainmentTest` | one place pins the accessibility class |
| Neither entitlements file raises `default-data-protection` to `NSFileProtectionComplete` | `DataProtectionEntitlementTest` | it would make every App-Group file unreadable while locked |
| Extension-linked Kotlin references only allowlisted `platform.*` frameworks | `ExtensionSafetyTest` | the extension's linkage is a platform limit |
| Runtime-identity literals appear exactly once, with pinned non-Kotlin copies agreeing | `RuntimeIdentityTest` | the installed base holds them |
| Apple enums decoded with a fallback arm are pinned to the platform klib's declared set | `PlatformVocabularyPinTest` | a Kotlin bump can move the vocabulary |
| The device actuals name the background session and the PhotoKit job APIs, the simulator actuals neither | `TransferSessionBindingTest`, `UploadJobSubsystemBindingTest` | PhotoKit job creation traps on a simulator |
| The event-link domain is generated from one resolved deployment everywhere; both delivery paths stay | `EventLinkDomainTest`, `EventLinkDeliveryTest` | a hand-written host drifts |
| A build-time-only module is contained by compilation; production declares no `SNAPSYNC_*` literal; the control channel binds loopback only | the build scripts, `RunbookSkillsTest`, `ControlChannelLoopbackTest` | test control must be absent from a shipped binary, not merely inert |
| Client and backend event-name caps and the gated-route list agree | `EventNameLimitTest`, `GatedPathPinTest` | two copies of a rule drift |
| Every runbook skill `AGENTS.md` points at exists | `RunbookSkillsTest` | a pointer that reaches nothing is followed without the skill |
| Retired dead weight stays dead | `DeletionLedgerTest` | bringing one back means deleting its row, with the argument |

### Tests verify requirements

The specs (`openspec/specs/`) say what a user observes; the code is laid out by its own laws, not by the specs'
cut. They are linked in one direction only: a test that verifies a requirement says so with
`@Verifies(spec = "…", requirement = "…"[, scenario = "…"])` (`:test:verifies`), keyed on the spec's directory
and the requirement's title. What an adapter or the platform decides is claimed by a named test beside the adapter (a
device test, a contract binding's own test, or a journey) — never by a whole-contract run, which proves every clause at
once, and never by a mock's binding, which proves only the mock. The Deno suites (`api/test`, `site/scripts/*.test.ts`)
write the same annotation as a one-line comment directly above a `Deno.test`, `// @Verifies(spec = "…", requirement = "…", scenario = "…")`, and claim
what the api or a web page decides. A suite claims a scenario only where its own code decides the outcome; a scenario
split between the api and the app carries one claim per deciding place, each saying in a line which part it proves.
Scenarios left unclaimed on purpose (a platform's or a store's outcome, an accepted "may" gap, a release obligation)
are listed with a reason in `AcceptedUnclaimed` (`:test:architecture`) — never in `openspec/`, and never work still to
do. `./gradlew :test:architecture:verifiesReport` lists which requirements and scenarios a test claims and which none
does, leaving the list out (`build/reports/verifies.md`, non-gating, never committed).

| law | gate | why |
|---|---|---|
| Every `@Verifies` names a spec, requirement and scenario that exist | `VerifiesGateTest` | a renamed requirement fails the build and names the tests that claimed it |
| Every `AcceptedUnclaimed` entry names an unclaimed scenario that exists, with a reason | `AcceptedUnclaimedTest` | an accepted gap is a decision, not a forgotten test |
| Production code cites no spec | `NoSpecCitationTest` (heuristic) | the link runs from tests to specs only |

### Disciplines with no gate

- **Necessity claims carry forcing proofs.** "The platform forces X" cites an API contract, a vendor
  doc, or a device measurement, never the current code, and names the trigger that would dissolve it.
  A gate that pins an exception carries the proof in its failure message.
- **A capability claim is settled by a compile, not a symbol table.** A klib records what ships, not
  what is callable (`Dispatchers.IO` is in the Native klib and is `internal`). To learn what an Apple API
  *declares* (enum sets, nullability), read the klib (`adapter/ios/AGENTS.md`, "Reading the Apple SDK from Linux").
  What the *device* does needs a measurement.
- **Expiry of the lane reasoning.** The three-lane argument is iOS-shaped (two processes). A platform
  whose background work runs in-process (Android) re-derives it rather than inheriting it.

### The rule inventory

Every rule of the core's layers once, by theme, with its gate. A gate **retired** is named with what holds its rule
now (`DeletionLedgerTest` keeps it dead).

| theme | rule | gate |
|---|---|---|
| layers & edges | the module set's three groups | `ModuleSetTest` |
| | a layer's permitted edges, `implementation()` only | the compiler + `ModuleSetTest` |
| | the core is `commonMain` only, its libraries allowlisted per layer | `PlatformCodeContainmentTest` + the compiler |
| | each core module in exactly one detekt tier | `DetektTierCoverageTest` |
| | each tier's `includes` matches a file | the build-logic check (`QualityPlugin`) |
| ports | interfaces, handlers and data vocabulary only | `PortsHoldInterfacesOnlyTest` |
| | every port extends `Port`; bundles hold ports only | `PortBundleTest` |
| | failures as values, no `@Throws` | `PortGridTest` + the contracts |
| | ports never call ports; adapter constructors take no function | `PortsNeverCallPortsTest`, `AdapterConstructorTest` |
| | every grid cell covered, recorded and claimed | `ClauseCoversTest`, `ProxyCompletenessTest`, `ContractCoverageTest`, `AdapterCoverageTest`, `NeverAnswersCheck` |
| | the one development port | `DevControlsContainmentTest` |
| inbound & entry | only the runtime implements a kind | `@SubclassOptInRequired(InboundImplementation)` |
| | only a composition registers handlers | `ListenDoorTest`, `@CompositionOnly` |
| | an entry point orders and never decides, and calls no port | `EntryPointShape`, `EntryPointsCallNoPortTest` |
| | host-first assembly | `EntryIntegrationTest`, `OsCompletionIntegrationTest` |
| steps & scheduling | only the runtime starts coroutines | `CoroutinesInScheduling` |
| | one call per step | `OneCallPerStep` |
| | single flight; the exclusion | `SingleFlightTest`, `ExclusionTest` |
| | lanes own time and threads | `TestsWaitOnEventsTest`, `MainLaneContainmentTest` |
| | completions ride their delivery; OS-callback state is confined | `OsHandlerContainmentTest`, `ConfinementGateTest` |
| errors & containment | only `InvalidProgramState` is thrown, with a literal reason | `OnlyInvalidProgramStateThrows`, `InvalidProgramStateBecause` |
| | typed library catches in data/format/wire/adapters; named adapter catch-alls; none in the core | `CatchSitesTest`, `CatchGateTest` |
| | nothing unwinds into ObjC | `ObjCBoundaryGateTest` |
| types & evidence | the domain is pure | `DomainPurity` |
| | evidence minted in one place | `@RequiresOptIn` markers + `OptInPinsTest` |
| | no function types | `NoFunctionTypes` |
| | no top-level functions | `NoTopLevelFunctions` |
| | constructors touch no port | `ConstructorsTouchNoPort` |
| data & cross-process | data translates, never decides | `DataShape` |
| | first-use opens; writes through the queries; shipped migrations frozen | `StoreOpenTest`, `StoreWritesAnnounceTest`, `MigrationPinTest` |
| | one writer per store, one owner per write | `TransportLedgerGateTest`, `ReceivedPhotosReaderTest` |
| logging & secrets | `Log` only, literal events | `EventNameLiteralTest` |
| | everything but an event key reaches the crash channel | `CrashChannelTest`, `SinkLinesTest` |
| naming | answer suffixes, trigger and need names, enums for choices | **review** |
| gates — new in phase 5 | `ConstructorsTouchNoPort`, `NoTopLevelFunctions`, `NoFunctionTypes` widened to every core module, `CatchSitesTest` as a named map | — |
| gates — retired in phase 5 | `CompositionSeamTest` (→ `NoFunctionTypes`), `JvmRootBootsColdTest` (→ the eager graph and `ConstructorsTouchNoPort`), `KermitBridgeTest` (the bridge is gone) | `DeletionLedgerTest` |
| gates — kept | `LambdaSeamShapeTest`, `ScreensTakeNoSuspendSeamTest` (outside the core, which no detekt rule scans) | — |

### The ports, the entries and the state, in detail

What each port is, what each entry point takes, and who owns each piece of state — the detail the themed tables above
name. Each row's gate is listed beside it.

| law | enforced by |
|---|---|
| A storage port is one external system and decides nothing (`Databases`: open by name, read-write or read-only; `Files`: read, tail, range, write, append, delete, exists, locate within an area — range and append so a file too large to hold is read and written a piece at a time; `Preferences`: get, set, remove). What a store holds, when it opens and what a failure means is the data layer's (`OwnedDatabase`, `SharedDatabase`, the repositories) | **review** |
| The photo library is one thin port: `GalleryReader` (both processes: assets by policy or id, resources by id, albums, album members, create, add, and the `albumKind` — a `COLLECTION` a photo is added to on iOS, the `FOLDER` a photo lives in on Android) and `Gallery` (the app: plus the access request, the selection picker, the partial grant's selection observer, the import and the change token). It answers what the platform shows, and `NotReadable` — never an empty answer — when no grant lets the process read. What may be read under which grant and which albums are denied are the selection's (the domain's `ReadPlan`, data's `OwnPhotos` and `DenylistedAlbums`),; what the kind means for the event album is read by data's `AlbumFiling` into the domain's `AlbumContents` (whether own photos are placed, whether an empty album does not resolve), and what the album then holds is the album's verdicts (`domain` `album/`), which never name a platform. Ids cross as the platform's own (`PlatformAssetId`), assets as the platform describes them (`GalleryAsset`: a capture instant or none, the media facts, a pixel area or none) and resources by `ResourceKind` (`PHOTO`, `VIDEO`, `PAIRED_VIDEO` — an original only) under an opaque `GalleryResourceHandle` only the adapter that handed it out reads; the domain's `AssetFacts` are the data layer's reading of them (`facts()`), the upload's resources too (`OwnPhotoResources`: each original keyed under its role, a `FoundResource` and the handle it sends from) | `GalleryReaderContract`, `GalleryContract`, `GalleryImportContract` (live on `IOS_SIM_APP`, the no-grant state on `IOS_SIM_KEXE`), `FolderAlbumContract` (live on `ANDROID_EMU`) |
| A photo's identity is one canonical `AssetId` (`domain`): non-empty, RFC 3986 unreserved characters only, never `..` — a URL path segment the backend accepts as it is, an object-name fragment, and free of the download tag's `\n`. The core, both stores, the manifest and the backend carry it unchanged; there is no translation on either side of the wire. It is minted from the platform's own id by ONE reversible encoding (`AssetIdText`, `:core:format`, read through data's `assetId()`: `/` → `_`, `~` escaping the rest), the identity on every id stored before it — PhotoKit's `{UUID}/L0/NNN` is `{UUID}_L0_NNN`, a media-store id is itself — so every platform id has one, and no adapter mints it. A value from outside (a backend reply, an OS-held task tag) that is not canonical is a malformed reply or an undecodable tag, never a crash | construction (`AssetId.parse`, its one way in) + `AssetIdTest`, `AssetIdTextTest` |
| The transfers are thin ports, one external system each. An upload goes through one of two, which differ in what the system keeps and tells: `OsUploadQueue` (the OS's job queue — PhotoKit's: create a job sending a library resource; the first failures offered for their free retry, and every job that ended presented until acknowledged; retry, acknowledge — a job is known by the destination it was made with, the one fact the OS keeps) and `TransferSession` (the app's own session — a background `URLSession`, Android's in-process PUTs: start a transfer of a file, or of a resource where it `takesResources`, tagged; list and cancel the live ones; every end told once, as it happens, under its tag, and a relaunch's events handed over then drained) — so neither carries the other's vocabulary. A failed transfer is one fact, `TransferFailure(detail)`: nothing above the adapter decides on why; `Download` (start, cancel everything the session holds, and a finish reported with its facts and temporary file), `Wake` (schedule and cancel a `WakeId`, and hear the wake — a `Granted` delivery, whose `Running` the adapter expires when the operating system withdraws the time) and `ExtensionRegistry` (set and read the upload extension's registration, `Unsupported` on a platform without one — so no root holds an `if` around it). What a job means for the upload rows and what a body may be staged as are the data layer's (`OsQueueTier` and `SessionTier` behind `UploadTier`, each process composing its own; `JobRecord`), when the heartbeat is armed the domain's (`Heartbeat`), and the rest the features' and entry points' (the download jobs, the wake's sequences). A platform's in-flight limit is its own and surfaces as `LIMIT_EXCEEDED`: `CreateJobs` creates until refused. An OS completion handler crosses as a `Completion`, handed with the delivery it rides and answered once by the run it starts. A transfer session's relaunch is two deliveries: `onBackgroundEvents` (a `Background<SessionRelaunch>`, carrying how many drain reports came before it) and `onEventsDrained` (an `Inbound<SessionDrain>`, numbered), so the relaunch's run waits for the first drain report after it however the two race to the core (`SessionDrains`) | `OsUploadQueueContract` (creation, the limit and the presented states recorded in the extension on a device; what asks the system nothing on `IOS_SIM_KEXE`), `TransferSessionContract` and `DownloadContract` (live on `IOS_SIM_APP` and `ANDROID_EMU`, their relaunches recorded), `WakeContract` and `ExtensionRegistryContract` (recorded, under their pre-11f names) + `OsQueueTest`, `SessionTierTest`, `JobOwnershipTest`, `HeartbeatTest` |
| The origin exclusions' tuning — the two resolution floors and the album denylist — is one `SelectionCalibration.STANDARD` value in the domain, product policy rather than a platform fact: no composition supplies its own, so the app and the extension cannot disagree. They judge a library walk only: the read's own case says where its candidates came from (`CandidateSet.Walked` or `Selected`), and a selection is judged without every rule that `judgesWhatItIs` — inside `SelectionPolicy.admit`, so no caller can pair a read with the wrong rules. A ledger row is judged by the rules that need only an id and a date (`RecordedRule`, `admitRecorded`) | construction (`SelectionPolicy.of`, its one way in) + `SelectionPolicyTest` |
| `SecureStore` writes answer a `WriteOutcome`, and trust's stores answer what they read and wrote as values — a refused write fails the operation where it is made (the device id is unavailable and never used unsaved; a token is not accepted; a key id is refused), an unreadable item is `Unavailable`, never mistaken for absence. A write may replace by delete-then-add, so after a refused one the old value may be gone | `SecureStoreContract` (`INACCESSIBLE_WRITE_REFUSES`) + `DeviceIdentityTest`, `CredentialStoreTest` |
| How a date reads is one thin port, `DateFormatting`, in the app's bundle only (the extension renders nothing): `deviceLocale()` states the device's language and region, and `formats(DateLocale)` answers a `DateFormats` (its vocabulary, in `ports`) in that locale over the platform's CLDR data — `java.time` on the JVM, ICU on Android, `NSDateFormatter` on iOS. Which locale a screen formats in is one rule every root applies: the adapter base's `DateLocales.forScreen` resolves it once (`DateLocales.resolve`). Each root builds a `ScreenDates` (`:ui:components`) over it, as `CutoffFormatter` over `Clock`, and hands that to its UI adapter — which may take no port — and on to `StatusScreen`; a rig build keeps the platform's, since it keeps no state to mock | `DateFormattingContract` (live on `JVM`, `IOS_SIM_KEXE`, `ANDROID_EMU`) + `DateLocaleTest`, `ScreenDateFormatsTest` |
| Cryptography is one thin port, `Crypto`: primitives only, each answered by the platform's own implementation (CryptoKit through cryptography-kotlin's bridge and CommonCrypto on iOS, the JCA provider on Android and the JVM). A cipher key and nonce — `AesKey` (32 bytes) and `GcmNonce` (12), the port's own vocabulary — are built by the data layer's cipher at the format's lengths, checked there (`InvalidProgramState`), so no member has a wrong length to refuse, and a seal that does not authenticate opens to `null`. No adapter knows the encrypted file format: its layout is `EncryptedFileFormat` (`:core:format`, in bytes; HKDF over an HMAC the data layer adapts over the port) and its files are sealed and opened a segment at a time by `FileCipher` (`data/crypto`). The TypeScript twin the edge and the event page run is `api/src/encrypted-file.ts` | `CryptoContract` (published vectors; live on `JVM`, `IOS_SIM_KEXE`, `ANDROID_EMU`) + both halves held to `test/vectors/encrypted-file.json` and, on the JVM, to Google Tink itself (`EncryptedFileFormatTest`, `api/test/encrypted-file.test.ts`) |
| The backend is reached over ONE plain port, `HttpTransport`: send a request, answer what came back — a status, headers and text, or `Unreachable` — whatever the status, and never throw. `KtorHttpTransport` is its one implementation on every platform, over the engine each composition supplies. What a route is, which token it carries and what an answer means are not the transport's: they are the backend boundary's ("The backend boundary", §8) | `HttpTransportContract` (live on `JVM`; `ANDROID_EMU` and the simulator compile it) |
| The backend's protocol is `BackendGateway`'s, in `data/`, and nowhere else (`BackendExchange` is it without a credential, for the `/attest/…` issuers); what an answer means is the domain's verdicts (`BackendAnswer`, `CredentialVerdict`, `RetryVerdict`, `VersionVerdict`): every request declares the build's version; the credential is read per request, and one that cannot be read sends nothing — the request is answered as no answer; an `Unauthorized` to a gated request that carried a token drops THAT token, recovers, and retries the request once with the recovered token; a request that must not go out without a token (create, join, the push registration — `sendObtainingFirst`) obtains one first; an `AppTooOld` on any route refuses the build; any answer from past the version check — a success, an `Unauthorized`, any other `4xx` — clears the refusal (a server issue or no answer says nothing). The app's credential re-attests; the extension's only drops the token and never retries. Every need-shaped backend service (directory, join, manifest, leave, union, device files, create, change, push token) sits over it and reads its route's generated reply; attestation reaches the open issuers through the exchange, so recovery never re-enters itself | `BackendGatewayTest` (`:test:data`), `BackendContract` (the real gateway against `api/` and the mock, `A_GATED_ROUTE_REFUSES_A_REQUEST_WITHOUT_A_TOKEN` included) |
| `DeviceIntegrity` only proves (`prove(challenge, handle?)`: a fresh key's attestation, or an assertion by an existing one), answering a `ProveResult` — `Ok`, or `Refused` naming why (`NotAvailable` in a process without the service, `UnknownKey`, `Failed`). Only the app process holds one; the extension has none. The app's credential (`AttestingCredential`, `data/`) asks when the domain's verdicts say so, and the credential store (`SecureCredentialStore` behind the in-memory `CachedCredentialStore`) keeps the token and key handle | `DeviceIntegrityContract` (`NotAvailable` live on `IOS_SIM_KEXE`; the ceremony and `UnknownKey` recorded on a device, replayed every build; `UnknownKey` and `Failed` live on `ANDROID_EMU`) + `AttestingCredentialTest` (`:test:data`) |
| Paths are area-relative (`FileArea.SHARED`/`PRIVATE`); only an adapter resolves a platform path, and `locate` is the one exit, for a platform API that must be handed a file. No absolute container path is stored | **review** (the download store's staged paths: `DownloadStoreMigrationTest`) |
| `Files` answers `NotFound` for a definite absence only: a present file that cannot be read is `Denied`, never absent — while the legacy membership file is still copied into the shared database, a file read as absent is a false leave | `FilesContract` (`DENIED_IS_NEVER_NOT_FOUND`, live on JVM and `IOS_SIM_KEXE`) |
| **Storage, by kind.** Event and membership state — anything keyed by an event, or that has to commit together with the membership or the upload rows — lives in the shared database (`ledger.db`, `SharedDatabase`): the membership, the uploaded manifest, the pending leaves, the event albums, the event checks, beside the ledger rows, so one consistency domain (SQLite's transactions) covers them. Device-scoped values, independent of any event and never committed with anything, live in `Preferences` (the mobile-data setting, the push registration record). Bytes live in `Files`; secrets in `SecureStore`, and an event key is written before the membership row that names it. The membership is definitively absent only when the database answered with no row: a database that cannot be opened is unreadable, never "not joined" | **review**; `MembershipRepositoryTest`, `LegacyRecordsTest` |
| Building a composition opens no database: a store opens through `Databases` on first use (`OwnedDatabase`, `:core:data`) and keeps only a successful open, so a locked launch's failure is retried on the next use — the membership too, which is read at its first use, never at construction | `StoreOpenTest`, `OwnedDatabaseTest` + `MembershipRepositoryTest` (each store opens on first use; no test reads which databases a whole process opened) |
| What another process committed reaches a process through `Databases.inbound` (`DatabaseHandlers.onChangedElsewhere`, naming the database): a commit is SQLDelight's notice to the driver's listeners — after the commit, never for a rollback — and a process's own commits never come back to it. On iOS each opened database posts and observes a Darwin notification (libnotify, on a queue of its own), and SQLite's `data_version`, read on the driver's writer, tells another connection's commit from this process's own; on the JVM and in the mocks another process is another instance; Android runs one process and delivers nothing. A missed notice costs freshness, never correctness: every entry re-reads what it acts on. The app registers one consumer (`OnChangedElsewhere`, which announces the commit on that store's `StoreChanges`); the upload extension registers none | `DatabasesContract` (`COMMIT_ELSEWHERE_NOTIFIES_ITS_DATABASE`, `READ_ONLY_OPEN_HEARS_CHANGES`, `ROLLBACK_ELSEWHERE_IS_SILENT`, `OWN_COMMIT_IS_NOT_ELSEWHERE`; live on JVM and `IOS_SIM_APP` — a simulator test executable is no launchd job, and libnotify answers it `NOTIFY_STATUS_OPT_DISABLE`) |
| What the screen shows of a store follows that store's change signal (`StoreChanges`, one per database: this process's commits through `OwnedDatabase`'s driver, the other process's through `OnChangedElsewhere`), never a poll and never a refresh call; the queries read only while a screen follows them and the app is in front, so a background run reads nothing for them. The status total, which no store holds, is the one count a step takes (`CountOwnPhotos`) | `StoreWritesAnnounceTest` (no raw statement), `SharingProgressQueryTest`/`ReceivingProgressTest` (re-read on change, none while away), `CrossProcessStatusIntegrationTest`, `BackgroundCompositionIntegrationTest` |
| The download store has one writer and one migrator, the app. The upload extension opens it read-only: no store suppresses nothing; an older schema **pauses** the pass (`UploadGate.Paused`, `OLD_SCHEMA`: `UploadPass.PAUSED`, answered to iOS as *processing*) and never runs it without suppression; an unopenable one stops it as `Unreadable` (`COULD_NOT_LOOK`, answered *failed*). The pause is asked only after the process's admission (`UploadGateRead`), so a partial grant never pauses | `ReceivedPhotosReaderTest`, `CycleVerdictsTest`, `PassGateTest` (a process that may not create never opens the echo store), `DatabasesContract` |
| A received photo's library name is the sender's with a SnapSync mark (`ReceivedPhotoName`: a hash of the source ref, never an id), given to the library by `LibraryImport` — the library stores the name it is handed, and a join records each marked photo of the event's union that the store has no row for — read from the event window of the library (`GalleryReader.assets`; on Android the event albums' folders are part of the default gallery like any folder) — as that ref's import, BEFORE the config save and for every direction. **Nothing of a membership is shared before its recognition:** a pass that READ the union records `recognised(eventId)` in the download store, and the pass's gate withholds every pass before it (`UploadGate.recognition`, read by `UploadGateRead`) — in the app and in the extension, which reads the record read-only — and the status total stays uncounted until then; nor is anything imported — `ImportNextStaged` takes the `Recognised` only a pass that read the union mints, so a photo downloaded again is never saved twice. A union it cannot read settles nothing, so the next pass (foreground, wake, import drain, network return) asks again. A selection change that brings marked photos the store does not record withdraws the record before the snapshot is published and recognises them first (`SelectionRecognition`: `RecognitionNeeded`, `WithholdRecognition`). The mark is read, never a selection rule: suppression stays `NotEcho` over the store | `ReceivedPhotoNameTest` (the token is a stored format, pinned), `LibraryImportTest` (the import names each file with the mark), `GalleryImportContract` (`AN_IMPORT_KEEPS_THE_NAME_IT_WAS_GIVEN`), `RecognitionRecordsTest` (adoption), `RecognizeReceivedPhotosTest` (recognition), `PassGateTest`, `StatusRefreshTest`, `ReinstallIntegrationTest` |
| A process-constant value (build version and number, baked host, store page, push environment, OS version, device model) is read from the `BuildInfo` port — raw, as the platform states it — never handed to a composition as a value or a thunk; whether the OS carries the upload-extension mechanism is the `ExtensionRegistry`'s `supported`. The boot banner and a dump's environment are composed from these facts (`startProcess`, the report's `ReportedEnvironment`); a root logs only what it alone knows, after composing | `PortBundleTest`, `BuildInfoContract`, `ExtensionRegistryContract` |
| What the operating system tells a process arrives through **entry ports**, each an event port named for one external system: the app's `Lifecycle` (became active / is leaving it), `Links` (a link delivered, raw), `PushNotifications` (the token, its failure, a silent push with its `Completion`), `Ui<S, I>` (show a state; a person's intent, a live screen — generic over the screen's vocabulary, bound to `UiState`/`UiIntent` in `AppPorts`, so the port names no screen type) and `DevControls` (the build's switches and reset), and the extension's `ExtensionHost` (an invocation, answered as the platform's three-case `ProcessingAnswer`, and its end). An adapter hands deliveries over raw and in platform-independent values, through the inbound kind its handler is typed by (`docs/patterns.md`); what one runs is the composition's entry point. Each root keeps one-line forwarders for its Swift callbacks and holds no entry of its own | `EntryIntegrationTest` + `OsCompletionIntegrationTest` (the entry points' promises over the JVM root, through the rig) + `ExtensionEntryTest` + `ListenDoorTest` + shell gates. Naming is **review** |
| The partial grant's selection observer opens on composition (`Gallery.observeChanges`), on every start — a background one included, so a wake that builds no screen still learns the selection and its uploads are not withheld; the latest snapshot is kept for a consumer that attaches late | `EntryIntegrationTest` + `SelectionSnapshotLaneTest` |

**The entry table** — every delivery an event port makes, the inbound kind it is, and the entry point that takes it (`entrypoints/`, `docs/patterns.md`):

| port | handler | kind | entry point — what it runs | runs on |
|---|---|---|---|---|
| `Gallery` | `onImportPlaceholder(ImportPlaceholder)` | `Inbound` | `OnImportPlaceholder`: the import marker's write, synchronous — it must land inside the change block, before the asset is observable | the platform's change block |
| `Gallery` | `onImportSettled(ImportSettled)` | `Inbound` | `OnImportSettled`: the import's confirm or clear, persisted inline — the platform reports it once, even when the requester is gone | the platform's completion |
| `Wake` | `onWake(id, completion)` | `Granted` | `OnScheduledWake`: the prelude, then the wake's work (`WakeWork`); it holds no background time of its own — the delivery is the grant, whose withdrawal expires the run | the platform's launch handler |
| `TransferSession` | `onFinished(FinishedTransfer)` | `Inbound` | `OnUploadFinished`: the session transfer's guarded terminal write, inline — reported once (`RecordFinishedUpload` → `SessionEnds`) — then, when the app's admission admits, a detached `TopUp` pass for the freed slot | the session's delegate queue |
| `TransferSession` | `onBackgroundEvents(SessionRelaunch, completion)` | `Background` | `OnUploadSessionEvents`: waits for the drain report after it, answers the relaunch, and runs the wake's work detached | the platform's relaunch callback |
| `Download` | `onBackgroundEvents(SessionRelaunch, completion)` | `Background` | `OnDownloadSessionEvents`: waits for the drain report after it and for the stagings its events caused, answers the relaunch, and runs the wake's work detached | the platform's relaunch callback |
| `TransferSession`, `Download` | `onEventsDrained(SessionDrain)` | `Inbound` | `OnSessionDrained`: records the session's numbered drain report | the session's delegate queue |
| `Download` | `onFinished(FinishedDownload)` | `Inbound` | `OnDownloadFinished`: the download jobs' integrity check and the move into staging, inline — the platform deletes the file when it returns | the session's delegate queue |
| `Download` | `onCompleted(CompletedDownload)` | `Inbound` | `OnDownloadCompleted`: frees the window slot | the session's delegate queue |
| `Lifecycle` | `onForeground(completion)` | `Background` | `OnForeground`, host-first: records the return, asks the push service for the token again, assembles the host, runs the foreground's own work (`ForegroundWork`) and then the wake's work, all under the process's background time | the main thread (UIKit's notification) |
| `Lifecycle` | `onBackground()` | `Inbound` | `OnBackground`: records the departure, stops the foreground's watches, records the memory footprint | the main thread |
| `Links` | `onLink(LinkDelivery)` | `Inbound` | `OnLink`: data's `LinkReading` over the sealed `LinkDelivery`, kept as the opened invite (`OpenedInvites`, read by `OpenedInvitesQuery`) — a `Link` is opened whole, an `InstallReferrer` carrying an invite opens that invite and one carrying none opens nothing, a `NotALink` or `NoUrl` is logged by name; the status screen opens the waiting invite once it starts, and tells it opened (`OnInviteOpened`) | the scene delegate's or SwiftUI's thread (main); the activity's on Android |
| `PushNotifications` | `onToken(PushToken)`, `onTokenFailure(PlatformError)` | `Inbound` | `OnPushToken` (the token reaches the source the push registration observes), `OnPushTokenFailure` (logged) — no host | the application delegate (main) |
| `PushNotifications` | `onMessage(PushMessage, completion)` | `Background` | `OnSilentPush`: the prelude, the push's own receive, and — for the active event only — the wake's work, detached; the completion is answered after the push's own work — no host | the application delegate (main) |
| `Ui` | `onIntent(intent)`, `onLive()` | `Inbound` | the container's intent table (a presentation intent); `onLive` is host-first, synchronous and idempotent — it assembles the host and answers its current state before the screen's first frame | the main thread |
| `ProcessMetrics` | `onReport(ProcessMetricPayload)` | `Inbound` | `OnMetricReport`: the report accounted for before the callback returns | the provider's thread |
| `ExtensionHost` | `onProcess()` | `Answered` | `OnProcess`: the credential, membership and key re-read, then one `ExtensionPass`, its `ExtensionAnswer` answered as the platform's `ProcessingAnswer` (`REST` → completed, `INVOKE_AGAIN` → processing, `COULD_NOT_LOOK` → failed) on the invoking thread, which the runtime blocks; any throwable answers `FAILED` | the operating system's invoking thread |
| `ExtensionHost` | `onTerminate()` | `Inbound` | `OnTerminate`: the invocation's end, logged | the operating system's thread |

State whose change starts work is followed, not delivered: `Observation.follow` hands each emission to an entry point
as a delivery, and the next waits for that run — the partial grant's selection (`OnSelectionChanged`, after the
received-photo recognition withholds what it must) and, once the host is assembled, a returned network
(`OnNetworkReturned`).

| state law | enforced by |
|---|---|
| Authority lives behind ports. After process death every fact is recoverable from a durable store or the external system. This binds adapters too | **review** |
| A delivery the platform makes **once** is persisted before the entry point returns, citing proof (API contract, vendor doc, or measurement) that it is once-only. A MetricKit report is handled **inline** on the thread MetricKit delivers on (the lane law's one exception for it): `ProcessAccount` attaches it — with the app's own last memory readings, `FootprintTrail` — to the crash channel and logs it before the callback returns | **review** |
| Each kind of write to a durable port has exactly one owner (a use case or a port's guarded write), written as one transaction with the guard in the statement. Two processes running that owner is fine | **review** |
| No transport adapter holds the upload rows (`OwnUploadRows`, `WalkRecord`, `RowTransitions`, `JobRecord`, `UploadRowsReset`): the data layer's tier records a transfer's end, through `JobRecord`'s guarded write, where the platform reports it | `TransportLedgerGateTest` |
| No OS completion handler is stored in a mutable field (Kotlin and Swift): an adapter wraps the block in an immutable `Completion` and delivers it with its event, and the run answers it once — after its work, or after the expiry's grace | `OsHandlerContainmentTest` (a plain ban, no licensed owner) + the runtime (`EntryPointTest`) |
| The walk memo (`MemoizedWalk`) is built in one place, the app's own photos (`AppGraph.ownPhotos`), never by the extension or the shared `UploadGraph` | `@AppProcessOnly` on its constructor; `OptInPinsTest` pins the app composition's one opt-in |
| Mutable state touched from an OS callback thread is confined to a named lane (`@ConfinedTo`) or is a thread-safe primitive | `ConfinementGateTest` (heuristic) |

Structural rules of one concept that no type can state:

| guard | holds |
|---|---|
| `SelectionPolicyContainmentTest` | the capture-date bounds are compared in exactly one place (`CaptureRange.holds`) |
| `QueryReadOnlyTest` | the query layer calls no write of the data layer |

---

## 3. How a gate is written

- **It fails closed on novelty.** A gate derives its scope from the repository at test time (directory
  listings, package patterns, "everything not allowlisted", the extension's dependency closure). It
  never scans a hand-maintained inclusion list. New code is in scope with zero gate edits.
- **It is never vacuous.** Every source-scanning gate asserts that it scanned something, per derived
  group. A renamed directory fails the gate. It does not pass forever and does not report "pending".
- **Pins are not scope lists.** A gate may pin an **expected value** (the identity inventory, Apple's
  enum sets, the `@Suppress` inventory, the seam inventory). Each pin states why its value is fixed and
  what would change it. Exact-both-ways pins shrink with the code.
- **Text gates match references, not imports.** Fully-qualified references import nothing. Where a
  gate reads comments as code, prose must describe a forbidden shape rather than quote it.
- **Heuristic gates say so in their source**, together with what they cannot see.
- **Dead-edge analysis** (`buildHealth`, dependency-analysis plugin) runs warn-only for jvm/common
  edges. iOS-only edges have no upstream support.

---

## 4. Ports and contracts

A port's obligations are stated once, as executable **clauses**, and run against every implementation:
the mock and each real adapter. A double therefore cannot quietly answer differently from the
system it stands in for. The contract code **is** the specification of a port's clauses, and no
`openspec/` spec restates them.

- **Contract** = a hand-written list of clause values in `:test:contracts` `commonMain` (id, required
  state, the port-grid cells it covers, body with `kotlin.test` assertions). Nothing records or generates an expectation.
- **Binding** = one implementation × one `Host` (`JVM`, `IOS_SIM_KEXE`, `IOS_SIM_APP`,
  `IOS_DEVICE_APP`, `IOS_DEVICE_PHOTOKIT_EXT`) × one kind (`Fake`, `Live`, `Replay`), declaring **as a
  literal** the states it reaches. It enters a state at construction or answers `Unreachable`. Bindings
  live beside their implementations.
- **Outcomes** are `Passed`, `Failed`, `NotRunHere`, `Diverged` (replay met an unrecorded OS call) and
  `NotWithin` (a real-clock bounded wait expired). `Failed`, `Diverged` and `NotWithin` fail the run on
  every binding kind.
- **Every clause runs against something real** on some host: a `Live` binding CI runs, or a committed
  device recording. `ContractCoverageTest` enforces this.
- **Every clause declares the port-grid cells it covers** (`covers = cells { … }`, typed references), and
  every declared cell is one the grid derives from `:core:ports`. `ClauseCoversTest` enforces this
  (`docs/testing.md`, "Every clause declares the cells it covers").
- **Every grid cell is covered**: declared outright by a clause that runs against something real, with no list
  of exceptions. `ClauseCoversTest` enforces this too, so new port surface without a clause fails the build
  (`docs/testing.md`, "Every cell is covered").
- **Every adapter is covered**: every production class implementing a grid port has a real binding that names it,
  and every cell it owes is proven by one, or claimed in a `@NeverAnswers` the compiled code is checked to support.
  The mocks and the simulator substitutes answer what the real adapters they stand in for are proven to answer.
  `AdapterCoverageTest` enforces this, reading the compiled code of every target (`docs/testing.md`, "Every adapter
  is covered").
- **A declared cell must occur.** Each binding wraps its implementation in the port's recording proxy, and
  the runner fails a clause whose declared cell never occurred during its body, on every host it runs.
  `ProxyCompletenessTest` holds every proxy to the grid (`docs/testing.md`, "A declared cell must occur").
- Hosts CI cannot reach are **recorded at the OS boundary** on a device (through the rig, under
  `-Psnapsync.rig=true`) into `test/contracts/recordings/<Contract>@<HOST>[.<GRANT>|.<PRECONDITION>].rec`, and
  **replayed** against the current adapter on every build. An adapter recorded this way routes its OS
  calls through an `internal` seam (`KeychainApi`, `AppAttestApi`, `BackgroundTaskApi`, ...).

- **An entry port's contract states what its adapter delivers, never what a delivery runs.** `LifecycleContract`,
  `LinksContract`, `PushNotificationsContract`, `ExtensionHostContract` and `DevControlsContract` hold each adapter to
  handing the platform's deliveries to the handlers registered on it — raw, once, in order, through the inbound kind
  each is typed by, a completion with the message it came with — driven through the adapter's own platform entries (the calls the OS's callbacks make). What an entry
  point promises — which work runs, when a completion is answered, which deliveries assemble the host — is the
  composition's, pinned by rig tests over the JVM root (`EntryIntegrationTest`, `OsCompletionIntegrationTest`) and
  `ExtensionEntryTest`. Until the entry surface became event ports (11g1) those handler promises were contracted as
  inbound ports (`PlatformEntriesContract`, `ExtensionEntriesContract`); the delivery contracts came with clause
  completeness, which holds every port's cells to a real host.

Measured platform facts belong in clauses (so a fact that stops being true fails a test) or, where no
host can exercise them, in the adapter's KDoc with their evidence. The full mechanism, the host matrix
and the runners are in `docs/testing.md` section 4. Decision record:
`changes/archive/2026-09-22-establish-port-contracts`.

---

## 5. Complexity and coverage ratchets

Both are **ratchets carried by a written contract, not proofs**. A number may move one way. Nothing
mechanical stops a change from moving it the other way, and a diff shows it if one does. An exact-match
("fail when you improve") check was rejected for both, because it teaches people to route around the
gate.
Coverage's unit measurement is not a ratchet: a module at zero is held there by a gate with no number to move. Its
integration measurement's exceptions are one.

### Complexity ceilings (fall only)

- The detekt tier tasks gate `build`, one per scope, registered by build-logic's `snapsync.quality` plugin
  (`QualityPlugin`): `detektShellTier`, `detektComposeTier`, `detektEntryPointsTier`, `detektSchedulingTier`,
  `detektDomainTier`, `detektDataTier`, `detektApplicationTier`, `detektCoreTier`, `detektUiTier`, `detektHarnessTier`,
  `detektTestsTier`, `detektBuildScriptsTier`. There is one task per scope because a detekt 1.x rule carries one threshold
  per config.
- Every tier layers `config/detekt/_base.yml` (readings of a rule, uniform everywhere, **never** a
  number) under its own `config/detekt/<tier>.yml` (ceilings). A tier's own file is **optional**, and
  its absence means the scope sits at the baseline. The files present are the scopes still carrying
  debt. Each file opens with the contract: lowering is ordinary work, and raising needs a stated forcing
  proof in the PR. Per-site rules ratchet by a scope exclusion list that may only shrink.
- Tier membership is derived from the live Gradle model (the plugin's tier map).
  `DetektTierCoverageTest` fails if a subproject is in zero or two tiers, or if a config file belongs to
  no tier. Each task fails if it scanned zero files.
- Each layer has its own tier (`inbound`, `wire`, `format` and `ports` share `core`); the composition modules share
  `composition`. `entrypoints/` and `scheduling/`
  and their files are the first that state a **claim**, written with the code, rather than debt measured after it:
  an entry point orders steps and decides nothing (cyclomatic and cognitive 4), and the runtime stays small enough to
  read in one sitting (7 / 9). `core/`'s claim is the entry points' (4 / 4), and it was measured before it was
  written: every pure rule bound for the layer was at most 6 / 4 where it came from, and each case above 4 re-checked a
  nullable or sentinel input (`docs/patterns.md`, "The domain"). `data/` claims 3 / 3 — it translates, and a function
  that needs more is deciding (`docs/patterns.md`, "The data layer") — and `application/` the same, measured on its
  first use case. A `when` whose every entry is one expression counts once (`_base.yml`'s reading of
  `CyclomaticComplexMethod`): the exhaustive `when` the layer rules demand selects over a fact's cases, and counted per
  entry it would measure the fact's size, not a decision. `inbound/` sits in `core`.
- **`detektAppShell` is not a tier** and must never be folded into one. It is the shells' decision-free
  proof, and its value comes from the number being 2.
- Compose trades `LongMethod` against `LongParameterList`, which is why `ui.yml` still holds three. A long
  parameter list is fixed by bundling, and a bundle is a constructor whose fields are counted; a long method is
  fixed by extracting composables, and each extraction is a new function with a new parameter list. Measured: two
  genuine extractions took `StatusScreen` from 138 statements to 71 while the tier's totals rose. Those two numbers
  are not debt to grind down.
- `api/`: cyclomatic complexity only, via the project-local `deno lint` plugin
  `api/src/lint/complexity.ts` (tested by `lint-complexity.test.ts`). Length, parameter and nesting
  rules are deliberately not mirrored. Swift has no ceiling (it is pinned by `SwiftShellGuardTest`), and
  `site/` is out of scope.
- Constraint: detekt's embedded compiler lags the repo's Kotlin. Newer syntax can fail to parse
  everywhere at once. This expires with detekt's next stable major.

Decision record: `changes/archive/2026-08-27-add-repo-wide-complexity-gates`.

### Format, Compose and platform lint

- The same tier tasks also run ktlint's formatting rules (`detekt-formatting`) and Compose's rules
  (`compose-rules`, the 0.4 line, the last built for detekt 1.x). Their readings sit in `_base.yml`.
  A formatting finding is fixed by the tool: `./gradlew <tier task> -Psnapsync.detektAutoCorrect`.
  A source-scanning guard must tolerate the wrapped shape ktlint writes (one argument per line, a
  trailing comma), or it silently stops seeing what it scans.
- Android Lint gates `build` through `:app:android:check` → `lintRelease`: the store variant and every
  module it links (`checkDependencies`), any warning failing, no baseline. It is the nearest local
  equivalent of Google Play's release recommendations, which have no API — it does not reproduce them
  (Play's SDK Index judges transitive dependencies; lint only the declared ones).
- Xcode treats warnings as errors in both iOS targets (`Config.xcconfig`), enforced where Xcode builds:
  CI's `ios-build`.

### Coverage

Every module describes its own coverage, in its own build file: a `coverage { }` block (`snapsync.coverage`,
`build-logic`'s `CoveragePlugin` and `CoverageDsl`, applied to every module by `snapsync.conventions`). It settles each
of two measurements, with a target or with a `skipped(…)` and its reason, and the comments beside a number record its
history:

```kotlin
// core/data/build.gradle.kts
coverage {
    skipped("generated by SQLDelight …", packages = listOf("app.snapsync.data.store.db", …))
    unit(alsoCoveredBy = listOf(":test:data", ":adapter:generic:mock"))
    integration(instructions = 86, branches = 64)
}
// ui/screens/build.gradle.kts
coverage { unit(); integration { skipped("Compose: the suite stays at the intent level") } }
// adapter/generic/mock/build.gradle.kts
coverage { skipped("test equipment: …") }
// test/integration/build.gradle.kts — the goal, where it is judged
integrationCoverage { goal(instructions = 95, branches = 85) }
```

- **`unit(…)`: the ZERO gate.** The module's `coverageZero` task (on `check`) reads its own Kover `report.xml` and fails
  on any missed `INSTRUCTION` or `BRANCH`. No number, no percentage: a miss is fixed by a test. The report is filtered
  to the module's OWN classes, so another module's tests count only when its block names it in `alsoCoveredBy` (below,
  "Credit").
- **`integration(…)`: what `:test:integration` ALONE covers.** The suite's `integrationCoverage` task (on `check`,
  right after the suite) reads the suite's own Kover binary report through Kover's report library, against each
  module's JVM classes less its skipped packages, and compares whole percentages. It credits no module and changes no
  unit measurement: it judges the SUITE. The goal is the suite's `integrationCoverage { goal(…) }`; a target left out is the goal, a higher one is
  stricter, and a lower one is an **exception**: the module's measurement on the day it was set, rounded down. An
  exception only rises: lowering one needs a reason stated in its PR, a ratchet carried by review like the complexity
  ceilings, not by a check. What IS checked: an exception the module no longer needs (it meets the goal) fails the
  build, so the list shrinks on its own and cannot go stale. A module with no branches meets any branch target. Every
  run writes `test/integration/build/reports/integration-coverage/summary.md`: each module's measurement, its target,
  and the gap to the goal. A measurement that moves between two runs of the same code is a flaky test to fix, never a
  margin. Of what the zero gate excuses (below), only a throw of `InvalidProgramState` is excused here, through the
  SAME reading (`CoverageZero.unreachable`) — taken out of the totals, so "make the state an `InvalidProgramState`"
  ends a branch for both gates alike; the compiler glue is not, since a percentage absorbs it.
  The wiring modules are held at 100/100, the suite being the test written for them, and the entry points too.
- **`skipped(reason, packages)`: the one way to leave something out**, its reason read in review. WHERE it stands
  decides what it skips: at the top of the block, the module from every measurement; inside `unit { }` or `integration { }`, only
  that measurement. With `packages`, only those packages; without, everything at its level. Today it removes generated
  code (SQLDelight's, the wire's, Compose Resources' `Res` accessors), the integration measurement of the adapters and
  of the Compose modules, and the test equipment. A module whose measurement swings is skipped, never held at a
  lowest-seen floor — as `:core:scheduling` and `:core:inbound` were until phase 3g-b removed the polling tests
  that reached them only in a process death's race window.
- **Every module decides.** `coverageEntries` (on `check`) fails, naming it, when a module with a JVM target outside
  `:test:*` declares no `coverage { }`, or a module Kover cannot measure declares one; a block that leaves a measurement
  unsettled, or contradicts itself, fails configuration naming its build file. So no module is measured — or left out —
  by nobody's decision.

**What the zero gate excuses: compiler-generated glue, recognised by its shape — and a throw of
`InvalidProgramState`.** Glue is code a compiler emits that no input can reach. Besides that one throw, nothing a person
wrote is ever excused, there is no opt-in annotation or comment marker, and a shape joins only as code in
`CoverageZero` with tests pinning both what it excuses and what it must not. Every excusal is printed by the task, so
the glue's growth stays visible. The shapes today:

*A throw of `InvalidProgramState`, on its lines.* The one thing a person writes that no input may reach: a state
guaranteed never to happen (`docs/patterns.md`, "Invalid program state"), which no test can therefore reach. It is the
deliberate exception to "nothing a person wrote", and it is narrow: recognised in the bytecode as exactly `new
InvalidProgramState; dup; ldc "<because>"; invokespecial <init>(String); athrow` — an elvis's `pop` before it included —
and the ONE conditional arm (a jump's or a switch case's) that alone leads into it, each on the line it is attributed
to. A block entered by plain fall-through or a `goto` — something else runs on the way in — excuses nothing; a block
several arms lead into excuses its instructions only; an exception of any other type is never this shape. Whatever
else the line misses is judged as ever.

*Compose declaration glue, only on declaration lines.* The Compose compiler attributes its generated glue — restart groups, the `$changed`/`$default` bits, `skipToGroupEnd`, `updateScope` — to the declaration
lines of a `@Composable` function, from `fun` through the body's opening `{`. Those lines' misses are excused and
counted; the task prints, per module, how many lines, instructions and branches it excused, so growth is visible. A
body line is excused only by the two shapes below. `@Composable` itself is the mark: there is no opt-in annotation and no comment marker.
Composable **lambdas'** own restart glue never shows as a miss — measured 2026-10-07, every miss inside one of
`:ui:*`'s 67 is a branch someone wrote (`if (body != null)`, `when (cause)`). The declaration ranges come from a source scan (bracket-balanced,
so a function-type parameter's `->` or a default value's `=` ends nothing), **checked against the bytecode**: every
composable method in the report must match a scanned declaration in the same file and the reverse, or the gate fails.
A scanner that misreads a signature therefore breaks the build rather than excusing the wrong lines.

*A remembered lambda's arms, on body lines.* A lambda literal in a composable body is cached, and the cache's test — the
`$changed` mask checks (read through the compiler's `$dirty` copy) and `Composer.changed`/`changedInstance`, feeding one
stored boolean — takes its other arms only on a recomposition, which no screen test performs. The gate reads the shape
from the bytecode and allows the line at most that shape's instructions and two arms per jump; any more fails. Because
the allowance is an upper bound, it is withheld from a line that also holds a user branch, or code of another method the
report does not list as run in full. Kover leaves a lambda whose body sits on its caller's line out of its method list
while counting that body in the line (measured 2026-10-08), so **a lambda's body goes on its own line** — there an unrun
body is a miss of its own. The gate's failure says so.
The compiler's trace guard, `if (isTraceInProgress()) traceEventEnd()`, shares a composable's last body line (a
`return remember(…) { … }` included); Kover filters it, so it is not counted as that line's user branch — recognised
exactly, an `ifeq` on `isTraceInProgress` skipping only the trace call. A branch someone writes on it stays theirs.

*The dead arm of an exhaustive `when`, on body lines.* Kotlin ends a `when` over a sealed or enum subject with a
`NoWhenBranchMatchedException` no value reaches; inside a composable the compiler wraps it in a replace group. Kover
filters the throw, but not the group calls or a conditional jump into the block (a sealed `when`'s last `is` arm). The
allowance is exact: those instructions, and one arm per such jump. A word-picking `when` is not left to this rule but
restructured: a plain mapping function chooses (testable with no Compose) and the composable renders once —
`:ui:screens`' `Phrase`.

*kotlinx-serialization's zero-mask missing-field check.* In a `@Serializable` class whose every field has a default,
the plugin's deserialization constructor still opens with `if ((0 & seen) != 0) throwMissingFieldException(…)`: the
required-field mask is the constant 0, so no document can take that branch. It is recognised in the **bytecode** — a
constructor taking a `SerializationConstructorMarker` whose code opens `iconst_0; iload_1; iand; ifeq` and whose
skipped block ends in the plugin's throw — and only that block's instructions and its one branch are excused, on the
class's header line; whatever else the line misses still fails. A class with a required field has a non-zero mask,
and is never excused: its check is real, and a test that leaves the field out reaches it. The task prints each site.

The gate also
refuses a report whose per-line counts do not add up to its own totals, so a Kover upgrade that moves the counts fails
loudly instead of passing vacuously. Logic: `build-logic`'s `CoverageZero` (the report's judgement), `ComposeGlue` and
`ComposableDeclarations` (Compose's glue and the declarations it is checked against), `SerializationGlue` and
`InvalidProgramStateGlue` (the other shapes), over `Bytecode` (tested by
`CoverageZeroTest` and `ComposeGlueTest`, which the root `check` runs).

**Inline functions need no exemption.** Kover credits inlined execution back to the inline function's own source
lines; an uncovered inline helper is untested, not unmeasurable. An inline body with many branches is still awkward to
measure — the branches a test reaches through one copy were not credited together with another copy's (`invocation`,
2026-10-08) — so keep such decisions in an ordinary function the inline one calls (`logEntered`/`logReturned`).

**Credit: `alsoCoveredBy`, and only where a placement rule put the tests elsewhere.**
- A module's own tests always count. Another module's count only through its block's `alsoCoveredBy`, which merges
  that module's coverage data (Kover's `kover` dependency) — never its classes. The modules whose tests cover others
  apply Kover themselves: `:adapter:generic:mock`, `:test:feature`, `:test:data`, `:test:integration`.
- Unit placement: `:core:ports` is also covered by `:adapter:generic:mock` and `:test:feature`, the data layer by
  `:test:data` and the mocks' suites (the legacy copy runs over the in-memory stores), the application layer by
  `:test:data` and `:test:feature`, the query layer by `:test:data`, and `:ui:components` and `:core:presentation` by
  `:ui:screens`.
- **The wiring**: the composition modules, `:core:entrypoints` and `:app:jvm` are also covered by
  `:test:integration`. "The composition layer" (§2) says the wiring graph is not unit-tested and IS smoke-tested by
  the integration surface, so for these the integration tests are the tests written for the code. The entry points
  join them for the same reason (and are covered by the mocks' suite too, which composes the process's metric-report
  entry point): each orders its trigger's steps and decides nothing (the `entrypoints` tier and `EntryPointShape`), and
  the integration surface delivers every trigger from the operating system's side, which is exactly what they are for.
  The suite covers no other module's unit measurement: a thick integration suite cannot stand in for a thin unit suite
  — which is why what it covers of the rest is a measurement of its own (`integration`), judged apart.
  What integration reaches in the wiring is construction and connection only: it holds no decision (`detektAppShell`),
  so every branch the graph needs is a unit-tested layer's, and the JVM root composes through the same
  device bundles (`AppDevicePorts`, `ExtensionDevicePorts`) the phone's roots do.
- A `:core:*` block names its covering modules as STRINGS, and that is no edge: the plugin adds the `kover` dependency,
  so the build file declares no `project(…)` outside its layer's law (`ModuleSetTest`, which reads exactly those), and
  the module graph leaves `kover*` configurations out. The covered module is where the measurement is merged, so the
  name stands in its block — not in the covering module's file, which would have to reach into another module's
  configurations.

**Declaring nothing**: the test modules (`:test:*`; the mocks and `:app:desktop` declare a skip, as test equipment), the
non-JVM shells (`:app:ios`, `:app:ios:extension`, `:app:android`) and the iOS/Android adapters, which Kover cannot see
(it is JVM-only) and which the port contracts hold to clause completeness instead (§4).

Decision record: `changes/archive/2026-08-28-add-coverage-bounds`, **partly superseded** by this section. D1 (Kover's
default engine), D3 (`INSTRUCTION` and `BRANCH`, never `LINE`) and D5a (credit follows placement) stand. D2 (aggregate
+ package-floor percentages) gives way to the own-`report.xml` gate — the very alternative D2 deferred — module by
module as each reaches zero; every module reached it, and the floors are gone. D4 stands except that integration now
covers the wiring modules and the entry points, and has a measurement of its own. D5 (the composition
unbounded) rested on Kover crediting per project while it was a package of `:core`; it is its own modules now, so
it is measured. D6 (seed at `floor(measured)`) gives way to zero.

---

## 6. The build

Every piece of build logic lives in `build-logic/`, an included build: the convention plugins and their tasks
(`src/`), and the project's detekt rules (`detekt-rules/`, a library the detekt tasks load by coordinate). The root
build file holds only the dependency-analysis classpath force (`buildscript`) and its `plugins { }` block: every
plugin `apply false`, so each module shares one classloader per plugin, and `snapsync.conventions`.
`DeletionLedgerTest` fails on any other top-level statement there.

`snapsync.conventions` (`ConventionsPlugin`) is what every module has the day it is included, configured from the
root so no module opts in: warnings as errors, FULL test failures, the derived `iosPlatformTest`, the iOS test
compiles off a Mac, the Kotlin/Native backend threads, dependency-analysis, and the build logic's own tests on
`check`. It applies the root's other plugins:
- `snapsync.quality` — every detekt task: the shell proof and the tiers (section 5). The build logic itself is in
  the `buildscripts` tier, its tests in `tests`;
- `snapsync.archive-names` — archive names from the module path;
- `snapsync.coverage` — every module's `coverage { }` (section 5);
- `snapsync.diagrams` — the diagrams (below).

The plugins a module applies itself, by what it is: `snapsync.targets` / `snapsync.layer` / `snapsync.android` (the
targets a layer may compile for), `snapsync.wire`, `snapsync.native-strings`, `snapsync.rig`, and the rest under
`build-logic/src`. A task that needs a library the build itself must not load runs it in a Gradle worker with
classloader isolation, the library `compileOnly` in build-logic (`snapsync.wire`'s generator).

### Generated files are written by the build

A file generated from the repository and committed — so it reads on GitHub, and so Xcode, which runs no Gradle, can
use it — is written by `./gradlew build`, by the build-logic task that owns it, only when its text changed. The task
says so (`rewrote <path>: commit it`). Those files are:
- the diagrams under `architecture/`;
- the OS-shown strings (`nativeStrings`, section 7, "Localization").

There is no freshness test per generator. One check after the build, in `ci.yml`'s `build` job and in `/ship`'s
local gate, asserts the whole working tree is clean (`git status --porcelain`, untracked files included). So a stale
file, a generator that is not deterministic, a new output nobody committed, or a test that leaves a file behind all
fail the same way, with the fix named: run `./gradlew build` and commit what it wrote. A generator is
byte-deterministic for that reason: code-point order, `\n`, UTF-8, no timestamp, no absolute path.

### Diagrams

`architecture/` is **generated, never drawn**: Mermaid-in-Markdown and tables, written by `snapsync.diagrams`
(`architectureDiagrams`, part of `build`).

| file | what |
|---|---|
| `modules.md` | the module graph, from the Gradle project model read at configuration time: every `project(…)` dependency of every configuration, each module in the subgraph of its top-level directory. `kover*` is excluded, and the file header says so |
| `ports.md` | every port — an interface of `:core:ports` extending the `Port` marker, directly or through another port — and every production class implementing it, by module, the mocks in their own column. An implementation counts where its file imports the port by name; implementing a port implements its super-ports. A scan that finds no port fails |

- No other diagram is kept. The layers ARE the module graph. The composition's graph classes read plainly
  (`docs/patterns.md`). What happens on each trigger is `entrypoints/`' two files, read top to bottom. Which contract
  clauses prove a port is the port grid's (`docs/testing.md`).

---

## 7. UI design-system rules

Screens (`:ui:screens`) are written only against the `App*` components in `:ui:components`. All
Material 3 styling lives in the components module (the "skin"), so a future skin (for example,
Cupertino) or a swap of the QR library changes one module.

- **Material 3 containment**: only `:ui:components` depends on Material 3, the icon artifact and the QR
  library. The compiler enforces this (`implementation` edges). `:app:desktop`'s control panels are test
  equipment and use raw Material 3, never `App*`.
- **Semantic-only signatures**: an `App*` parameter carries data and meaning (text, fractions, sealed
  semantic values, callbacks, `@Composable` slots). It never carries appearance: no color, text style,
  shape, elevation, `Modifier`, or Material 3 type. **review** (ungated).
- **Components decide nothing**: an `App*` component shows what it is told. Which state is shown, and what a tap
  means, are presentation's (`StatusContainerHost`). **review** (ungated).
- **Design-time variants are distinct components** (`PrimaryButton` vs `SecondaryButton`,
  `AppConfirmDialog` vs `AppDestructiveConfirmDialog`), never a role or flag parameter. **Runtime-data
  variants are sealed values** (`StatusIndicator`). **review**
- **Containers own convention-bearing arrangement.** Insets, title placement, centering, the bottom
  action cluster (`ScreenLayout`), hero layout (`StatusHero`) and QR plus caption (`AppQrCode`) come
  from containers. Raw layout primitives are only for meaning-free geometry. **review**
- **Accessibility and consequence lines**: every interactive row has an explicit role and state and is
  one tap target, a dimmed control stays in the semantics tree, reduce-motion is honoured, and a
  consequence line is derived from the state passed in, never hard-coded. Held by `:ui:screens` UI tests
  and review.
- **Palette**: the skin meets measured AA contrast in both themes over a frozen palette, with no new
  colour token per component. Skin-local, no signature change.
- The inventory grows only when a screen needs it. KDoc on each component is its reference.
  `./gradlew :app:desktop:run` reaches every state through the world inspector's levers.

### Localization

The app ships in English (the base) and German, and is built so that another language is a translated file rather than a
code change.

- **Every word a person reads is a string resource.** `:ui:screens` and `:ui:components` each carry
  `src/commonMain/composeResources/values/strings.xml` (Compose Multiplatform Resources), read with
  `stringResource` / `pluralStringResource`. `values/` is the base language; a translation is
  `values-<lang>/strings.xml` with the same names. A screen's words live in `:ui:screens`, the design
  system's own (the status line, the calendar) in `:ui:components`. Gate: the `HardCodedUiText` detekt
  rule (build-logic's `:detekt-rules`, scoped in `config/detekt/_base.yml`) fails on a letter-bearing string
  literal in either module's main code. A string nobody reads (a bug report's screen label) says so with
  `@Suppress("HardCodedUiText")`.
- **Only `ui/` turns state into words.** The domain carries facts: `UiState` holds a typed
  `ScreenMessage` where it once held an English sentence, and `CutoffFormatter.coarseDuration` answers a
  `CoarseDuration`, not "2 weeks". So `:core:*` has no resources dependency, and a message nobody
  worded is a missing `when` branch at compile time (`Words.kt` in `:ui:screens`).
- **Dates are the platform's.** `UiState` carries dates as values (`LocalDateTime`, `LocalDate`), never as text.
  `DateFormats` (`ports`) formats a CLDR skeleton (`yMMMd`, `jm`); the `DateFormatting` port answers one through
  `java.time` on the JVM, ICU on Android and `NSDateFormatter` on iOS. `:ui:components` holds no platform code: the
  root hands the screen a `ScreenDates` over the port, and `StatusScreen` provides the formatter for the resolved
  language as `LocalDateFormats`. Month names, day/month
  order and the 12/24-hour clock follow the user. It formats in the language the strings resolved to
  (`date_language` in each translation) and in the device's region, so an English app on a German phone
  reads `5 Oct`, 24-hour time, and never German month names next to English words. `StatusScreen` provides
  it; nothing in the design system spells out a month. Write a skeleton's letters in the order
  `y M E d j m`, which the JVM requires.
- **Text the OS shows is generated from the same file.** Keys prefixed `ios_` / `android_` in
  `:ui:screens`' `strings.xml` are not shown by a screen. The `snapsync.native-strings` plugin writes them
  into iOS's `InfoPlist.xcstrings` (plus the base value in `Info.plist`) and the Android adapter's
  `res/values/strings.xml`. Gate: `HardCodedSystemText` fails on a literal passed to a notification's
  text.
- **The language list is declared once**: `nativeStrings { locales }` in `ui/screens/build.gradle.kts`,
  base first. The plugin turns it into iOS's `CFBundleLocalizations` and Android's
  `locales_config.xml`, so the OS offers exactly those languages, including in its per-app language
  setting. There is no language picker in the app. The generated files are committed and written by
  `build` (`nativeStrings`, section 6, "Generated files are written by the build"); the same task fails while
  either UI module's `values-*` directories disagree with the list.
- **Tests find nodes by resource, not by copy.** Screen tests look a node up by
  `str(Res.string.x)` (`TestStrings.kt`), so a copy edit breaks no test. The UI test JVMs pin `en-GB`,
  so a date assertion holds on any machine.

**Adding a language:** add `values-<lang>/strings.xml` to both UI modules (including `date_language`),
add the tag to `nativeStrings { locales }`, and run `./gradlew nativeStrings`. The store listing, its
screenshots and the web join page stay English for now.

---

## 8. Backend (`api/`)

A **streaming proxy** in Deno/TypeScript with **Hono** on **bunny Edge Scripting**. The device-facing
origin is `snapsync.stho.net`, a CNAME in our Bunny DNS zone to the pull zone that fronts the script.
Owning the name means a runtime swap is a DNS repoint, never an iOS build. It mints events, streams
upload bytes into a bunny native Storage zone, records facts in a relational store (bunny Database,
libsql), and serves listings. Downloads are **presigned S3 GET URLs** fetched straight from bunny's S3
endpoint (no proxy, no on-device SigV4). bunny is load-bearing: there is no standby, and the client
retries forever, so an outage delays uploads but loses none.

> A CDN pull zone sits between every device and the script. Anything the device relies on must hold
> **as observed through the pull zone**. The pull zone may answer `OPTIONS` itself, and it caches on
> the origin's `Cache-Control`. That is why listings send `no-store, no-cache, max-age=0` (bunny
> documents `no-cache`).

**The HTTP API is not a user contract.** It is bounded by the minimum app version (`426`, below),
and each version is frozen only while builds that speak it are served. `/api/v1`, what builds below 0.4
spoke, is no longer served: every device runs 0.4 or later.
`/api/v2` is frozen for **compatible changes only** since 0.4, its first App Store build: an addition 0.4
ignores (a new route, a new optional response field) is fine, and anything 0.4 would misread is breaking.
A breaking change goes to `/api/v3`, or ships with a deliberate `MIN_APP_VERSION` bump that sends 0.4 to
the update notice. `v2.test.ts`'s existing tests must pass unedited; new ones may be added.

### Where state lives

**The database holds the facts. Storage holds the bytes.** Every row is **rebuildable** by a device
round-trip (a manifest republish or a re-attestation), and no user-visible fact exists only in the
database. That is what makes the platform's public-preview limits acceptable (1 GB per database, a
10 s data-loss window on failover, 32,766 bound parameters per statement). One gap is stated: under v2,
a lost `resources` row is repaired only by a re-upload, which happens only if the device does not
believe the upload landed.

```
events        id, name? (NULL exactly for a sealed event, migration 0017), created_at, starts_at, ends_at,
              capacity (a placeholder nothing reads), lifetime_seconds,
              closed_at?, completed_at?, last_landed_at?, zone? (migration 0009),
              key_id? (migration 0011: an encrypted event's key id; NULL = plain),
              open_until? (migration 0014: backfilled; NULL only for a row the previous bundle wrote),
              sealed_meta? (migration 0017: an encrypted event's sealed metadata record; CHECK: plain = name only,
              sealed = key_id + sealed_meta, legacy encrypted = key_id + name)
memberships   (event_id -> events CASCADE, device_id), state in {sharing, settled, done, left} (CHECK),
              joined_at, manifest_version?, received_position? (migration 0014), receives_nothing (0016)
event_assets  (event_id, device_id -> memberships CASCADE), asset_id, creation_date, roles (JSON array)
              + index (device_id, asset_id)
resources     (event_id, device_id -> memberships CASCADE), asset_id, role, path (the full storage
              path), content_type, filename; PK (event_id, device_id, asset_id, role) (migration 0010)
devices       device_id, created_at, attest_* (NOT NULL; attest_platform ios|android, what proved the
              key), push_* (nullable together)
union_log     seq AUTOINCREMENT, (event_id -> events CASCADE), kind = gained, device_id?, asset_id?
              + index (event_id, seq)
```

The generated snapshot is `api/schema.sql` (section "Database" below).

- **Existence is a row.** An event exists iff its `events` row does. No route deletes on touch, and
  only the nightly sweep deletes. So a `404` is a real deletion, and a client may use it as a witness
  that the event is gone — past its own deadline, the app marks the membership deleted and keeps it. A **completed**
  event (below) keeps its row until its deadline precisely so it can answer "completed" instead of `404`.
- **Completion, with no close** (`changes/early-event-completion`, `changes/back-dated-events`). Every event is
  **open until** a date fixed at its create (`open_until`): its range end, or — for an event created after its
  end — the date its host chose, at most the window maximum after the later of the end and now. Once the member's own
  range has ended and its device has looked at what it shares since then (or shares nothing, so has nothing to look at)
  the device publishes its manifest with `final: true` — its membership becomes `settled`; the bytes may still be
  uploading — and reports the union position up to which it holds every photo of the others
  (`PUT …/received`, monotonic, `received_position`) — a share-only device says instead, at once and whenever, that
  it receives nothing (`receivesNothing`, `receives_nothing`; any later report without it clears it). Nothing closes
  and nothing is stamped: a member is **done** when it is `settled`, every role it declares has landed and it
  receives nothing or its `received_position` reaches the event's union head; an event is **finished** when it is past `open_until`, someone joined it, and every member still in it is
  done. Both are SQL predicates in `src/db.ts`, read on every use (the sweep, the event read), so a late join, a new
  own photo or another member's gain makes them false again by itself. Joining, renaming and changing an asset set
  keep working until the photos are deleted. An event closed by the OLD rule keeps its `closed_at` and the old
  refusals (`410 {error:"closed"}` for join and rename, `409 {error:"closed"}` for a changed set) until it is
  dropped; and the event read serves builds that predate open-until dates a **derived** `closedAt` — the stored
  stamp, else `open_until` once it has passed and every member still in it has shared everything — which is when
  such a build may leave without missing a photo (`PENDING_CLEANUPS.md`). A leave is recorded before it is sent
  (`PendingLeaves`) and re-sent by every wake until the backend confirms it — which is what makes EMPTY
  dependable. The byte route stamps `last_landed_at`; the **clock** is
  `max(open_until, last joined_at, last_landed_at) + 3 days`.
- **The host's zone is a label, beside the instants** (migration 0009). `starts_at`/`ends_at` stay canonical
  `…Z` instants, because they are compared lexicographically as capture cutoffs and installed apps parse that
  shape. `zone` is the host's IANA name (`Europe/Berlin`), sent with the create, which only the event page
  reads, to name the dates as the host chose them. An absent or unusable zone stores NULL (rendered in UTC)
  and never refuses the create; it is never on the wire.
- **Membership is one state column** (migration 0008), never inferred from objects: `sharing` (joined,
  not settled), `settled` (its last publish declared its share settled; it follows each
  publish both ways), `done` (left having everything: settled, every declared role landed, and the
  device's `?received=true` — it holds every photo of the others), `left` (left with anything missing).
  `sharing`/`settled` are PRESENT (pushed, counted in the waiting line, they hold an event unfinished);
  `done`/`left` are GONE, and are told apart for observability only — nothing decides on the difference.
  A manual leave and the app's own leave are the same request. A gone member's assets stay in the
  union; a join puts any state back to `sharing`.
- **A resource belongs to one event** (migration 0010, `changes/per-event-storage-layout`): a child of
  its membership, so completion and a dropped event take it. Its `path` is where the bytes are —
  `files/<eventId>/<sha256>` for every byte written since, `files/devices/<deviceId>/<key>` for one written
  before — and every reader follows it; none composes a layout. A photo in two events is two resources and
  two objects. The wire's `key` (`<assetId>-<role>.<ext>`, the union's) is derived from
  `asset_id`/`role`/`filename` (`objectNameFor`), so installed clients see the string they always did.
  It hangs off the membership, not `event_assets`: a publish deletes and re-inserts those, and a byte may
  land before its declaration.
- **Row existence is the upload record.** There is no upload-state column, and the backend records only
  bytes it watched arrive. "Pending" = declared by a manifest with no `resources` row.
- **The union log is what makes a union read incremental** (`changes/incremental-union`). Its `seq` is
  the cursor a client reads from. A `gained` row is written exactly where the server already learns that
  the union gained an asset, which is also where the push fires: the v2 byte route (the events
  `eventsCompletedBy` answered, committed in ONE batch with the `resources` row) and the v2 publish (the
  `gained` set of `publishUnionChanges`, inside the publish batch under its version and closed-event
  gates). A delta is the `gained` rows past the cursor, put through the union's own
  declaration and completeness filter. What a publish withdraws and who read the union are the delivery
  record's (`withdrawn=`, `reader=`; "The request log" below), never rows here: nothing read them, and
  migration 0017 deleted the `removed` and `fetch` rows earlier builds wrote. The log is
  deleted when an event completes (with the photos, capability `privacy-security`), and cascades with
  the event row. Assets a v1 build wrote before v1's retirement are in no `gained` row; they reach readers
  through full reads.
- **A `devices` row exists iff the device attested.** Push registration is an `UPDATE` and never
  creates a row.
- **Bounds** (policy in `deployments/components/policy.json`): capture window `[startsAt, endsAt]` of
  at most 30 days, which bounds uploads only and closes nothing. Lifetime `lifetime_seconds` (30 days)
  is stamped as a duration. The delete-by is derived per read as `max(createdAt, openUntil) + lifetime`
  (`src/lifecycle.ts`, shared with the sweep): 30 days after the event stops being open, so a 30-day event is not
  cut off on its last day and a back-dated one is not born expired. An event admits any number of devices; the
  `capacity` column is a placeholder kept for a rollback and for released apps (`PENDING_CLEANUPS.md`). What
  grows with the member count is bounded per wake instead: one wake reaches at most `FANOUT_MAX_RECIPIENTS`
  members, chosen at random (`deployment.md`, "Edge Scripting limits worth knowing").
- The **nightly sweep** (`src/scripts/sweep.ts`, a GitHub Actions workflow, since Edge caps requests at
  50 subrequests / 30 s CPU) gives each event one `sweepVerdict` (`src/lifecycle.ts`): **drop** the row
  past its delete-by; **complete** an ever-joined event that is finished, empty (no member present) or past
  its clock — memberships (and so `event_assets`) and the union log deleted, `closed_at`/`completed_at` stamped, the row
  kept — then deletes the byte folder `files/<eventId>/` of every event that no longer lives (one recursive
  DELETE; any such folder it finds, so a crash or a byte that landed after the completion is healed by the next
  run), and collects a `devices` row only once no token minted for it can still verify. Its delete decision,
  and the read of which events live, run in an interactive transaction (primary), not an ordinary read.
  `files/devices/` (the bytes written before migration 0010) is never touched by it.
  Details are in `docs/deployment.md`.

### The request pipeline

Top-level Hono middleware in `src/app.ts`, in this order:

0. **Request log** (`src/request-log.ts`, below). First, so every request writes its record, a gate's
   refusal included.
1. **Maintenance gate.** While the bundle carries the maintenance flag, everything under the `/api/`
   and `/web/` **prefixes** answers `503` with `Retry-After` and no-cache, touching no store. It runs before
   authentication. The other root routes keep serving.
2. **Version gate** (v2 only). The `x-snapsync-app-version` header must be at least `minAppVersion`
   (in source, `src/config.ts`, pinned by `min-app-version-floor.test.ts`, deliberately not config).
   Absent, unparseable and too old all get `426 {error, minAppVersion}`.
3. **Token gate.** Every route requires a device token: a backend-minted, HMAC-signed bearer that can
   only be obtained through App Attest. **Verifying touches nothing** (one HMAC compare, no store read,
   no Apple call), because it sits on the streaming upload hot path. A route that needs the device record
   reads it itself afterwards. The **closed list** of exceptions: the three `/api/vN/attest/*` issuers;
   `OPTIONS` anywhere; `GET`/`HEAD` on `/`, `/join`, `/join/<one segment>` (the event's own page),
   `/.well-known/apple-app-site-association`, `/health`; `GET`/`HEAD` on exactly `/web/events/<id>/photos`
   (the event page's own read, below — a browser cannot attest, so possession of the event id is its read
   capability, `changes/separate-event-page-from-device-api` D1); `GET`/`HEAD` on `/api/vN/events/<id>` and
   `/api/vN/events/<id>/files` (read by app builds that send no token; the event page no longer reads them); and `GET`/`HEAD` on the
   download redirect `/api/vN/events/<id>/files/devices/<d>/<asset>/<role>` (`isDownloadRedirect`, which
   the version gate exempts too: the OS's download transports fetch it with no app header). The gate
   normalizes the `/api/vN` prefix before matching. The two event reads check a token **themselves** when
   one is sent (`401` for a bad one), so their `401` is a verdict on that token although the gate never runs
   (`verifiesToken` on the client); the app sends one on both. `GatedPathPinTest` keeps the client's copy of this list in step.

4. **Device binding** (per route, `actsFor` in `src/routes/support.ts`). The token names the device it was minted for,
   and every route that names a device in its path (join, leave, manifest, byte upload, device listing, push
   registration) refuses any other id with `403 not this device`, before reading or
   writing anything. It is `403`, not `401`: the credential is valid, and a `401` from a gated route makes
   the client drop its token and re-attest, which would loop. Device ids are not secret (the ungated union
   lists every member's), so without this any genuine install could act as any member. The binding judges
   the router's DECODED parameter, so a percent-encoded id cannot slip past it.

What the binding does not close: `/attest/token` mints for whatever `deviceId` its body names, so a genuine
install can still attest AS a known id (overwriting that device's key) and then hold a token bound to it.
Refusing a re-attestation for a known id would also refuse the legitimate one after a reinstall, which
keeps the Keychain device id but loses the App Attest key.

### The request log

Every request produces **exactly one record**, when its response body has finished, and that record is
the only thing the edge script writes (decision record `changes/api-request-log`). The sink writes it twice:
as one console line (bunny's Edge Scripting log in production; the console under `deno task dev:local` and
the ephemeral test backend) and as one row of the store's `request_log` table (migration 0012;
`src/request-log-store.ts`), in production and the dev rig alike. Tests collect lines instead.

```
<ISO> [<reqid>] <METHOD> <url> <status> <ms>ms v=<declared app version|-> in=<bytes|-> out=<bytes> <fields…> [errors=<tag>,…]
```

**Why the row.** bunny keeps a ring of the script's **last 100 console lines**, not a time window, so a burst
of errors pushes out everything before it. The row is the history: kept 30 days (the nightly sweep deletes
older ones, `docs/deployment.md` §3), read with `deno task logs` (filters and statistics, §2 "Reading the
api's log"). The columns are the request's own shape (`at`, `reqid`, `method`, `url`, `status`, `ms`,
`bytes_in`, `bytes_out`); every other fact, the declared app version included, is a key of `fields` (the delivery
record's contract, below) — one JSON object, where a key recorded once holds its value, one recorded
again an array of its values — and `errors` holds the fault tags, space-joined and each once, `NULL` when
there were none (`' ' || errors || ' ' LIKE '% fanout %'` finds one).

**The response's end waits for the row.** The sink's insert is a subrequest the platform may cut off once
the response is out, and bunny offers no `waitUntil`, so the counted response stream closes only after the
insert settled (a body-less answer is returned after it). Fire-and-forget would save that round-trip, but
whether bunny lets a post-response subrequest finish is unmeasured. A refused insert never fails the
request: it is said on a second console line, `[<reqid>] logdb=failed detail="…"` — the one exception to
one line per request. Rows are operator-only like the line, and belong to no event: deleting an event
deletes none of them.

- `url` is whole, query included (`?filename=`). The line never carries the caller's address or
  User-Agent (capability `privacy-security`).
- `ms` runs until the body finishes, so it includes the transfer. `out=` counts the bytes actually sent.
  `in=` counts the bytes a route read, or is the declared `Content-Length` (or `-`) when none was read.
  A body abandoned or broken mid-stream adds `cut=true` (and the `body-cut` fault when it broke).
- A field value is bare when it is one plain token (`[A-Za-z0-9._:/-]+`), otherwise JSON-quoted.
- `reqid` is six random hex characters per request: the key that ties a line to the same request's
  error report.

**Routes record; nothing else writes.** A handler adds `key=value` with `c.var.log.field(k, v)` and a
server fault with `c.var.log.error(tag, detail)`: the tag — one of the closed `ERROR_TAGS` in
`src/request-log.ts`, so a misspelt one does not compile — joins the record's `errors`, and the detail is
kept as a field under the tag's name (`fanout="notify: …"`). Both only buffer. Only the handler touches the
context:
- a helper takes the values it needs (path params, the token's device, the declared version, the body);
- a helper whose outcome is a failure reply **throws a `Refusal`** (`src/refusal.ts`: status, the reply
  body byte-identical to before, an optional `fault` (tag + detail), optional fields), which `onError`
  answers and records;
- a best-effort helper **returns** its outcome or failure for the handler to record (`notifyMembers` →
  `{recipients, pushed, unsent, errors}`).

`deno lint`'s `no-console` holds this. The request log's write is its one exception, and the
command-line tools in `src/scripts/` and `src/dev/` opt out per file.

The fields today:

| Where | Fields |
|---|---|
| a wake (byte landing, publish) | `recipients= pushed= [unsent=<why ×n, …>]` |
| byte upload (v2) | `completed=<n>` + one wake's per completed event; `aborted=true` when the phone's body broke off (answered `400 upload aborted`: the client's network, not a 5xx) |
| manifest (v2) | `refused=older-version version=<n>` + the wake's |
| union read, event page read, … | the delivery record's (below) |
| push registration | `refused=not-attested` |
| attest | `attested=<platform>/<environment>` · `rejected="<format> (<reason> (<detail>)): <error>"` |
| renew | `refused=not-attested\|vanished` · `rejected="<platform> assertion: <error>"` |
| any 5xx or best-effort failure | `<tag>="<what>: <error>"` + `errors=<tag>` (the tags below) |
| an exception no route caught | `bugsink=<event id>` where the service reports (below), else the `uncaught` fault; answered `500 Internal Server Error` |

#### The delivery record: the fields are a contract

The request log is also the **delivery record** (capability `privacy-security`, "The service records how photos are
delivered"; decision record `changes/delivery-record`): every fact about how a photo travels and how SnapSync is
used is a key of `fields`, and nothing of it lives in an operational table. Generated columns, indexes and views
will read these keys later (that change's D1), so **a key, once shipped, keeps its name and its shape**:

- **Names** are lower `snake_case`, one per fact. **Timestamps** are the wire's `Timestamp`, whole-second UTC
  (`2026-07-09T19:24:17Z`) — a phone's own clock where the fact happened on the phone. **Ids** are the strings the
  wire carries. **Yes/no** is a JSON boolean. A **per-photo list** is an array of objects (below).
- **Never refused, never trusted**: each phone fact is dropped when it does not read as its shape, and none is
  verified — the record is diagnostic.

| Request | Keys | Source |
|---|---|---|
| every request | `app_version` (the line prints it in its head, as `v=`) | the version header |
| the nightly sweep (no request) | method `SWEEP`, url `sweep:/events/<id>`: `event` `outcome` (`completed` `deleted`), written with the change | the sweep |
| every app request | `device` `os` (`ios/18.6`) `model` `flavour` (`release` `dev`: the same binary is promoted between tracks, so a build knows no more) `access` (`full` `limited` `none`) `network` (`unrestricted` `restricted` — metered: mobile data, a hotspot, Low Data Mode or Data Saver — `offline` `blocked`) `mobile_data` `low_power` `background` | `x-snapsync-context: <key>=<value>;…` (`src/delivery-context.ts`) |
| byte upload (event-scoped) | `kind` (`fresh`, or `resume` with a range) `from` `size` `media` (`photo` `video` `other`) · `taken` `discovered` `discovered_by` `queued` `queued_by` (phone) · `backlog` (taken before this device joined) · `uploaded_all` (the landing of a settled device's last declared photo) | route · `x-snapsync-taken` … `-queued-by` headers |
| upload probe | `offset` (what a drop kept — `0` for nothing) or `landed=true` | route |
| union read | `reader` (the verified token's device, `-` for any other reader) `served` `trigger` `from` (a delta's cursor) `to` (the position served) · `notified` `announced` (the push that caused the read, phone) | route · `x-snapsync-notified`, `-announced` headers |
| event page read | `served` `to` — nothing about the visitor | route |
| download redirect (a download starts) | `queued` `queued_by` (phone) · `backlog` (landed before the context's `device` joined; absent for a browser) | route · headers |
| received report | `position` · `delivered` once per photo: `{owner, asset, downloaded, added, by}` (phone) · `caught_up` (the position reaches the union head) | route · body |
| manifest that took | `assets` `state` (`sharing` `settled`) · `withdrawn` once per servable photo it withdrew | route |
| create | `event` `encrypted` `window_days` `open_days` `backdated` (created after its end) | route |

The device context leads the fields on the line, before what the route adds. A join and a leave add nothing: their
method and URL say who joined or left which event, and how. A **restart** — a byte upload that sends a photo from
byte 0 again (the PhotoKit extension; Android in a plain event) — is told when reading: a `fresh` attempt for a path
that already has one. Per-photo totals (attempts, first attempt to landing, throughput, bytes sent again) are joins
over a photo's records, never stored.

The fault tags:

| Tag | Fault | Request answered |
|---|---|---|
| `upstream` | a call to the store or to storage threw | `502` |
| `upstream-rejected` | storage answered a non-2xx | `502` |
| `uncaught` | an exception no route caught, where nothing reports | `500` |
| `body-cut` | the response body broke off mid-stream | as it was |
| `completion-lookup` | the byte route could not ask which events the upload completed (it wakes nobody) | `201` |
| `landing-stamp` | the byte route could not stamp the landing time | `201` |
| `union-position` | a wake could not read the position it announces (sent without one) | as it was |
| `fanout` | a wake's fan-out failed or timed out (the write stands) | as it was |
| `fetchability` | the manifest publish could not look up which declared assets are fetchable | as it was |
| `delivery-fact` | a delivery fact the route reads from the store (`uploaded_all`, `caught_up`) could not be read; the request stands without it | as it was |
| `event-page` | the event page could not read its event | as it was |
| `store-down` · `zone-down` | `/health`: the store / the storage zone did not answer (both always checked) | `503` |

Per-push failures are not faults: they are counted by reason in `unsent=`. The byte upload is the one route
that can record several: two of its own and two per event it completed and woke.

### Failure reports

An exception no route caught is reported to the operator's Bugsink, project 1, the one the apps report
to (`src/error-report.ts`, over `@sentry/deno`, tagged `platform=api`). It is answered `500` as before, and
its line carries only `bugsink=<event id>` (capability `privacy-security`, "The service reports its own
failures to the operator"). A `Refusal` is an answer, never a report. Caught upstream and best-effort
failures stay faults on the record. A day's **5xx** reach Bugsink too, as one nightly report from the
kept log (`docs/deployment.md` §3).

- **Only the deployed service reports.** `main.ts` starts the SDK only when the bundle carries a DSN,
  which the resolver renders for `prod` and `maintenance` (`docs/deployment.md`). `createApp` takes the
  reporter as a dependency, so a dev rig, the ephemeral backend and the tests run without one.
- **What a report carries:** the stack, the request as it arrived (URL with query, all headers: the
  browser or app, `cdn-requestcountrycode`, `cdn-ja4`) and the request's outbound calls as `fetch`
  breadcrumbs. It is tagged `reqid`, `route` (the Hono pattern) and `v`. It never carries the
  requester's address: `beforeSend` drops `user.ip_address` and every address-bearing header.
- **Per request.** The request-log middleware runs each request in `Sentry.withIsolationScope`, because
  bunny serves through `Bunny.v1.serve`, which the SDK does not instrument. `onError` captures, then
  awaits `Sentry.flush(2000)` before answering, since the isolate may freeze once the response is out.
- **Default integrations, no tracing.** A floating promise rejection is captured too. On bunny the
  script keeps serving after one (measured; under a local `deno run` the SDK re-throws and Deno exits).
- **Readable stacks.** A frame names the bundle (`/mod.ts:<line>:<col>`). `bundle-<sha>` holds
  `main.js.map`, and the `/bugsink` skill maps frames back to `api/src` through the map of the event's
  `release`.

### The backend boundary

**`api/openapi.yaml` is the ONE source of the wire format** (OpenAPI 3.1.2, hand-written, linted by Redocly in
`api-test`'s `OpenAPI lint`). Everything else is generated from it at build time and never committed, on both sides:

- **The backend's half** — `api/src/gen/wire.ts` (`deno task gen`, `src/scripts/generate-wire.ts`): every device
  operation mounted at the description's own method and path, its input validated by zod schemas generated from the
  description before its handler runs, and a `Handlers` type that makes a missing handler a type error. A route the
  description does not declare cannot exist, nor take a shape it does not declare.
- **The app's half** — `:core:wire` (build-logic's `snapsync.wire`): Fabrikt's DTOs (`app.snapsync.wire.models`)
  and our own generator's routes, replies and limits (`app.snapsync.wire.api`). Each operation gets a `Routes.<Op>`
  object — its `ID`, `METHOD`, `TEMPLATE`, `path(…)`, and, for one the app sends itself, a typed `request(…)` and the
  `decide` that reads an answer — and one sealed `<Op>Reply`.

**Validation lives in the description**, through a closed `x-snapsync-*` vocabulary (`outcome`, `instant`, `after`,
`max-span`, `check`, `gate`, `export`, `transport`, `refusals`, `on-invalid`, `trim`), read by both generators; a name
outside it fails both builds. `x-snapsync-export` hands a constraint to the app as a constant
(`Limits.EVENT_NAME_MAX_LENGTH`), so the screen and the route bound a name by one value.

**Every declared response names its outcome** (`x-snapsync-outcome`), and an outcome is a type: a route's own is
nested in its reply (`CreateEventReply.InvalidName`), one defined once under `components/responses` is ONE type every reply
that references it implements (`EventNotFound`, `Unauthorized`, `AppTooOld`, …). Each outcome belongs to one group by
its status — `Success` (2xx–3xx), `ClientError` (4xx — a refused build is the client's), `ServerIssue` (5xx) — and
every reply also answers `NoAnswer`: `Unreachable` (nothing came back) or `Malformed` (an answer outside the
description: an undeclared status, a media type or text it does not name, a body that does not decode — and a body on
a success that declares none: the backend sends those empty, so a body there came from something in its place, a Wi-Fi
login page or a proxy, and is never a done write). One status may name several outcomes, by media type or by the body's
text (an annotated enumeration, `oneOf` + `const`).

**What a member is told of a failing service** is one reading of the reply, made once (`faultOf`, over
`domain.service.ServiceFault`): `NoAnswer` — no answer, or one the app cannot read — is *cannot be reached*, a
`ServerIssue` is *having trouble*, and `Maintenance` is *being updated*; every other answer is a working service.
Each need-shaped service carries it in its outcome, and every answer also reaches `ServiceConditions`, the cell the
joined screen's service line reads (decision record `changes/tell-service-faults`).

**The layers, app side:**

| layer | owns | where |
|---|---|---|
| `HttpTransport` (port) | send one request, answer what came back — never throws | `ports/`; `KtorHttpTransport` in `:adapter:generic:app` |
| `:core:wire` (generated) | routes, request shapes, replies — the description, in Kotlin | `core/wire`, from `api/openapi.yaml` |
| `BackendExchange` / `BackendGateway` | the protocol: the build's base and version header on every request (`BackendTarget`), the bearer on a `GatedRequest` only, `Unauthorized` → recover → retry once, `AppTooOld`/`Success` → the version gate, every answer → the service's condition (`data/service/ServiceConditions`) | `data/backend/` |
| need-shaped services | what a reply MEANS to the app (`EventNotFound` is "gone" to the directory, "nothing to leave" to the leave) | `data/event/`, `data/membership/`, `data/push/` (built in `BackendGraph`) |

**Two kinds of route.** An operation whose `security` admits no token is `OpenRequest` (the `/attest/…` issuers);
every other one is `GatedRequest`, which alone can carry the token — the two legacy optional reads (`getEvent`,
`eventFiles`) included: the app always sends its token there (`PENDING_CLEANUPS.md`, "Anonymous event reads").

**The byte routes** are performed by the OS transfer services (`x-snapsync-transport: os`): the app builds them —
the data layer's `UploadRequests` (the upload's address and headers) and the union source (each download's address) through
the same exchange, so they carry the version header too — and decodes no reply. The download redirect serves a
request without the header (builds that predate it) until a minimum app version retires them (`PENDING_CLEANUPS.md`,
"Header-less download redirects").

**Instants are one shape** — whole-second UTC (`2026-07-01T00:00:00Z`), compared as text on the backend, so the
description's `Timestamp` pattern is the contract (migration `0013` truncated the stored ones).

**Held by:** `BackendContract` — the production gateway against the real `api/` (`Live`) and against the backend
mock's HTTP face (`Fake`, which also plays a failing store and a maintenance window through its fault lever); its
cells are the replies' outcomes (`Backend.<operationId> → <Outcome>`, one `Backend.* → <Outcome>` for a shared one),
derived by the port grid from `:core:wire`. `HttpTransportContract` holds each platform's engine.

### Trust

**What the backend gates on, and how a device holds it.** Every gated request carries the device's bearer token,
minted by the backend over a proof from the platform's integrity service (App Attest on iOS, Keystore key attestation
on Android) and renewed with an assertion by the same key a week before it expires. The concept lives in the layers:

- **domain** decides — `BearerToken` (a secret type, rendering masked, carrying the expiry it was minted with),
  `RenewalVerdict` (due a week early, so every wake the app gets is a chance to renew), `UsabilityVerdict` (authorizes
  until the instant it expires — never collapsed into the renewal verdict, `SNAPSYNC-20`), what an issuer's answer means
  (`RenewalAnswerVerdict`, `MintAnswerVerdict`, `ChallengeVerdict`), and what a backend answer means to a credential and
  to the version gate.
- **data** translates — the issuers' routes and the integrity port (`AttestationIssuer`), the two `SecureStore` slots
  (`SecureCredentialStore`) and the in-memory copy each process keeps in front of them (`CachedCredentialStore`: every
  in-process write drops it, and it is re-read at every wake, every extension invocation and every rejection), the
  device id (`DeviceIdentity`), and the read-model cells the screen and the report read (`AttestationStatus`).
- **application** runs it at every wake: `RefreshAttestation`, the step every wake's `Prelude` calls.

**The credential loop runs in both processes, and each holds its own kind** — separate types, not flags:

| | app | upload extension |
|---|---|---|
| identity | `MintingDeviceIdentity` — mints when the shared slot is genuinely empty (`@AppProcessOnly`) | `StoredDeviceIdentity` — reads the slot, never writes |
| credential | `AttestingCredential` — drops a rejected token (compare-and-clear, under the refresh lock) and recovers it now: renew, else attest afresh (`@AppProcessOnly`) | `DropOnlyCredential` — drops a rejected token and nothing else; never recovers, never retries |
| version gate | `AppVersionGate` | `VersionIgnored` — no screen to tell |

**A rejected token is invalidated only if it is still the one held** (B3): a late rejection of a token another
request's recovery — or the other process — already replaced must not erase its successor. Refreshes are serialized, so a
burst of `401`s costs one renewal. The extension cannot attest (App Attest is unavailable in an app extension, measured),
so dropping the token is what heals a device whose token the backend rejected while the app sat unopened: the app's
next wake finds none and obtains one.

**Test hosts attest too.** No backend serves a gated request without a token — the dev server and the backend mock
refuse one exactly as deployed. Every test host attests as a device does: a simulator rig build, the JVM rig host over
the real `api/`, the Backend contract's setup and client and the journeys' second member with a software Android key
the local deployment's `androidAttestationTrust: any` accepts (`SoftwareKeyAttestation`, `SoftwareKeyIntegrity`,
`DeviceTokens` in `:test:contracts`); the backend mock with its own in-memory proofs. The mock checks a token as the
real gate does: minted there, unexpired on the device's clock (30 days), for the device the route names — on the byte
routes too.

### The membership

**What this device is a member of, and how it changes.** One membership at a time (capability `manage-membership`): the
event as the server last served it and the member's own share of it. The concept lives in the layers:

- **domain** decides — `Membership.Active` / `Deleted` (`event: Event`, `share: Share`: the server's facts and the
  member's choice, each replaced only by what owns it), what a join or a settings change may choose (`JoinChoice`,
  `ShareChoice`, `CaptureRange`'s rule), what the server's word means (`DeletionVerdict`, `MembershipUpdate`), whether a
  switch may leave without asking (`SwitchVerdict`), and when a wake reads the event again (`CheckCadence`). Its
  evidence types (`docs/patterns.md`, "Evidence types") make the order structural: no entry without `Enrolled`, no
  deletion mark without `ConfirmedDeletion`, no sweep of a leave's leftovers without `ConfirmedNotJoined`. A
  deletion is confirmed by the server's positive word only: an event read as completed, a write it refused because
  the photos are deleted (`DeletionVerdict.answeredOver` — a rename, the shared set's publish), or a "not found" past
  the membership's own deadline; a lone "not found" is no witness.
- **data** translates — the membership row (`MembershipRepository`: `MembershipRead` is `Joined`, `NotJoined` with
  its evidence, or `Unavailable`, never one for another), the leaves owed (`PendingLeaves`), the check times
  (`EventCheckRecord`), and the backend's routes (`EventDirectory`, `EventRename`, `EventJoin`, `LeaveNotifier`).
  Every write after the entry is compare-and-set against the membership it was decided for; a leave (the row gone,
  the leave owed, the checks forgotten) and a share change (the share and the manifest version) are each ONE
  transaction, so the member is either fully joined or fully left.
- **application** runs each operation: `EnrollDevice` (the server admits the device, refused while joined elsewhere),
  `EnterMembership`, `LeaveEvent`, `RefreshMembership`, `ReconfigureEvent`, `RenameEvent`, `DeliverPendingLeaves`,
  `ClaimStateCheck`.
- **query** answers the screen: the membership, an invite's event (`JoinDetailsLookup`), the server's word on the
  joined one when another invite opens (`SwitchLookup`), and each request's status.
- **entrypoints** order it (`MembershipEntryPoints.kt`): a join is admitted → keep the key → enrol → load the share set → adopt
  what an earlier install received → enter → what follows a join; a leave ends the membership first and then stops
  what it held, and a leave that cannot be written tells the backend nothing; another event's invite loads first and
  only then asks whether to switch, so an invite that cannot be joined never leaves the joined event.

The membership is the layers' whole: its row, its use cases (the join-time share-set load, `LoadShareSet`, among them)
and its queries.

**A member's request answers on its status** (`docs/patterns.md`, "Queries and requests"): the join, a settings
change, a rename and a switch's question cross the door with a `RequestId` and are answered on `MembershipStatuses`;
the leave answers nothing but the membership itself. A failure the service caused carries how it failed (`ServiceFault`, decision record
`changes/tell-service-faults`) from the data layer's reading of the reply up to the answer — `JoinCommit.Failed`,
`RenameResult.Failed`, an invite's `JoinDetails.Unanswered` with the wait an update suggested — so the screen tells it
in its own words.

**The screen reads the membership** through `MembershipQuery.shown` — held, none, or unreadable while nothing has been
read since the process started (a row this build refuses reads as none: a join is the way back) — and the diagnostic
dump reads the repository itself.

### Receiving

**The others' photos, from the event's union to this device's library** (capabilities `photo-sharing`,
`event-lifetime`, `sync-status`). Every foreign photo travels one typestate — planned → `StagedDownload` (its bytes
staged) → `ImportedAsset` (in the library, its marker confirmed) → released (its staged bytes freed) — and every
transition is a compare-and-set against the state the step was decided from, so a relaunch, a prune or a second
process can never move a photo twice. The concept lives in the layers:

- **domain** decides (`domain/receiving/`) — the union's values (`AssetRef`, `UnionPage`, `ForeignPhotos`: what of a
  page is the others' and still open), which read a trigger makes (`UnionReadPlan`: the whole union, a delta from the
  stored position, or nothing for a push whose position is read), what a finished transfer is worth
  (`TransferVerdict`: an HTTP error delivered as a "successful" body is never staged) and where it stages
  (`StagedPath`, `StagingFolder`), what an interrupted import came to (`InterruptedImportVerdict`), the received
  photo's library name (`ReceivedPhotoName`), the recognition window and match (`RecognitionWindow`,
  `RecognitionMatch`), what is still awaited (`OthersDeclared`, `AwaitedPhotos`) and when a position is reported
  (`HeldPosition`, `ReportDue`). Its evidence types make the order structural: no plan or import without
  `ReceivingMembership` (an active membership that receives and holds its key — `ReceivingVerdict`), no import
  without `Recognised` (the membership's received photos were recognised against its union), and the typestate's
  `StagedDownload` / `ImportedAsset`, minted only by the store that recorded them.
- **data** translates (`data/receiving/`) — the `downloads.db` store (`DownloadStore`, one lock for every write, and
  the transfer queue it drives, `DownloadTransfers`), the plan of a union page (`ReceivingPlans`), the import queue and
  its releases (`ImportQueue`, `ImportedReleases`), the recognition's records (`RecognitionRecords`), the counts
  (`ReceivingCounts`), the staging area (`StagingArea`), the library import with its marker writes (`LibraryImport`,
  `ImportMarkerWrites`), the union read (`EventUnion`, which nets the declared total by this device's own,
  `ManifestRecord`) and the reported position (`ReceivedReporting`). What was received is read by `ReceivedPhotos` —
  and by the upload extension through `ReceivedPhotosReader`, which opens the store read-only and can write nothing.
  `OwnPhotos` reads it itself (`ReceivedPhotosRead.handles`): no caller passes the echo-suppression set in. A read of
  the member's photos is admitted by what was received **after** that read (`OwnPhotos.discover`): an import stores its
  handle before its photo can be seen, in any process, so only that order covers an import landing during the read.
- **application** runs each operation (`application/receiving/`): `PlanReceiving` (and `PlanIfDue`, the hourly
  bound), `StageDownload` (inline, on the delivering thread), `RecordStaged`, `FreeTransfer`, `ImportNextStaged`,
  `ReleaseImported`, `SweepUnclaimedStaging`, `SettleInterruptedImports`, `RecognizeReceivedPhotos` (single-flight;
  `RecognitionNeeded`, `WithholdRecognition`), `ForgetOtherEvents`, `ImportReady`, `HoldsEverything`,
  `ReportReceivedPosition`, `StopReceiving` and `ReceivingOf`.
- **query** answers the screen: `ReceivingProgress`, the joined event's download counts.
- **entrypoints** order it (`ReceivingEntryPoints.kt`): a join forgets the other events' union, recognises, enters and
  then plans from the union the recognition read — one union read per join; a foreground, a push and a wake plan
  (`PlanJoined`, `PhotoCheck`); the session's deliveries stage, record and free (`OnDownloadFinished`,
  `OnDownloadCompleted`), and its drain report awaits the records they started (`OnDownloadSessionEvents`); the
  import drains one staged photo at a time (`ImportReceived`, a `Sequence`), each import outliving its run; a selection
  change recognises before it publishes (`SelectionRecognition`); a leave, a deletion, a share-only change and a
  sweep of leftovers stop it (`StopReceiving`).

**Reset is a reinstall.** No production code voids receiving's state: a rig build crossing backends is uninstalled and
installed again, and the photos it received are recognised by their mark at the next join.

### The event album

**The album in the member's library that holds the event's photos** (capability `event-album`): made by the app — its
one creator — when the member wants one and the grant allows it, reused across a rejoin, recreated after the member
deleted it only on their opt-in; the member's own photos placed as their uploads are first queued, received ones filed
in at import, and what the device already holds gathered in when the album is turned on. The concept lives in the
layers:

- **domain** decides (`domain/album/`) — the choice (`AlbumChoice`), what the phone's album holds (`AlbumContents`: a
  collection that takes any photo, or a folder that holds received ones only, decision record
  `2026-09-30-android-event-album`), who asks (`AlbumAsk`: only an opt-in recreates a deleted album) and the verdicts
  above.
- **data** translates (`data/album/`) — `AlbumMap`, each event's album with its fill mark in `ledger.db`, read fresh at
  every call so the other process is current; `AlbumFiling`, what both processes may do with the library's albums;
  `AlbumCreation`, the app's alone (`@AppProcessOnly`); and `PhotoAccessChanges`, the grant with the one before it.
- **application** runs each operation (`application/album/`, listed above).
- **entrypoints** order it: a join and a Save ensure, then request a gather (`EventAlbum`); the photo grant is followed
  (`OnPhotoAccessChanged`, installed only from the UI launch) — ensured whenever access is usable, gathered into once
  it became usable while the app runs; the gather is a single-flight `Sequence` (`AlbumGatherPass`), started detached
  (`AlbumGathering`); the import pass reads its album once (`AlbumForImport`) and marks it filled; an own photo is
  placed before its job (`AlbumPlacement`).

**The extension only places.** Its composition holds `PlaceOwnPhotos` and nothing else of the album: making an album
and gathering are `@AppProcessOnly`, built in one place (`AlbumGraph`, `:core:composition:app`).

### HTTP API (v2, current)

**`api/openapi.yaml` is the authority**; this table is the reading guide, not a second definition.

All device routes are under `/api/v2`. `<eventId>`/`<deviceId>` are UUIDs (`400` otherwise), and a
`<deviceId>` other than the one the token was minted for is `403` on every route that names one. Anything
not listed is `404` (no `405`) and makes no upstream request.

| method | path | does | answers |
|---|---|---|---|
| `GET` | `/attest/challenge` | stateless HMAC-signed, time-bounded nonce, writes nothing | `200 {challenge}` |
| `POST` | `/attest/token` `{deviceId, challenge, proof}`, `proof` = `{format:"apple-appattest", keyId, attestation}` or `{format:"android-key", chain:[DER b64, leaf first]}` | the format only CHOOSES the verifier. App Attest: chain to Apple root, nonce, app-id hash, counter, aaguid. Android key attestation (`src/android-attest.ts`): chain to a pinned Google root (by key), its provisioning shape, one extension in the leaf, validity (expiry ignored on factory chains), revocation (Google's public status list, cached per `max-age`), the challenge's SHA-256, the package and signing digest, TEE/StrongBox and a locked, verified boot — the last three per `androidAttestationTrust`. **Persists the device row with the platform that proved it, then mints** | `201 {token}` · `400` body (the retired flat v1 body too) · `401` failed check, its body `attestation rejected: <reason>` with the reason one of `device-modified` (software key, unlocked or unverified boot) · `app-not-genuine` (package, signing digest, App Attest app-id hash) · `device-unverifiable` (every other check — the default, which never accuses the user), suffixed ` (certificate)` when a certificate check failed (the chain's root, shape, signatures or validity, or a revoked certificate — diagnostics only, never told to the user) · `409` stale challenge · `502` write failed, or Android's status list unreachable |
| `POST` | `/attest/renew` `{deviceId, assertion, challenge}` | verifies by the STORED row's `attest_platform`: an App Attest assertion, or an Android ECDSA-P256 signature over the challenge's UTF-8 bytes, against the stored key (no vendor call); advances expiry, then mints | `201 {token}` · `401` no attestation / refused · `409` stale challenge · `502` read/write failure (never `401`: that would force a throttled re-attestation) |
| `POST` | `/events` `{name?, sealedMeta?, startsAt, endsAt?, openUntil?, zone?, keyId?}` | a plain event carries `name` (trimmed, non-empty, ≤100 chars), an encrypted one (`keyId`) `sealedMeta` instead — the sealed metadata record, shape-checked only (unpadded base64url, a known version byte, ≤1 KiB, else `400 invalid sealedMeta`) — and never `name`; either mismatch is `400 invalid name` (`changes/seal-event-name` D4); window rules; `openUntil` (absent → `endsAt`) at least `endsAt` and at most the window maximum after the later of `endsAt` and now (plus an hour for a creating phone whose clock runs ahead), else `400 invalid openUntil`; `zone` stored when usable, else NULL (never a `400`); `keyId` (16 lowercase hex — the encrypted file format's key id) makes the event **encrypted**, and a present invalid one is a `400`, never a plain event; backend mints the id | `201 {eventId, name?, sealedMeta?, createdAt, startsAt, endsAt, openUntil, capacity, deletesAt, closedAt, completedAt, keyId?}` (`keyId` only on an encrypted event, in every event body; `name` exactly when the row holds one — a plain event, or an encrypted one created before names were sealed — and `sealedMeta` otherwise; `capacity` a placeholder released apps require) · `400` · `502` |
| `GET`/`HEAD` | `/events/<eventId>` (ungated; an optional bearer token is verified, `changes/separate-event-page-from-device-api` D7) | metadata; `deletesAt` derived per response; `openUntil`; `closedAt` (the stored stamp, else derived for builds that predate open-until dates), `completedAt`, `members {active, final, waiting}` (`waiting`: present members not settled or with a declared role not landed), `keyId`, and `name` or `sealedMeta` as the create answers them | `200` (a completed event too, with `completedAt`) · `401` a sent token that does not verify · `404` sealed absence · `502` read failure |
| `PATCH` | `/events/<eventId>` `{name}` or `{sealedMeta}` (`changeEvent`) | the only write to an existing event row, and it writes the name alone: a plain event takes `name`, a sealed one `sealedMeta`, a legacy encrypted one either — and `sealedMeta` moves it to the sealed shape, clearing `name`; any other pairing is `400 invalid name`; last-write-wins; no ownership check | `200` (metadata shape) · `400` · `404` · `410` an event closed before open-until dates, or completed · `502` |
| `PUT` | `/events/<eventId>/devices/<deviceId>` | **join**: the only route that creates or reactivates a membership; one conditional insert, admitting any device; clears `manifest_version` and `final`; idempotent | `200` · `404` · `410` an event closed before open-until dates, or completed · `502` |
| `DELETE` | `/events/<eventId>/devices/<deviceId>` | **leave** (`?received=true\|false`): a present membership becomes `done` or `left`; closes nothing and wakes nobody; idempotent, a repeated leave keeps the first state (a completed event answers `200` too); assets retained; frees no slot | `200` · `404` · `502` |
| `PUT` | `/events/<eventId>/devices/<deviceId>/manifest` | **contribution**: full-state replace of the membership's asset set in one transaction, ordered by `version`; `final` → `settled` (both required: absent is `400`), stored whenever it arrives, only for a present membership, and a later non-final reverts it; closes nothing; writes no resource rows; enrols nobody; each resource is its `role` and `contentType` — the `key` and `filename` builds before `seal-file-names` still send are ignored | `200` (applied, or refused as older with nothing written, or the same set to an event closed before open-until dates) · `400` · `404` · `409` not a member · `409 {error:"closed"}` changed set to such an event · `410` completed · `502` |
| `PUT` | `/events/<eventId>/devices/<deviceId>/received` `{position, receivesNothing?}` | the union-log position up to which the device holds every photo of the others (`received_position`, monotonic: a lower one changes nothing), or that it receives nothing at all (`receives_nothing`, as the latest report says); only a present member | `200` · `400 invalid position` · `404` · `409` not a member · `502` |
| `PUT` | `/events/<eventId>/files/devices/<deviceId>/<assetId>/<role>?filename=<name>` (the download redirect's path; the PUT is gated) | accepted only from a **present** member (`sharing`/`settled` — an ended event still takes its members' bytes, a completed one has none); stores bytes at `files/<eventId>/<sha256 of event/device/asset/role>` — one write when under one part, S3 multipart parts otherwise ([Resumable uploads](#resumable-uploads)) — then records the `resources` row. With `x-snapsync-sha256` (the file's SHA-256) a dropped upload keeps what arrived, and a **partial PUT** (`Content-Range: bytes X-(N-1)/N`, RFC 9110 §14.5) continues it: a lower `X` discards the overlap, a higher one is `409` + `Upload-Offset`. **A failed record fails the request.** If this completed the asset, wakes the event's other members. **An encrypted event takes only encrypted files** (`routes/encrypted-upload.ts`, read in the same statement as the membership): a body whose 9-byte prefix names the event's key id passes untouched; with the iOS extension's `x-snapsync-file-key` + `x-snapsync-file-head` (ONE file's key and opening bytes, never the event key; never logged) the edge seals the plaintext itself, byte for byte as a device would; anything else is refused before a byte is stored. **An encrypted event takes a file's name only sealed** (`admitFilename`): a `filename` that is not a sealed metadata envelope is `400 invalid filename`, before a byte is stored. A plain event refuses those headers | `201` · `400` bad role / missing filename / an encrypted event's unsealed filename / file-key headers on a plain event or malformed / a malformed `x-snapsync-sha256` or `Content-Range`, a range without the sha256, or the body broke off mid-upload (`upload aborted`) · `403` not a present member, or a file head of another key · `409` + `Upload-Offset` a resume from past what is stored, or one a newer request took over · `422` an encrypted event's body that is not a file of its key · `502` (`OPTIONS` → `204`) |
| `GET` | `/events/<eventId>/files/devices/<deviceId>/<assetId>/<role>/upload` with `x-snapsync-sha256` | **where an upload stands**, asked before a device retries one ([Resumable uploads](#resumable-uploads)); gated like the PUT; `Cache-Control: no-store, no-cache, max-age=0` | `204` these bytes landed · `200` + `Upload-Offset: X` — send from `X` · `404` nothing stored for these bytes · `400` · `403` not a present member · `502` |
| `PUT` | `/files/devices/<deviceId>/<assetId>/<role>?filename=<name>` | the **event-less** upload of builds before `per-event-storage-layout`: the same write, filed under the device's one present membership — and the same encrypted-event rule, so a build that predates encryption cannot put plaintext into one | as above, `409` with no present membership |
| `GET` | `/events/<eventId>/files/devices/<deviceId>` | what the backend holds for me **in this event**, from the DB — never another event's | `200 [{assetId, role, filename}]` · `502` |
| `GET` | `/files/devices/<deviceId>` | the event-less listing: the same answer for the device's present membership, `[]` with none | `200 [{assetId, role, filename}]` · `502` |
| `GET`/`HEAD` | `/events/<eventId>/files[?cursor=<n>][&urls=false]` (ungated; an optional bearer token is verified) | the event union: one query over every membership, present or gone; an asset is included only when **every declared role** has a resource (a set comparison, not a count). `cursor` serves only assets the union log says were gained after it (`changes/incremental-union`); `urls=false` omits `url`, which is otherwise the download redirect's absolute address. Every answer carries `SnapSync-Cursor: <n>`, the position it covers. Every answer also carries `SnapSync-Declared: <n>`, the event's declared total — every asset the whole union serves plus every not-yet-complete asset a present member declares, the same on a full and a delta read; a member's received total is it minus the member's own published declaration as it stood at that read (`changes/count-declared-photos`). The `SnapSync-Trigger` header (`push`·`wake`·`foreground`·`join`·`grant`·`reconfigure`·`leave-check`), the verified device, if any, and the `x-snapsync-notified`/`-announced` facts go into the read's delivery record | `200 [{deviceId, assetId, creationDate, resources:[{role, contentType, key, filename, url?}]}]` · `400` bad cursor · `401` a sent token that does not verify · `404` · `502` |
| `GET`/`HEAD` | `/events/<eventId>/files/devices/<deviceId>/<assetId>/<role>` (ungated; behind the version gate, but a request with no version header is served — legacy builds) | the **download redirect**: the stored path of that role, when the event still declares the asset with it and its bytes are recorded; each segment percent-encoded | `302` to a 7-day presign, `Cache-Control: no-store, no-cache, max-age=0` · `404` · `502` |
| `PUT` | `/devices/<deviceId>` `{pushToken: {kind, token, env}}` (`kind` is the push adapter's — `apns` or `fcm`) or explicit absence | updates the push columns | `201` · `400` · **`401` when no row was affected** (device never attested; the client re-attests and re-sends) · `502` |

Served at the root under no version: `OPTIONS` on any path (`204`, no resumable upload advertised, so
the iOS uploader uses a plain `PUT`); `GET`/`HEAD` `/`, `/_astro/*` (the Astro build proxied
from the storage `site/` prefix), the event page and its read (below), the AASA (claiming `/join` and `/join/*`) and `/.well-known/assetlinks.json` (Android's: the package and the
`androidSigningCertDigests` the attestation policy accepts, `[]` while none is named or under trust `any`); `GET /health` (`200 {sha, maintenance?}` after
`SELECT 1` and a storage listing succeed, `503` otherwise; `maintenance` absent means closed).

**The event page is the one per-request page** (`src/routes/site.ts` + `src/routes/event-page.ts`, capability
`event-site`). The build emits ONE `site/join/index.html` carrying `%%TOKEN%%` placeholders (title, og tags,
view, status pill, heading, facts), and the api reads it as a template and fills it, HTML-escaping every
value. `/join` (a fragment invite: the server cannot see which) fills it generically, and the page's island
moves the browser to `/join/<eventId>`. `/join/<eventId>` reads the event row and its member counts and
renders its name, dates in the event's `zone` (UTC when NULL), phase and counts, so link previews and
script-less browsers see the event: `404` unknown or malformed, `410` completed (both the invalid view,
naming no event), a closed event renders as ended, a read failure `502`. Both answer `NO_CACHE` (a cached
copy would key the CDN cache on the event's identity) and `Referrer-Policy: no-referrer`. Per view: one
storage GET and one row read plus one count. The page also carries the event's key id (`%%KEY_ID%%`, empty
for a plain event), so an encrypted event's page checks the invite's `#k=` with no further request.

**The event page's read** is `GET`/`HEAD /web/events/<eventId>/photos` (`src/routes/web.ts`): the page's only
call, at the root and under no version, so no device-API version can break the page
(`changes/separate-event-page-from-device-api`). It lists the union's complete assets (the same
`completeAssets` the device union uses) in the page's own shape,
`200 [{deviceId, assetId, resources:[{role, filename, url}]}]`, each `url` a presign living **1 hour**. It
reads no token (an `Authorization` header is ignored, not checked), leaves one anonymous delivery record, and
answers `NO_CACHE`; `404` for an absent or malformed event, `502` on a read failure. A zip that outlasts the
hour reads the list once more (the page matches files by device, asset and role).

The event's identity reaching `/join/<eventId>` is a decision
(`changes/server-rendered-event-page`), not a leak: it supersedes the fragment-only rule of
`migrate-to-universal-links` D1.

**Conventions that hold on every route:**

- **Faithful outcome.** A write returns `2xx` only when the store confirmed it. A read returns an array
  only when every query succeeded, otherwise `502`, never a partial list (a short list looks like a
  complete one, and a photo vanishes silently). A zero-row conditional write with two causes (closed
  or absent) is disambiguated by a follow-up read (`410` vs `404`).
- **Presigned URLs.** SigV4 query-signed S3 `GET`, path-style
  `https://<s3-host>/<zone>/<the resource's path, each segment percent-encoded>`, signed with the zone name as
  access key id and the storage password as secret. Minted by one builder: by the download redirect, one
  per download started, with a 7-day expiry; and by the event page's read, with a **1-hour** expiry. The 7 days stay on purpose:
  an iOS background session resumes from the redirect target and never revisits the redirect (measured,
  `changes/incremental-union` D2). A browser uses its links at once, so the page's hour bounds how long a
  photo withdrawn after the page loaded stays reachable (`changes/separate-event-page-from-device-api` D3).
- **The device-facing origin** of a redirect URL comes from the deployment's `domain` (`deviceOrigin`:
  `http` for a loopback literal, `https` otherwise), never from the request, which behind the pull zone
  promises nothing.
- **Wakes are best-effort and bounded.** The recipient set (present members with a push token) is one
  query, and the push happens **after** the transaction commits. A wake for a gain carries the union
  position (`seq`: top-level on APNs, a string `data` field on FCM) read after the commit, so a device
  whose cursor is already there reads nothing; one whose position read failed carries none. A failed, skipped or timed-out push
  never changes the response. The byte upload wakes when its resource was the last missing declared
  role. The manifest publish wakes only when it made an asset **newly** fetchable (a widening that
  re-admits stored bytes). Otherwise nobody is woken (iOS allows only a few background pushes an hour).
- **Manifest ordering.** `version` (optional, non-negative safe integer): at least the stored version,
  or none stored, applies and records it; strictly older changes nothing and answers `200`; absent
  applies unconditionally and clears the stored version. The comparison is inside the transaction, and
  every chunk of a chunked replace carries it.
- **Object writes are last-write-wins.** The response never distinguishes create from overwrite. The
  filename is a query parameter, so no caller bytes reach the storage key. It is metadata only, not
  identity.

### Database

- **Schema = ordered migrations** (`api/migrations/NNNN_*.sql`, checksummed, applied once, recorded in
  the store). **`api/schema.sql` is generated** (`deno task schema`) by replaying them, and CI fails if
  it is stale. Read a migration's effect in that file's diff, especially its deletions (a rebuild that
  forgets an index shows up only there).
- **Every `TEXT` primary key is explicitly `NOT NULL`** (SQLite only implies it for `INTEGER`). Tables
  are `STRICT`.
- **Foreign keys are trusted**: the platform defaults `PRAGMA foreign_keys = 1`, as measured. If the
  store is re-provisioned or its engine changes, re-measure.
- **One writer per table** on the current version: `events` by create (and rename, name only; the
  byte route stamps only `last_landed_at`, the sweep `closed_at` and `completed_at`; `open_until` is written
  once, by create), `memberships` by join/leave (the publish writes only `manifest_version` and `state`'s
  settled half, the received report only `received_position`, and join only clears the version),
  `event_assets` by the manifest publish, `resources` by the byte upload, `union_log` by the byte upload
  and the publish (`gained`), and `devices` by attestation
  and the config write, each naming only its own column group. The version a device runs is the request
  log's (`app_version`), never a device column. The sweep otherwise only deletes, and records what it
  completed and deleted in the request log. **review** (plus route tests).
- **Capacity is one conditional insert**, never read-then-write. Measured: 10 racing devices for 3
  slots gave 10 under read-then-write and exactly 3 here.
- **A manifest publish is one transaction**, chunked within it if needed, never across transactions.
  Out-of-DB work (push) happens after commit.
- Migration safety (carry data rather than dropping it, in-file SQL preconditions, no `SELECT *`, FK
  enforcement genuinely off while applying, one migration per deploy) is in `docs/deployment.md` and
  gated by `api/test/migrations.test.ts`.
- A one-time data cutover is never committed. It runs from a scratchpad.

**Accepted limitations** (decided 2026-09-25, spec diet follow-ups; no user promise either way):
- **A lost upload record is not repaired.** If a database failover loses a `resources` row after the
  bytes landed, the uploader believes the photo is shared, but it never appears in the event union for
  others. The photo stays in the uploader's own library. Rare infrastructure event, deliberately no
  device-side reconciliation.
- **A reinstall forgets what was received.** The download memory lives in the App-Group container,
  which a reinstall deletes. After reinstalling and rejoining mid-event, photos received earlier may be
  re-shared as the member's own (others see duplicates), and received photos the member deleted may
  arrive again. Inferred from the code, not measured. Accepted: reinstalling mid-event is rare and
  events are short.
- **The leave notice is sent once.** An offline leave never tells the backend, which only forgoes the
  opportunistic early deletion of an event everyone has left. The 30-day deletion is unaffected.

Decision records: `changes/archive/2026-08-25-record-uploads-in-database`,
`changes/archive/2026-09-07-adopt-bunny-cli-migrations`, `changes/archive/2026-09-22-manifest-versions`.

### Layout

```
src/app.ts         createApp({config, db, fetch}): the three gates and the composition of the routers
src/routes/        the routes as `(deps) => Hono` factories: site.ts (site proxy + AASA + assetlinks.json),
                   web.ts (the event page's read),
                   attest.ts, shared.ts, v2.ts, manifest.ts (manifest body parsing) and
                   support.ts (actsFor, presignDownloadUrl, the routes' shared refusals)
src/db.ts          the one narrow `Db` port and every statement (the enrollment, atomic publish, union,
                   sweep queries). No schema here
src/db-libsql.ts   the deployed `Db` (bunny Database)
src/storage.ts     byte-store and site-prefix key builders + LIST/GET/PUT/DELETE, shared with the sweep
src/attest.ts      App Attest verification, the stateless challenge, the device token (mint, verify, the
                   one expiry derivation)
src/android-attest.ts  Android Keystore key attestation (Google's verifier's rules, pinned roots, the
                   cached revocation list) and the renewal signature check
src/attest-proofs.ts   the mint/renew proofs the routes accept and which verifier each goes to
src/lifecycle.ts   deleteByMs / clockMs / sweepVerdict, shared with the sweep
src/push.ts        the silent-wake fan-out's one sender: each token through the service its kind names
src/push-send.ts   the push senders' shared POST-and-report tail and the PushToken type
src/apns.ts        ES256 provider JWT + silent push per token, per-token best-effort
src/fcm.ts         service-account RS256 assertion → OAuth access token + HIGH-priority data message
                   (`eventId` only, collapsed per event) per token; sends only under the configured
                   Firebase project; no key → every FCM token skipped, the backend still boots
src/validators.ts  UUID / filename / event name / instants (MAX_EVENT_NAME_LENGTH)
src/object-names.ts  a resource's wire object name (the union's `key`) and the role vocabulary
src/config.ts      readConfig over the resolved deployment (non-secrets) + env secrets; throws on a
                   missing secret (the optional FCM key excepted); minAppVersion
src/deployment.ts  GENERATED by scripts/resolve-deployment.py; never committed
src/version.ts     /api/vN splitting, version compare
src/main.ts        Edge entry: read config, serve createApp
src/lint/          the complexity deno-lint plugin
src/scripts/       out-of-edge programs, never bundled (sweep, schema generation, migration plan, probe)
src/dev/           dev-only local rig (fs storage shim, node:sqlite Db, serve.ts, replay.ts, tunnel);
                   unreachable from main.ts, so it cannot ship
migrations/        ordered schema history; schema.sql = its generated snapshot
test/              Deno tests over app.request() with fetch/db/config injected, --allow-net absent
```

Configuration, secrets, deploy, maintenance windows and Edge limits: `docs/deployment.md`. Tests and
the local rig: `docs/testing.md` section 9 and the `local-backend` skill.

---

## 9. Manifest and storage layout

None of these is a user contract. The manifest is re-derived from the ledger every pass, and a
re-upload after an app update is acceptable. They are still the data real devices and the backend hold
today. Change them knowingly: a renamed on-device identifier strands state in the field
(`RuntimeIdentityTest` is there to make that loud).

### The device manifest (wire format)

The body of `PUT /api/v2/events/<eventId>/devices/<deviceId>/manifest`, one full-state document per
(event, device), recorded relationally (no manifest object in storage):

```json
{ "deviceId": "<uuid>", "version": 42,
  "assets": [ { "assetId": "<canonical AssetId>", "creationDate": "<ISO-8601>",
                "resources": [ { "role": "primary", "contentType": "image/heic",
                                 "key": "<assetId>-primary.heic", "filename": "IMG_0001.HEIC" } ] } ] }
```

- **`assetId`** is the device's canonical `AssetId` (section 2), sent and stored as it is. The iOS form is the
  PhotoKit identifier with `/` mapped to `_` — the same bytes every build since v1 has sent, so no stored row, object
  name or ledger key changed when the type was introduced.
- **Roles** are platform-neutral: `primary` (exactly one: the original still or video) and `live`
  (at most one: a Live Photo's paired video). Originals only, never edits. Image vs video is carried by
  `contentType`.
- **It declares intent, not completion.** It projects the upload ledger's rows **whatever their
  upload state** (a failed upload is still owed), filtered only by the membership's one selection
  policy. The union's role-set comparison is what hides an incomplete asset.
- The projection applies no filter of its own. Date bounds apply at projection, so a narrow or widen
  changes the manifest without re-uploading. Origin exclusions (screenshots, and so on) apply before a
  ledger row exists, so an excluded photo never reaches any manifest — and only to a library walk: a partial grant's
  selection is judged by the range and echo suppression alone (inside `SelectionPolicy.admit`, by the case of the
  read, `Walked` or `Selected`). A row carries no origin facts, so the projection cannot re-apply them; a full walk that sees a
  recorded photo and refuses it for what it is (a screenshot shared under a partial grant, after access widens)
  deletes its row, as it would a deleted photo's (`changes/archive/2026-10-15-limited-selection-decides`, D2). A bare row (loaded at join from the
  file listing, no capture date yet) is excluded fail-closed by the policy until a walk backfills it.
  Every walk backfills every bare row it covers.
- **Deletion and de-selection** remove ledger rows on the next authoritative walk, so that pass's
  manifest retracts the asset, even if its upload is in flight. Under a partial grant, a read selection
  snapshot counts as an authoritative walk.
- **An empty manifest is valid and published.** A manifest is **suppressed** (the previous one stays)
  whenever the pass cannot trust the ledger — its gate stopped it, or `PublishManifest` could not read the rows. Suppression and "empty" are logged differently.
- **Writer**: `PublishManifest` (`application/sharing`), the step a pass's walk ends with (`WalkAndPublish`, the
  extension's `ExtensionUploads`), in whichever process runs it (app on every iOS version, the extension on iOS
  26.1+), carrying the `version` the pass's gate read first. Two concurrent publishes are ordered by `version` (the
  ledger's manifest counter, which advances on any change that could alter the projection, including a
  reconfigure save, a join — the backend's join forgets the version and `final` — and the join-time load). A pass
  skips the publish only when event id, version and snapshot all match the last **successful** write (the shared
  database's `uploadedManifest`, which never moves to an older version of its event). A refused (older) publish counts
  as published.
- **Settling is evidence** (`changes/settling-needs-a-real-look`): `final` is set only from `SettledShare`, minted by
  `ShareDeclaration.of` from a look that succeeded and began after the member's own range ended, or from a policy that
  shares nothing. Once the range has ended and a settled share was sent, a publish whose look proves nothing (it could
  not read, or began earlier) is skipped rather than sent as sharing, which would only take the settlement back. The end
  of a wake (`SettleShare`) walks and publishes while no settled share was sent. Under a partial grant the selection
  the app holds is the look: its observer keeps it current, so a read of it is as of now — as a walk the memo answers
  is, the library being unchanged since.
- The job-creation limit never withholds the manifest.

### Byte store (bunny Storage zone)

```
files/<eventId>/<sha256>                          one object per resource of an event; the name is the hex
                                                  SHA-256 of "<eventId>/<deviceId>/<assetId>/<role>"
                                                  (`eventBytePath`), so a re-upload overwrites it and one
                                                  photo in two events has two unrelated names. Deleted as
                                                  one folder by the sweep
files/<eventId>/<sha256>.tail-<claim>             what a dropped resumable upload received past its last
                                                  stored part; deleted once used, or with the folder
files/devices/<deviceId>/<assetId>-<role>.<ext>   bytes written before migration 0010, still served
                                                  through rows that point at them; written no more
                                                  (`changes/per-event-storage-layout`, migration plan)
site/index.html · site/join/index.html · site/_astro/*   the Astro build (mirror-deployed, public)
```

Keys are percent-encoded per segment, so they stay flat. The storage `host` must be the zone's **main**
region (read-after-write). Replicas are asynchronous. There is exactly one zone (`snap-sync-dev`),
shared with real users. **Never add a whole-zone reset.** Clean up targeted only.

Besides objects, the zone holds **open S3 multipart uploads** — the parts of resources still uploading. They
are not objects: no listing shows them and a folder's recursive DELETE leaves them behind (measured), and
completing one afterwards puts its object back. So the sweep aborts the open uploads of every event that no
longer lives (`ListMultipartUploads` on `files/`) before it deletes folders.

### Resumable uploads

On a slow link the pull zone ends a request after 60 s without progress, and every retry used to start
again from byte 0 (Bugsink SNAPSYNC-54: one video retried seven times, 40 MB sent, nothing kept). The byte
route now keeps what arrives (`api/src/routes/resumable-upload.ts`, `api/src/s3.ts`):

- **Parts.** The body is cut into parts of `max(5 MiB, size ÷ 40)` (S3's floor; about 40 parts at most,
  since each part is an edge subrequest), and each full part is stored as an S3 multipart part as soon as it
  fills, one in flight while the next fills. A body under one part is one plain write. Complete joins them
  (~2.3 s, measured) and only then is the resource recorded.
- **The session** lives on the resource's row (migration 0015): `landed_at` (NULL while a first upload is
  under way — such a row is in no union, listing or completeness check, the `landed()` filter in `db.ts`), the
  declared SHA-256 and size, the S3 upload id, the part size, the tail, and the **claim** of the request
  writing it. Only a request that declares its file's SHA-256 (`x-snapsync-sha256`) has one; an older build's
  or the PhotoKit extension's upload is stored the same way but keeps nothing when it drops.
- **The tail.** When the client drops, the script keeps running (≥10 s, measured) and writes what it received
  past the last stored part as `<path>.tail-<claim>`. It counts only while it starts where the stored parts
  end, so the offset never overstates what is there.
- **The retry** asks `GET …/upload` first — the device cannot know: after a network cut its own count runs
  0.7–1.1 MB ahead of what arrived (its socket buffer, at every speed, measured) — and sends the rest as a
  partial PUT. A newer request for the same bytes claims the row; every later write of the older one is
  conditional on the claim, so it stops and lands nothing. Bytes whose row is gone when they complete (the
  member left, the event closed) are deleted again.
- **The app** (`data/upload`'s `SessionTier`, `FileUploads`): a platform that uploads from a FILE (the iOS app's
  URLSession; Android for an encrypted event, sealed into a file) declares each staged file's SHA-256, hashed once
  as it is staged (`Crypto.sha256`, `StagedDigests`) and kept beside it (`<staged>.sha256`). A FAILED upload keeps
  both, and the staging sweep (`StagingSweep`) spares them while the file's upload row still needs it. The retry asks `Routes.UploadState`
  (`UploadStandings`, over the `BackendGateway`) and creates the transfer with `TransferSource.File(path, from)` and `Content-Range`; a fresh
  upload never asks. Bytes the backend already holds are finished by resending the last one. The platform
  sends the rest: iOS copies it into a file of its own in Caches (a background session uploads only whole
  files), Android skips to it. The PhotoKit extension and Android in a plain event send the library's own
  bytes and restart, as before. ⚠️ A photo edited in place between a failed attempt and its retry resumes the
  pre-edit bytes (Android, encrypted events only): the kept staged file is not checked against the library.
- **What the delivery record keeps of it** ("The request log"): each attempt's `kind` (`fresh`/`resume`), `from`
  and `size`, and the probe's `offset` — what a drop kept. Attempts per photo and the bytes sent twice are joins
  over those records; a restart from byte 0 is a second `fresh` attempt for the same path.
- **Why the wire looks like this:** URLSession's own resumable uploads (the IETF resumable-upload draft)
  need a `104` interim response and `HEAD`, and the pull zone forwards neither — it drops 1xx and sends a
  HEAD to the origin as a GET (measured, bunny ticket open). So the status read is a GET on its own path
  (GET on the upload's path is the download redirect), the resume a standard partial PUT, and the answer
  carries the draft's `Upload-Offset`. An early answer is impossible too: a reply sent before the body ends
  reaches the client as a `502`, so a `409` comes after the body was read.

### The encrypted file format

The stored bytes of an **encrypted** event's resource. An event is encrypted when it was created with a key; the
key travels only inside the invite link's fragment and the server holds nothing but its `key_id`. A shipped build
creates PLAIN events for now (every build from 0.5 reads encrypted ones), so every invite it shares opens on 0.4; a rig
build creates encrypted events by default and can still create a plain one. Encrypted creation returns to shipped
builds once the minimum app version is 0.5 (`PENDING_CLEANUPS.md`, "Re-enable encrypted creation"), and a later change
retires plain events.

```
prefix    version (1 byte, 0x01) ‖ key id (8 bytes)                       SnapSync's own
header    header length (1 byte, 40) ‖ salt (32 bytes) ‖ nonce prefix (7)   ┐ Google Tink's
segments  AES-256-GCM, 64 KiB ciphertext each (the first shorter by the     │ AesGcmHkdfStreaming
          header, the last shorter still); nonce = nonce prefix ‖           │ (HmacSha256, 32, 65536, 0),
          segment number (u32 BE) ‖ last (0|1)                              ┘ byte for byte
```

- **Segment key** = HKDF-SHA256(event key, salt, `snapsync/v1/<eventId>/<deviceId>/<assetId>/<role>`): a file
  moved to another event, device, asset or role fails to authenticate.
- **Key id** = HKDF-SHA256(event key, no salt, `snapsync/key-id/v1`), first 8 bytes: what the event row holds.
- **The last-segment flag** makes a truncation at a segment boundary fail, and a decryption writes nothing until
  every segment authenticated.
- **Reference vectors**: `test/vectors/encrypted-file.json`, regenerated by `scripts/encrypted-file-vectors.py`
  (an implementation independent of both halves). Plain events' bytes are raw, with no prefix.

**The sealed metadata record** (decision record `changes/seal-event-name`): what an encrypted event row holds of the
event's own description — today its name — instead of the readable `name` column. It is an encrypted record, not
AEAD associated data (which would be authenticated but readable).

```
envelope  version (1 byte, 0x01) ‖ nonce (12 bytes, random per seal) ‖ AES-256-GCM ciphertext ‖ tag (16 bytes),
          unpadded base64url — the same text on the wire (`sealedMeta`) and in the column; at most 1 KiB of text
key       HKDF-SHA256(event key, no salt, "snapsync/meta/v1"), 32 bytes; GCM carries no associated data
plaintext a UTF-8 JSON object, today {"name": "…"}
```

- **The binding is the derivation's label**, as a file's is its HKDF info. The key id needs none of its own: it is
  derived from the very key the record opens under.
- **The JSON grows by adding keys only.** A reader ignores the keys it does not know, and a re-seal keeps them
  (`MetaRecord.named`), so a newer build's field survives a rename by an older one. A change that is not additive is
  a new version byte, never a version key inside the JSON.
- **A photo's file name** in an encrypted event is the same envelope and record (decision record
  `changes/seal-file-names`), under a key derived PER PHOTO — `HKDF-SHA256(event key, no salt,
  "snapsync/file-name/v1/<eventId>/<deviceId>/<assetId>/<role>")` — so a record moved onto another photo opens under
  no key there. It travels where the readable name does in a plain event — the upload's `?filename=`, then
  `resources.filename`, the union's and the web list's `filename` — so in an encrypted event `filename` is always a
  sealed record: the uploader seals it (`UploadRequests`, from the upload's `SealOrder`), the edge checks its shape
  (`admitFilename`), and the readers open it with the joined event's key (`ServedNames`, for `EventUnion` and
  `DeviceListing`; the event page's `openedNames`). A photo whose name does not open is never received and is left
  out of the zip. Each phone derives a resource's key (`<assetId>-<role>.<ext>`) from the readable name it holds; the
  union's `key` is kept only for builds before that change (`PENDING_CLEANUPS.md`).
- **Halves**: `SealedMetaFormat` (`:domain:format`, the layout) and `MetaCipher` and `FileNameCipher` over their shared
  `SealedRecords` (`data/crypto`, over the `Crypto` port); `api/src/sealed-meta.ts` (the edge only checks an envelope's
  shape, `envelopeShape`; the event page opens it).
- **Reference vectors**: `test/vectors/sealed-meta.json`, regenerated by `scripts/sealed-meta-vectors.py`, held by
  `SealedMetaFormatTest` (JVM, over the real JCA) and `api/test/sealed-meta.test.ts`.

**Where each step runs in the app** (the core's layers, over the `Crypto`, `SecureStore` and `Files` ports):

- **The key** is the domain's `EventKey`, a secret type: its bytes are read only behind `@KeyMaterial` (its text form
  `EventKeyText`, its id's derivation `EventKeyIds`, the cipher) and made from bytes only behind `@MakesEventKey`
  (`EventKeyText`, `EventKeyIds.mint`); `IdentifiedKey` is the evidence that its id was derived. A new event's key is
  minted by the creating device (`NewEventKeys`, unless `DevControls.createsPlainEvents` — inert `true` in production
  until encrypted creation is re-enabled; a rig build answers `true` only after `dev-controls.encryptNewEvents(false)`),
  sent as its id alone (`POST /events {keyId}`), and kept on every member's device by `EventKeyStore` in the secure
  store's shared slot `EncryptionSlots.EVENT_KEY` — kept by the join (its `KeptKey` is what `EnrollDevice` requires),
  forgotten with the evidence that it may be: a leave (`LeftMembership`), a sweep of a failed leave's leftovers
  (`ConfirmedNotJoined`). Its `state` (`KeyFact`: kept · absent · invalid — something
  that is no key, warned once — · unreadable) is re-read at every entry, as the membership is. The event row holds
  only its `Encryption` (`Plain | Encrypted(keyId)`).
- **The link decides, never the backend** (`Encryption.openedBy`, asked by the join screen's lookup and again by
  `EnrollDevice`): a link whose `#k=` does not name the event's `key_id`, or that lacks one for an encrypted event,
  never enrols — the gate shows `JoinPhase.WrongLink` at load, and the commit refuses it again. An invite for an
  encrypted event is the path form `/join/<id>#k=…`; a plain event's stays the fragment form every installed build
  reads.
- **A lost key** (`KeyPresence.of` → `Lost`: the event is encrypted and the slot holds no key of its id — an
  unencrypted backup restored onto a new phone, an invalidated Android Keystore key) stops both directions at their
  admission: the upload pass's gate answers `UploadGate.Withheld` in the app and the extension alike (nothing
  walked, staged or published), and no plan starts a download (`ReceivingVerdict.KeyLost` mints no `ReceivingMembership`, and
  unlike `ReceivingVerdict.SharesOnly` it never reads as "nothing to receive"). A store that cannot be read is `Unknown`, never
  `Lost`, so a locked wake runs as before and withholds at the seal. The query's `EventKeyQuery.presence` is the
  joined screen's `SyncHealth.KeyLost`, and its held key the invite's — one read, so the screen never offers an
  invite without its key: `Layer.Joined.inviteUrl` is `null` while an encrypted event's key cannot be read (lost or
  locked), which hides Share and QR. Reopening the joined event's whole invite restores it in place: the screen's
  same-event rung requests `UserCommands.restoreKey`, `OnKeyRestoreRequested` runs `RestoreEventKey` (a
  `RestoreVerdict`: only the event's own key, only while it is lost), answers on `MembershipStatuses.restore`, and
  then the foreground's work resumes.
- **The files** (`FileCipher`, data): sealed and opened a segment at a time, each bound to its `FileBinding`; the
  file and nonce keys the port is handed are of the format's lengths, checked where they are built.
- **Uploads** (`UploadSealing` inside `SessionTier` and `OsQueueTier`): a `SealVerdict` judged for the event
  the upload goes to (`sealFor(event, asset, role)`), so a pass that outlived its membership — a leave or a switch
  mid-pass — seals nothing and sends nothing. The app's session (`TransferSession`: the iOS app's `URLSession`,
  Android's — which takes a file too) exports, seals into the staged file and deletes the export at once; the OS's
  queue (`OsUploadQueue`, PhotoKit), which sends the library's own bytes, carries `x-snapsync-file-key` +
  `x-snapsync-file-head` for that one file (`EdgeSeal`) and the edge seals it. A key that is unreadable, absent or not
  the event's withholds the job — never plaintext.
- **Downloads** (`DownloadOpening`, asked by `RecordStaged`): an `OpenVerdict` over the joined event and the event the
  transfer fetched for; the finished file is adopted beside its staging path (`Landing.Sealed`) and opened into it off
  the delegate queue; only a fully authenticated file is staged. One that does not open is discarded and downloaded
  again by the next plan, and its fourth consecutive failure (`OpenFailures`) is reported once at `Error` (the
  crash channel).
- **No key reaches a log or a report** by type, not by scrub: an event key prints masked (`EventKey`), an invite's
  text prints masked (`InviteText`, `ShownInvite` — only what shows or shares it reads it, `OptInPinsTest`), and
  `LinkReading` logs what a link opened, never its text.
- **The event page** (`site/src/lib/event-key.ts`, over the edge's own `api/src/encrypted-file.ts`) reads the key
  from its address's `#k=`, checks it against the event's `keyId` before fetching anything, and opens each file of
  the zip in the browser; without the whole invite it says so and offers nothing. The page is served with a
  `Content-Security-Policy` (`eventPagePolicy`) that runs only the site's own scripts and talks only to this origin
  and the storage host. Google Play's install referrer carries the key too (`v=3&d=…&k=…`, appended only once the
  page checked the key against the event; `inviteLinkFromInstallReferrer` answers the keyed path form), so an
  Android install from the page opens the whole invite. A build before 0.6 reads such a referrer as an organic install.

### On-device layout (iOS)

App Group `group.app.snapsync`, shared by the app and the extension, at the default data-protection
class (readable after first unlock; see `DataProtectionEntitlementTest`):

| path / key | what | owner |
|---|---|---|
| `ledger.db` | the shared database (SQLDelight, `SharedDatabase`): the upload ledger — the membership's share set + manifest version; every row names its event and every read is scoped to the joined one, so another event's rows are inert until the next join purges them — and the records both processes share: the membership (`membership`; no row **is** "left the event"), the uploaded manifest (`uploadedManifest`: event id, version, snapshot), the pending leaves, the event albums (album + filled mark per event) and the event checks. Either process opens it read-write and migrates it; the app copies the records' legacy homes into it at its first open after the update, and the extension pauses until it has (`LegacyRecords`) | the upload rows (`OwnUploadRows`, `WalkRecord`, `RowTransitions`, `UploadRowsReset`, `UploadProgress`), `MembershipRepository`, `DeviceManifestService`, `PendingLeaves`, `AlbumMapService`, `EventCheckRecord` over `IosDatabases` |
| `downloads.db` | the download store: one record per received photo (its state and import marker, across events), which event's union each photo belongs to (`eventRef`, purged at the next join), which membership's received photos have been recognised (`recognised`, purged with `eventRef`), and each planned resource with the event whose bytes it fetches. The app writes and migrates it; the extension opens it read-only. It dies with the app; a join rebuilds its imported rows from the SnapSync mark each received photo's library name carries (`ReceivedPhotoName`, `RecognizeReceivedPhotos`), which is a second record, never the store's replacement | `DownloadStore` / `ReceivedPhotosReader` over `IosDatabases` |
| `upload-staging/` | bytes staged for the app's background `URLSession` uploads, each at a path that is a function of its key, with its `.sha256` digest beside it | `StagedFiles`, `StagedDigests`, `StagingSweep` over `IosFiles` |
| `download-staging/` | downloaded bytes awaiting import, under `<eventId>/<sourceDeviceId>/` (a transfer an earlier build started stages without the event folder). The download store keeps each path **relative** to the container (schema 4 rewrote the absolute ones) | `StagingArea` over `IosFiles` |
| `ext-debug.log` (+ `.1`) | the extension's verbatim log (the app's is `Documents/debug.log` in its own container) | log writers; read by `DeviceLogTails` |
| defaults `app.snapsync.settings.mobileData` | the device's mobile-data choice | `MobileDataStore` over `IosPreferences` (`AndroidPreferences` on Android) |
| defaults `app.snapsync.push.lastRegistered` | the last push registration the backend accepted (token, env, device id); dies with the install, so a reinstall publishes | `PushRegistrationRecord` over `IosPreferences` |
| legacy: `eventconfig.json`, `device-manifest/last-uploaded.json`, `membership/pending-leaves.txt`, `push-registration/last-registered.txt`, defaults `app.snapsync.album.map` / `.filled` / `app.snapsync.check.*` | where the shared records lived before they moved; an updated device's app copies each once and deletes it (`PENDING_CLEANUPS.md`) | `LegacyRecords`, `PushRegistrationRecord` |

Keychain (only in `:adapter:ios:ext-safe`, as `IosSecureStore`; every item is readable after first unlock). Every item is a `SecureSlot` in its owner's slots object (`TrustSlots`, `EncryptionSlots`); `shared = true` names the access group, `false` searches unscoped:

| (service, account) | what | access group |
|---|---|---|
| (`app.snapsync.deviceid`, `deviceid`) | the per-install device id | shared group `<TEAM_ID>.<group>`, cross-checked against both signing entitlements |
| (`app.snapsync.attest`, `token`), (`app.snapsync.attest`, `keyid`) | device token, App Attest key id | unscoped (pinned set) |

`simulator.entitlements` carries the App Group only and **must not** declare `keychain-access-groups`,
because that makes an ad-hoc simulator build unlaunchable.

OS-registered identifiers: the heartbeat's two BGTasks — `app.snapsync.upload.heartbeat` (busy, a processing
task) and `app.snapsync.heartbeat.idle` (idle, an app refresh) — (Kotlin and `Info.plist` must agree:
`RuntimeIdentityTest` asserts `BGTaskSchedulerPermittedIdentifiers` lists **exactly** the pinned set, the wake adapter
registers exactly that set in its `inbound`, and the Swift shell registers none, so a retired id such as
`app.snapsync.download.backstop` left in the plist fails the build); background `URLSession`s `app.snapsync.upload.session` and
`app.snapsync.download.bg` (the OS reattaches transfers by these); framework base names `SnapSyncKit`
and `SnapSyncUploadKit`. The pinned inventory, including retired names kept for recognition, is
`DOCUMENTED_INVENTORY` in `RuntimeIdentityTest`.

---

## 10. Background execution: each wake's own work, then the wake's work

How the app process spends an OS wake. The user-visible promises this serves are in
`openspec/specs/delivery` and `receiving-photos`. The code of record is `entrypoints/AppEntryPoints.kt` (one entry
point per trigger, the shared `WakeWork`, and `ImportReceived`) and `entrypoints/UploadEntryPoints.kt` (the upload's
sequences) over the runtime in `scheduling/` (`docs/patterns.md`).
Decision records: `changes/archive/2026-09-25-own-work-per-wake`, and the core-layers design's phase 3, which moved
the flows, the tail runner and the completion holder under entry points and a runtime without changing what each
wake runs.

**Own work per wake.** Every wake — an entry point — runs a shared prelude (`Prelude`: membership re-read,
attestation refresh), then only the work its event is about, and then the **wake's work** (`WakeWork.full`):

| wake (entry point) | own work | wake's work | heartbeat re-arm |
|---|---|---|---|
| silent push (`OnSilentPush`; active event only, `PushTailLookup`) | union read, plan, download enqueue | full, detached | the cadence rule |
| download-session relaunch (`OnDownloadSessionEvents`) | stage the delivered files (the drain, then the stagings recorded) | full, detached | the cadence rule |
| upload-session relaunch (`OnUploadSessionEvents`; iOS 18–26.0) | the delegate records terminals (the drain) | full, detached | the cadence rule |
| heartbeat (`OnScheduledWake`; on iOS the `BGTask` `app.snapsync.upload.heartbeat`, or `app.snapsync.heartbeat.idle` when idle) | none: the wake *is* the wake's work | full | the cadence rule |
| limited-grant selection change (`OnSelectionChanged`) | snapshot-fed walk → manifest (`WalkAndPublish`) | full | the cadence rule |
| foreground (`OnForeground`) | download reconcile, stored-upload settle (`SettleStoredUploads`), upload-staging sweep (`StagingSweep`), staged-byte reclaim, status and membership refresh (`ForegroundWork`) | full | the cadence rule |
| the network's return, in the foreground (`OnNetworkReturned`) | the foreground's own work, unchanged | full | the cadence rule |
| membership transition's arm (`UploaderArm.apply` on `ARM`, labelled `OnArm`) | none (detached) | full | the cadence rule |
| upload completion (`OnUploadFinished`; admission `Admit` only) | the terminal write | `TopUp` only, detached | never |
| download staged in a running process (`OnDownloadStaged`) | record the staging | `ImportReceived` only, detached | never |

**The wake's work** (`WakeWork.full`) runs two single-flight sequences (`Sequence`, `docs/patterns.md`) **at the same
time**, then the end of the wake once both have ended; neither's failure stops the other, and an import stalled in the
library holds no upload back: `ImportReceived` (import staged downloads — it **yields to a join**, so an import stalled
on a photo-library transaction never holds a new request back; the stalled import runs on, claimed, `outliving` its
pass), and `UploadOwn` (`TopUp` — create jobs for the known `DISCOVERED` rows the policy admits now, until the platform
refuses — then `WalkAndPublish`, the walk → manifest publish, full grant only, then a top-up again when the walk
recorded rows). Under a partial grant the walk
is a selection change's own work, and the top-up resolves from the in-memory selection snapshot (withheld while it is
unread). A request while a pass runs **joins** it: the sequence makes exactly one more pass, however many joined, and
each joiner awaits it; a joiner whose time is up withdraws without cancelling a pass others wait for. Whatever the
passes ended as, `ensure` steps re-arm the heartbeat (a cut pass counts as work left) and record the memory footprint
before the run answers. Own work runs **outside** the sequences, so no wake waits behind another wake's walk. The
download reconcile never imports; `ImportReceived` is the only drain, plus the once-per-process interrupted-import
sweep at host assembly (same per-asset claims). There is no download backstop task any more.

**The heartbeat** is requested through data's `WakeArming` over the `Wake` port: a one-shot wake at a **cadence** —
**busy**, no sooner than 60 s, or **idle**, no sooner than an hour (`HeartbeatCadence.trigger`, domain) — needing the
network, re-requested each time; a request at one cadence replaces a pending one at the other. **The cadence rule**
(the domain's `CadenceFacts.cadence`, applied by the use case `RearmHeartbeat` in `WakeWork`'s `ensure("re-arm")` at
the end of every wake's work) keeps one pending **for as long as the device is joined**: busy while work remains
(uploads a wake left, or staged downloads not yet imported) or while a full-grant sharer notices its own new photos only
by looking — no library-change wake standing and the OS uploader not confirmed registered — until the event's end;
idle for every other joined device (receive-only, uploads held back, a partial grant, caught up with a library wake,
and everyone after the end); none once not joined. The use case reads its facts fresh: the membership and its
direction, the event's phase, the grant (`GrantRead`), the OS uploader's standing (`OsUploader`) and the mobile-data
choice. Decision record: `changes/timely-background-receiving` (D1–D3).

**The library watch** is the arming's other wake: `watchLibrary` requests a one-shot `WakeId.LibraryChanged`,
delivered within 60 s of a change to the photo library, and answers whether one now stands. `RearmHeartbeat` renews it
after every wake whose uploads ran for a contributing membership (any trigger but an import-only one, any outcome
but `SKIPPED`), so a caught-up device still notices the next photo; where one stands (Android), a caught-up sharer
idles. iOS answers it `Unsupported` — its library-change wake is the upload extension, which counts only once the OS
confirms it registered. A disarm cancels both wakes. Decision record: `changes/archive/2026-09-29-android-sharing`
(D5).

**In-process requests hold time too.** Work started from inside the process — a transition's arm, an upload
completion's top-up, a staged download's import, a restored event key — is `detached`: it runs in the runtime's
scope under its own background-time hold, labelled with the trigger that started it, as a wake does. Free on iOS,
where background time is per app; on Android it is what keeps a worker's top-up from being frozen mid-pass.

**Handlers and time.** An OS completion handler crosses as a `Completion`, delivered with its event, and the run that
event starts answers it exactly once — nothing stores one in a field. A `Background` delivery's run (a push, a
relaunch, a foreground) holds the process's background time, the `BackgroundTime` port (`beginBackgroundTask` in
`:adapter:ios:app-only`), begun **on the delivering thread, before the callback returns** — the OS may suspend the
process the moment it does. A push is answered after its own work, and a `URLSession` relaunch after its drain report
(and, for downloads, once the stagings it caused are recorded — its adapter's `Completion` puts the call on the main
thread UIKit requires); the wake's work then runs detached under its own hold. A `BGTask` is a `Granted` delivery:
its run holds no time of its own, and `setTaskCompleted` waits until the wake's work ends. **No clock of ours**:
there is no receipt deadline, no manifest timeout and no `backgroundTimeRemaining` read. "Time is up" is only the
`BGTask`'s `expirationHandler` (the wake adapter expires the `Running` the delivery answered; nothing in Swift answers
it) or the background task's expiration handler (the hold expires the run it was begun for); a refused hold reports
as an immediate expiry. On expiry the run is **cancelled**: no new step starts, a sequence's pass is cancelled once
every waiter has gone, and the completion is answered once the cancellation has unwound — or after
`EXPIRY_GRACE` (2 s, measured on the SE2: see its KDoc) regardless, and the hold ended with it. Work that must
happen together is one non-cancellable step: a top-up checks cancellation between creations, and a job the platform
created is recorded `REQUESTED` before the pass can end; a cancelled walk records and publishes nothing; a
photo-library import runs on, `outliving` its pass. A cut pass counts as `WORK_LEFT` for the re-arm. The only
timeout left is the HTTP client's per-request one, which bounds a request and ends no wake.

**Cheaper work in `darwinbg`.** Background wakes run CPU-clamped (~9× after the first second, measured on an
SE2), so:
- **Walk memo** (app process only): the full-grant walk is reused while `PHPhotoLibrary.currentChangeToken`,
  the selection policy and the grant are unchanged (~2 ms against a 1.3–2.0 s walk). It reproduces a fresh
  walk exactly, so it stays authoritative for deletion. It lives in memory only, stores only completed
  full-grant walks, and reads the token before the walk. A build constant switches it to shadow (walk every
  time, log a disagreement at `Error`) as the revert.
- A walk's resources are handed, once, to the top-up that follows it (`RecentWalk`), so a row it just read is not
  looked up again.
- Download planning is batched (`settledAmong`, `planAll`, `markAllEnqueued`).
- Limited-grant selection changes queued behind a running enumeration are folded into one enumeration. Both
  the change and the baseline path drop a snapshot built under a grant that has since become full.
- The denylisted-album lookup is asked only under a full grant (it can only answer the empty set otherwise).
- The status counts are read only while a screen follows them and the app is in front (`AppForeground.whileInFront`):
  away, a commit reads nothing, and each return reads afresh.
- The network is watched only while foregrounded: `ForegroundWork` starts the network's `ForegroundNetwork` and
  `OnBackground` stops it, so the `NetworkMonitor` port's cold flow — read through data's
  `NetworkReadings`, and the platform's monitor behind it — runs only while a screen shows the result. The watch is
  scheduling's `GatedWatch` (latest-wins); when a reading is told is the domain's `NetworkNotice` (a missing network
  after its grace, its return at once), and what is told is data's `NetworkStatus` (`Online` whenever stopped).
  Presentation reads it as the query `NetworkStatusQuery`: the access to show and `returned`, the moment a shown
  notice clears, which `OnNetworkReturned` follows (capability `sync-status`; decision record
  `changes/tell-when-offline`, D1–D2). `Online` carries whether the network is restricted (mobile data, a hotspot,
  Low Data Mode / Data Saver), which the status line reads for "Waiting for Wi-Fi…" (capability `mobile-data`).
- **A photo transfer carries the device's network rule** (capability `mobile-data`; decision records
  `changes/archive/2026-10-04-mobile-data-for-photos` and `changes/archive/2026-10-07-mobile-data-per-device`): `TransferNetwork`, read
  from the device's choice (`MobileDataStore`, one key in the shared `Preferences` both iOS processes read — an
  unreadable one holds to Wi-Fi) the moment a transfer is CREATED and
  handed over on `UploadTarget.network` / `Download.start` — so a change of the choice governs only later transfers,
  with no state of its own. Whoever owns "when may this run" honours it: iOS's request flags (`applyTransferNetwork`,
  one builder for both upload tiers and the download), `DownloadManager`'s metered flag, and on Android's in-process
  uploader the adapter's own wait — resumed after a process death by the busy heartbeat, whose `WakeNetwork` is
  `UNRESTRICTED` while photos are kept off mobile data. Every other request — the device API over `HttpTransport` — ignores it.

**On Android** there is one process and one uploader. The `Wake`, `BackgroundTime` and `TransferSession` adapters
(`:adapter:android`'s `work/`) are WorkManager's, but for the library watch:
- **a wake** is the heartbeat's unique one-time WorkManager work (its delay and network constraint), or the library
  watch's own JobScheduler job: content-URI triggers on MediaStore's image and video collections, run by
  `WakeJobService`. The watch is not WorkManager's because WorkManager reads a swipe from the recent apps as a
  force-stop at the next start, and cancels the very job that woke the app (`AndroidWake`'s KDoc). A re-request
  replaces a pending wake, except from inside that wake's own run, where it appends: WorkManager's `APPEND_OR_REPLACE`,
  or the job's other id of two, since rescheduling a running job's id stops it. Worker and job are entry ports on the
  `Application.onCreate` composition and build no screen; their stop (`onStopped`, `onStopJob`) expires the wake's
  run. A reboot drops the watch (a content-URI job cannot persist) until the heartbeat's next run re-arms it.
- **a background-time hold** is an expedited work that runs until the hold ends; its stop is the hold's expiry. It is
  what keeps a process the member just left, or one a worker started, from being frozen mid-unit.
- **an upload** is an in-process PUT streamed from MediaStore (it `takesResources`: no staged copy), one at a
  time (parallel streams only divide a thin uplink), each end reported inline. The adapter holds background time while any is live; the hold's expiry
  cancels them, reported failed, to restart on a later run. A small journal names every job not yet reported, and a
  process that died with jobs in it has them reported failed when the next one listens — so a row never waits on a
  transfer that no longer exists. A connection per transfer: a streamed body is sent once, and a pooled connection
  the server closed would fail it outright.

A force-stop cancels every job, WorkManager's and the watch's, until the next opening, when the foreground's wake re-requests them;
before the first unlock the app (not direct-boot aware) never runs. Doze and App Standby defer work to maintenance
windows.

**Push registration is change-driven.** The app asks the push service for the token at every launch (as the graph
is composed, from `onLaunch`) and in `OnForeground` at every foreground entry, and the OS answers every time.
`OnPushToken` keeps the delivered token (`PushTokens`) and runs `RegisterPush`, which publishes only a registration —
(kind, env, deviceId, token) — that differs from the last one the backend accepted (`PushRegistrationRecord`), and
records it only once accepted, so a failed publish is re-sent at the next delivery. A join (`AfterJoin`) and every new
device credential (`OnCredentialChanged`, followed from `AttestationStatus.tokenChanged` on every cold start,
background included) publish whatever the record holds. Host assembly is foreground-only.

**Device-token copies.** Each process may serve the device token from memory. Its own writes drop the copy.
The app re-reads the Keychain at every attestation decision (every wake), the extension at every
`process()`, and both after a `401`. A `401` on a metadata call is recovered IN the call: the app's authenticated
backend waits for the re-attestation and retries once, so the call's caller is answered by the retry (this costs
the wake a challenge, an Apple attestation or a local assertion, and a mint — only when the backend has actually
rejected the token). The extension drops the rejected token and defers to the app's next wake. A retry's request is built through `UploadRequests.retry`, which reads
the store of record.

**The upload extension has no cooperative stop.** Measured (SE2, iOS 26.6): its only end is assetsd's 60.0 s
timer (SIGKILL), `notifyTermination` follows only normal returns, `performExpiringActivity` never fires, and its
memory limit is 32 MB (an overrun is a jetsam kill loop). A `process()` measured 0.6–1.4 s, and every unit is a
safe retry. It walks afresh every call and holds no memo.

---

## 11. The iOS platform layer (`:app:ios`, `:app:ios:extension`, `iosApp/`)

The iOS composition roots and the Xcode host are **wiring only and untested**: all testable logic, shared or
iOS-specific, lives in tested modules, and nothing testable is parked here. This section explains the structure; the
rules an agent must not break while editing are in `app/ios/AGENTS.md`, `iosApp/AGENTS.md` and
`adapter/ios/AGENTS.md`. On-device work is the `snapsync-device` skill, builds and signing `ssh-mac-build`, portal
chores `asc-portal`.

### Two processes, two frameworks

The app and the background-upload extension are **separate iOS processes**, each a separate Gradle
module exporting its own **static** framework that the Xcode project links:

```
:app:ios            → framework "SnapSyncKit"        ← app process (UI + ledger reader)
:app:ios:extension  → framework "SnapSyncUploadKit"  ← extension process (discover→upload)
```

(A third, the forge binary for the marketing screenshots, was deleted in phase 12: the raws come from the rig build
of the real app on a simulator — `screenshots.yml`.)

Two frameworks, not one, for two real reasons: the **extension-safety line** (app-only API —
UIKit/BGTask/URLSession adapters — must be structurally un-linkable from the appex, and
Kotlin/Native links whole modules) and the **appex footprint** (Compose/Skiko has no business in a
memory-capped extension). Both images DO embed the shared domain code, each privately — that is
fine; no Kotlin type ever crosses the process boundary. The app framework carries Compose/UI + the
full `domain` stack; the extension framework is lean (`:core`'s upload layers — `ExtensionPass` over
`UploadGraph` — over the extension-safe adapters `:adapter:ios:ext-safe` + `:adapter:generic:app`). Both are
`isStatic = true` — the Compose-iOS norm (avoids dynamic-linking issues with the bundled
Skiko/Compose native libs).

### The Gradle ↔ Xcode boundary

`iosApp/` (repo root, **not** under this module, **not** a Gradle project) is the Xcode host: app
target + `BackgroundUploadExtension` target. Each target has a run-script phase that calls
`./gradlew :app:ios[:extension]:embedAndSignAppleFrameworkForXcode` to build + embed the
Kotlin framework, and each links `-lsqlite3` in `OTHER_LDFLAGS` (SQLDelight's native driver needs
the system SQLite — without it the device app fails to link). Shared build settings live in
`iosApp/Configuration/Config.xcconfig`; export configs in `iosApp/ExportOptions*.plist`.

You cannot build or run any of this on Linux. Use the proxy:

- `./gradlew compileIosMainKotlinMetadata` — **Linux-runnable** compile of `iosMain`/`commonMain`
  (+ cinterop) for both modules; catches iOS-only Kotlin breakage without a Mac.
- The Swift shells and the Xcode project compile **only on macOS CI** (`macos-26`); there is no
  Swift toolchain locally, so they cannot be built or run on this machine. The extension shell is
  verified on device (real-s3-upload, build 70) — keep edits to it minimal and lean on CI.

### The Swift ↔ Kotlin seam (Swift is a pure transcriber)

Swift shells forward raw, ObjC-visible OS inputs **whole**; every decision is Kotlin (migration
step 12; `SwiftShellGuardTest` pins the decision keywords — `if`/`guard`/`switch` at zero, one `??`).

- **App entry** (`iosApp/iosApp/iOSApp.swift`): UIKit `@main` app delegate + scene delegate. The scene
  delegate forwards every delivered `NSUserActivity` whole, under its own entry name per half —
  `onLaunchActivity` (cold) / `onSceneContinueActivity` (warm), so a dump says WHICH hook the platform
  invoked — `IosLinks` tells a browsing-web activity with its raw `absoluteString` (fragment included) from one with
  no URL and from any other activity, and the core's `LinkReading` reads a link as the invite it opens. A silent push forwards its `userInfo` dictionary whole
  (`onSilentPush(userInfo:completion:)`; the `eventId` extraction is the tested payload codec).
  Foreground/background are **not** a Swift split any more: `SnapSyncRoot.onLaunch()` (called from
  `didFinishLaunchingWithOptions`) installs Kotlin-side `NSNotificationCenter` observers for
  `didBecomeActive`/`willResignActive`. `ContentView.swift` bridges
  `MainViewControllerKt.MainViewController()` (Compose) into SwiftUI — **gated on activation**: it binds
  Kotlin's scene generation (0 before any activation, 1 after) to `.id(…)`, so the Compose view is built
  once, at the first `didBecomeActive`, and never rebuilt. Until then `MainViewController()` returns a bare
  placeholder. WHICH it returns is Kotlin's tested decision (`resolveScene`), never Swift's. Why: iOS
  connects UI scenes in the BACKGROUND, so a silent-push wake would otherwise stand up a Compose runtime
  and Metal renderer in a process that cannot draw and present it hours later drawing dead textures
  (capability `app-experience`; mitigation for CMP-5978 — delete when fixed upstream). ⚠️ Key on the
  APP-level notification, not `sceneDidBecomeActive`: `dvt launch` foregrounds the process WITHOUT
  connecting a scene session, so a scene-level hook gives a black screen and kills the headless
  screenshot loop (measured 2026-08-06).
- **Extension principal** (`iosApp/BackgroundUploadExtension/BackgroundUploadExtension.swift`):
  `@main` class conforming to the iOS 26.1 `PHBackgroundResourceUploadExtension`; its `process()`
  constructs `PHBackgroundResourceUploadProcessingResult(rawValue:)` from the raw `Int` Kotlin's
  core decided — the `ProcessingAnswer` it returned, through `processingResultRawValue()` (`?? .failure` — the one remaining Swift
  pin; the system type is Swift-only, so the construction cannot leave the shell).

### Composition roots (manual DI — no expect/actual; each calls its composition)

Both roots are wiring only: each constructs its process's platform adapters and hands them to the
composition functions — the app's `composeApp` in `:core:composition:app`, the extension's in `:core:composition:extension` (law "The composition layer") — there
is no per-root cycle or feature assembly any more.

- **App**: `app/ios/src/iosMain/.../SnapSyncRoot.kt` — app-lifetime singleton owning a
  `SupervisorJob` scope on its own composition lane (outlives Compose recomposition). Builds `AppPorts` —
  ports and nothing else (`PortBundleTest`): the process's `ProcessPorts` with its `IosBuildInfo`, the App-Group
  storage ports, PhotoKit, the generic HTTP backend, the entry ports — and calls `composeApp`
  (with the lane its scope runs on), whose first act sets the process up (crash reporting first) and which builds the
  whole graph eagerly over the ports — opening nothing (law "A constructor never touches a port"); the screen host is
  assembled later, by `AssembleHost`, when a person first reaches the app. **The scope carries a
  `CoroutineExceptionHandler`** (scheduling's `RootScopeFailures`) — its one
  non-negotiable member: a `SupervisorJob` isolates siblings from each other but does **nothing**
  for a throwable no child handles, which on Kotlin/Native hits the default terminate → `SIGABRT`.
  Without the handler an uncaught launch-path failure (a platform-API call, an App-Group read, a
  deprecated PhotoKit selector on a newer iOS) aborts the whole app before first paint. The handler
  logs the throwable to `debug.log` (the un-redacted channel) and lets the app live — the
  "errors reduce into state, never crash the shell" rule, applied to the one seam Compose reduction
  cannot reach. Every layer still reduces its own domain errors into `UiState`; this is the last
  resort for what nothing else did, and it is what makes an otherwise-invisible launch crash
  self-diagnosing (the exception text lands in `debug.log` instead of an opaque abort).
- **Extension**: `app/ios/extension/src/iosMain/.../UploadExtensionRoot.kt` — builds
  `ExtensionPorts` (its `ProcessPorts`, the App-Group databases, files and protected store, the PhotoKit library
  reader and upload-job queue — from `:adapter:ios:ext-safe`, where the platform adapter lives — the
  `:adapter:generic:app` HTTP backend, its `IosExtensionHost`) and calls `composeExtension(ports)`, which builds the
  process first (`startProcess`), then its `ExtensionGraph` — the read-only identity, the read-only suppression view,
  the drop-only credential, each declared as its restricted type — and registers its handlers on the
  host; `process()` runs `OnProcess` — one blocking `ExtensionPass` over the shared `UploadGraph` and the OS's queue
  (`OsQueueTier`, `OsQueueDrain`) — whose `ExtensionAnswer` it maps to the `ProcessingAnswer` (an in-flight job
  answers *processing*, `UploadPass.extensionAnswer`) and the raw-value handoff through the tested `ports/` rules.
- The app-driven tier's uploader (`UploaderGraph`, `:core:composition:app`) builds the same `UploadGraph` over the app's own
  services and its `SessionTier` over the `TransferSession` port (the background-`URLSession` adapter); the wake's
  sequences (`TopUp`, `WalkAndPublish`, `UploadOwn`) drive it.

**Neither is the direction gate** (capability `delivery`). Whether a membership uploads **at all** is
decided inside every upload pass, past its gate: `DeriveContribution` derives the membership's one policy (data's
`Contribution`, its `SelectionPolicy` built from the direction *and* the cutoff), and one that does not
`contributes` walks nothing and creates no job — the pass answers `UploadPass.SKIPPED`, publishing only the empty
manifest, its honest statement (`WalkAndPublish`, `ExtensionPass`), and the heartbeat reads it as not
contributing. The roots pass no fact for it at all: the policy is derived from the membership the gate read.
That gate sits at the **choke point every trigger funnels through**, not at the arm's invoker, because an
invoker-gate is only as good as its enumeration of invokers. It used to be one: a download-only membership was
handled by simply not enabling the producer, on the reasoning that "the OS never invokes the extension" — true
on ≥26.1, false here, where the *app* invokes its own cycle. `onForeground` walked straight past it and
uploaded the camera roll of a member who had been promised "you won't share yours".

**The upload lifecycle is NOT decided here** either (same capability). `SnapSyncRoot` supplies only
**ports** — among them the `ExtensionRegistry`, whose `supported` says whether this OS carries the extension, and
`DevControls`, the rig's per-uploader pin. What each membership transition does (join · reconfigure · launch ·
grant change) is the domain's `UploaderChange` (`registration`) and `UploaderArming`, applied by the use case
`ReconcileUploader` from data's `OsUploader.admission` and `standing` read fresh at that moment: it registers the
extension through `ExtensionRegistrar.register` (the disable → enable ritual — never a bare enable — and only with
a `FullGrant` read then), and answers what `UploaderArm` does to the app's uploader (arm; disarm, the wakes only;
or nothing, `UNCHANGED`, when a change needing the membership cannot read it). Stopping is `StopUploads` alone, and
it takes **evidence** the membership's uploads end (`EndsUploads`: a committed leave, no membership after a leave
that did not finish, a deleted event): the extension deregistered, the wakes cancelled, the live session
transfers cancelled with their staged files (`AfterJoin`, `AfterSettings`, `Leaving`, `SweepLeftovers`,
`DeletedTransfers` in `MembershipEntryPoints.kt`). **None of them clears the upload rows**: only a join resets
them (`LoadShareSet` over `UploadRowsReset`: the joined event's seeded from a confirmed listing, every other
event's purged; and the rig's reset clears them), as its own step (`delivery`, `join-event`). And **no transition but an end of uploads stops in-flight
work**: the registration spans the membership (never removed by a reconfigure or a grant change), a revoke stops
only new creation — so nothing is ever orphaned, and nothing needs repair (decision record
`openspec/changes/both-uploaders-active`).

**Both uploaders run, and that is by design.** Every app-side trigger goes to the app's uploader unconditionally;
its pass's gate (`UploadGateRead`) withholds only without usable access (`AppUploaderAdmission`, or when the rig
switched it off), and the extension's withholds without a full grant (`ExtensionUploaderAdmission`). On ≥26.1 under a
full grant both create, over the one App-Group ledger: `CreateJobs` reads each row again just before its job and
creates only for a `Discovered` one, and `RowTransitions` records it requested only once the platform accepted the
job, so an overlap is at worst a duplicate upload of the same object, and the guarded terminal write converges the
row. The launch reconcile is
called explicitly from host assembly — the permission `StateFlow`'s replay is no longer a transition — and it
*compares* against the OS's registration, registering only a record the OS reads absent, so a launch never
wipes the extension's in-flight jobs; only a join forces the ritual.

This structure is load-bearing, not tidiness. The lifecycle *used* to live here as a pile of
`if (useAppDrivenUpload)` branches, and because this module is wiring-only and untested, nothing caught
that `provisionEvent` → `enableBackgroundUpload()` → `disableExtension()` resolved, on the app-driven tier,
to a **full leave** (cancel transfers, cancel the heartbeat, wipe ledger + cursor) followed by a no-op
enable. Joining an event tore the upload arm down and started nothing. `ProducerExclusivityTest` guards what
the compiler cannot — driving the real `ReconcileUploader` and `ExtensionRegistrar`, that the extension is never
registrable below 26.1, and that no transition sequence wipes in-flight work anywhere but at a leave.

**Ledger writers are owned by code, not by a process** (`photo-sharing`). The app holds the upload rows' writers on
every OS version — built in `UploadGraph` and the app's uploader (`UploaderGraph`) — and on **iOS ≥26.1**
the **extension** builds the same layers. Every write is one guarded transaction owned by named code: the walk's
(`WalkRecord`), a tier's guarded transitions (`JobRecord` over `RowTransitions`), and the join's reset
(`UploadRowsReset`). Outside the shared composition, `:app:ios` constructs no writer.

### Entitlements & Info.plist (the cross-process glue)

- **App Group `group.app.snapsync`** (both `*.entitlements`): the shared on-disk container for the
  shared database both processes write (`ledger.db`, `SharedDatabase` over `IosDatabases`): the ledger rows and the
  membership of record (`MembershipRepository`; its `membership` row is the only membership there is, so the
  container's lifetime IS the membership's: **reinstall = left the event**). An updated device's app copies the membership's
  legacy file (`eventconfig.json`) into it once; until it has, the not-found classification (`isFileAbsence`,
  behind `IosFiles`) is what stands between a misread error and a silent logout. **Must be registered in the
  Developer portal** and enabled on both App IDs, or signed builds fail to provision.
- **Keychain group `$(AppIdentifierPrefix)app.snapsync.shared`** (both `*.entitlements`): lets the
  extension read the shared Keychain items the app writes — the device id
  (`PersistedDeviceIdentity`) and the attestation token (`AttestState`). The legacy config
  item used to be a third; the Stage-2 change deleted its reader, so nothing addresses it any more
  and an already-migrated device simply carries it as an inert orphan (purging it would mean keeping
  the seat, its runtime-identity pin, and a Keychain call on the leave path alive to delete data no
  code path can observe). Keychain groups need **no** portal step. ⚠️ The **device id names this group explicitly**
  (`kSecAttrAccessGroup`); declaring the entitlement is *not* sufficient. This entry used to read
  "…all of which omit `kSecAttrAccessGroup` and rely on this default group", and that was false: with
  no group named, the platform picks one **at write time** from the writing build's entitlements, so a
  dev-signed build (whose profile grants the wildcard `<TEAM>.*`) writes into each process's own
  `application-identifier` group instead. On 2026-07-20 the app and the extension therefore held two
  different device ids — both reads succeeding — and the app re-imported every photo it had uploaded
  as if a stranger had sent it. The attest pair remains unscoped deliberately (the token is
  demonstrably read cross-process); the config reader and the album map were unscoped seats too, until
  their readers were deleted, and their entries left the inventory with them. That inventory
  is pinned in `:test:architecture` — a *new* unscoped seat fails the build, including a
  reconstructed config one.
- **Associated domain `applinks:snapsync.stho.net`** (app entitlements only, via
  `$(ASSOCIATED_DOMAIN)`): claims the event link's Universal Link (capability `invite-link`), which is
  how a Camera-scanned QR opens the app. Like App Groups (and unlike keychain groups) **it must be
  enabled on the app.snapsync App ID in the portal**, or signed builds fail to provision — and
  enabling it *invalidates existing profiles*, so the dev build loop's baked secret needs refreshing
  (the `ssh-mac-build` skill). The **extension declares none**: it never handles URLs.
- **App `Info.plist`**: registers **no** `CFBundleURLTypes` — the `snapsync` scheme is retired, and a
  scheme re-added here would route links the one authoritative codec no longer accepts.
  `CADisableMinimumFrameDurationOnPhone = true` is **mandatory** — Compose MP ≥1.7
  hard-aborts at launch without it. Portrait-only, iPhone-only.
- **`BackgroundUploadURLBase` — in BOTH `Info.plist`s, and it is the one deployment value that lives
  there.** `assetsd` reads it out of a **bundle's own `Info.plist`** to validate the background-upload
  registration insert; it can see no resource we bundle, so `Deployment.plist` cannot stand in. Absent,
  `setUploadJobExtensionEnabled(true)` fails with a bare `PHPhotosErrorDomain -1` and **empty**
  `userInfo`, the OS launches the extension never, and nothing uploads on that tier — which is exactly
  what shipped in builds 675/687 when a config change moved the key out (measured A/B on device,
  SE2/26.6, 2026-08-28; Bugsink `SNAPSYNC-37`). The daemon's **matching rule is not established** — do
  not assert one. ⏰ Re-measure at the next iOS major.
  Written as `$(UPLOAD_SCHEME)://$(UPLOAD_HOST)/api/v2`, **composed** rather than carried: an
  `Info.plist` substitution can only read a build setting and `//` opens a comment anywhere on an
  xcconfig line, so the resolver emits a scheme enum and a bare host and the URL is assembled in the
  plist, where `//` is data. CI's `ios-build` asserts it equals that bundle's `Deployment.plist` `uploadBase`.
  It must be an HTTPS endpoint: default ATS (HTTPS-only) applies, no `NSAllowsLocalNetworking`
  exception. (A user-configurable upload host is impossible with this API.)
- **Extension `Info.plist`** also declares `EXExtensionPointIdentifier =
  com.apple.photos.background-upload` (under `EXAppExtensionAttributes`, the ExtensionKit iOS 26
  model).

### iOS-version deviation & the two uploaders

App deploys **min iOS 18**. There are two uploaders, and **both run** where both exist (decision record
`openspec/changes/both-uploaders-active`): each decides at its own entry gate whether it may create, and an
overlap is a duplicate upload of the same object, never a loss. `SnapSyncRoot`'s one switch decides only
**presence** — whether this OS carries the OS-driven uploader at all (`isOperatingSystemAtLeastVersion(26.1)`).
Whether the extension may be **registered** is the domain's `OsUploaderAdmission` over that fact, the current
photo permission, and the rig's per-uploader switch (always `null` on a production build — its only writer is the
rig's boot hook, not compiled in without `-Psnapsync.rig=true`).

- **iOS ≥26.1 — PhotoKit (`delivery`).** The OS-driven upload extension, using the
  **deprecated 26.1** `PHBackgroundResourceUploadExtension` (the only protocol runnable on current GM
  devices). `setUploadJobExtensionEnabled` is confined to `PhotoKitExtensionRegistry` (`:adapter:ios:app-only`), the
  sole caller of that selector and of its read-back, reached through the `ExtensionRegistry` port by
  data's `ExtensionRegistrar`; `ReconcileUploader` registers only where `OsUploaderAdmission` says registrable,
  never where the OS does not carry this uploader (<26.1) — so it can never trap on a lower system, and there the
  port answers `Unsupported`. The registration spans the membership: registered at the join wherever the OS allows
  it (a full grant), download-only included, and removed only when its uploads end (`StopUploads`). Under a partial grant no registration write is
  attempted (every one is refused — 3311); a surviving record is invoked by the OS, and its extension withholds
  at its own gate — it records and acknowledges, and creates nothing. A later move to the iOS 27 async
  `PHBackgroundResourceUploadJobExtension` is confined to the Swift shell + deployment target.
- **app-driven `URLSession` (`delivery`) — every iOS version, whenever access is usable.** The
  **main app process** uploads over a background `URLSession` + `BGProcessingTask`, via
  `IosUrlSessionUploadPlatform` / `IosWake` (`:adapter:ios:app-only`) under the same `:core` upload
  layers (`SessionTier`), whose sequences (`TopUp`, `WalkAndPublish`, `UploadOwn`) the app's runtime runs single-flight. Below 26.1 it is the only uploader; from 26.1
  it runs beside the extension, both writing the one App-Group ledger (every write a guarded single transaction).

**Exercising one uploader alone on a device works through the rig**: `rig dev-controls pin --app true|false --extension true|false`
(`rig-channel`). `extension=off` deregisters the extension (`ExtensionRegistrar.followSwitch`); `app=off` makes the
app's uploader withhold (`AppUploaderAdmission`). Both read the pin through `UploaderSwitches` over
`DevControls.uploaderPin`.

There is **no host axis** any more: nothing reads `SIMULATOR_DEVICE_NAME`, and there is no
simulator-specific session. The transport used to be downgraded to a foreground session on the
simulator, on an unmeasured belief that a background one could not run there; measured 2026-08-09 on
`iosSimulatorArm64`, it runs — `getAllTasks` answers and an upload task completes. ⚠️ That covers the
**transport** only: whether the OS relaunches a terminated app to deliver
`handleEventsForBackgroundURLSession` on a simulator is still unproven.

⚠️ The OS's upload-job registration lives in the **system**, not the app, and survives relaunch and
reinstall. To exercise the app's uploader alone on a ≥26.1 device, switch the extension off with `rig-channel`'s
`rig dev-controls pin --app true --extension false`. The on-device loop is in the **`snapsync-device`** skill . Irrelevant on a real 18–26.0 device, where no appex can exist at all.

### Gotchas

- **Device logs:** both composition roots hand their composition the sinks `PublicNSLogSink()` and
  `FileLogSink(<destination>)` — they live in `:adapter:ios:ext-safe` (capability `privacy-security`) — and it
  (`StartProcess`, run by the composition's first act) installs `Log`'s writers and logs the boot banner. The file
  sink takes its **destination**: the app passes `appLogDestination()`
  (its own `Documents/debug.log`, pullable as before), the extension `extensionLogDestination()`
  (`ext-debug.log` in the **App Group**, so the app can read it for a diagnostic dump; it falls back
  to its own Documents when the container is unavailable and says so in the boot banner). Verbatim,
  10 MB roll. The os_log `PublicNSLogSink` is redacted `<private>` on current iOS. The extension's log
  lives in the App Group, which is not USB-pullable; read it through the control channel
  (`GET /device/logs?process=extension`, load `rig-channel`) — the copy-into-Documents launch trigger that
  used to serve this is gone, along with every other one. The banner is the process start's (`StartProcess`,
  from `BuildInfo`'s facts); a root logs only what it alone knows (the adapter choice, the extension's log
  destination) right after composing, and wraps its entry points with `Logger.invocation`, so every line carries a `[<entryPoint>]` prefix. Keep new
  entry points wrapped, or their downstream lines lose the trigger prefix.
- **`-lsqlite3`:** required in each target's `OTHER_LDFLAGS` (above) for any target linking SQLDelight's
  native driver — check any new target; the symbol resolves at link time, not
  at call time, so the failure is a link error rather than a crash.
- **In-memory SQLite on Native:** `NativeSqliteDriver` shares an in-memory DB across connections via
  shared-cache — give each backend a **unique db name** to avoid cross-test/instance leakage.
- **Compose scope ownership:** `SnapSyncRoot` deliberately uses a process-lifetime `SupervisorJob`,
  not `rememberCoroutineScope` (which dies with the view). Move ownership to Swift only if
  scene-aware lifecycle (multi-window, reset/logout) is ever needed.
