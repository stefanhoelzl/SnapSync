# Testing SnapSync

How the app and the backend are tested: where each kind of test lives, which targets it runs on, what the
test-only modules are for, and which behaviour is measured on hardware instead of asserted. This is an
engineering doc, not a contract. The user-facing contract lives in `openspec/specs/`. The reasons behind
the rules here live in the decision records cited inline (`openspec/changes/archive/<id>`).

Runbooks are skills. Load the skill before doing what it covers:

| task | skill |
|---|---|
| drive the app over the control channel (device, simulator or JVM host) | `.claude/skills/rig-channel` |
| see or click the UI with no device (either desktop harness) | `.claude/skills/ui-harness` |
| run the app on a simulator, two members, seeded library | `.claude/skills/ios-simulator` |
| run the iOS simulator tests or build on a Mac | `.claude/skills/ssh-mac-build` |
| run `api/` locally, point a build at it | `.claude/skills/local-backend` |
| install and read logs on the phone | `.claude/skills/snapsync-device` |

Main decision record: `changes/archive/2026-08-27-establish-testing-architecture`.

---

## 1. The checks

| command | what it is |
|---|---|
| `./gradlew build` | **The canonical check.** Compiles every target, runs every JVM test and every gate. Needs no display. Needs `deno` on `PATH`. |
| `./gradlew iosPlatformTest` | The **platform-bound** iOS tests (every module's `iosTest`) on the simulator. macOS only (CI `macos-26`, or `ssh-mac-build`). |
| `./gradlew androidPlatformTest` | The **platform-bound** Android tests (every module's `androidDeviceTest`) on a Gradle-managed emulator it boots itself. Needs KVM and the SDK. |
| `./gradlew compileIosMainKotlinMetadata` | Linux proxy for the iOS source sets. A **compile, not coverage**. Never describe it as a test. |
| `cd api && deno task test` | The backend suite. Offline by construction. |

### Where each test runs

The shared `commonTest` runs **once, on the JVM**, under `build`. A platform runtime runs only what only it can answer:
the adapters and their contract bindings over the platform's own APIs (Keychain, PhotoKit, the native SQLite driver;
Keystore, MediaStore, WorkManager, DownloadManager). Each platform has the same three CI gates (`ci.yml`): its
**build** followed on the same runner by its **platform tests** (`ios-build`: the signed archive, then
`iosPlatformTest`; `android-build`: R8 over the plain release, then `androidPlatformTest`), and its **journeys**
(`journeys (ios)`, `journeys (android)`, section "Journeys").

The shared tests used to run on Kotlin/Native and on ART as well. In their history that caught only test NAMES those
compilers reject (a comma for Kotlin/Native, an apostrophe or a space below DEX 040), never shared logic behaving
differently at runtime; every real platform bug came from an adapter or a driver, through its contract binding. So a
difference between runtimes in shared code is what the journeys catch — they run the composed app on each runtime.
The Kotlin/Native compile of every shared source set is still checked by `ios-build` and the journeys' app builds.

**Deno is a hard prerequisite of `build`.** `:adapter:generic:app:jvmTest` runs the `Backend` port's
contract against the real `api/`, started as `src/dev/serve.ts --ephemeral` (loopback only, filesystem
store, no bunny zone reachable). Without Deno those tests **fail naming it**. They never turn into
`NotRunHere`. `api/src` is declared as an input of that task, so a backend-only change re-runs them
(`changes/archive/2026-09-23-contract-backend-clients`).

**Build-property-gated source sets are compiled by CI.** Code built only under `-Psnapsync.rig=true` is invisible
to `build`. That is what compile-time containment means, and it is also a blind spot. Both platforms' journeys build
the rig app on every push, which compiles those trees in full. A gated tree that carries **tests** must have them
RUN there too, not only compiled: the forge's gated test set once stopped compiling and stayed broken for weeks while
a main-only compile step stayed green beside it. No gated tree carries tests today.

---

## 2. Where tests live and which targets they run on

### A test lives with the code it tests

A test goes in the module that owns the logic. Suppose a test would need a library or platform API that
the module does not have. That shows the logic is in the wrong module. It is **never** a reason to add the
dependency to the module.

### Logic tests go in `commonTest`

`commonTest` runs on the JVM (see "Where each test runs"). A platform test source set holds only what that platform's
toolchain cannot run anywhere else. Where two targets have equivalent implementations, they share one port contract
(section 4).

`commonTest` is where a test *goes*. It is not a reason to *move* code. Do not move a platform-to-neutral
translation into `model/` just to reach the faster JVM loop. The translation stays beside its inputs, so
its test asserts against the platform's own symbols rather than a copied constant (`docs/architecture.md`
spec, "Zones inside the core").

Non-`commonTest` source sets that exist today:

| source set | why | exception? |
|---|---|---|
| `:adapter:ios:ext-safe`, `:adapter:ios:app-only` `iosTest` | these modules have no JVM target, so `iosTest` **is** their common set | no |
| `:adapter:generic:app` `jvmTest` + `:adapter:ios:ext-safe` `iosTest` | JVM-driver and native-driver halves of one storage contract; together they cover both targets | no |
| `:adapter:generic:app` `jvmTest` (backend Live bindings) | they launch `api/` as a local process, which a K/N test executable under `simctl` cannot do. Nothing is lost: the clients are `commonMain` code, and their K/N compile is covered by `commonTest` | yes, stated in the build file |
| `:ui:components` `jvmTest` | Compose component tests with no iOS counterpart | yes |
| `:test:architecture`, `:tools:diagrams` `src/test` | they read the repository's own text | yes |
| `:test:integration` `src/test` | drives the JVM host of the control channel, which is a JVM server. The lost coverage (the composed graph running on K/N over mocks) is partly covered by the core's own simulator tests and by the simulator app under the contracts and journeys | yes, stated in the build file |

Where a target is genuinely skipped, the build file that declares the source set **says which coverage
is lost and why**.

### Feature tests compose real services over port mocks

Features see services only — `:domain:feature` does not depend on `ports/` — so a feature test that needs a service is
built the way production builds it: the **real** service over the ports' in-memory mocks from `:adapter:generic:mock`
(`inMemoryDatabases()`, `inMemoryFiles()`, `inMemoryPreferences()`, `inMemorySecureStore()`, the gallery and access
mocks). There are no doubles of the services: what a test observes or forces, it observes or forces **at the port** —
the rows in the in-memory database, the files in an area, a refusal the mock answers on demand
(`inMemoryDatabases(mapOf(name to DbOpen.OldSchema))`), a `Files` decorator that fails a write.

Those tests live in **`:test:feature`** (JVM and the iOS simulator), the one module that sees feature, services, ports
and the mocks together; its `support/` package holds the shared setups (`configService`, `TestLedger`, `testIdentity`,
`RecordingFiles`, …). A feature test that touches no port and no service stays in `:domain:feature`'s own
`commonTest`, and so does one that needs a feature `internal`. A service's own tests live in `:domain:services`
(over hand-written port doubles) or, where they need the mocks, in `:adapter:generic:mock`'s `commonTest` — which also
holds the flow tests and the mocks' own contract bindings, because the mocks are `internal` and only their own module's
tests can build one in a chosen state. **A feature's tests may be split across `:domain:feature` and `:test:feature`.
Look in both.**

### No `:app:*` module has a test source set

`:app:ios`, `:app:ios:extension`, `:app:jvm` and `:app:desktop` declare no tests. If behaviour
there needs coverage, **move it** into `:domain` or an adapter and test it there.

The reasons differ, so keep them separate:
- `:app:ios` and `:app:ios:extension` are **wiring-only by gate**. `detektAppShell`,
  `KotlinShellGuardTest` and `SwiftShellGuardTest` forbid decisions in shell source (see
  `docs/architecture.md`). "Nothing worth testing is there" is what makes leaving them untested safe.
- `:app:jvm` is **wiring-only by the same gate** (`detektAppShell`, `KotlinShellGuardTest`). What it composes is
  exercised by every test that drives the rig's JVM host: `:test:control`'s protocol tests (a relaunch among them)
  and the whole `:test:integration` suite.
- `:app:desktop` is test equipment. It is exempt from the shell laws and is exercised, without gating,
  through `:test:harness-driver`.

**Remaining risk, which must be stated wherever shell correctness is relied on:** the gates do not catch
*mis-transcription*. A forwarding with no conditional that names the wrong collaborator passes every gate.
Most of this risk has been removed by moving code:
- OS callbacks arrive through entry ports (`Lifecycle`, `Links`, `PushNotifications`, `Ui`, `DevControls`,
  `ExtensionHost`). The shell forwards each Swift callback to its adapter in one line; what a delivery runs is the
  composition's handler. The rig's entry tests (`EntryIntegrationTest`, `OsCompletionIntegrationTest`) deliver each
  entry to the JVM root and read what it did; `ExtensionEntryTest`, beside the mocks, pins the extension's re-read on
  JVM and the simulator.
- The tap → `UiIntent` table is one factory in `:ui:screens`, and it is click-tested there.

What is still uncovered:
- Swift argument-level forwarding: the right entry called with a wrong argument of the same type. (Since 11f the
  `BGTask` registration is Kotlin's — `IosWake` registers the heartbeat and hands its expiry to the wake's
  `Completion` — so Swift forwards no task identifier at all.)
- The hand-written shell entries outside the port: `onLaunch`, the event-link activity filter's call site,
  and the log-only callbacks.

Decision record: `changes/archive/2026-09-22-shell-as-driving-adapter`.

---

