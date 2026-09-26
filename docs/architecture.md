# SnapSync architecture

How the app and the backend are put together, which rules hold them in shape, and what enforces each
rule. This is an engineering doc, not a contract. What a user can observe is specified in
`openspec/specs/`. The reasons behind a rule live in the decision records cited inline
(`openspec/changes/archive/<id>`). Testing lives in `docs/testing.md`, and build, deploy and release
live in `docs/deployment.md`.

**The gates are the authority, and this doc explains them.** Almost every rule below is a check that
fails `./gradlew build` (or `deno task lint` / `deno task test` for `api/`). Where the doc and a gate
disagree, the gate wins. Fix the doc. A rule marked **review** has no gate, and only a reviewer who
knows it can hold it.

Main decision record: `changes/archive/2026-07-17-establish-target-architecture`. Seam, failure, state
and concurrency rules: `changes/archive/2026-09-23-harden-seam-bug-classes`.

The structure is being re-cut into thin ports. The direction is §11.

---

## 1. Overview

```
api/        the backend: Deno + Hono on bunny Edge Scripting, a relational store, a byte store (section 8)
site/       the Astro landing and no-app download pages (served by api/ from the storage zone)
iosApp/     the Xcode project: the app target and the upload-extension target (Swift shells only)
domain/ adapter/ ui/ app/ test/ tools/    the Gradle modules (below)
architecture/   GENERATED diagrams (section 6)
```

### The module set: three groups

Every module in `settings.gradle.kts` belongs to exactly one group. The group names the law that
justifies the module's existence. A structure that no law justifies is a **package** (with a derived
text gate if it needs one), not a module. The enumeration lives in `ModuleSetTest`
(`:test:architecture`). Adding a module means adding the `include` **and** placing the module in one
group, with the group's argument in the commit.

| group | why it exists | members |
|---|---|---|
| **Withholding** | withholds a dependency (third-party, platform, or another core zone) from its consumers by compile error | `:domain:model` `:domain:ports` `:domain:services` `:domain:feature` `:domain:flow` `:domain:presentation` `:domain:compose` `:domain:host` · `:ui:screens` `:ui:components` · `:adapter:ios:ext-safe` `:adapter:ios:app-only` `:adapter:generic:app` `:adapter:generic:fake` · `:app:ios` `:app:ios:extension` `:app:desktop` |
| **Contained** | exists so that something is **absent** from a production build, and is linked only under a build property | `:app:ios:forge` (`-Psnapsync.forge`) · `:test:rig`, `:test:contracts` (`-Psnapsync.rig`) |
| **Support** | never linked into a shipped-format binary, exempt from production-module laws | `:test:world` `:test:integration` `:test:architecture` `:test:harness-driver` `:test:edge` `:test:control` `:tools:diagrams` |

Key placements:

- `:ui:components` is the only module that may depend on Material 3.
- The host, `:domain:host`, is the only module that sees both the core's `compose/` zone and
  presentation, so neither gains the other.
- `:test:contracts` is the only module whose main code may assert (it withholds `kotlin-test`).
- `:test:control` and `:test:integration` compile against `model/`, presentation and `feature/`, each
  declared explicitly (the zones export nothing transitively), and never against `ports/`, `flow/`,
  `compose/`, the host or the world. Within `feature/`, `ReadModelImportsTest` confines them to the
  `readmodel` packages. The compile boundary and that gate are the integration surface's read-model rule.

The per-module one-liners are in `CLAUDE.md` ("Modules"). The live graph is `architecture/modules.md`.

### The core and its zones