## 3. The test-only modules

Each `:test:*` module exists because it provides something a production module may not have. They are
exempt from the production-module laws. **No production main source set may depend on a `:test:*`
module.** The one exception is a source set that a build script adds only under a containment property
(`-Psnapsync.rig=true`). That source set is not part of a production build.

| module | what it provides |
|---|---|
| `:test:contracts` | the contract mechanism and every port contract (section 4). The only module whose **main** code depends on `kotlin-test`. Links into the app only under the rig property. |
| `:test:rig` | the control channel: one HTTP protocol served by an iOS app host and a JVM host (section 6). The only module allowed `ktor-server-*`. |
| `:test:control` | the typed JVM client of that protocol (`RigClient`), plus the JVM host's own tests. |
| `:test:integration` | the seam → UI-state integration suite, driven only over the protocol (section 7). Also the `journeys` source set. |
| `:test:edge` | `LiveEdge`: the real `api/` as a local Deno process, one per test JVM. Used by the backend contracts' Live bindings and the rig's JVM host's second backend. |
| `:test:architecture` | JVM guards over the repository's text. What each guard checks: `docs/architecture.md`. |
| `:test:harness-driver` | serves either desktop harness headlessly over HTTP. Dev infra, not a gate (section 8). |

Two support modules outside `:test:*` belong with them: `:adapter:generic:mock`, the mocks (section 5), and
`:app:jvm`, the JVM root the rig's JVM host and the desktop world harness compose the app with (section 5).

---

## 4. Port contracts

A **port contract** states what every implementation of a port must do, as executable clauses. The same
clauses run against the mock and against each real adapter, so a double cannot quietly behave
differently from the system it stands in for. **The contract code is the specification of the port.** No
doc or spec restates its clauses. This section covers the testing mechanics. Which ports are contracted,
and why that matters for architecture, is in `docs/architecture.md`.

**Only ports have contracts, and a contract's subject IS the port.** A service built over a port is never a
subject, even when the service is all a caller ever sees of the port: its rules are tested over the mocks
(section 5), which are bound to the same contracts as the real adapters, so the mocks' fidelity is held where the
adapters' is. (Eight contracts once took a service as subject — the ledger, the download store and six App-Group
stores; their scenarios now live in those services' suites.) What an entry
port's handler RUNS is the composition's, pinned by rig tests over the JVM root (section 7); an entry port's contract
holds only its adapter's delivery to the handlers, driven through the adapter's own platform entries.
A format a port's answers are framed into is not a port: the encrypted file format is held to shared reference
vectors instead (below, `Crypto`).

**Every statement runs on every SQLite.** The services' SQL is tested over the `Databases` mock, which is real
SQLite — but the JVM's, and the mock suites run on the JVM only. What each platform's own SQLite makes of the
production schemas is `DatabasesContract`'s schema clauses (`SchemaClauses.kt`): for `ledger.db` and
`downloads.db`, every statement SQLDelight generates runs through the port (each read as it is, each write in a
rolled-back transaction with the reads inside it), and a database created at its first shipped version migrates to
the shape a fresh one has and then runs them all again. They assert that a statement RUNS, never what it answers —
that is the services'. The statement list is generated from SQLDelight's own output (`generateSchemaStatements`,
`SchemaStatementsTask` in `build-logic`) with a fixture per argument type, so a query added to a `.sq` file is run
with nothing to remember, and a type with no fixture fails the build naming it.

Decision record: `changes/archive/2026-09-22-establish-port-contracts`. Later extensions are listed at the
end of this section.

### Clauses, states, bindings

- A **contract** is a hand-written list of clause values in `:test:contracts` `commonMain`. Each clause
  has a stable id, the **state** it needs, and a body that asserts with plain `kotlin.test`. Nothing ever
  generates or rewrites a clause from observed behaviour. A person decides.
- Each contract has its own **state vocabulary** beside it. Production code never gains it.
- A **binding** pairs one implementation with one **host** and has a kind: `Fake`, `Live` or `Replay`.
  It declares, **as a literal**, the states it can reach. `create(state, clauseId, log)` returns a fresh
  implementation already in that state, or `Unreachable(reason)`. Each clause gets a fresh instance. The
  runner checks the declaration: a declared state answered `Unreachable`, or an undeclared one answered, is
  `Failed`. Its first act is to wrap the implementation in the port's **recording proxy** over `log`, and
  it builds everything else over the proxy ("A declared cell must occur").
- Where an outcome cannot be read through the port itself, the clause also gets an **observation
  handle**. Examples: a port method that returns nothing, a refusal that reaches the app only through
  the HTTP interceptor, or what a reporter transmitted to an endpoint. The handle reads **outcomes**
  (state reached, objects landed), never which collaborator was called.
- A scenario that needs the implementation to change state mid-run is not a clause. It is an ordinary
  fake-backed test of the project's own logic.