The core is eight modules under `domain/`: seven zones and the host, each with its package at
`app.snapsync.<zone>` (the host's is `app.snapsync.host`). Each declares only the zone edges its law
permits, always with `implementation()`, so a forbidden reference does not resolve. That covers
fully-qualified names, typealiases and generated source, which a text scan would miss. The permitted
edges are `ModuleSetTest`'s `permitted` map.

```
model  <-  ports  <-  services  <-  feature  <-  flow  <-  compose
                                       ^                     ^
                                       +-- presentation -----+-- host   (host = compose + presentation + ports)
```

| zone | holds | may reference |
|---|---|---|
| `model/` | vocabulary, pure domain services and codecs, `UserCommands`/`UserQueries` bundle types (and the `EventCreator` command), logging helpers, `UiState`, and **every pure-data type a port carries** | nothing project-internal |
| `ports/` | every port interface, outbound and inbound (`PlatformEntries`, `ExtensionEntries`), plus port-adjacent logic not yet re-homed (`resolveOrMint`, `runProcessCycle`, the `CycleResult` raw-value mapping, …) | `model/` |
| `services/` | the shared capabilities built over the thin ports: what a store holds, when it is opened, what a failure means. Today the storage services (`LedgerService`, `DownloadService`, `SuppressionService`) and their SQLDelight databases (the `.sq` files and generated code live here, so the zone's compile boundary covers them), and the gallery services (`GalleryDiscovery`, `GalleryAssetPresence`, `GalleryCandidateSource`, `GalleryAlbums`). Each still implements its transitional interface in `ports/` | `model/`, `ports/` |
| `feature/` | business rules, one package per feature, mutually blind. A type consumed outside `feature/` lives in that feature's `readmodel` package (`feature/<feature>/readmodel/`) — the package is the definition of a read-model | `model/`, `ports/`, `services/` |
| `presentation/` | the UI-state reduction (`StatusContainerHost`, reducing into `model/`'s `UiState`) | `model/`, and `feature/` read-model packages only |
| `flow/` | the OS-callback trigger flows (`Foreground`, `Background`, `SilentPush`, `Provision`): ordering only | `model/`, `feature/` (never `ports/`) |
| `compose/` | the shared composition (`snapSyncApp`, `uploadCore`), the inbound-port implementations, decorators, port-state subscriptions | every zone but `presentation/` and the host |
| host (`host/`) | the shared host composition, `snapSyncHost`: the core plus the status host over it | `model/`, `ports/`, `compose/`, `presentation/`, and `feature/` read-model packages only |

A **pure-data type** is a data class, an enum class, or a sealed class or interface that references no
port and carries no logic beyond its own members. A port's pure-data types are declared in `model/`, so an
adapter and a feature share them without either naming the other's zone.

**Allowed targets.** The core's modules and the `:ui:*` modules compile for exactly `jvm`, `iosArm64` and
`iosSimulatorArm64`, declared once by the `snapsync.targets` convention plugin in the `build-logic/`
included build. No such module lists its own targets; it may configure one the plugin declared (its test
runtime, say). Adding a target (Android, one day) is an edit to the plugin and this list, not to any law.

`domain/` is a path grouping, not a module. The zone split is the one place where withholding is done
by an internal boundary. It was chosen because the text gates it replaced had to enumerate violation
forms, could not see generated code, and passed green when their directory was renamed.

### Adapters

The adapter tree is two levels deep, `adapter:<platform-axis>:<linkage-leaf>`, and the prefixes are
paths, not modules. Each axis names the question that separates its leaves:

- **ios axis = process linkage.** `ext-safe` may link into the upload-extension process. `app-only`
  must not. It owns the app-process `URLSession` adapters, whose OS-reattached session ids make an
  extension-side link unsafe.
- **generic axis = shippability.** `app` ships in the app and extension binaries. `fake` never ships.
  Its classes are all `internal` behind port-typed factories, so honesty is checked by the compiler.

Adapters are named for the technology and hold implementations only. Finer structure is packages.

### Binaries

| binary | composition root | calls |
|---|---|---|
| iOS app (`SnapSyncKit`) | `:app:ios` `SnapSyncRoot` | `snapSyncHost` from `:domain:host` |
| iOS upload extension, iOS 26.1 and later (`SnapSyncUploadKit`) | `:app:ios:extension` `UploadExtensionRoot` | `uploadCore` + `extensionEntries` |
| forge (marketing screenshots) | `:app:ios:forge` | forged sources only, no live graph |
| desktop harnesses, JVM rig host | `:app:desktop`, `:test:rig` over `:test:world` | the same `snapSyncHost` |

---

## 2. The laws

One line each. The authority is the named gate. Gates live in `:test:architecture`
(`test/architecture/src/test/kotlin/app/snapsync/architecture/`) unless noted. **review** = no gate.

### Structure

| law | enforced by |
|---|---|
| The build's module set equals the three groups, and each module is in exactly one group | `ModuleSetTest` |
| A core zone references only its permitted zones, through `implementation()` edges only | the compiler (zone modules) + `ModuleSetTest` "the core declares only permitted zone edges" |
| The core names no platform API and imports only its per-zone allowlisted libraries | the compiler. The allowlist is each zone's `build.gradle.kts` dependency block |
| Features are mutually blind (no feature references a sibling) | `ZoneFeatureBlindnessTest` (text; features enumerated from the directory) |
| Outside `feature/`, presentation, the host, every `:ui:*` module and `:test:control` (main and test) name only a feature's `readmodel` package. `:app:desktop` is exempt until the entry-surface phase rewires it | `ReadModelImportsTest` (text: the line runs inside the one `:domain:feature` module, which no module edge can draw) + a non-vacuity twin + a ratchet that fails once the desktop exemption is no longer needed |
| A port's pure-data types live in `model/` | **review** (`model/` compiles without `ports/`, so a moved type cannot reference a port) |
| Core and `:ui:*` modules declare no target list of their own | **review** (the `snapsync.targets` plugin declares them) |
| Only `:ui:components` sees Material 3, its icons, or the QR library | the compiler (`implementation` dependencies in `ui/components/build.gradle.kts`) |
| `:domain` has no `iosMain` source directory | **ungated** (visible in review) |
| `:domain` declares no top-level mutable state (no allowlist) | **ungated.** The old spec named a "core-purity gate", but none exists |

### Ports and seams

| law | enforced by |
|---|---|
| Anything touching an external system (time, files, network, platform) goes through a port in `ports/`, named for the **need**, never the technology | **review** (partly the compiler: `ports/` cannot import Ktor, nor any of SQLDelight but its runtime interfaces, which `Databases` carries) |
| A storage port is one external system and decides nothing (`Databases`: open by name, read-write or read-only; `Files`: read, tail, write, delete, exists, locate within an area; `Preferences`: get, set, remove). What a store holds, when it opens and what a failure means is a service's, in `services/` | **review** |
| The photo library is one thin port: `GalleryReader` (both processes: assets by policy or id, resources by id, albums, album members, create, add) and `Gallery` (the app: plus the access request, the selection picker, the partial grant's selection observer, the import and the change token). It answers what the platform shows, and `NotReadable` — never an empty answer — when no grant lets the process read. Whether a walk is authoritative for deletion, which grant may say a photo is gone and which albums are denied are the gallery services' (`services/`). Asset ids cross in one form, opaque to the core | `GalleryReaderContract`, `GalleryContract`, `GalleryImportContract` (live on `IOS_SIM_APP`, the no-grant state on `IOS_SIM_KEXE`) + `GalleryServicesTest` |
| The origin exclusions' tuning — the two resolution floors and the album denylist — is one `SelectionCalibration` value in `model/`, product policy rather than a platform fact: no composition supplies its own, so the app and the extension cannot disagree | **review** |
| `SecureStore` writes answer a `WriteOutcome`; the identity and attestation services throw where the old throwing store did, so a refused write fails the operation (the device id is unavailable and never used unsaved; a token is not accepted; a keyId is refused). A write may replace by delete-then-add, so after a refused one the old value may be gone | `SecureStoreContract` (`INACCESSIBLE_WRITE_REFUSES`) + `PersistedDeviceIdentityTest`, `AttestStateTest` |
| Ports never call ports: no adapter's constructor takes a port. Combining two external systems is a service's job (`services/`) or a feature's, so no decision hides inside an adapter where a mock cannot see it and a second platform would have to re-make it | `PortsNeverCallPortsTest` (every constructor parameter of the adapter modules' production source sets typed as a `ports/` interface; the rig's decorators are exempt). Its allowlist is exact both ways and only shrinks: the holdings 11d, 11e and 11f remove, each named with its phase |
| The backend is ONE port, `Backend`: one method per route, answering a typed `Reply` (`Ok`, `Refused(status, body)`, `Malformed`, `Unreachable`), deciding nothing. A method takes a `token` exactly when its route is gated. `HttpBackend` is its one implementation on every platform, over the HTTP client each composition supplies | `BackendContract` (live against `api/`) + `HttpBackendTest` (the token-taking routes are exactly `isGatedRequest`'s) |
| The backend's verdicts are `AuthenticatedBackend`'s, in `services/`, and nowhere else: it reads the credential per call; a `401` on a gated call that carried a token drops THAT token, recovers, and retries the call once with the recovered token; a `426` on any route refuses the build; a success clears the refusal. The app's credential re-attests; the extension's only drops the token and never retries. Every need-shaped backend service (directory, join, manifest, leave, union, device files, create, rename, push token) sits over it; attestation reaches the ungated `/attest/…` routes on the raw port, so recovery never re-enters itself | `CredentialedBackendTest`, `BackendServicesTest`, `CredentialRecoveryWorldTest` (the loop, composed) |
| `DeviceIntegrity` only proves (`prove(challenge, handle?)`: a fresh key's attestation, or an assertion by an existing one). The attestation service decides when, and `AttestState` keeps the token and key handle | `DeviceIntegrityContract` (recorded on a device, replayed every build) + `DeviceAttestationTest` |
| Paths are area-relative (`FileArea.SHARED`/`PRIVATE`); only an adapter resolves a platform path, and `locate` is the one exit, for a platform API that must be handed a file. No absolute container path is stored | **review** (the download store's staged paths: `DownloadStoreMigrationTest`) |
| `Files` answers `NotFound` for a definite absence only: a present file that cannot be read is `Denied`, never absent — a config file read as absent is a false leave | `FilesContract` (`DENIED_IS_NEVER_NOT_FOUND`, live on JVM and `IOS_SIM_KEXE`) |
| Building a composition opens no database: a storage service opens through `Databases` on first use and keeps only a successful open, so a locked launch's failure is retried on the next use | `CompositionOpensNoDatabaseTest` (`:test:world`) + `LedgerServiceOpenTest` |
| The download store has one writer and one migrator, the app. The upload extension opens it read-only: no store suppresses nothing; an older schema **pauses** the cycle (`CycleResult.Paused`, answered to iOS as *processing*) and never runs it without suppression; an unopenable one skips it. The pause is asked only after the extension's admission, so a partial grant never pauses | `SuppressionServiceTest`, `ExtensionSuppressionWorldTest`, `DatabasesContract` |
| A platform's magic values, ABI integers and error tables stay in adapters, never in `model/`/`ports/`/`feature/` | `PhotoKitAbiContainmentTest` (PhotoKit media ABI). The JVM target rejects Apple types. Otherwise **review** |
| A function type is a seam only for an in-process, non-throwing callback into the core. Anything that leaves the process, can throw, or needs a lane is a port | `CompositionSeamTest`: every function-typed field of a `*Ports` bundle and every function-typed constructor parameter in `feature/`/`compose/` is pinned with its reason, exact in both directions |
| A process-constant value (build version, baked host) is a plain value, not a thunk | **review** (surfaces via `CompositionSeamTest` pins) |
| No function-typed `var` (callback slot) in production. Callbacks are bound at construction | `LambdaSeamShapeTest` |
| No default on a function-typed constructor parameter (except `@Composable`) | `LambdaSeamShapeTest` |
| Each OS entry surface is one **inbound port** (`PlatformEntries`, `ExtensionEntries`), named for what the OS says, implemented in `compose/`, reached by Kotlin delegation from the root | the inbound-port contracts (`PlatformEntriesContract`/`ExtensionEntriesContract`) + shell gates. Naming is **review** |
| **Events arrive through `listen`.** An event port extends `Listenable<H>`; its `*Handlers` bundle is built in `compose/` only and registered by the host zone, once per adapter, as the graph is composed — so a background wake's delivery finds it. `listen` only registers: it runs no handler and builds no feature. Every handler is a flow command, a service call or a presentation intent (the table below). A `*Handlers`-typed `var` exists only behind an `override fun listen` | `ListenDoorTest` (both halves, non-vacuous) + `LambdaSeamShapeTest` + `CompositionOpensNoDatabaseTest` |
| The partial grant's selection observer opens only from host assembly (`Gallery.observeChanges`), so a wake that never builds the screen reads nothing; the latest snapshot is kept for a consumer that attaches late | `SelectionObserverTimingTest` (`:test:world`) + `SelectionSnapshotLaneTest` |
| A read that can be unknown returns a sealed result with an explicit *unreadable*, never folded into a known answer. Predicates are named for the states they accept | **review** |
| Absence is never silent: a seam that can answer "nothing" separates *nothing* from *could not tell* wherever the consequences differ, or states why the collapse is safe for every cause | **review** (design discipline) |

**The handler table** — every handler an event port is registered with, and what it is:

| port | handler | is | runs on |
|---|---|---|---|
| `Gallery` | `onChanged(SelectionSnapshot)` | hands the snapshot to the core's conflated channel; host assembly's collector recounts `N` and runs the selection's tail (a flow command) | the selection lane |
| `Gallery` | `onImportPlaceholder(ref, id)` | the download store's guarded marker write (a service call), synchronous — the lane exception: it must land inside the change block, before the asset is observable | the platform's change block |
| `Gallery` | `onImportSettled(ref, outcome)` | the download store's confirm or clear (a service call), persisted inline — the platform reports it once, even when the requester is gone | the platform's completion |

### State and authority

| law | enforced by |
|---|---|
| Authority lives behind ports. After process death every fact is recoverable from a durable store or the external system. This binds adapters too | **review** |
| A delivery the platform makes **once** is persisted before the entry point returns, citing proof (API contract, vendor doc, or measurement) that it is once-only | **review** |
| Each kind of write to a durable port has exactly one owner (a feature use case or a port's guarded write), written as one transaction with the guard in the statement. Two processes running that owner is fine | **review** |
| No transport adapter holds `LedgerStore` (transports get the narrow `TransferRecord`) | `TransportLedgerGateTest` |
| Holding an OS completion handler is confined to `ports/OsCompletions` (Kotlin and Swift). Releasing before the wake's own work is not expressible on it | `OsHandlerContainmentTest` (storing) + the type (releasing early) |
| The walk memo is built in one place (`appUploadDiscovery`) and bound only by the app's uploader, never by the extension or the shared `uploadCore` | `WalkMemoContainmentTest` |
| Mutable state touched from an OS callback thread is confined to a named lane (`@ConfinedTo`) or is a thread-safe primitive | `ConfinementGateTest` (heuristic) |

### Flows, commands and queries

| law | enforced by |
|---|---|
| Rules live in features. Flows order work and never decide | the `flow` complexity tier (section 5) + `architecture/flows/` transcription (an untranscribable flow fails generation) |
| A flow declares no `CoroutineScope`, and every `Unit`-returning lambda it takes is `suspend` | `ZoneFlowLifetimeTest` |
| A flow never hands work to the process tail: an OS wake's tail (and whether it has one) is requested by the inbound port's implementation after the flow returns. The one request on a flow's call path is a membership transition's arm, which requests it detached through the uploader seam | **review** |
| A flow fans out only through the isolating `model/` helper (a failed child is logged, siblings finish, the entry awaits all) | `ZoneFlowLifetimeTest` "flows fan out only through the isolating helper" |
| Every command crosses `flow/` (user taps, OS callbacks, port-state transitions). Presentation gets the `UserCommands` bundle and never calls a feature command or flow directly | `ReadModelImportsTest` (a feature command lies outside every `readmodel` package) + the compiler (presentation has no `flow/` edge) |
| Presentation's only function-typed port-reaching reads are the `UserQueries` bundle | **review** |
| Every `UserCommands`/`UserQueries` field is built through a lane-declaring decorator, with no default lane | `CommandLaneTest` (and the compiler: decorators take no default) |
| `:ui:screens` takes no `suspend` function parameter or field | `ScreensTakeNoSuspendSeamTest` |
| What a screen shows is `UiState`. Compose-remembered state in `:ui:screens` is allowlisted per file with its reason | `ScreenStateContainmentTest` |

### Composition and shells

| law | enforced by |
|---|---|
| One shared composition: every binary that assembles the live core calls `snapSyncHost` (app) or `uploadCore` (extension). A root supplies ports only and never builds the status host or installs subscriptions | structural (one function) + **review**. The wiring graph is not unit-tested. It is smoke-tested by the world and the integration surface |
| A platform-mechanism decision (today: may the upload extension be registered, `extensionRegistrable`) is a pure, total, unit-tested function of runtime state, re-evaluated when an input changes. Target-fixed facts are not inputs | the compiler (exhaustive `when`) + `ProducerExclusivityTest` (no cell true below iOS 26.1) |
| Upload transitions stop in-flight work only at a leave (no deregister or cancel on revoke/reconfigure/launch; no registration write under a partial grant; enable always goes disable→enable) | `ProducerExclusivityTest` |
| Shells (`:app:ios`, `:app:ios:extension`, `:app:ios:forge`, the host `:domain:host` — a core zone, but wiring every root calls — rig-contributed shell source) hold zero decisions | `detektAppShell` (cyclomatic threshold 2, gating) + `KotlinShellGuardTest` (roots exist, `@Suppress` inventory exact both ways) |
| Source a build script contributes into a shell's source set is shell source for the gates | the root `build.gradle.kts` `appShellSources` list, mirrored in `KotlinShellGuardTest` |
| Swift is a transcriber: decision keywords only at pinned occurrences, and every Swift shell function forwards to Kotlin | `SwiftShellGuardTest` |
| A shell passes the status screen's shared tap factory (`statusActions`) and never binds taps itself | **review** |
| The scene-mode resolver has one caller and the scene generation one writer | `SceneRecordCompletenessTest` |
| The credential-recovery loop is composed, never wired by a shell: `snapSyncApp` builds the authenticated backend over the `Backend` port a root supplies, with the attestation service as its credential. (It used to need a text pin on the iOS root, which handed the core's rejection hook to the HTTP client — a cycle `compose/` could not close) | `CredentialRecoveryWorldTest` (the world composes the phone's loop) |

### Concurrency and failure

| law | enforced by |
|---|---|
| Three lanes: **main** (platform UI only), **CPU** (presentation reduction), **composition** (a dedicated serial dispatcher for blocking calls, network, stores; its thread pinned to `QOS_CLASS_USER_INITIATED` by its first task, logged once). The live scope is never UI-bound, in any binary | **review** for the scope. The main lane is gated below |
| One adapter-owned hop lane beside them: the **PhotoKit read lane** (`photoKitReadLane`, `:adapter:ios:ext-safe`), a single dedicated thread pinned to `USER_INITIATED`, used by the discovery walk and the candidate source. A dedicated thread, because a pooled worker left at `USER_INITIATED` would carry the class into unrelated work | **review** |
| The main thread is named only by allowlisted platform-UI adapters (Kotlin `Dispatchers.Main`, `MainScope()`, `dispatch_get_main_queue`, `NSOperationQueue.mainQueue`; Swift `DispatchQueue.main`). `runBlocking` appears only in the extension root | `MainLaneContainmentTest` |
| An adapter's dispatcher hop means throughput, never safety (the composition already keeps work off main) | **review** |
| No `runCatching` / `catch (Throwable\|Exception)` outside the cancellation-keeping and ObjC-boundary helpers. A cancellation is never reported as a failure | `CatchGateTest` |
| No Kotlin throw escapes into ObjC (delegate overrides and blocks run through the boundary helper), and no ObjC `Boolean`/`NSError**` result is dropped (checked-call helper) | `ObjCBoundaryGateTest` (heuristic) |
| A multi-step use case declares each step required (stops and returns failure) or best-effort (logs and continues) | **review** |

### Platform, runtime identity and containment

| law | enforced by |
|---|---|
| All Keychain API use lives in `:adapter:ios:ext-safe` (import or fully-qualified) | `KeychainContainmentTest`. That module's own tests pin the accessibility class on every query |
| Neither entitlements file raises `default-data-protection` to `NSFileProtectionComplete` (it would make every App-Group file unreadable while locked, which kills background sync) | `DataProtectionEntitlementTest` |
| Extension-linked Kotlin references only allowlisted `platform.*` frameworks (scope derived from the extension's dependency closure) | `ExtensionSafetyTest` |
| Runtime-identity literals (strings the OS or installed base holds) appear exactly once, with pinned non-Kotlin copies agreeing | `RuntimeIdentityTest` (inventory in the test; see section 9) |
| Apple enums decoded with a fallback arm are pinned to the platform klib's declared set | `PlatformVocabularyPinTest` |
| The device (`iosArm64`) actual of the transfer session names the background configuration, and the simulator actual does not | `TransferSessionBindingTest` + an executable simulator test in `:adapter:ios:app-only` |
| The device actuals of the upload-job subsystem name the PhotoKit APIs, and the simulator actuals name neither (PhotoKit job creation traps on a simulator) | `UploadJobSubsystemBindingTest` |
| The event-link domain is generated from one resolved deployment everywhere (entitlement, `LINK_ORIGIN`, AASA, upload host, site URLs). No hand-written host literal. The extension claims no associated domain | `EventLinkDomainTest` |
| The Swift shell keeps both event-link delivery paths (`.onOpenURL` and the scene delegate's cold and warm halves) | `EventLinkDeliveryTest` (its failure message carries the device evidence) |
| A build-time-only module is contained **by compilation**: without its property, a build contains none of its source. There are no runtime flags or inert stubs. A needed shell call site is contributed by the module under the same property | the build scripts + CI's gated compile (`docs/testing.md` section 1). Shape is **review** |
| Production Kotlin declares no `"SNAPSYNC_*"` literal (no launch triggers) | `RunbookSkillsTest` |
| Every runbook skill `CLAUDE.md` points at exists, with a matching frontmatter name | `RunbookSkillsTest` |
| The control channel binds the loopback address only | `ControlChannelLoopbackTest` |
| Client and backend event-name caps agree, and no screen states the cap as a literal | `EventNameLimitTest` |
| The client's gated-route predicate matches the backend's closed list in `api/src/app.ts` | `GatedPathPinTest` |
| Retired dead weight stays dead (zxing/kotlincrypto, the `capability/` tree, the manifest accumulator, a second `*Enrollment` uploader, ...) | `DeletionLedgerTest`. Bringing one back means deleting its row in the same commit |

### Feature invariants held by guards

These pin one feature's structural rule that no type can state:

| guard | holds |
|---|---|
| `SelectionPolicyContainmentTest` | the capture-date bounds are compared in exactly one place |
| `SelectionPolicyConstructionTest` | a `SelectionPolicy` is built only by its one derivation (which always emits the capture floor) |
| `EventPhotoSetSourceTest` | production candidates come from the policy-taking read seam |
| `BackgroundTeardownTest` | no background trigger flow can tear a membership down (only the foreground, on confirmed absence) |
| `DumpScrubExemptionTest` | only the operator-confirmed diagnostic dump bypasses the UUID scrub |
| `WorldBootsColdTest` | constructing the world forces no `AppCore` member |

### Disciplines with no gate

- **Necessity claims carry forcing proofs.** "The platform forces X" cites an API contract, a vendor
  doc, or a device measurement, never the current code, and names the trigger that would dissolve it.
  A gate that pins an exception carries the proof in its failure message.
- **A capability claim is settled by a compile, not a symbol table.** A klib records what ships, not
  what is callable (`Dispatchers.IO` is in the Native klib and is `internal`). To learn what an Apple API
  *declares* (enum sets, nullability), read the klib (`CLAUDE.md`, "Reading the Apple SDK from Linux").
  What the *device* does needs a measurement.
- **Expiry of the lane reasoning.** The three-lane argument is iOS-shaped (two processes). A platform
  whose background work runs in-process (Android) re-derives it rather than inheriting it.

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
the honest fake and each real adapter. A double therefore cannot quietly answer differently from the
system it stands in for. The contract code **is** the specification of a port's clauses, and no
`openspec/` spec restates them.

- **Contract** = a hand-written list of clause values in `:test:contracts` `commonMain` (id, required
  state, body with `kotlin.test` assertions). Nothing records or generates an expectation.
- **Binding** = one implementation × one `Host` (`JVM`, `IOS_SIM_KEXE`, `IOS_SIM_APP`,
  `IOS_DEVICE_APP`, `IOS_DEVICE_PHOTOKIT_EXT`) × one kind (`Fake`, `Live`, `Replay`), declaring **as a
  literal** the states it reaches. It enters a state at construction or answers `Unreachable`. Bindings
  live beside their implementations.
- **Outcomes** are `Passed`, `Failed`, `NotRunHere`, `Diverged` (replay met an unrecorded OS call) and
  `NotWithin` (a real-clock bounded wait expired). `Failed`, `Diverged` and `NotWithin` fail the run on
  every binding kind.
- **Every clause runs against something real** on some host: a `Live` binding CI runs, or a committed
  device recording. `ContractCoverageTest` enforces this.
- Hosts CI cannot reach are **recorded at the OS boundary** on a device (through the rig, under
  `-Psnapsync.rig=true`) into `test/contracts/recordings/<Contract>@<HOST>[.<GRANT>].rec`, and
  **replayed** against the current adapter on every build. An adapter recorded this way routes its OS
  calls through an `internal` seam (`KeychainApi`, `AppAttestApi`, `BackgroundTaskApi`, ...).

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

### Complexity ceilings (fall only)

- Eight detekt tasks gate `build`, one per scope: `detektShellTier`, `detektFlowTier`,
  `detektComposeTier`, `detektCoreTier`, `detektUiTier`, `detektHarnessTier`, `detektTestsTier`,
  `detektBuildScriptsTier`. There is one task per scope because a detekt 1.x rule carries one threshold
  per config.
- Every tier layers `config/detekt/_base.yml` (readings of a rule, uniform everywhere, **never** a
  number) under its own `config/detekt/<tier>.yml` (ceilings). A tier's own file is **optional**, and
  its absence means the scope sits at the baseline. The files present are the scopes still carrying
  debt. Each file opens with the contract: lowering is ordinary work, and raising needs a stated forcing
  proof in the PR. Per-site rules ratchet by a scope exclusion list that may only shrink.
- Tier membership is derived from the live Gradle model (`detektTierOf` in the root build).
  `DetektTierCoverageTest` fails if a subproject is in zero or two tiers, or if a config file belongs to
  no tier. Each task fails if it scanned zero files.
- `flow/` and `compose/` have their own tiers. `flow/`'s target is the shell's decision-free ceiling,
  and on reaching it the tier merges into `shell`.
- **`detektAppShell` is not a tier** and must never be folded into one. It is the shells' decision-free
  proof, and its value comes from the number being 2.
- Compose trades `LongMethod` against `LongParameterList`. Extracting composables can raise tier totals
  (`CLAUDE.md` explains why `ui.yml` still holds three).
- `api/`: cyclomatic complexity only, via the project-local `deno lint` plugin
  `api/src/lint/complexity.ts` (tested by `lint-complexity.test.ts`). Length, parameter and nesting
  rules are deliberately not mirrored. Swift has no ceiling (it is pinned by `SwiftShellGuardTest`), and
  `site/` is out of scope.
- Constraint: detekt's embedded compiler lags the repo's Kotlin. Newer syntax can fail to parse
  everywhere at once. This expires with detekt's next stable major.

Decision record: `changes/archive/2026-08-27-add-repo-wide-complexity-gates`.

### Coverage bounds (rise only, toward 100%)

- Kover `verify` rules with `onCheck = true`, in each bounded module's `build.gradle.kts`. Each bounded
  module carries an `INSTRUCTION` aggregate, a `BRANCH` aggregate, and an `INSTRUCTION` **package
  floor** (the worst package). `LINE` is not bounded, and `BRANCH` has no package floor (too noisy at
  package size).
- **Unit tests only.** Instrumented: `:domain:*`, `:adapter:generic:app`, `:adapter:generic:fake`,
  `:domain:presentation`, `:ui:screens`, `:ui:components`. Bounded: all of those except
  `:adapter:generic:fake` (its `commonTest` hosts `:domain`'s fake-driven feature tests, and the fakes
  themselves are test equipment). `:test:world`, `:test:integration`, `:test:contracts` and the other
  test modules contribute nothing, so a thick harness cannot stand in for a thin unit suite.
- **Crediting edges** (root `build.gradle.kts`) let tests that a placement rule forced elsewhere credit
  the module they test: the `:domain:*` zones from `:adapter:generic:fake`, and `:ui:components` and
  `:domain:presentation` from `:ui:screens`. Always name leaf modules. `:domain` is an empty container, and
  a filter on it measures nothing. Incidental coverage is never credited.
- `compose/` is **permanently unbounded**. The wiring graph is not unit-tested by law, so the gap
  cannot be paid.
- What a green gate does **not** cover: Kover is JVM-only, so the iOS adapter modules (PhotoKit,
  Keychain, `URLSession`) are invisible. The Compose compiler depresses `BRANCH`. Bounds are integer
  percentages. Bounds are engine-specific, so changing the engine means re-seeding.

Decision record: `changes/archive/2026-08-28-add-coverage-bounds`.

---

## 6. Diagrams

`architecture/` is **generated, never drawn**: `./gradlew architectureDiagrams`, Mermaid-in-Markdown,
byte-deterministic (code-point sort, `\n`, UTF-8, no timestamps or absolute paths).

| file | what |
|---|---|
| `modules.md` (+ `.modules-inputs.txt` sidecar) | module graph from the Gradle model's **build** configurations. `kover*` is excluded, and the file header says so |
| `zones.md` | zone/feature graph (derived from `build.gradle.kts` text, so never declare a report-aggregation edge in a scanned module's build script; the root build is the place) |
| `flows/<Flow>.md` | one sequence diagram per trigger flow |
| `ports.md` | port × adapter matrix, including a fake-and-contract column |
| `features.md` | feature cards, including the forge name→sources map |
| `di.md` | DI wiring graphs and the binary × port matrix |

- The flow transcriber accepts a **closed grammar**: straight-line calls; the awaited `fanOut { child
  { } }`; a `when` over a feature's sealed result with single-call branches; one leading guard; a
  best-effort wrap; a loop over an injected list; and `log.*`. A flow it cannot transcribe **fails
  generation**, which makes the transcriber the flow law's instrument. An escaping `scope.launch` is not
  in the grammar.
- Freshness: `:tools:diagrams:test` regenerates and diffs against the committed files (inputs include
  `settings.gradle.kts` and every `build.gradle.kts`). In CI, the required `diagrams` check regenerates
  on a clean checkout and fails on any difference, including untracked files. Stale diagrams block the
  PR. Regenerate and commit.
- The module-graph renderer has a byte-identical twin in `:tools:diagrams`. Keep them in step.

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
  `./gradlew :app:desktop:runForge` shows every state.

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
and `/api/v1` is frozen only while builds that speak it are served. Change v2 freely within that.

### Where state lives

**The database holds the facts. Storage holds the bytes.** Every row is **rebuildable** by a device
round-trip (a manifest republish or a re-attestation), and no user-visible fact exists only in the
database. That is what makes the platform's public-preview limits acceptable (1 GB per database, a
10 s data-loss window on failover, 32,766 bound parameters per statement). One gap is stated: under v2,
a lost `resources` row is repaired only by a re-upload, which happens only if the device does not
believe the upload landed.

```
events        id, name, created_at, starts_at, ends_at, capacity, lifetime_seconds
memberships   (event_id -> events CASCADE, device_id), state in {active, departed}, joined_at, manifest_version?
event_assets  (event_id, device_id -> memberships CASCADE), asset_id, creation_date, roles (JSON array)
              + index (device_id, asset_id)
resources     (device_id, asset_id, role) PK, key UNIQUE per device, content_type, filename
devices       device_id, created_at, attest_* (NOT NULL), push_* (nullable together)
```

The generated snapshot is `api/schema.sql` (section "Database" below).

- **Existence is a row.** An event exists iff its `events` row does. No route deletes on touch, and
  only the nightly sweep deletes. So a `404` is a real deletion, and a client may use it as a witness
  for self-leave.
- **Membership is a column** (`active`/`departed`), never inferred from objects. A departed member's
  assets stay in the union.
- **`resources` sits outside the event cascade** and is device-scoped. This is forced: the byte
  route's path carries no event. It also lets one byte serve two events during a switch.
- **Row existence is the upload record.** There is no upload-state column, and the backend records only
  bytes it watched arrive. "Pending" = declared by a manifest with no `resources` row.
- **A `devices` row exists iff the device attested.** Push registration is an `UPDATE` and never
  creates a row.
- **Bounds** (policy in `deployments/components/policy.json`): capture window `[startsAt, endsAt]` of
  at most 30 days, which bounds uploads only and closes nothing. Lifetime `lifetime_seconds` (30 days)
  is stamped as a duration. The delete-by is derived per read as `max(createdAt, startsAt) + lifetime`
  (`src/lifecycle.ts`, shared with the sweep). `capacity = 10` ever-enrolled devices (active ∪
  departed; leaving frees nothing, rejoin reuses the slot) is the only refusal, `409`.
- The **nightly sweep** (`src/scripts/sweep.ts`, a GitHub Actions workflow, since Edge caps requests at
  50 subrequests / 30 s CPU) deletes events past their delete-by or empty (joined, no active member),
  then unreferenced bytes, and collects a `devices` row only once no token minted for it can still
  verify. Its delete decision runs in an interactive transaction (primary), not an ordinary read.
  Details are in `docs/deployment.md`.
- **Legacy storage objects** (`events/<id>/metadata.json`, `events/<id>/devices/<id>.json[.left]`,
  `devices/<id>.json`, `devices/<id>.attest.json`) may still exist. Nothing reads or writes them, and
  the sweep ignores them.

### The request pipeline

Top-level Hono middleware in `src/app.ts`, in this order:

1. **Maintenance gate.** While the bundle carries the maintenance flag, everything under the `/api/`
   **prefix** answers `503` with `Retry-After` and no-cache, touching no store. It runs before
   authentication. Root routes keep serving.
2. **Version gate** (v2 only). The `x-snapsync-app-version` header must be at least `minAppVersion`
   (in source, `src/config.ts`, pinned by `min-app-version-floor.test.ts`, deliberately not config).
   Absent, unparseable and too old all get `426 {error, minAppVersion}`. v1 is exempt.
3. **Token gate.** Every route requires a device token: a backend-minted, HMAC-signed bearer that can
   only be obtained through App Attest. **Verifying touches nothing** (one HMAC compare, no store read,
   no Apple call), because it sits on the streaming upload hot path. A route that needs the device record
   reads it itself afterwards. The **closed list** of exceptions: the three `/api/vN/attest/*` issuers;
   `OPTIONS` anywhere; `GET`/`HEAD` on `/`, `/join`, `/.well-known/apple-app-site-association`,
   `/health`; and `GET`/`HEAD` on `/api/vN/events/<id>` and `/api/vN/events/<id>/files` (the no-app
   download page, where possession of the event id is the read capability). The gate normalizes the
   `/api/vN` prefix before matching. `GatedPathPinTest` keeps the client's copy of this list in step.

4. **Device binding** (per route, `actsFor` in `src/app.ts`). The token names the device it was minted for,
   and every route that names a device in its path (join, leave, manifest, byte upload, device listing, push
   registration, under v1 and v2 alike) refuses any other id with `403 not this device`, before reading or
   writing anything. It is `403`, not `401`: the credential is valid, and a `401` from a gated route makes
   the client drop its token and re-attest, which would loop. Device ids are not secret (the ungated union
   lists every member's), so without this any genuine install could act as any member. The binding judges
   the router's DECODED parameter, so a percent-encoded id cannot slip past it.

What the binding does not close: `/attest/token` mints for whatever `deviceId` its body names, so a genuine
install can still attest AS a known id (overwriting that device's key) and then hold a token bound to it.
Refusing a re-attestation for a known id would also refuse the legitimate one after a reinstall, which
keeps the Keychain device id but loses the App Attest key.

### HTTP API (v2, current)

All device routes are under `/api/v2`. `<eventId>`/`<deviceId>` are UUIDs (`400` otherwise), and a
`<deviceId>` other than the one the token was minted for is `403` on every route that names one. Anything
not listed is `404` (no `405`) and makes no upstream request.

| method | path | does | answers |
|---|---|---|---|
| `GET` | `/attest/challenge` | stateless HMAC-signed, time-bounded nonce, writes nothing | `200 {challenge}` |
| `POST` | `/attest/token` `{deviceId, keyId, attestation, challenge}` | verifies chain to Apple root, nonce, app-id hash, counter, aaguid; **persists the device row, then mints** | `201 {token}` · `401` failed check · `409` stale challenge · `502` write failed |
| `POST` | `/attest/renew` `{deviceId, assertion, challenge}` | verifies a Secure Enclave assertion against the stored key (no Apple call); advances expiry, then mints | `201 {token}` · `401` no attestation / refused · `409` stale challenge · `502` read/write failure (never `401`: that would force a throttled re-attestation) |
| `POST` | `/events` `{name, startsAt, endsAt?}` | name trimmed, non-empty, ≤100 chars; window rules; backend mints the id | `201 {eventId, name, createdAt, startsAt, endsAt, capacity, deletesAt}` · `400` · `502` |
| `GET`/`HEAD` | `/events/<eventId>` (ungated) | metadata; `deletesAt` derived per response | `200` · `404` sealed absence · `502` read failure |
| `PATCH` | `/events/<eventId>` `{name}` | the only write to an existing event row; last-write-wins; no ownership check | `200` (metadata shape) · `400` · `404` · `502` |
| `PUT` | `/events/<eventId>/devices/<deviceId>` | **join**: the only route that creates or reactivates a membership; one conditional capacity insert; clears `manifest_version`; idempotent | `200` · `404` · `409` at capacity · `502` |
| `DELETE` | `/events/<eventId>/devices/<deviceId>` | **leave**: `state = departed`; idempotent; assets retained; frees no slot | `200` · `404` · `502` |
| `PUT` | `/events/<eventId>/devices/<deviceId>/manifest` | **contribution**: full-state replace of the membership's asset set in one transaction, ordered by `version`; writes no resource rows; enrols nobody | `200` (applied, or refused as older with nothing written) · `400` · `404` · `409` not a member · `502` |
| `PUT` | `/files/devices/<deviceId>/<assetId>/<role>?filename=<name>` | streams bytes to storage (never buffered), then records the `resources` row. **A failed record fails the request.** If this completed an asset, wakes the declaring events' other members | `201` · `400` bad role / missing filename · `502` (`OPTIONS` → `204`) |
| `GET` | `/files/devices/<deviceId>` | what the backend holds for me, from the DB | `200 [{assetId, role, filename}]` · `502` |
| `GET`/`HEAD` | `/events/<eventId>/files` (ungated) | the event union: one query over active and departed members; an asset is included only when **every declared role** has a resource (a set comparison, not a count) | `200 [{deviceId, assetId, creationDate, resources:[{role, contentType, key, filename, url}]}]` · `404` · `502` |
| `PUT` | `/devices/<deviceId>` `{pushToken: {kind:"apns", token, env}}` or explicit absence | updates the push columns | `201` · `400` · **`401` when no row was affected** (device never attested; the client re-attests and re-sends) · `502` |

Served at the root under no version: `OPTIONS` on any path (`204`, no resumable upload advertised, so
the iOS uploader uses a plain `PUT`); `GET`/`HEAD` `/`, `/join`, `/_astro/*` (the Astro build proxied
from the storage `site/` prefix) and the AASA; `GET /health` (`200 {sha, maintenance?}` after
`SELECT 1` and a storage listing succeed, `503` otherwise; `maintenance` absent means closed).

**Conventions that hold on every route:**

- **Faithful outcome.** A write returns `2xx` only when the store confirmed it. A read returns an array
  only when every query succeeded, otherwise `502`, never a partial list (a short list looks like a
  complete one, and a photo vanishes silently). A zero-row conditional write with two causes (capacity
  or absent) is disambiguated by a follow-up read (`409` vs `404`).
- **Presigned URLs.** SigV4 query-signed S3 `GET`, path-style
  `https://<s3-host>/<zone>/files/devices/<deviceId>/<key>`, 7-day expiry, signed with the zone name as
  access key id and the storage password as secret. Minted fresh on every response by one builder, so
  listings agree by construction.
- **Wakes are best-effort and bounded.** The recipient set (active members with a push token) is one
  query, and the push happens **after** the transaction commits. A failed, skipped or timed-out push
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

### v1 (frozen) differences

`/api/v1` serves builds that cannot be updated. Its behaviour must not change while it is served, and
its wire tests (`v1.test.ts`) must pass **unmodified** across any schema migration. It differs from v2:

- `PUT /files/devices/<deviceId>/<filename>`: the object name is in the path (single segment, no `/`,
  `%2F` or `..`), identity is recovered by `src/legacy-v1.ts`, and recording the row is
  **best-effort** (the response is the storage outcome). This is safe only because v1's manifest publish
  re-creates missing rows. Do not change one without the other.
- `PUT /events/<eventId>/devices/<deviceId>` **is** the manifest publish **and** the enrollment
  (capacity `409`). It also upserts resource rows monotonically (a later publish cannot un-say an
  upload). There is no `version`.
- `POST /events/<eventId>/notify` exists (`202`, best-effort fan-out to active members).
- `GET /files/devices/<deviceId>` returns `[{filename, url}]`.
- A stale attest challenge is `401` (v2: `409`). There is no version gate.

### Database

- **Schema = ordered migrations** (`api/migrations/NNNN_*.sql`, checksummed, applied once, recorded in
  the store). **`api/schema.sql` is generated** (`deno task schema`) by replaying them, and CI fails if
  it is stale. Read a migration's effect in that file's diff, especially its deletions (a rebuild that
  forgets an index shows up only there).
- **Every `TEXT` primary key is explicitly `NOT NULL`** (SQLite only implies it for `INTEGER`). Tables
  are `STRICT`.
- **Foreign keys are trusted**: the platform defaults `PRAGMA foreign_keys = 1`, as measured. If the
  store is re-provisioned or its engine changes, re-measure.
- **One writer per table** on the current version: `events` by create (and rename, name only),
  `memberships` by join/leave (the publish writes only `manifest_version`, and join only clears it),
  `event_assets` by the manifest publish, `resources` by the byte upload, and `devices` by attestation
  and the config write, each naming only its own column group. The sweep only deletes. v1's extra writes
  are a bounded, named exemption, and no new version gets one. **review** (plus route tests).
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
src/app.ts         createApp({config, db, fetch}): the three gates, v1 + v2 routers, site proxy + AASA,
                   presignDownloadUrl()
src/db.ts          the one narrow `Db` port and every statement (capacity insert, atomic publish, union,
                   sweep queries). No schema here
src/db-libsql.ts   the deployed `Db` (bunny Database)
src/storage.ts     byte-store and site-prefix key builders + LIST/GET/PUT/DELETE, shared with the sweep
src/attest.ts      App Attest verification, the stateless challenge, the device token (mint, verify, the
                   one expiry derivation)
src/lifecycle.ts   deleteByMs / eventIsStale, shared with the sweep
src/apns.ts        ES256 provider JWT + silent push per token, per-token best-effort
src/validators.ts  UUID / filename / event name / instants (MAX_EVENT_NAME_LENGTH)
src/legacy-v1.ts   v1-only identity parse + the object-name composer v2 also uses; deleted with v1
src/config.ts      readConfig over the resolved deployment (non-secrets) + env secrets; throws on a
                   missing secret; minAppVersion
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

None of these is a user contract. The manifest is re-derived from the ledger every cycle, and a
re-upload after an app update is acceptable. They are still the data real devices and the backend hold
today. Change them knowingly: a renamed on-device identifier strands state in the field
(`RuntimeIdentityTest` is there to make that loud).

### The device manifest (wire format)

The body of `PUT /api/v2/events/<eventId>/devices/<deviceId>/manifest`, one full-state document per
(event, device), recorded relationally (no manifest object in storage):

```json
{ "deviceId": "<uuid>", "version": 42,
  "assets": [ { "assetId": "<device-local id>", "creationDate": "<ISO-8601>",
                "resources": [ { "role": "primary", "contentType": "image/heic",
                                 "key": "<assetId>-primary.heic", "filename": "IMG_0001.HEIC" } ] } ] }
```

- **Roles** are platform-neutral: `primary` (exactly one: the original still or video) and `live`
  (at most one: a Live Photo's paired video). Originals only, never edits. Image vs video is carried by
  `contentType`.
- **It declares intent, not completion.** It projects the upload ledger's rows **whatever their
  upload state** (a failed upload is still owed), filtered only by the membership's one selection
  policy. The union's role-set comparison is what hides an incomplete asset.
- The projection applies no filter of its own. Date bounds apply at projection, so a narrow or widen
  changes the manifest without re-uploading. Origin exclusions (screenshots, and so on) apply before a
  ledger row exists, so an excluded photo never reaches any manifest. A bare row (loaded at join from the
  file listing, no capture date yet) is excluded fail-closed by the policy until a walk backfills it.
  Every walk backfills every bare row it covers.
- **Deletion and de-selection** remove ledger rows on the next authoritative walk, so that cycle's
  manifest retracts the asset, even if its upload is in flight. Under a partial grant, a read selection
  snapshot counts as an authoritative walk.
- **An empty manifest is valid and published.** A manifest is **suppressed** (the previous one stays)
  whenever the cycle cannot trust the ledger. Suppression and "empty" are logged differently.
- **Writer**: the upload cycle, synchronously in-cycle, in whichever process runs it (app on every iOS
  version, the extension on iOS 26.1+). Two concurrent publishes are ordered by `version` (the ledger's
  manifest counter, which advances on any change that could alter the projection, including a
  reconfigure save and the join-time load). A cycle skips the publish only when event id, version and
  snapshot all match the last **successful** write (`device-manifest/last-uploaded.json`). A refused
  (older) publish counts as published.
- The job-creation limit never withholds the manifest.

### Byte store (bunny Storage zone)

```
files/devices/<deviceId>/<assetId>-<role>.<ext>   one object per resource (ext from the capture filename,
                                                  lowercased, else "bin"); composed by the backend,
                                                  byte-identical under v1 and v2 and to the client's
                                                  model/UploadKeys.kt
site/index.html · site/join/index.html · site/_astro/*   the Astro build (mirror-deployed, public)
```

Keys are percent-encoded per segment, so they stay flat. The storage `host` must be the zone's **main**
region (read-after-write). Replicas are asynchronous. There is exactly one zone (`snap-sync-dev`),
shared with real users. **Never add a whole-zone reset.** Clean up targeted only.

### On-device layout (iOS)

App Group `group.app.snapsync`, shared by the app and the extension, at the default data-protection
class (readable after first unlock; see `DataProtectionEntitlementTest`):

| path / key | what | owner |
|---|---|---|
| `eventconfig.json` | the membership (config file of record). A missing file **is** "left the event", so renaming it is a false leave on every device | `ConfigService` over `IosFiles` |
| `ledger.db` | the upload ledger (SQLDelight): the membership's share set + manifest version. Either process opens it read-write and migrates it | `LedgerService` over `IosDatabases` |
| `downloads.db` | the download store. The app writes and migrates it; the extension opens it read-only | `DownloadService` / `SuppressionService` over `IosDatabases` |
| `device-manifest/last-uploaded.json` | the manifest skip record (event id, version, snapshot) | `DeviceManifestService` |
| `upload-staging/` | bytes staged for the app's background `URLSession` uploads | `IosUrlSessionUploadPlatform` |
| `download-staging/` | downloaded bytes awaiting import. The download store keeps each path **relative** to the container (schema 4 rewrote the absolute ones) | `StagingService` over `IosFiles` |
| `push-registration/last-registered.txt` | the last push registration the backend accepted (token, env, device id); dies with the install, so a reinstall publishes | `IosPushRegistrationRecord` |
| `ext-debug.log` (+ `.1`) | the extension's verbatim log (the app's is `Documents/debug.log` in its own container) | log writers; read by `LogTailService` |
| defaults `app.snapsync.album.map` | event album map | `AlbumMapService` over `IosPreferences` |
| defaults `rejoin.joinedEventId` | **retired**: pinned only at its start-up removal site | `removeOrphanedJoinMarker`, called by the app shell |

Keychain (only in `:adapter:ios:ext-safe`, as `IosSecureStore`; every item is readable after first unlock). Every item is a `SecureSlot` in `model/SecureSlots`; `shared = true` names the access group, `false` searches unscoped:

| (service, account) | what | access group |
|---|---|---|
| (`app.snapsync.deviceid`, `deviceid`) | the per-install device id | shared group `<TEAM_ID>.<group>`, cross-checked against both signing entitlements |
| (`app.snapsync.attest`, `token`), (`app.snapsync.attest`, `keyid`) | device token, App Attest key id | unscoped (pinned set) |
| (`app.snapsync.album`, `albummap`) | album map cache | unscoped (pinned set) |

`simulator.entitlements` carries the App Group only and **must not** declare `keychain-access-groups`,
because that makes an ad-hoc simulator build unlaunchable.

OS-registered identifiers: the one BGTask `app.snapsync.upload.heartbeat` (Kotlin, `Info.plist` and the Swift
registrations must agree: `RuntimeIdentityTest` asserts `BGTaskSchedulerPermittedIdentifiers` lists **exactly**
the pinned set, and the Swift shell registers exactly that set, so a retired id such as
`app.snapsync.download.backstop` left in the plist fails the build); background `URLSession`s `app.snapsync.upload.session` and
`app.snapsync.download.bg` (the OS reattaches transfers by these); framework base names `SnapSyncKit`
and `SnapSyncUploadKit`. The pinned inventory, including retired names kept for recognition, is
`DOCUMENTED_INVENTORY` in `RuntimeIdentityTest`.

---

## 10. Background execution: each wake's own work, then one tail

How the app process spends an OS wake. The user-visible promises this serves are in
`openspec/specs/background-upload` and `receiving-photos`. The code of record is `compose/AppEntries.kt`,
`compose/Wakes.kt` and `feature/upload/TailRunner.kt`. Decision record:
`changes/archive/2026-09-25-own-work-per-wake`.

**Own work per wake.** Every entry point (the inbound port's implementation in `compose/`) runs a shared
prelude (membership re-read, attestation refresh), then only the work its event is about, and then hands the
rest to the tail:

| wake | own work | tail | heartbeat re-arm |
|---|---|---|---|
| silent push (active event only, `PushTailGuard`) | union read, plan, download enqueue | full | always |
| download-session relaunch | stage the delivered files | full | only if work remains |
| upload-session relaunch (iOS 18–26.0) | the delegate records terminals | full | only if work remains |
| heartbeat `BGTask` `app.snapsync.upload.heartbeat` | none: the task *is* the tail | full | always |
| limited-grant selection change | snapshot-fed discovery → manifest | full | always |
| foreground | download reconcile, stored-upload settle, staged-byte reclaim, status and membership refresh | full | always |
| membership transition's arm | none (requested detached) | full | always |
| upload completion (admission `Admit` only) | none | ② only | never |
| download staged in a running process | record the staging | ① only | never |

**The tail** (`TailRunner`, one per process, single-flight): ① import staged downloads, ② upload top-up
(re-create retry-spent failures, enqueue known `DISCOVERED` rows), ③ discovery walk → manifest publish, full
grant only; when ③ added rows it runs ② once more. Under a partial grant it runs ① and ② only, and ② resolves
from the in-memory selection snapshot (withheld while it is unread). A request while a tail runs **joins** it:
the runner makes exactly one more pass covering the union of the joiners' units, and each joiner awaits it and
applies its own re-arm to the outcome. A throwing unit fails the tail and every waiter. Own work runs
**outside** the runner, so no wake waits behind another wake's walk. The download reconcile never imports; ①
is the only drain, plus the once-per-process interrupted-import sweep at host assembly (same per-asset claims).
There is no download backstop task any more.

**Handlers and time.** OS completion handlers are held only by `ports/OsCompletions`, with two release paths:
after the own work, or at once on the OS's expiry, each handler exactly once. A push or `URLSession` handler is
released right after its own work (the `URLSession` one on the main thread, at `urlSessionDidFinishEvents`, once
the stagings it started are recorded). The tail then runs under the process's background time, the
`BackgroundTime` port (`beginBackgroundTask` in `:adapter:ios:app-only`), begun no later than the handover and
also held by foreground entry. A `BGTask` holds `setTaskCompleted` until its tail ends. **No clock of ours**:
there is no receipt deadline, no manifest timeout and no `backgroundTimeRemaining` read. "Time is up" is only
the `BGTask`'s `expirationHandler` (forwarded by Swift as `onBackgroundTaskTimeUp`, never answered in Swift) or
the background task's expiration handler; a refused hold reports as an immediate expiry. On expiry the core
requests the tail's stop and releases and ends everything **at once**. The unit in flight is not cancelled and
runs on until suspension, nothing new starts, and a stopped tail counts as `PROCESSING` for the re-arm. The
walk is atomic: a stopped walk is abandoned and writes nothing. The only timeout left is the HTTP client's
per-request one, which bounds a request and ends no wake. Each expiry is logged twice (at the stop, and when
the stopped tail ends, with the staged imports left over).

**Cheaper work in `darwinbg`.** Background wakes run CPU-clamped (~9× after the first second, measured on an
SE2), so:
- **Walk memo** (app process only): the full-grant walk is reused while `PHPhotoLibrary.currentChangeToken`,
  the selection policy and the grant are unchanged (~2 ms against a 1.3–2.0 s walk). It reproduces a fresh
  walk exactly, so it stays authoritative for deletion. It lives in memory only, stores only completed
  full-grant walks, and reads the token before the walk. A build constant switches it to shadow (walk every
  time, log a disagreement at `Error`) as the revert.
- A walk's resources are handed to the top-up that follows it, so a row it just read is not resolved again.
  The engine's `isWork` answers "is this work" without minting a request.
- Download planning is batched (`settledAmong`, `planAll`, `markAllEnqueued`).
- Limited-grant selection changes queued behind a running enumeration are folded into one enumeration. Both
  the change and the baseline path drop a snapshot built under a grant that has since become full.
- The denylisted-album lookup is asked only under a full grant (it can only answer the empty set otherwise).
- Ledger counts refresh after tail units only while foregrounded (foreground entry re-reads them anyway).

**Push registration is change-driven.** The Kotlin root asks the OS for the APNs token at every app entry
(`onLaunch`, `didBecomeActive`). `PushRegistration` publishes only when (token, env, deviceId) differs from the
`PushRegistrationRecord`, and unconditionally on join and on every fresh credential (mint, re-attestation,
renewal). The subscription is installed by `snapSyncHost` on every cold start, background included. Host
assembly is foreground-only. The token source carries a `deliveries` flow so an unchanged re-delivery still
re-sends a failed publish.

**Device-token copies.** Each process may serve the device token from memory. Its own writes drop the copy.
The app re-reads the Keychain at every attestation decision (every wake), the extension at every
`process()`, and both after a `401`. A `401` on a metadata call is recovered IN the call: the app's authenticated
backend waits for the re-attestation and retries once, so the call's caller is answered by the retry (this costs
the wake a challenge, an Apple attestation or a local assertion, and a mint — only when the backend has actually
rejected the token). The extension drops the rejected token and defers to the app's next wake. A retry's request is minted through `provideForRetry`, which reads the
store of record.

**The upload extension has no cooperative stop.** Measured (SE2, iOS 26.6): its only end is assetsd's 60.0 s
timer (SIGKILL), `notifyTermination` follows only normal returns, `performExpiringActivity` never fires, and its
memory limit is 32 MB (an overrun is a jetsam kill loop). A `process()` measured 0.6–1.4 s, and every unit is a
safe retry. It walks afresh every call and holds no memo.

---

## 11. Direction: the thin-ports re-cut (in progress)

This section describes where the structure is **heading**, not what the gates enforce today. Each phase that
lands moves its part into the sections above and trims this one.

**Goal.** The app should be honest to test against mocks, and an Android build should need only new adapters.
Both follow from one rule: **a port is what the core uses of ONE external system**, thin and platform-neutral.
Every decision lives in `:domain:services` (shared capabilities) or `:domain:feature` (product behaviour that
cannot see other features). Ports never call ports, and only services compose them — a gated law since 11c
(section 2), with an allowlist the later phases empty.

**Target zones.** Every edge between zones is `implementation()` only, pinned by ModuleSetTest:

```
model ← ports ← services ← feature ← flow ← compose
                              ▲                  ▲
                              └── presentation ──┴── host   (host = compose + presentation + ports; never services)
```

**Ports:**
- Backend: typed, one HTTP client per composition (11c, shipped — section 2).
- Gallery and GalleryReader (shipped in 11d; `export` of a resource to a file waits for the transfer ports in 11f).
- Upload, ExtensionRegistry, Download and Wake.
- Files, Databases, Preferences, SecureStore and PlatformDeviceId.
- DeviceIntegrity (11c, shipped), CrashReporter, ProcessMetrics, SystemUi, Clock, ProcessInfo, LogSink and EntryContext.
- PushNotifications, Links, Lifecycle, ExtensionHost, Ui and DevControls.

Event ports extend `Listenable<H> { fun listen(handlers: H) }` — the `Gallery` since 11d (section 2, "Events arrive
through `listen`"). Each composition calls `listen` once per adapter. It only registers the handlers: the core and the
status host stay lazy. Once-only deliveries are
persisted inline on the delivering thread.

**Rules that land with the phases:**
- Pure port data lives in `model/`. This is true since 11a.
- The inbound `PlatformEntries`/`ExtensionEntries` ports become Listenable ports (11g).
- A root holds no `if`, and the rig becomes an adapter set chosen at build time (`platformAdapters()`, prod or
  rig variant). `rigBoot` goes away (11g).
- An adapter constructor takes no function-typed parameter. The gate lands in 11g.
- `ports/` holds interfaces only (11g).
- `SelectionCalibration` is one product-policy value in `model/` (11d), not supplied per composition: the floors and
  the denylist mean the same on every platform.

**Phases.** 11a structure (shipped) → 11b storage and `:domain:services` (shipped) → 11c backend and integrity
(shipped), 11d gallery (shipped) and 11e process ports, in parallel → 11f transfer → 11g entry surface. 11i (raw asset
ids) follows 11c, 11d and 11f, then comes 11h (the mock mix chosen at launch). Phases 11e–11g are being designed again
against §10's wake model.