- Clauses that share a system that cannot be reset (the simulator's photo library) are isolated by
  **addresses derived from the clause id** (capture-date windows, titles, fixture routes). They never
  delete what they seed.

Where bindings live: beside their implementations.
- Fakes: `:adapter:generic:mock` `commonTest` — the in-memory `Backend` mock (`BackendContractBindingTest`), the
  in-memory `DeviceIntegrity`, and the upload-job queue and download session mocks (`TransferContractBindingsTest`)
  among them.
- The `Backend` port is ONE contract (`BackendContract`, split into part files by route area for size), held by
  two bindings: `HttpBackend` against the real `api/` (`Live`, the coverage) and the in-memory mock (`Fake`). Its clauses state each route's answers in the backend's own vocabulary —
  statuses and bodies — because what they MEAN is the services' decision above the port, tested beside those
  services (`CredentialedBackendTest`, `BackendServicesTest`). A binding enters states through the backend's public
  surface: `EdgeSetup` over HTTP, `PortSetup` through the port itself for a backend with no HTTP surface.
- The live backend, and the JVM `Databases` and `Files` adapters: `:adapter:generic:app` `jvmTest` — so every
  `build` runs them, not only CI's simulator job.
- The iOS `Databases`, `Files` and `Preferences` adapters on Kotlin/Native: `:adapter:ios:ext-safe` tests.
- The Android `Files`, `Databases`, `Preferences` and `SecureStore` adapters on ART: `:adapter:android`'s device tests
  (`src/androidDeviceTest`), on `ANDROID_EMU` — the only place the production schemas meet Android's SQLite.
  The Android module has no `commonTest` — every binding there needs the platform — so the convention plugin declares
  the device test for a module with `src/androidDeviceTest` too.
- `DeviceIntegrity` on Android: its AVAILABLE clauses run live on `ANDROID_EMU` (a key is made, attested, named and
  signs; an unknown one refuses) — which proves the adapter, never the hardware. The same test RECORDS a proof the api
  replays through its real routes (`api/test/android-emulator-proof.test.ts`), which is what checks that the bytes the
  adapter produces are the bytes the verifier reads. A real phone's chain first meets the production verifier in the
  closed test; until then Google's recorded device chains stand in (`api/test/android-attest.test.ts`).
- Receiving on Android: the `Download` contract runs live on `ANDROID_EMU` over DownloadManager against the transfer
  fixture (`AndroidDownloadContractTest`, which also shows a transfer finished while no broadcast arrived is delivered
  by the next start), and the `GalleryImport` contract over the MediaStore import (`AndroidImportContractTest`, which
  also pins what no shared clause states: fixtures carrying an iPhone's metadata — a HEIC with an offset, an HEVC MOV —
  and an Android MP4 land once in `DCIM/Camera` at their capture time; a JPEG with no date of its own gets the capture
  time as its modification time, since MediaProvider ignores an app's `DATE_TAKEN`; a Live Photo arrives as ONE JPEG
  motion photo — a HEIC still re-encoded with its date, offset, location and camera carried over and its rotation
  applied, a JPEG still kept byte for byte, both XMP tag sets, the MOV appended unchanged — or, when that cannot be
  built, as its still, once; a killed import's pending item reads absent and is cleaned; the fixtures live in
  `src/androidDeviceTest/resources/import/`; an import into an event album lands only in its folder, and a killed one
  there is cleaned too). The motion photo is asserted on its bytes: whether Google Photos PLAYS it is measured on a
  phone: a test cannot read what another app plays. The byte formats themselves are `:domain:model`'s
  `MotionPhoto.kt`, tested on the JVM. Whether a gallery app sorts by those dates, and whether a MOV plays, is the
  closed test's.
- The event album on Android: the `FolderAlbum` contract runs live on `ANDROID_EMU` (`AndroidGalleryContractTest`) and
  over the library mock playing an Android library: two albums of one title are two folders; an empty album folder
  does not resolve until a photo lands; a moved photo keeps its `_ID` (measured at API 30 and 36 before it was pinned)
  and is no candidate to share; an import into an album lands there. The one clause it cannot reach on the emulator
  is a camera photo **another** app owns being left in place (every photo the test seeds is its own): the core never
  hands one over (`FolderAlbumCoordinatorTest`, `AlbumGatherTest`), and the closed test observes the platform's refusal.
- The lesser photo grants on Android: a device-test APK is installed with every runtime permission it declares granted,
  and revoking one ends the process, so each grant is an APK of its own — `:adapter:android`'s holds the full grant,
  `:test:partial-grant`'s declares only `READ_MEDIA_VISUAL_USER_SELECTED` (the `Gallery` and `PhotoAccess` contracts'
  partial-grant clauses: the selection read, its observer's baseline), and `:test:no-grant`'s declares none (never
  asked; refused — the adapter's own record that it asked).
- `PlatformDeviceId`: its contract runs live on `ANDROID_EMU` over `ANDROID_ID` (an offered id is stable and
  canonical), and on the JVM over `NoPlatformDeviceId` (no id is `null`). "The same after a reinstall" is the property
  the id is chosen for and no process can test on itself; it is checked by hand on the emulator.
- `DateFormatting`: live on `JVM`, `IOS_SIM_KEXE` and `ANDROID_EMU` over each platform's real adapter — a skeleton's
  order, names and hour cycle in a pinned locale, a bare language, and the device's own. A space in an answer compares
  as a plain one (CLDR's no-break and narrow no-break spaces before `PM` differ by platform version). Its grid is one
  cell, `formats → returns`: the `DateFormats` it hands over is a `model/` value, so its `format` is no cell of its
  own. The `:ui:components` tests render through the real JVM adapter, so a label is asserted as a locale writes it;
  the `:ui:screens` tests through it too (an `expect` whose one real `actual` is the JVM's: that suite runs on the JVM only).
- `Crypto`: live on `JVM`, `IOS_SIM_KEXE` and `ANDROID_EMU` — published known-answer vectors only (RFC 4231 HMAC, the GCM
  specification's AES-256 test cases), so every platform computes what the others do. The encrypted file format framed
  over it is not a contract: both its halves are held to `test/vectors/encrypted-file.json` (the Kotlin one by
  `EncryptedFileFormatTest` over the real JVM adapters, the TypeScript one by `api/test/encrypted-file.test.ts`), and
  on the JVM to Google Tink itself, which opens what `FileCipher` writes and the reverse. The mock module's
  `fakeCrypto()` is not cryptography and is bound to nothing.
- `NetworkMonitor`: live on `ANDROID_EMU` in all four states — online, restricted (the emulator's Wi-Fi marked metered
  through `cmd netpolicy`), airplane mode (offline), and the package denied by the `OEM_DENY_3` firewall chain
  (blocked), each entered by the binding through the platform's shell; live on `IOS_SIM_APP` online; recorded on the SE2
  online, in Low Data Mode (restricted) and in airplane mode, once each (`.ONLINE.rec`, `.RESTRICTED.rec`, `.OFFLINE.rec`).
- `DeviceConditions` (what a bug report says about power saving, the battery, the thermal state and the background
  allowance, capability `privacy-security`): live on `ANDROID_EMU` as an Android device and on `IOS_SIM_APP` as an
  iPhone, each clause holding a platform to its own facts and to answering the other's unsupported. The simulator has
  no battery, so its battery reads failed — the contract holds the battery only to its range. That `IosDeviceConditions`
  leaves `UIDevice`'s battery monitoring as it found it is unobservable through the port and is stated on the adapter;
  how a report renders each fact, and a read that stalls, run on the JVM (`CollectDiagnosticDumpTest`).
- A transfer held to unrestricted networks (capability `mobile-data`): `Upload`'s and `Download`'s
  `A_TRANSFER_HELD_TO_UNRESTRICTED_NETWORKS_WAITS_FOR_ONE` run live on `ANDROID_EMU` (metered Wi-Fi, via the shared
  `MeteredWifi` entry) and over the transfer mocks; `AndroidWorkContractTest` pins that an unrestricted wake waits out a
  metered network. iOS has no host for them — the simulator shares its Mac's network, and the device's transfer
  recordings go to the rig's loopback receiver, which no network restriction touches — so the iOS request flags are
  pinned by `UploadUrlRequestTest` and what iOS does with them is the measurement recorded in
  `changes/archive/2026-10-04-mobile-data-for-photos/design.md` (SE2: Low Data Mode and a hotspot; XS: cellular).
  iOS's blocked path (the per-app Cellular switch) has no host — no phone the project drives has a SIM — so its
  mapping is pinned by `IosNetworkMonitorTest` and documented, unmeasured, on `IosNetworkMonitor`.
- The install referrer on Android (the invite a Play install carried, capability `invite-link`): which referrers are an
  invite is pure and runs on the JVM (`EventLinkTest`, `inviteLinkFromInstallReferrer`); the once-per-install
  bookkeeping runs on `ANDROID_EMU` over a scripted Play answer behind the reader's internal `ReferrerSource` seam and
  the real `SharedPreferences` (`AndroidInstallReferrerTest`). It is no port and has no contract — the Links port's
  promise is unchanged. What the real Play client answers on a Play-installed build is reachable only once the listing
  is public (production launch), and is checked then by hand.
- The storage services' suites — their rules, their migrations (each entered at its version through the
  `Databases` port, so the service's own open runs the chain) and their answers to every port failure:
  `:adapter:generic:mock` `commonTest` (`services/`), over the storage mocks, which `DatabasesMockContractBindingTest`
  and `StorageMockContractBindingsTest` hold to the platform adapters' clauses. `LedgerWriter`, a feature over the
  ledger, is `:test:feature`'s (`LedgerWriterTest`).
- Keychain and App-Group stores: `:adapter:ios:ext-safe` tests.
- Simulator-app PhotoKit and URLSession: `:adapter:ios:app-only` `src/rig`.

### Outcomes

Every clause ends in exactly one outcome:

| outcome | meaning | fails the run? |
|---|---|---|
| `Passed` | | no |
| `Failed(msg)` | implementation (or recorded answer) violates the clause | **yes** |
| `NotRunHere(reason)` | binding cannot reach the clause's state; the body never ran | no, but see coverage |
| `Diverged(msg)` | on replay, the adapter made an OS call the recording lacks | **yes**, so re-record |
| `NotWithin(T)` | a bounded wait on an OS callback expired | **yes**, on every binding kind |

Whether a clause runs is decided **before** its body runs. A body cannot skip itself, so an unexercised
clause cannot report `Passed`. On CI there is one test per (contract, binding). It runs every clause and
fails once with the full outcome table. A wait on an OS callback is bounded on the **real** clock, because
the test scheduler's virtual clock would expire the wait before a main-queue callback could arrive
(`changes/archive/2026-09-23-notwithin-fails-run`).

### Coverage: every clause runs against something real

`ContractCoverageTest` fails the build for a clause that no real implementation reaches. "Real" means one
of these:
- a `Live` binding on a host CI runs that declares the clause's state, or
- a `Replay` binding whose recording holds a block for the clause.

A host that some `Replay` binding names counts **only through its recording**. So a device-only clause
fails the build until someone records it, and declaring a state unreachable cannot hide a failing clause.
If no host can exercise a platform fact, it belongs in the adapter's KDoc with its evidence, not in a
clause.

A clause must be able to FAIL: a do-nothing implementation of the port has to break it. A clause a stub
would satisfy states nothing about the real system.

Where a port's own surface cannot enter a clause's state, an external OS stimulus may drive it —
`simctl openurl/push/launch/terminate`, a `BGTask` simulation the rig triggers, an XCUITest host.

A real adapter still counts as real when its location or configuration is injected: a temp directory
instead of the App-Group container, a loopback reporting endpoint instead of the baked DSN, or the
simulator's default `URLSession` instead of the device's background session. **The one lookup it bypasses
is not covered.** A clause about that lookup being *unavailable* is entered through the production default,
so the host's own answer drives it. An injecting constructor is `internal` to the adapter's module.

Where production reaches a port only through a composition that owns part of the port's contract, the
Live binding binds **that composition**. An example is a grant-aware wrapper that answers "not readable"
where the bare adapter would answer "empty". An adapter bound bare must pass the whole contract itself.

### The port grid

`ContractCoverageTest` asks whether each clause reaches something real. `PortGridTest` asks the reverse
question: what could an adapter answer at all? It reads `:domain:ports` by reflection and lists every
**cell** an adapter can produce:
- `Port.member → Variant`: a member crossed with each variant of its return type. A sealed type gives one
  cell per leaf subtype (only the outer type of a generic), an enum one per entry, `Boolean` a `true` and
  a `false`, a `Flow<T>` the variants of `T`, and any other type a single `returns`. A nullable type adds
  `null`.
- `Port.handlers.field(Arg, …)`: a handler an event port's adapter calls, crossed with the variants of
  every argument it passes.
- `Port.member.param(…)`: the same, for a callback handed to a member.
- `Port.Handle.member → Variant`: a handle (`Completion`, …) counted under each port that hands it out.

Errors are reduced into values, so a throw is a cell only where the member **declares** it with
`@Throws` (one `throws` cell, whatever it throws: `AttestStore.token`, `DeviceIntegrity.prove`). A member
with a default body in the interface is not the adapter's answer, so it gets no cell. The test writes
`build/reports/port-grid/port-grid.txt`. It also writes an estimate that marks a cell with `~` where a
port contract's source mentions the member and the variant. That is a mention, not a clause asserting the
cell. **Report only:** it fails nothing but its own scan.

### Every clause declares the cells it covers

Each clause names the grid cells it checks, by typed reference, in a required `covers`:

```kotlin
clause(
    "INACCESSIBLE_READ_IS_UNAVAILABLE",
    SecureStoreState.INACCESSIBLE,
    covers = cells { on<SecureStore>().answers(SecureStore::read).with(SecureStoreRead.Unavailable::class) },
) { store -> … }
```

The forms are `answers(member).with(LeafClass::class | ENUM_ENTRY | true | null)`, `.withGenericLeaf(…)`
(a leaf of a generic sealed type such as `Reply.Ok`, which the compiler cannot check), `.returns()`,
`.throws()`, `emits(flowMember)` for a `Flow`, `calls(XHandlers::field, args…)` for an event port's
handler, `callsBack(member, "param", args…)` for a callback handed to a member, and `handle<H>()` for a
handle's members. The port is a reified `on<P>()` because a Kotlin/Native member reference carries only its
name. The variant is fixed against the member's return type in two steps, so a wrong leaf, enum or `null`
fails the compile; handler arguments and the member's owning port are not type-checked.

A clause claims a cell only where its body **checks** that answer: it asserts it, or asserts an effect
only that answer produces (one pending wake after a `schedule`). A call made for setup whose answer the
clause never checks is not a claim. A clause that accepts either of two answers (a grant that reads
`NOT_DETERMINED` or `DENIED`, a `start` that may or may not begin) declares them as a **one-of group**:
`oneOf { on<P>()…; on<P>()… }`. One run sees one of them, so a cell claimed only inside groups is a
**weak claim** and does not count as covered.

The declarations render through `:test:contracts`' common code (`Covers.kt`), the same on every host.
`ClauseCoversTest` loads every contract as a value (`ContractCatalog`, by reflection over the contracts'
JVM classes) and holds the declarations to the grid. It **fails** on a clause with no cell and on a
declared cell the grid does not hold. It **reports** the unclaimed cells, the cells claimed only by
clauses no real implementation runs, and the cells claimed only weakly, in
`build/reports/port-grid/clause-covers.txt`.

### A declared cell must occur

A declaration is checked on every host a clause runs, `Fake` included: a clause's state is fixed, so its
answers do not depend on the host, and one check keeps the mock and the adapter honest to the same claim.

- The runner makes one `CallLog` per clause and passes it to `create`. The binding wraps its
  implementation in the port's **recording proxy** (`adapter.recorded(log)`, `:test:contracts`
  `proxy/`) before anything else, and builds the subject over the proxy only — the `EdgeSubject`, the
  `SeededLibrary`, each `Download` a factory opens.
- A proxy forwards every call unchanged and records the cell it saw as the grid's own text, rendered by the
  same `Covers.kt` the declarations use: the instance's class is the class the declaration names, on the
  JVM and Kotlin/Native alike. It wraps what it hands on, too: the handlers passed to `listen` (logged as
  `Port.handlers.field(…)`), the callbacks handed to a member, and the handles a member returns or a
  handler receives (`Completion`, `BackgroundTimeHold`, `LibraryChangeToken`), each under the port that
  hands it out. A member that declares `@Throws` records `throws` only for the declared type: another
  throw, or a cancellation, passes unrecorded, and every throw is rethrown unchanged.
- Only the clause's **window** counts: from just before its body to the end of the binding's `dispose`.
  What `create` does to enter the state (seeding writes, `listen`) satisfies no claim; a handler the
  adapter calls late, during the body or the disposal, does. A call after the window is dropped. So a
  binding's own cleanup in `dispose` goes through the bare implementation: a cleanup `delete` through the
  proxy would satisfy a clause that claims a delete its body never made.
- After a body that passed, a declared cell that never occurred is `Failed("declared <cell> never
  occurred")`, and a one-of group none of whose cells occurred is `Failed("none of … occurred")`. A cell
  that occurred undeclared is ignored.

Kotlin/Native cannot read `@Throws` at run time, so each proxy names its members' declared types in a
`THROWS` map. `ProxyCompletenessTest` (`:test:architecture`) holds every proxy to the grid: each grid
port has one; it drives **every** cell of the grid through it, over a reflective double that answers the
cell's variant, throws its declared type or calls its handler, callback or handle, and requires exactly
that cell to be recorded; and each `THROWS` map equals the port's `@Throws` declarations. A proxy for a
port no contract binds yet is held to the grid all the same. `ProxyRenderingTest` runs the rendering on
every target `:test:contracts` tests, the iOS simulator included.

### Open cells

A cell is **covered** when a clause that runs against a real implementation on some host
(`ContractCoverageTest`'s reading) declares it. A claim by a clause only a mock answers does not count.
Every grid cell must be covered or listed in **`test/contracts/open-cells.txt`**: the cells no clause
covers yet, one per line in the grid's own text, sorted. `ClauseCoversTest` fails when:
- a grid cell is neither covered nor listed. That is new port surface (a member, a variant, a handler)
  without a clause. Write the clause; list the cell only where none can be written yet.
- a listed cell is covered. Delete the line: the list only shrinks.
- a listed cell is not in the grid: a member or variant was renamed or removed. Delete it; the cells it
  became fail under the first rule.
- the list is unsorted or names a cell twice.

The one reading of "covered" is `ContractCoverage.coveredCells`, so a rule that counts a claim for less
changes that function only. A renamed port member moves its cells, so it fails the first and third rule
together: the messages print the lines to add and delete.

### Hosts

A `Host` is identity that changes which states are reachable: platform × process kind × entitlements. OS
version, device model and date are provenance, not identity. A photo grant is not identity either. It is a
**precondition**: a binding declares the one grant it runs under and refuses the whole run in a process
holding a different one.

| host | process | what matters |
|---|---|---|
| `JVM` | JVM test | no Keychain, no App Group, no photos |
| `IOS_SIM_KEXE` | K/N `test.kexe` under `simctl`, unentitled | Keychain answers `-25291`, App-Group lookup `nil`, photos `DENIED` with no route to a grant |
| `IOS_SIM_APP` | rig build of the app on a simulator, ad-hoc signed (`scripts/sim-sign`) | App Group available; photo grant via `applesimutils` (`simctl privacy grant` does not work for PhotoKit); no Keychain group; no partial grant exists |
| `IOS_DEVICE_APP` | entitled app on a device | Keychain, App Group, any grant a person sets. The **only** place a partial grant exists |
| `IOS_DEVICE_PHOTOKIT_EXT` | the upload extension on a device, launched by the OS | about 60 s per `process()` call, then killed; 6–11 min back-off after a kill |
| `ANDROID_EMU` | a device-test APK (or the rig app) on the Android emulator | app-private storage and the Keystore; its KeyMint attests in SOFTWARE under a per-AVD "Google Test LLC" root (measured 2026-09-29), so no hardware attestation |

A backend a binding launches is **not a host**. It is part of the implementation. The real `api/` makes a
binding `Live`, and the in-memory mock makes it `Fake`. An endpoint
the binding stands up only to *receive* (the Sentry ingest), or to *answer with a status the clause chose*
(the transfer fixture `scripts/transfer-fixture.py`, an upload receiver answering 200/403/500), is a clause
input or observer. It does not change the binding's kind. A receiving endpoint must not be more lenient
than production on a limit production is measured to enforce.

One exception is deliberate and narrow: a **wire fixture** for the `Backend` port's HTTP adapter. A loopback server
answering every route with success and bytes no backend version this build speaks would send stands in for a backend
of another version, and its binding counts as `Live` for `BackendState.UNREADABLE_SUCCESS` alone — the one state
whose clause judges only what the client's own decoding makes of the bytes (`Reply.Malformed`), never what a backend
answers. The real `api/` never sends such a body, so nothing else could produce that answer; every other backend
state stays the real `api/`'s or the in-memory mock's.

No host can enter "protected data unavailable before first unlock", and the reason is mechanical rather
than a gap in the fleet: before first unlock the App-Group file the launch-time adapters read sits in a
class the OS will not decrypt, so `LaunchMix` refuses and a rig build composes nothing — the observer is
disabled by the very condition it would observe. A clause conditioned on it has no real host, so it is
not written. What that state would prove is covered where a host CAN enter it: `AttestStoreContract`'s
`AN_UNREADABLE_TOKEN_IS_NOT_ABSENCE` and `AN_UNREADABLE_KEY_ID_IS_NOT_ABSENCE`, whose `INACCESSIBLE`
state is reached on `IOS_SIM_KEXE`, where every `SecItem*` call answers `-25291`.

### How each host is run

- **JVM:** ordinary test tasks in `build`.
- **`IOS_SIM_KEXE`:** `iosPlatformTest`, in the `ios-build` job.
- **`ANDROID_EMU`:** `androidPlatformTest` on a Gradle-managed emulator, in the `android-build` job.
- **`IOS_SIM_APP`: live on every push.** The `journeys (ios)` job builds the app under
  `-Psnapsync.rig=true`, applies the declared grant, launches it, and `scripts/sim-contracts` runs every
  entry of the host's in-app registry (`GET /contract`) through the rig's contract verb. It fails on any
  run-failing outcome, any refusal, or an empty registry. Each run starts from a fresh simulator. A host CI
  can run is **never** recorded.
- **`IOS_DEVICE_APP`, `IOS_DEVICE_PHOTOKIT_EXT`: recorded on the device, replayed on every build.**

One contract may be registered on a recorded host and on `IOS_SIM_APP` at once, when its states split
between them. For example, a state that would push the app out of the foreground is recorded on the
device and the rest runs live. The verb runs the entry for the host it is on. A contract asked to record
for a host it is not running on **refuses**, so a recording can never be filed under the wrong host.

The JVM rig host refuses the contract verb and `GET /contract` with `409` naming `JVM`. JVM bindings run
in `build`. The refusal exists so that "run everything the host lists" cannot pass with nothing run.

### Record and replay

For a host CI cannot run, the contract runs in-app on demand and records, per clause, **every call the
adapter makes to the OS and the OS's answer**. It never records the port's answers. Each CI build replays
the recording through the **current** adapter's `internal` OS seam (`KeychainApi`, `AppAttestApi`,
`BackgroundTaskApi`, …), with the current clauses judging. State seeding goes through the same seam. A
recording is input to a clause, never an expectation.

- Matching is **exact and in order** within a clause. Reordered or changed calls give `Diverged`, and the
  fix is to re-record. The same calls with an answer that violates a clause give `Failed`, a finding
  against the code or the clause that re-recording will not fix.
- Answers are recorded in full, so a newly read attribute is present on replay. Only a named list of
  volatile keys is masked. An OS-minted id that the adapter sends back is masked to the same placeholder
  in both places. **Credential material (attestations, assertions, tokens) is always masked**, because
  recordings are public.
- **What the OS delivers unasked is recorded too, as an event** — a `<- text` line where it arrived among the
  calls: an expiration handler fired, a relaunch's session events, a report handed over. On replay the replaying
  seam takes the events due after each call it answers (`Replayer.takeEvents`) and delivers them to the adapter as
  the OS did, on another thread. A call made while an event is still due, or an event never delivered, is
  `Diverged`. A recording without events replays as it always did.
- Clause inputs are deterministic: fixed values, a fixed id generator, and addresses derived from the
  clause id.
- A missing recording, or a missing block for a declared-reachable clause, is `Failed`, not `NotRunHere`.
- Files: `test/contracts/recordings/<Contract>@<HOST>.rec`, or `<Contract>@<HOST>.<GRANT>.rec` when the
  binding declares a grant (for example `UploadExtensionRegistry@IOS_DEVICE_APP.LIMITED.rec`), or
  `<Contract>@<HOST>.<PRECONDITION>.rec` when it declares another condition of the device a person sets before the
  run (`NetworkMonitor@IOS_DEVICE_APP.OFFLINE.rec`: airplane mode). Each file
  has a provenance header, then one `[CLAUSE_ID]` block per clause, sorted, of `call -> answer` lines. A
  run overwrites the file. **Commit it unedited.** Git holds its history, and an iOS update that changes
  an answer shows up as a diff on the same host.

**Recording.** Build with `-Psnapsync.rig=true`, install on the entitled device, and call
`POST /contract/<name>` over the rig. It answers with the recording text and the live outcome table, and keeps
it in the app's `Documents/contracts/` too. A production build contains none of the contract module, the
bindings or the recorder.

Some states exist only once the app has left the person's hands, and their runs wait for it: the screen locked
(`ProcessInfo@IOS_DEVICE_APP.LOCKED.rec`), the app sent to the home screen until its background time runs out
(`BackgroundTime`). Each holds background time and waits — reading the app's own state, unrecorded — for the person
to put the phone there. Two span launches, and are asked for in two steps (`?step=arm`, then `?step=collect`): the
relaunch, where the app prepares a transfer in a session of its own and exits, and iOS relaunches it in the
background to deliver the transfer's end (`Upload@IOS_DEVICE_APP.rec`, and `SharePresenter@IOS_DEVICE_APP.rec` from
the same windowless launch); and MetricKit's delivery, which the run waits for across launches, about a day
(`ProcessMetrics@IOS_DEVICE_APP.rec`). A step that records nothing answers `409`, so it never lands in a `.rec`.

The extension cannot be reached by the rig directly. The verb writes a run request into the shared App
Group and re-registers the extension. The OS invokes it, and the rig build's extension runs the contract
**instead of** its upload cycle and writes the result back. The verb answers that file, or a timeout status
with no recording (never a partial one). Where a state exists only after the OS acts *between* two
`process()` calls, the binding prepares it in earlier calls and runs the clause in a later one. The
preparing calls head the clause's block in call order. Clauses entered this way that share one OS queue
run alone. The recording procedure itself is in `.claude/skills/rig-channel`.

Decision records for the extensions: `2026-09-23-contract-app-group-stores` (injected locations),
`2026-09-23-contract-backend-clients` (external service, observation handle),
`2026-09-23-photokit-contracts` (simulator-app host, grants, binding the production composition),
`2026-09-23-device-credential-contracts` (masking), `2026-09-23-contract-platform-handoffs` (two-host
registration, real-clock bound), `2026-09-23-diagnostics-reporter-contracts` (injected build value,
receiving endpoint), `2026-09-23-contract-background-transfers` (per-target adapter, transfer fixture),
`2026-09-23-contract-upload-job-tier` (extension host, grant-keyed recordings, cross-call states). All
are under `openspec/changes/archive/`.

---

## 5. Mocks and the JVM root

### Mocks

`:adapter:generic:mock` holds **one mock per port**, and each mock has three faces:
- **its durable state** — what the real external system keeps, which a relaunch of the app does not touch: the
  backend's events, memberships and bytes; the photo library; the device's files, databases, user defaults and
  Keychain; the operating system's upload jobs, download session and scheduled wakes;
- **the port face** a process is handed (`port()`), built anew per process over that state — typed as the port,
  and backed by an `internal` class, so an app can reach nothing else;
- **the operator face** (`operator`), a separate public type: the levers (refuse an import, fail a job, expire the
  background time, take the backend offline, play another member) and the reads (the library, the jobs, the objects
  and the pushes the backend would send).

A mock with nothing to pull (`PreferencesMock`, `SecureStoreMock`, `DeviceIntegrityMock`) has no operator face;
`DatabasesMock`'s answers only which databases the device saw opened. The port-typed factories the contract bindings use (`inMemoryBackend()`, `inMemoryGallery()`, ...)
build the same classes over caller-held cells.

- **The backend mock** (`BackendMock`) is the JVM default backend. Its port face answers in the backend's own
  vocabulary and declares the version its build declares (`DeclaredVersion`, a cell an operator may change to play
  an update in place). It holds the real backend's rules the contract checks and the ones it does not: membership is
  one record with an active/departed state (the real backend's `done`/`left` split is observable through no route,
  so the mock keeps none), the union spans departed members, the publish or leave that leaves no active member
  unsettled closes the event, capacity counts every device ever
  enrolled, a rejoin clears the stored manifest version, a strictly older manifest is answered and changes nothing,
  names are 1–100 characters, and a write that makes something newly servable records a silent push to every other
  active member holding a token (`pushesSent`, the APNs mock). Bytes are not a port route: an OS upload reaches the
  mock through `BackendOperator.receive`, the byte route, which the upload-job queue mock's network is by default.
- **The transfer mocks are the transfer contracts' `Fake` bindings** (`TransferContractBindingsTest`, beside them). A
  clause the mock fails is fixed in the mock. The PhotoKit tier's one free retry, which no real host shows, stays
  uncontracted.
- **`LibraryAssets`** builds the photos an operator adds — an ordinary 12 MP photo by default, and the kinds the
  selection policy excludes (or must not).
- **`MockDevice`** is the device as mocks — one of each, in common code. The JVM root's `JvmMocks` is one; a rig build
  of the iOS app keeps one for the systems its launch-time adapters mocks (section 6). Each mock's durable state encodes to
  text system by system (`MockState`), and `DatabasesMock` takes a directory, where its databases are files that
  outlive the process. The module compiles for `iosArm64` for the adapter choice alone: only a rig build links it, and
  `MockContainmentTest` fails the build the day a shipped root names it outside that switch.

### The JVM root (`:app:jvm`)

`JvmApp` composes the app on the JVM exactly as `SnapSyncRoot` does on the phone — `snapSyncHost` over `AppPorts`, ports
and nothing else — and the upload extension beside it exactly as `UploadExtensionRoot` does (`snapSyncExtension` over
`ExtensionPorts`), over the adapters **its caller** chooses for each launch, from a durable state the caller keeps:
`JvmApp(scope, durable) { durable -> adapters }`. `JvmMocks` is that durable state as one mock per external system, and
`JvmMocks.adapters(build, attests, backend, logSinks)` its launch's adapters (`build` is a `BuildInfoMock`, its declared version a cell the caller holds); a
caller may put the real `api/` behind the backend port instead (`VersionedHttpBackend`). **`relaunch()` is process
death**: the running app's collectors and launches end, and a new app is composed over a fresh set of port faces over the
same durable state. It is wiring only — no lever, no test DSL — and gated as a shell.

Its stated deviation from the phone:
- both processes live in one JVM: the app and the extension each set their own process up over their own process ports
  (the extension's files reach only the shared area, its crash channel is one nobody observes), and a process that
  supplies no log sinks leaves Kermit's JVM-global writer list alone — the rig's JVM host hands the app's process its
  recorder, which is how `/device/logs` reads the app's log back.

The screen's "now" is the launch's `Clock`, like the core's: a test runs on mocked time only, never the wall clock.

**Both uploaders run, as on a phone.** The app's uploader is the real one over the mocked transfer session
(`UploadSessionMock`: a `URLSession`'s shape — four live transfers, no free retry, each end reported to the app as it
happens), whose transfers the operator lands (`/device/uploads/complete`); the extension's cycle runs when a caller
invokes it (`/os/photokit-ext/processRawValue`) and its jobs land through `/device/jobs/complete`. Both would take the
same photos, so a test about one of them says so: a test driving the extension's cycle switches the app's uploader off
first (`extensionUploadsOnly()`, over `/device/uploaders?app=off` — the build's development controls, which the JVM
host honours like a device's rig build), and a test under a partial grant, where the extension withholds by its own
admission, lands the app's transfers. The push service re-delivers the token it issued to a relaunched process's
request, as the OS answers every launch's.

Its callers are the rig's JVM host (section 6) and the desktop world harness (section 8).

### The JVM root boots cold

Constructing a launch of the JVM root forces nothing the iOS root does not force at process start, so a path that
works only because something else was built first fails over the JVM root as it would on a device — and every rig
test on the JVM host runs over it. A gate (`JvmRootBootsColdTest` in `:test:architecture`) fails if `JvmApp.kt` — its
own members or its launch's — reads a member of the composed core eagerly (in `init` or an eager property). Defer it
with `by lazy`, `get()`, or the function that needs it. Decision record: `changes/archive/2026-09-23-harden-seam-bug-classes`.

The world (`:test:world`) and its mini-edge, which the JVM root and the mocks replaced, were deleted in 11g2b; their
tests are rig tests now (section 7). `DeletionLedgerTest` keeps them deleted.

---

## 6. The control channel (`:test:rig`, `:test:control`)

**One HTTP protocol, served by two hosts** from the same server, routes and `RigState`:
- the **app host**: the rig build of the iOS app (`-Psnapsync.rig=true`) on a device or simulator, over
  real ports. It is contained at compile time.
- the **JVM host**: the app the JVM root composes (section 5) over the mocks, with the backend mock or the real
  `api/` behind the backend port: `./gradlew :test:rig:runJvmHost -Psnapsync.rigBackend=mock|deno`. No device and no
  lock needed.

The hosts differ only in the hook they hand the server. They never differ in a route or in the state
encoding. Both bind loopback only.

**On the app host the rig is an adapter set, chosen at build time.** The root builds its real adapters as one lazy
bundle (`AppDevicePorts`, `:domain:compose`) and calls `platformAdapters(real, …)`, which a rig build compiles from
`:test:rig`'s hook directory instead of the app's `src/prod`: the ports are the launch-time adapters' (below), the UI is
decorated (`RigUi`, which forwards everything to the screen it wraps), the development controls are the channel's
(`RigDevControls`: the per-uploader switch, invite-link hints, the reset), and the server starts once the root has
finished initializing. Nothing runs at image load and the root holds no rig field. The extension's rig build hands its
root the adapter choice's ports too, and decorates its `ExtensionHost` so a requested contract runs in place of a cycle.

Verbs:
- `/os` for OS entry points — each an `EntryDriver` delivery (`:test:contracts`) under the name the iOS shell's
  callback has always carried; the app host drives the iOS adapters' own `deliver…` methods, the JVM host the entry
  mocks' operator faces (`MockEntryDriver`), so one table maps the names for both,
- `/user` for user commands at intent level — each the `UiIntent`s a tap produces, handed to the UI port's
  `onIntent` handler (through `RigUi` on a device, the screen mock on the JVM host),
- `/device` for state, levers and reads,
- `/health`.

**`/device/state` is narrow** (`RigState`): the reduced `UiState`, the readiness derived from it, the download
progress read-model, the build's facts and the OS's extension answer. Reading it is the screen's pull — it refreshes
the ledger-count read-model first, as the foreground poll does — but it reports no ledger count: the ledger is the
app's own bookkeeping, and bytes that landed are read from the backend. 11g2 dropped `ledger`, `permission`,
`inviteUrl`, `eventName` and `transientError` (the last four are inside `ui`); `/device/reset` answers `{"reset":true}`.

**On the JVM host every lever and read is a mock's operator face** — the backend's, the photo library's, the upload
queue's, the download session's, the disk's, the clock's, the reporter's, the screen's — never the composed core
(11g2b). What the app makes of a lever is observed, not awaited: `downloads/stage` finishes the OS's transfers and
answers; the staging and the import its tail runs are read off the staging directory and the library. The reconcile
and the status read are the foreground entry's (`/os/app/onForeground`), so the protocol has no verb for either. The
gallery read is the library's own answer under the person's grant, through the selection policy — the whole library
under a full grant, the person's selection under a partial one. Over the real backend, a lever or read only the
backend mock's operator has answers `409` with the reason.

**The JVM host plays the operating system, its expiry and its record included:**
- `os/app/onExpiry` is the OS saying time is up: every completion handler it holds and every background-time hold is
  told so; `?arg=next` hands the next handler over already expired. The app host honours it only when its adapter choice mocks
  the background-time holds — a real OS's expiry is its own.
- `device/os-record` is what the OS recorded of the app: the completion handlers handed over, released and released
  a second time; whether a screen was shown; whether the selection observer is open; the heartbeat requests and the
  heartbeat pending now (`pendingWake`: its cadence and delay, or `null`); the
  background-time holds, by the name each was begun under; the push registrations; the transfer sessions; the
  databases opened and the files staged. A receipted entry's answer carries it **as read at the release**
  (`osAtRelease`), because "released after the wake's own work" is a statement about that instant.
- `device/relaunch?scene=false` is a cold **background** launch: the process starts with no scene, so nothing builds
  a screen. A test about what a wake must not build starts there and reads only the OS record — `/device/state` and
  `/user` read the screen, and asking would assemble it.
- `device/reinstall` deletes the app and installs it again: its files, databases and user defaults go, while the
  Keychain (the device id), the photo library and the backend keep theirs, and a cold foreground launch follows. It
  is the JVM host's alone; both app hosts refuse it, since deleting the app ends the process that serves the channel.

There are **no click, semantics or pixel verbs**. Taps and pixels belong to the UI tier (section 8).

- `/user` is one table both hosts invoke. A command absent on a build answers `409` with the reason. For
  example, the diagnostics send is absent when no reporter is configured.
- `/device` and `/os` form **one closed vocabulary** (`RigVocabulary`). Every host classifies every entry
  as honoured or refused, with a reason. `GET /device` lists that classification. A refused entry answers
  `409` with its reason, never `404` and never a silent success. An unknown verb answers `404`. An entry a
  host leaves unclassified makes `GET /device` fail naming it, and the JVM host's tests then fail in
  `build`.
- The operator levers are ONE table (`MockLevers.kt`), each naming what it needs: a mocked system, the backend mock's
  operator, a reachable backend or a changeable build version. A host honours a lever exactly when its needs are met
  — the JVM host all of them (the backend mock's operator aside over the real `api/`), the app host those over the
  systems its adapter choice mocks — and refuses the rest naming the real system.
- `POST /device/reset` voids this device's durable sync state without telling any backend, through the
  development controls' reset (`DevControls.onReset`). Use it
  **whenever a build crosses backends** (for example device ↔ local rig). Otherwise leftover `COMPLETED`
  rows make the device upload nothing, with no error anywhere. It clears the upload ledger, the membership
  config (locally), and prunable download rows. It keeps every row that carries an import handle, and it
  keeps the attestation credential, because a foreign token heals itself through a `401`. It is
  best-effort per step. An upload cycle already running may write rows back afterwards, and a second reset
  clears them. The runbook is in `rig-channel` (`changes/archive/2026-08-24-retire-launch-env-triggers`).
- `:test:control` holds `RigClient` (a `409` is a typed `Reply.Refused`) and the JVM host's tests. Those
  tests run over **both** backends and prove the protocol is faithful to the application: routes, state
  encoding, the vocabulary advertisement, refusals. The only untested code in the channel is the iOS
  gallery seeder and wiper, for the reason its build file gives.

### Launch-time adapters

**A rig build of the real iOS app — on a simulator or the phone — runs with some systems mocked and the rest real,
chosen at launch** (11h): its launch adapters. The systems are the mocks of `MockDevice`, one key each
(`MockedSystem`): `backend`, `library`, `files`, `databases`, `preferences`, `keychain`, `integrity`, `crash-reporter`,
`process-info`, `network`, `clock`, `wake`, `background-time`, `extension-registry`, `upload-queue`, `upload-session`,
`downloads`, `lifecycle`, `links`, `push`, `screen`, `system-ui`. Not among them: the development controls (always the
channel's), the extension's entry port (the channel already plays its invocations through the real adapter) and
MetricKit (`/device/process-metrics` feeds the real handler). Code: `:test:launch-adapters`.

- **The adapters file** is `rig/adapters` in the App Group — the adapter choice: one `system=mock|real` per line, `#`
  comments, a missing system real. `POST /device/adapters` takes it as the body, checks it, writes it and **exits the
  app**; the next start of any process — a manual launch, a BGTask, a URLSession relaunch, a silent push, the upload
  extension — reads it, and the app and the extension compose over ONE adapter choice. `POST /device/adapters/current`
  reads it (and names the folder it lives in); `POST /device/adapters/clear` deletes the `rig/` folder — the choice
  and every mocked system's state — and exits. On a simulator a script may write the file itself, into the `folder`
  that verb names (`simctl` does not list an ad-hoc-signed app's App Group). On the phone the App Group is not
  reachable over USB, so only the verb writes it, and the relaunch is the `snapsync-device` launch step. The verb
  refuses while the device is a member of an event: the membership would be carried into another set of systems —
  reset first.
- **Only the rig build reads it.** The rig variants of `platformAdapters()` and the extension's `extensionPorts()` read
  it through a real files adapter of their own (never a port the choice may have mocked) and hand each root its ports;
  production has no code for it and links no mock (`MockContainmentTest`).
- **Coherence** (`AdapterChoice.incoherence`, JVM-tested beside it) — a choice that breaks a rule is refused, one
  reason per rule: a mocked backend needs mocked transfers (upload queue, upload session, downloads), push service and
  Secure Enclave; a mocked Secure Enclave or upload queue needs the mocked backend (placeholder proofs and bytes never
  reach the real one); a mocked library needs mocked uploads (a mocked photo has no bytes); and a REAL extension
  registration keeps every system the extension writes real (backend, library, upload queue, files, databases,
  preferences, keychain) — the operating system runs a real registration in the extension's own process, and a mocked
  system would then have two writers. Mock the registration and the channel invokes the extension inside the app
  (`/os/photokit-ext/processRawValue`). Anything no rule names may be chosen freely — real photos with a mocked backend
  included.
- **Refused means nothing composes.** A choice that does not parse, is incoherent, or whose state does not restore makes
  the launch compose NOTHING: every entry point still reaches its real adapter, which completes the OS's handler at
  once; the channel's server starts, `/health` says `adapters=REFUSED …` and every route that would reach the app
  answers `409` naming why, the adapter verbs aside. Never a fall-back to real: a run believed mocked that reached a
  real system could write to the shared `snap-sync-dev` zone.
- **Mock state persists** in `rig/state/<system>.json` (the app writes each changed system every 500 ms and before an
  exit) and `rig/databases/` (a mocked database is a file). The extension, in its own process, never writes: the
  coherence rules keep every system it would write real there. A relaunch finds what the last launch left.
- **Everything mocked is operator-driven**: nothing a mock plays happens on its own. A mocked wake cancels the real
  heartbeat and answers a real one at once; a mocked upload session holds the app's uploader's transfers until the
  channel lands them (`device/uploads/complete`), as on the JVM root, and a mocked upload-job queue holds the
  extension's until `device/jobs/complete`. `/os` delivers through each system's mock where
  it is mocked (`ChosenEntryDriver`) and through the iOS adapter otherwise; `device/os-record` reports the mocked
  systems' part. A mocked download stages a 16×16 JPEG, which a real library imports.
- **Memory**: the extension, in its own process, restores only the systems its choice mocks — under the rules above,
  none that hold photos or bytes — so a launch with mocked systems adds no photo state to its 32 MB.
- **Measured** 2026-09-28 on an iOS 26 simulator and the SE2 (iOS 26.6.2): real photos with a mocked backend —
  create, join, a cycle through the mocked queue, the objects in the mocked backend, all surviving a relaunch (a
  SIGKILL on the phone); an all-mock launch on the simulator; a misspelt file composing nothing.

### The Android emulator host

The Android rig build (`:app:android` under `-Psnapsync.rig=true`) serves the same protocol from inside the app on an
emulator or the A40, reached over `adb -s <serial> forward tcp:<host port> tcp:18099` (load `snapsync-android`). It reads its adapter choice from
the adapters file as the iOS app host does (`device/adapters*` write it, and the app exits), with two differences that
both come from Android not having every real adapter yet (today it has the screen, the lifecycle, the clock, the
storage, attestation, the backend, links, the photo library, the system UI, the wakes, the background-time holds, the
upload session, the downloads, push and the crash reporter). **No file is not all real**: it is every system mocked but
the screen and its foreground life, fresh in memory at every start — what every launch without a file has always
composed. And **a choice may leave real only the systems Android has an adapter for** (`AndroidRig.kt`'s list; naming
another `real`, or omitting it, refuses the launch, which then composes nothing). A file-chosen launch saves its mocks'
state beside the file, as on iOS, because a real store then outlives the process. It refuses `device/relaunch`, `device/reinstall` and the
upload extension's `/os` verbs (Android has none), and is contract host `ANDROID_EMU` in `GET /device` — the host the
device tests run on too. The `journeys (android)` CI job drives it over every real adapter Android has
(`scripts/android-journeys`). A build without the property composes every real adapter and starts; its crash reporter
starts only on a distributed build, which carries a DSN.

The Android adapters' contract bindings (`:adapter:android`'s `src/androidDeviceTest`) are device tests, never host
tests (that is the JVM again), at the app's own minSdk (30 — D8 writes a backtick name's spaces only from DEX 040). An
ASCII apostrophe or a comma in a backtick name is never representable in DEX: write `’`, and `—` for the comma. `./gradlew androidPlatformTest` runs
them on a Gradle-managed Pixel 6 / API 36 emulator it boots and tears down itself (`snapsync.android`,
`android.testoptions.manageddevices.emulator.gpu=swangle_indirect`); `./gradlew connectedAndroidDeviceTest` on an
emulator you booted. Either way the build serves `scripts/transfer-fixture.py` on the host for the run and passes the
address an emulator reaches it at (`http://10.0.2.2:8123`) as the `fixture` instrumentation argument the upload
contract needs.

**A force-stop is checked from outside the process** (`scripts/android-force-stop-check`, non-gating — no CI job runs
it): a contract clause runs inside the app, and a force-stop kills the process that would observe it. The script
installs the rig build, chooses the real WorkManager wake, joins a receive-only event over the mocked backend and reads
JobScheduler's own record (`dumpsys jobscheduler`): the idle heartbeat pending, gone after `am force-stop`, pending again
once the app is opened. The iOS force-quit has no such check: only a person's swipe records one, so it rests on Apple's
documented behaviour. Decision record: `changes/timely-background-receiving` (D8).

**Photos on the emulator are seeded by the process that reads them** (measured 2026-09-29): MediaStore hides a photo
the SHELL owns (`adb push`, `UiAutomation`) from every other app, so a photo-library contract inserts its own fixture —
which it reads with no grant and deletes with no confirmation — with the capture date in the file's EXIF as well as
`DATE_TAKEN`, since publishing re-derives it. For the same reason an end-to-end run seeds through the rig's
`device/gallery/seed`, which inserts the app's own photos into `DCIM/Camera` where the library is real. The grant is the
test process's own and revoking one kills it, so `NO_GRANT` clauses stay the iOS test executable's; the album writes
are iOS's too (Android files no photo into an album — `GRANTED_SEEDED_ALBUMS_WRITABLE`). The mocks'
SQLite reaches the platform through a context their AAR's `MockAndroidContext` provider takes at process start.

The client compiles against `model/`, presentation and `feature/`, never `ports/`, `flow/`, `compose/` or
the host, and `ReadModelImportsTest` confines its `feature/` references to the `readmodel` packages. **That
compile boundary and that gate are the read-model rule.** Decision record:
`changes/archive/2026-09-23-add-rig-jvm-host`.

---

## 7. Integration tests and journeys

### `:test:integration`: seam → observable outcome

Each test (`rigTest { … }` in `Rig.kt`) starts a **fresh in-process JVM host over the backend mock**, drives
it only through `RigClient`, and closes it. Tests run sequentially. A test cannot name a mock, a port, a flow or
`compose/`: the module does not compile against them.

Tests use **mocks only**, with no per-test real system. The port contracts are what justify trusting the
mocks, and the journeys exercise the real systems.

**Assert observable outcomes only:**
- the projected `UiState`, **required** when the seam reaches presentation,
- what a system outside the app records:
  - backend objects, union, manifests, the push registration it stored and how many, event existence and name,
    departed members, publish counts,
  - the photo library (census, albums, original filenames),
  - OS upload jobs,
  - the operating system's record of the app (`device/os-record`): completion handlers released, heartbeat requests,
    the heartbeat pending now and its cadence,
    background-time holds, the screen, the selection observer, push registrations, the transfer sessions, the
    databases opened,
  - the download staging directory,
  - reporter dumps,
  - pushes sent,
  - logs.

**Never assert** ledger or download-store content (the protocol no longer carries the ledger), in-memory feature
state, or call counts on a mock whose real system records nothing
a person could read. If the only possible assertion is internal, the test belongs elsewhere, as a unit test
in its zone or a contract clause — which tail unit ran is `TailRunnerTest`'s, what a ledger row holds is
`UploadCycleTest`'s, the extension's per-invocation credential re-read is `ExtensionEntryTest`'s (`:adapter:generic:mock`,
over the composition). A seam with no presentation effect is fully covered by its outside outcomes: a
selection-policy exclusion is proved by missing bytes and a missing manifest entry.

**Each test claims the requirements it verifies.** A test that verifies a requirement of `openspec/specs/`
carries `@Verifies(spec = "…", requirement = "…")` (on the class when every test in it does, on the function
otherwise, repeated for several; `scenario = "…"` narrows it). The journeys carry it too. `VerifiesGateTest` holds
every claim to a heading that exists; `./gradlew :test:architecture:verifiesReport` shows the requirements no test
claims (`docs/architecture.md`, "Tests verify requirements").

A test that needs a missing lever or read adds it **to the vocabulary, classified on both hosts**. It
never reaches past the protocol.

**What it credits.** The suite is instrumented, and its coverage counts toward exactly three modules: the wiring —
`:domain:compose`, `:domain:host` and `:app:jvm` (`docs/architecture.md`, "Coverage"). The wiring graph is not
unit-tested by law and is smoke-tested here, so this suite is the test written for it. Everything else it runs —
model, feature, services, presentation — it credits nothing: those modules reach zero through their own unit tests,
never through a scenario that happens to pass through them.

### Journeys: the contracts' safety net

A few end-to-end runs with **everything real**: the rig build on **one** simulator or emulator, `api/` served
locally, and the real photo library — the same journeys on iOS and Android. They cover creating and joining an event, the app's own photos reaching the
backend and the union, and the app receiving another member's photos into its library. That other member is
played by the journey itself (`Member`), over the backend's **public HTTP surface only**, with real JPEG bytes,
so the app's download ends in a real photo-library import. It is never a JVM-host lever or a private route,
because a member the backend could tell apart from a device is not a member. One simulator, because a second
fresh simulator's first-boot work swamped the hosted runner (it tripled the job and made the journeys flaky).

- Source set `journeys` in `:test:integration`, task `:test:integration:journeys`. It is **outside
  `build`**.
- They run in the `journeys (ios)` CI job (`scripts/sim-contracts` boots the simulator and the backend, after the
  simulator app's contracts) and the `journeys (android)` one (`scripts/android-journeys`, on an emulator the job
  boots, over every real adapter Android has — only the crash reporter is mocked, and the iOS-only upload-job queue
  and extension registration). There they run on a bare JVM, `java … org.junit.runner.JUnitCore app.snapsync.journeys.Journeys`
  over the classpath `:test:integration:journeysClasspath` writes at compile time, with
  `-Dsnapsync.journey.appA|backend`. No Gradle is alive next to the simulator: a Gradle daemon and a test JVM
  starting there pushed the runner into swap, and the app timed out a response the backend had sent 9 s
  earlier (run 36173548419). Locally, `:test:integration:journeys -Psnapsync.journey.appA|backend` runs the
  same test. They gate merges. On a failure the job prints the failing assertion's message in its log.
- They **fail, never skip**, when an address is missing.
- **Read a journey failure first as a missing contract clause**: the mocks lack a behaviour. Add the
  clause, and the mocked suite then covers it.

Decision records: `changes/archive/2026-09-24-integration-over-control`,
`changes/archive/2026-09-25-one-simulator-journeys`.

---

## 8. The desktop harness (`:app:desktop`)

A Compose Desktop window, `./gradlew :app:desktop:run`: **test equipment, not a product**, and the one place every UI
state is reviewed without a device. It mounts the real `:ui:screens` `StatusScreen` in a phone frame (about 390×844,
`PhoneFrame`, through `ScreenPane`). The control pane is raw Material 3, never `App*`, holds no logic, and carries no
tests.

**No UI state is forged.** The forge — a harness and an iOS binary that fed the status screen canned inputs — was
deleted in 12: it could show a frame the app never reached. Every state is reached here through the mocks' levers,
including the screens the app shows only while it waits. The mocks answer at once, so those exist for a moment no
reviewer can catch; the inspector's **Hold** switches keep the backend's event-details load, create, join or leave —
or the library's enumeration — unanswered until released (the same levers are `/device/backend/hold` and
`/device/gallery/hold-enumeration` over the rig, `HeldScreenIntegrationTest`).

**World.** The app is the one the JVM root composes (section 5) over a fresh set of mocks. The phone pane
(`ScreenPane`) renders exactly what the app showed on its `Ui` port and hands every tap back as its `UiIntent`: the
harness builds no status host, so the screen is the app's own, commands and all — create, the join gate, leave,
reconfigure, rename, the bug report. The inspector plays the mocks' **operator faces** through a **single
controller**, one named method per control, with no mutation inside composables, and reads only them and the
screen (it is held to `ReadModelImportsTest` like the other consumers). It offers:
- presets (Clean, Enrolled, Fresh join, Re-provision (dedup), Foreign download). Each builds a fresh app over fresh
  mocks, because deposited state cannot be un-set, and joins through the app's own create form and join gate.
- **Invoke extension**: the extension's `process()` through its entry port, then the silent push a member's upload
  makes the backend send, whose receiver is the download reconcile. **Heartbeat** fires the scheduled wake.
- gallery and backend columns, the upload queue (Complete / Fail with an `UploadError`), downloads
  (Stage), failure levers, and Create event with a past or future start.
- an engine console that streams Kermit output, each invoke's `CycleResult`, and what the share sheet was handed (it
  is copied to the clipboard).
- a Light/Dark phone toggle.

Every mutating action ends with the operating system's foreground entry — the phone's own status refresh — and the
inspector's snapshot also follows the app's own changes (an import landing, the screen moving). The app is composed on
a serial, non-UI scope, matching the device shell's dispatcher lanes.

With `-Psnapsync.attach=<url>` the world harness instead **mirrors a remote rig host** (JVM host,
simulator or phone). It renders `StatusScreen` from the host's wire `UiState` and sends taps as `/user`
intents. A tap with no intent is inert and logged. Forward the port first, because hosts bind loopback
only.

**Headless:** to click or screenshot the harness without a display, load `.claude/skills/ui-harness`
(`:test:harness-driver`, `driveWorld`). **Never** use `java.awt.Robot`, and never capture
the real screen `:0`. It raises a portal consent prompt and blocks until someone answers.

Decision records: `changes/archive/2026-07-03-add-full-stack-harness`,
`changes/archive/2026-07-31-add-bug-report-description`.

---

## 9. Backend tests (`api/`)

```bash
cd api
deno task test     # full suite, offline
deno task lint     # includes the complexity ceiling (src/lint/complexity.ts)
deno task check    # type-checks src/, src/dev/, src/scripts/, src/lint/
deno fmt --check
deno task schema:check   # the generated schema.sql is fresh
```

- Tests (`api/test/*.test.ts`) drive the Hono app through `app.request()`, with upstream `fetch`, the
  database and config injected. The database is a migrated in-memory SQLite (`test/support/db.ts`, with
  `enrolDevice` for the attestation row every device-scoped write needs).
- **`deno task test` has no `--allow-net`, on purpose.** That missing flag is what guarantees no test
  reaches the real bunny zone or database: a network call fails as a permission error instead of becoming
  a live request. Never add it.
- `test/dev/` pins the filesystem storage shim to the same bunny assumptions the mocks encode. It proves
  shim ≡ mocks, **not** that either matches bunny.
- Migration safety (migrate, don't drop; refuse when narrowing; name columns in row copies) is gated by
  `migrations.test.ts`. The rules themselves are in `docs/deployment.md`.
- Every task resolves a deployment first (`deno task config`), and there is no default.

**The Kotlin side also tests the backend.** The `Backend` port contract's Live binding in `build` runs the
real `api/` through `:test:edge` (`serve.ts --ephemeral`: loopback only, fresh filesystem store). The
journeys run it too. A backend change that breaks a client contract therefore fails `./gradlew build`, not
only `deno task test`.

### The local rig

`deno task dev:local` (127.0.0.1:8080) and `deno task dev:tunnel` (adds a cloudflared quick tunnel for a
physical device). This is dev infrastructure and does not gate anything. It composes the **same**
`createApp({ config, db, fetch })` as the deployed edge, over:
- a filesystem `fetch` in place of bunny storage. Keys map 1:1 to `api/.localstore/objects/<key>`, so
  `find` is the oracle for bytes.
- a real SQLite file at `api/.localstore/api.db`, running the same statements production runs. It
  migrates on start.

Facts worth knowing:
- **Reset is `rm -rf api/.localstore`**, which clears both halves. Deleting only the objects leaves events
  whose photos are gone, and that looks like "downloads are broken" with no error.
- A `filesystem` deployment declares **no database credentials**, so a dev run structurally cannot reach
  the production store.
- The attestation gate stays on. A request with **no** `authorization` header gets a dev token, so bare
  `curl` works. The same fallback **enrols** the device the path names, because a simulator can never
  attest and would otherwise get `401` on push registration forever. A physical device recovers by itself:
  the `401` drops its token and it attests for real.
- `src/dev/` cannot ship: the bundle is rooted at `src/main.ts`, which imports nothing under it. Running
  `src/main.ts` directly targets the **real** zone and database.
- Crossing backends with a device build requires `POST /device/reset` (section 6).

Pointing a device or simulator build at the rig, and the step whose omission fails silently, are in
`.claude/skills/local-backend`.

---

## 10. Measured on hardware, not asserted

Behaviour that only hardware shows is **not** asserted by unit tests. Such a test only restates its own
fixture. Instead:
- **What a host can reach is stated by the contracts' bindings** (their literal reachable-state sets), not
  by smoke tests that call the platform without asserting anything.
- **Reachable on the simulator app, so asserted live there:** PhotoKit under a full grant, asset and album
  creation, imports (including Photos accepting a received motion photo, taken apart, as ONE Live Photo:
  `LivePhotoImportContract`), and both app-process `URLSession` transports over the default session
  (everything except the background session's lifecycle).
- **Device-only, reached through recordings replayed on every build:** the upload-job subsystem (recorded
  *inside the extension*, where production calls it), extension registration including its refusal under
  a partial grant, `BGTaskScheduler`, the Keychain and App Attest.
- **Device-only with no contract:**
  - the background session's lifecycle (survival across suspension, relaunch delivery, reattachment,
    invalidation),
  - the background download session following a `302` to the bytes, and resuming an interrupted
    redirected transfer from the redirect's TARGET (never the original URL) — so an expired target ends
    that transfer as a `403` until the next reconcile. Measured on the SE2 (iOS 26.6, 2026-10-05) by a
    one-off run of `DownloadContract` in the rig app over an https tunnel; `IosDownload` has no
    `URLSession` seam, so no recording can carry it (`changes/incremental-union`, design "Measured").
    Android's `DownloadManager` half is a live clause (`A_REDIRECT_IS_FOLLOWED_TO_THE_BODY` on
    `ANDROID_EMU`); its retry goes back to the original URL. Re-measure at the next iOS major,
  - APNs delivery,
  - the limited-access alert's arming,
  - protected data before first unlock.

  These stay as **recorded on-device measurements with an expiry trigger** in the adapter's KDoc or the
  decision record (for example "re-measure at the next iOS major"). No clause is written against a target
  that cannot show the property.
- A remaining smoke test is allowed only for a device-only surface no contract binds yet, and it names the
  change expected to replace it.
- What an Apple API *declares* (enum cases, nullability) is read from the Kotlin/Native platform klibs
  and pinned by `PlatformVocabularyPinTest`. Do not use a copy of the constants. See CLAUDE.md, "Reading
  the Apple SDK from Linux".
