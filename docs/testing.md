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

Testing is moving with the thin-ports re-cut. The direction is §11.

---

## 1. The checks

| command | what it is |
|---|---|
| `./gradlew build` | **The canonical check.** Compiles every target, runs every JVM test and every gate. Needs no display. Needs `deno` on `PATH`. |
| `./gradlew iosSimulatorArm64Test` | The same shared test sources compiled to Kotlin/Native. macOS only (CI `macos-26`, or `ssh-mac-build`). |
| `./gradlew compileIosMainKotlinMetadata` | Linux proxy for the iOS source sets. A **compile, not coverage**. Never describe it as a test. |
| `cd api && deno task test` | The backend suite. Offline by construction. |

Both `build` and the simulator run gate merges. The simulator run is the **only** place Kotlin/Native-only
breakage shows up: code the JVM accepts and Native rejects passes `build` and fails there.

**Deno is a hard prerequisite of `build`.** `:adapter:generic:app:jvmTest` runs the `Backend` port's
contract against the real `api/`, started as `src/dev/serve.ts --ephemeral` (loopback only, filesystem
store, no bunny zone reachable). Without Deno those tests **fail naming it**. They never turn into
`NotRunHere`. `api/src` is declared as an input of that task, so a backend-only change re-runs them
(`changes/archive/2026-09-23-contract-backend-clients`).

**Build-property-gated source sets are compiled and run by CI.** Code built only under
`-Psnapsync.rig=true` or `-Psnapsync.forge=true` is invisible to `build`. That is what compile-time
containment means, and it is also a blind spot. `build.yml` therefore runs
`compileIosMainKotlinMetadata :domain:presentation:jvmTest -Psnapsync.rig=true -Psnapsync.forge=true` on every
push. The gated **tests** run too, not only the main code. The forge test set once stopped compiling and
stayed broken for weeks while a main-only compile step stayed green beside it. `ForgeStatusHostTest`
checks that each marketing preset reaches its frame through the real reduction, and the honesty of the
App Store screenshots depends on that. Do not narrow that step back to a compile.

---

## 2. Where tests live and which targets they run on

### A test lives with the code it tests

A test goes in the module that owns the logic. Suppose a test would need a library or platform API that
the module does not have. That shows the logic is in the wrong module. It is **never** a reason to add the
dependency to the module.

### Logic tests go in `commonTest`

`commonTest` runs on every target the module declares. The JVM is the fast loop and `iosSimulatorArm64`
is the target that ships. A platform test source set holds only what that platform's toolchain cannot run
anywhere else. Where two targets have equivalent implementations, they share one port contract
(section 4).

`commonTest` is where a test *goes*. It is not a reason to *move* code. Do not move a platform-to-neutral
translation into `model/` just to reach the faster JVM loop. The translation stays beside its inputs, so
its test asserts against the platform's own symbols rather than a copied constant (`docs/architecture.md`
spec, "Zones inside the core").

Non-`commonTest` source sets that exist today:

| source set | why | exception? |
|---|---|---|
| `:adapter:ios:ext-safe`, `:adapter:ios:app-only` `iosTest` | these modules have no JVM target, so `iosTest` **is** their common set | no |
| `:adapter:generic:app` `jvmTest` + `iosSimulatorArm64Test` | JVM-driver and native-driver halves of one storage contract; together they cover both targets | no |
| `:adapter:generic:app` `jvmTest` (backend Live bindings) | they launch `api/` as a local process, which a K/N test executable under `simctl` cannot do. Nothing is lost: the clients are `commonMain` code, and their K/N compile is covered by `commonTest` | yes, stated in the build file |
| `:ui:components` `jvmTest` | Compose component tests with no iOS counterpart | yes |
| `:test:architecture`, `:tools:diagrams` `src/test` | they read the repository's own text | yes |
| `:test:integration` `src/test` | drives the JVM host of the control channel, which is a JVM server. The lost coverage (the composed graph running on K/N over fakes) is partly covered by the core's own simulator tests and by the simulator app under the contracts and journeys | yes, stated in the build file |

Where a target is genuinely skipped, the build file that declares the source set **says which coverage
is lost and why**.

### Fake-driven feature tests live in the fake module

Feature tests that drive `:domain` through the honest in-memory fakes live in
**`:adapter:generic:fake`'s `commonTest`**, not in `:domain`. `:adapter:generic:fake` depends on
`:domain`, so a test edge back would be a dependency cycle, and one module cannot depend on another
module's test source set. The fakes' own contract bindings live there too, because the fakes are
`internal` and only their own module's tests can build one in a chosen state.

So `:domain`'s `commonTest` holds only tests over pure functions or hand-written local doubles. **A
feature's tests may be split across `:domain` and `:adapter:generic:fake`. Look in both.**
`:adapter:generic:fake`'s `commonTest` is a test host that can see more than any other consumer. That is
a property of `internal`, not a gate. The fake-honesty gate checks only what the fakes expose in main.

### No `:app:*` module has a test source set

`:app:ios`, `:app:ios:extension`, `:app:ios:forge` and `:app:desktop` declare no tests. If behaviour there
needs coverage, **move it** into `:domain` or an adapter and test it there.

The reasons differ, so keep them separate:
- `:app:ios` and `:app:ios:extension` are **wiring-only by gate**. `detektAppShell`,
  `KotlinShellGuardTest` and `SwiftShellGuardTest` forbid decisions in shell source (see
  `docs/architecture.md`). "Nothing worth testing is there" is what makes leaving them untested safe.
- `:app:ios:forge` links no live graph at all.
- `:app:desktop` is test equipment. It is exempt from the shell laws and is exercised, without gating,
  through `:test:harness-driver`.

**Remaining risk, which must be stated wherever shell correctness is relied on:** the gates do not catch
*mis-transcription*. A forwarding with no conditional that names the wrong collaborator passes every gate.
Most of this risk has been removed by moving code:
- OS callbacks cross an inbound port (`PlatformEntries` / `ExtensionEntries`). The core implements the
  port, the shell delegates to it through compiler-generated delegation, and a port contract covers it on
  JVM and the simulator.
- The tap → intent table is one factory in `:ui:screens`, and it is click-tested there.

What is still uncovered:
- Swift argument-level forwarding: the right entry called with a wrong argument of the same type. (The one
  `BGTask` registration forwards the OS-delivered identifier to `onBackgroundTask`, and its expiration handler
  forwards the same identifier to `onBackgroundTaskTimeUp`, so a copied registration block cannot misroute a
  task or its expiry.)
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
| `:test:world` | the controllable in-memory world that runs the real core (section 5). Consumed by `:app:desktop` and the JVM rig host. Hosts the inbound ports' contract bindings. |
| `:test:contracts` | the contract mechanism and every port contract (section 4). The only module whose **main** code depends on `kotlin-test`. Links into the app only under the rig property. |
| `:test:rig` | the control channel: one HTTP protocol served by an iOS app host and a JVM host (section 6). The only module allowed `ktor-server-*`. |
| `:test:control` | the typed JVM client of that protocol (`RigClient`), plus the JVM host's own tests. |
| `:test:integration` | the seam → UI-state integration suite, driven only over the protocol (section 7). Also the `journeys` source set. |
| `:test:edge` | `LiveEdge`: the real `api/` as a local Deno process, one per test JVM. Used by the backend contracts' Live bindings and by the world's real-backend option. |
| `:test:architecture` | JVM guards over the repository's text. What each guard checks: `docs/architecture.md`. |
| `:test:harness-driver` | serves either desktop harness headlessly over HTTP. Dev infra, not a gate (section 8). |

---

## 4. Port contracts

A **port contract** states what every implementation of a port must do, as executable clauses. The same
clauses run against the honest fake and against each real adapter, so a double cannot quietly behave
differently from the system it stands in for. **The contract code is the specification of the port.** No
doc or spec restates its clauses. This section covers the testing mechanics. Which ports are contracted,
and why that matters for architecture, is in `docs/architecture.md`.

Decision record: `changes/archive/2026-09-22-establish-port-contracts`. Later extensions are listed at the
end of this section.

### Clauses, states, bindings

- A **contract** is a hand-written list of clause values in `:test:contracts` `commonMain`. Each clause
  has a stable id, the **state** it needs, and a body that asserts with plain `kotlin.test`. Nothing ever
  generates or rewrites a clause from observed behaviour. A person decides.
- Each contract has its own **state vocabulary** beside it. Production code never gains it.
- A **binding** pairs one implementation with one **host** and has a kind: `Fake`, `Live` or `Replay`.
  It declares, **as a literal**, the states it can reach. `create(state)` returns a fresh implementation
  already in that state, or `Unreachable(reason)`. Each clause gets a fresh instance. The runner checks
  the declaration: a declared state answered `Unreachable`, or an undeclared one answered, is `Failed`.
- Where an outcome cannot be read through the port itself, the clause also gets an **observation
  handle**. Examples: an inbound port that returns nothing, a refusal that reaches the app only through
  the HTTP interceptor, or what a reporter transmitted to an endpoint. The handle reads **outcomes**
  (state reached, objects landed), never which collaborator was called.
- A scenario that needs the implementation to change state mid-run is not a clause. It is an ordinary
  fake-backed test of the project's own logic.
- Clauses that share a system that cannot be reset (the simulator's photo library) are isolated by
  **addresses derived from the clause id** (capture-date windows, titles, fixture routes). They never
  delete what they seed.

Where bindings live: beside their implementations.
- Fakes: `:adapter:generic:fake` `commonTest` — the in-memory `Backend` mock (`BackendContractBindingTest`) and
  the in-memory `DeviceIntegrity` among them.
- The `Backend` port is ONE contract (`BackendContract`, split into part files by route area for size), held by
  three bindings: `HttpBackend` against the real `api/` (`Live`, the coverage), `HttpBackend` against the mini-edge
  and the in-memory mock (both `Fake`). Its clauses state each route's answers in the backend's own vocabulary —
  statuses and bodies — because what they MEAN is the services' decision above the port, tested beside those
  services (`CredentialedBackendTest`, `BackendServicesTest`). A binding enters states through the backend's public
  surface: `EdgeSetup` over HTTP, `PortSetup` through the port itself for a backend with no HTTP surface.
- The live backend, and the JVM `Databases` adapter with the storage services bound through it: `:adapter:generic:app`
  `jvmTest`. The storage services live in `:domain:services`, but their contracts (`LedgerStore`, `DownloadStore`)
  are bound **through** the service over each platform's real `Databases` adapter, beside that adapter: a
  `:domain:*` build file names no module, and the contract is a claim about the service over the real database.
- The JVM `Files` adapter, and the file-backed services' contracts (config, manifest, staging, log tail) through the
  services over it: `:adapter:generic:app` `jvmTest` — so every `build` runs them, not only CI's simulator job.
- The iOS `Databases`, `Files` and `Preferences` adapters, and the storage services through them on Kotlin/Native:
  `:adapter:ios:ext-safe` tests.
- `PlatformDeviceId` has **no contract**, on purpose: its only implementation answers a constant `null`, and a
  clause must run against a real implementation somewhere (`ContractCoverageTest`). The identity service's test covers
  "`null` ⇒ random" with a stub. The contract lands with the first adapter that answers an id (Android).
- The storage services' fake-driven tests (their answers to what no contract state enters): `:adapter:generic:fake`
  `commonTest`, over the storage mocks.
- The mini-edge and the world's transfer doubles: `:test:world` `commonTest`.
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

A real adapter still counts as real when its location or configuration is injected: a temp directory
instead of the App-Group container, a loopback reporting endpoint instead of the baked DSN, or the
simulator's default `URLSession` instead of the device's background session. **The one lookup it bypasses
is not covered.** A clause about that lookup being *unavailable* is entered through the production default,
so the host's own answer drives it. An injecting constructor is `internal` to the adapter's module.

Where production reaches a port only through a composition that owns part of the port's contract, the
Live binding binds **that composition**. An example is a grant-aware wrapper that answers "not readable"
where the bare adapter would answer "empty". An adapter bound bare must pass the whole contract itself.

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

A backend a binding launches is **not a host**. It is part of the implementation. The real `api/` makes a
binding `Live`, and the mini-edge makes it `Fake`, even though the client code is the same. An endpoint
the binding stands up only to *receive* (the Sentry ingest), or to *answer with a status the clause chose*
(the transfer fixture `scripts/transfer-fixture.py`, an upload receiver answering 200/403/500), is a clause
input or observer. It does not change the binding's kind. A receiving endpoint must not be more lenient
than production on a limit production is measured to enforce.

No host can enter "protected data unavailable before first unlock". A clause conditioned on it has no real
host, so it is not written.

### How each host is run

- **JVM, `IOS_SIM_KEXE`:** ordinary test tasks in `build` and `iosSimulatorArm64Test`.
- **`IOS_SIM_APP`: live on every push.** The `ios-contracts` job in `ios.yml` builds the app under
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
- Clause inputs are deterministic: fixed values, a fixed id generator, and addresses derived from the
  clause id.
- A missing recording, or a missing block for a declared-reachable clause, is `Failed`, not `NotRunHere`.
- Files: `test/contracts/recordings/<Contract>@<HOST>.rec`, or `<Contract>@<HOST>.<GRANT>.rec` when the
  binding declares a grant (for example `UploadExtensionRegistry@IOS_DEVICE_APP.LIMITED.rec`). Each file
  has a provenance header, then one `[CLAUSE_ID]` block per clause, sorted, of `call -> answer` lines. A
  run overwrites the file. **Commit it unedited.** Git holds its history, and an iOS update that changes
  an answer shows up as a diff on the same host.

**Recording.** Build with `-Psnapsync.rig=true`, install on the entitled device, and call
`POST /contract/<name>` over the rig. It answers with the recording text and the live outcome table. A
production build contains none of the contract module, the bindings or the recorder.

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

## 5. The world (`:test:world`)

The world runs the **real** platform-agnostic stack (upload cycle, sync engine, join-time share-set load,
manifest producer, download orchestration, status sources, creation) against controllable in-memory
infrastructure. The whole system, upload *and* download, can then be observed on JVM and
`iosSimulatorArm64` with no device. The code that most needs coverage used to run only inside an iOS
extension. The world fakes the *execution edge* instead of the logic, so that code runs anywhere
(`changes/archive/2026-07-03-add-harness-world-model`).

Targets are `jvm()` and `iosSimulatorArm64` only. It never links into a shipped framework.

### What is real and what is doubled

- **The composition is production's.** `World.core` and `World.statusHost` come from the same
  `snapSyncHost(scope, AppPorts)` the iOS shell calls. The upload cycle comes from the same `uploadCore`
  the device tiers call. The world wires no status host, subscription, HTTP callback or feature of its
  own. It binds **no port to a body that stands in for core machinery**: the provision, the attestation
  refresh and push registration all run for real. A mirror of a composition root drifts silently, and a
  mirror that is *more* correct than production stays green while the defect ships.
- **Only the edges are doubles:** `BackgroundTransfer`, `DownloadTransport`, the `Gallery`, the storage
  seams and the HTTP client. The services over them (the gallery's discovery, presence and albums; the stores) are
  the real ones.
- **Honest fakes live in `:adapter:generic:fake`; levers live in `:test:world`.** A lever (a settable
  cell, a failure switch, an inspection list) goes on a world wrapper that owns the fake's
  constructor-injected state (`WorldGallery` — its import script is `WorldImports` —, `RecordingDownloadStore`). It is
  never a public member of the fake, and the fake-honesty gate enforces that. For a contracted port, the
  world uses the contract-bound fake, wrapped, and never a second levered implementation.
- **The world's transfer doubles are contract `Fake` bindings** (`TransferContractsTest`). A clause the
  double fails is fixed in the double. The PhotoKit tier's one free retry, which no real host shows, stays
  uncontracted.
- **Attestation is inert by default** (`isSupported()` false, as on a simulator and in the extension). An
  opt-in lever turns it on. The world does not model the token gate or the `401` for an unattested
  device-scoped write.

### The backend: one seam, two implementations

- **Mini-edge (default, every target):** an in-memory backend store served through a Ktor `MockEngine`.
  The production `HttpBackend` runs unmodified against it. It serves both API versions (`/api/v1`, `/api/v2`,
  and no prefix means v1), because the backend does. Unmatched routes answer `404`.
- **Real backend (JVM only):** `api/` via `:test:edge`, reached through its **public HTTP surface only**.
  The world never reads the process's storage directory.

The mini-edge is the `Backend` contract's `Fake` binding (`MiniEdgeContractsTest`), reached through the production
`HttpBackend`, and serves the three `/attest/…` routes so an attesting world obtains a token over the port like a
device does. On any **contracted**
route, drift from the real edge is a red build, and the fix goes in the mini-edge. It must not be fixed by
declaring the clause's state unreachable. Drift on routes and fields no contract covers is accepted, with
no golden fixture.

Everything the world does to its backend goes through the seam: the shared client, the upload double's
byte `PUT`, operator seeding, and inspection. **`World.neutral`** holds the backend-neutral reads and
levers. Each answers a value or `Answer.Unavailable(reason)`, never an empty value or a silent no-op. On
the real backend these are unavailable: the manifest read, publish counters and the stored manifest
version, backend-offline, the minimum-version lever, the sweep, and the byte wipe/collect.
`provisionMinted` / `addForeignDeviceMinted` return backend-minted ids. `World.store` and the helpers
that take a caller-chosen id are **mini-edge-only**, and on the real backend they fail with a stated error
(`changes/archive/2026-09-23-add-rig-jvm-host`).

Mini-edge fidelity rules that are easy to break:
- Membership is **one record with an active/departed state**. A leave sets `departed`, keeps the assets in
  the union, drops the member from push fan-out, and deletes nothing. Reclamation belongs to the nightly
  sweep alone. Do not model leave-time GC.
- The union includes an asset only when **every** resource it names is uploaded. It spans departed
  members, and an unknown event is *absent*, not empty.
- v2 separates joining (bodyless `PUT …/devices/<id>`, the only route that refuses at capacity) from
  contributing (`PUT …/manifest`, which is refused for a non-member and never enrols). The v2 manifest is
  **ordered by version**: an older version is a no-op that still answers success, and the world counts
  applied and refused publishes separately.
- The v2 per-device listing answers in identity terms and mints no URL.
- Validation mirrors the real routes, including the order of checks (a bad name for a missing event is
  `400` in both). `startsAt` must be canonical. The canned `createdAt` deliberately carries milliseconds,
  so the world is not "cleaner than production".
- The version gate (`426`) is **off until armed**.
- The mini-edge **records the pushes it would send**. It delivers none: the operator fires the
  silent-push entry.

### Operator levers

Nothing auto-runs. **The operator plays the OS.**
- **The tail is real.** The app uploader's units are inert, but every OS entry the world delivers drives them
  through the **real** composed tail runner, and they count what the runner asked (top-ups, walks, transfer
  handbacks), so a test reads which units a wake reached and in what order. A lever parks the next unit, so a
  test can hold a tail in flight and deliver an expiry or a join meanwhile. The tail's import unit is the real
  download drain. The heartbeat the runner re-arms is counted, never run.
- **Background time** is an operator-expirable table of outstanding holds over the honest in-memory
  `BackgroundTime` double: a wake's hold is visible until it ends, and the operator fires "time is up" on every
  outstanding hold (`changes/archive/2026-09-25-own-work-per-wake`).
- **Upload jobs:** create, **complete** (a real `PUT` to the backend's upload route; it suspends until the
  backend answers, and it never deposits store-direct), **fail** with a chosen `UploadError` (drives the
  real retry chain), a settable job-limit (`LIMIT_EXCEEDED`), and inspectable pending/retry/ack buckets
  with per-key creation counts.
- **Downloads:** the real `QueuedPhotoDownloadJobs` over a fake `DownloadTransport`. **Stage** delivers a
  chosen `TransferOutcome`. An unstaged or rejected transfer just stays pending, because there is no
  terminal download error. A stage action is complete when it returns: it awaits the feature's own tracked
  stagings (`awaitOutstandingStagings`), then requests the import of the **composed** tail runner the way a
  staged download does, and awaits it. The fake importer names files through the same shared rule the iOS importer uses, and mints a
  new id per import, so a duplicate is visible.
- **Failure levers:** backend-offline (`502` on listings, union and rename), import failure, one-shot
  gallery-enumeration failure (the total stays *not counted*, never `0`), unreadable walk (no candidates,
  not a full enumeration, so nothing is deleted), unreadable membership (distinct from absent), an import
  that **suspends before or after its commit** and resumes with a chosen outcome, and an **attempt cap**
  that turns a live-lock into a failing assertion instead of a hang.
- **Discovery** is a full enumeration of the in-memory gallery, narrowed only as far as a device's fetch
  predicate can narrow it. It is not narrowed by the full admission. Otherwise an excluded but present
  asset would look absent and have its rows deleted, which is a false deletion that happens only in the
  harness.
- **Devices:** exactly one own device, plus any number of injected foreign devices.
- **Operator `provision()`** performs the join-time ledger load through the same composed share-set load a
  real join runs, **before** setting config. Operator `leave()` runs the real leave edge. It is the one
  permitted synchronous deviation beside the bundle's fire-and-forget leave.
- **`relaunch()`** models process death. The durable state survives: ledger, download store, config,
  secure store, the last-registered push record, staged files, gallery, album map, backend, and both transfer
  doubles' OS-held sessions. Every other cell starts fresh. The relaunched app installs nothing but its push
  registration until its host is touched (as a background relaunch on a device), and that registration
  publishes only a token that differs from the last one the backend accepted. The durable/memory classification sits in **one place** and a world test
  pins it.

### The world boots cold

Constructing the world forces nothing the iOS root does not force at process start. A path that works only
because something else was built first fails in the world as it would on a device. A gate (`WorldBootsColdTest` in `:test:architecture`) fails if
`World.kt` reads a member of the composed core eagerly (in `init` or an eager property). Defer it with
`by lazy`, `get()`, or the function that needs it.

### Tests over the world

`:test:world`'s own `commonTest` is a test tier of its own. It is easy to miss, so a feature can look
untested when it is not. It holds:
- feature tests over the composed world that involve no UI state (upload cycle, sync engine, leave
  cascade, manifest),
- the mini-edge's fidelity tests,
- the inbound ports' contract bindings (`PlatformEntriesContract`, `ExtensionEntriesContract`, on `JVM`
  and `IOS_SIM_KEXE`).

Decision record for its seam, failure, state and concurrency rules:
`changes/archive/2026-09-23-harden-seam-bug-classes`.

---

## 6. The control channel (`:test:rig`, `:test:control`)

**One HTTP protocol, served by two hosts** from the same server, routes and `RigState`:
- the **app host**: the rig build of the iOS app (`-Psnapsync.rig=true`) on a device or simulator, over
  real ports. It is contained at compile time.
- the **JVM host**: a world's composed core and status host, over the mini-edge or the real `api/`:
  `./gradlew :test:rig:runJvmHost -Psnapsync.rigBackend=mini|deno`. No device and no lock needed.

The hosts differ only in the hook they hand the server. They never differ in a route or in the state
encoding. Both bind loopback only.

Verbs:
- `/os` for OS entry points,
- `/user` for user commands at intent level,
- `/device` for state, levers and reads,
- `/health`.

There are **no click, semantics or pixel verbs**. Taps and pixels belong to the UI tier (section 8).

- `/user` is one table both hosts invoke. A command absent on a build answers `409` with the reason. For
  example, the diagnostics send is absent when no reporter is configured.
- `/device` and `/os` form **one closed vocabulary** (`RigVocabulary`). Every host classifies every entry
  as honoured or refused, with a reason. `GET /device` lists that classification. A refused entry answers
  `409` with its reason, never `404` and never a silent success. An unknown verb answers `404`. An entry a
  host leaves unclassified makes `GET /device` fail naming it, and the JVM host's tests then fail in
  `build`.
- The app host refuses world levers (for example backend-offline) with the shared world-lever reason.
- `POST /device/reset` voids this device's durable sync state without telling any backend. Use it
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

The client compiles against `model/`, presentation and `feature/`, never `ports/`, `flow/`, `compose/` or
the host, and `ReadModelImportsTest` confines its `feature/` references to the `readmodel` packages. **That
compile boundary and that gate are the read-model rule.** Decision record:
`changes/archive/2026-09-23-add-rig-jvm-host`.

---

## 7. Integration tests and journeys

### `:test:integration`: seam → observable outcome

Each test (`rigTest { … }` in `Rig.kt`) starts a **fresh in-process JVM host over the mini-edge**, drives
it only through `RigClient`, and closes it. Tests run sequentially. A test cannot name a world type, a
port, a flow or `compose/`: the module does not compile against them.

Tests use **mocks only**, with no per-test real system. The port contracts are what justify trusting the
mocks, and the journeys exercise the real systems.

**Assert observable outcomes only:**
- the projected `UiState`, **required** when the seam reaches presentation,
- what a system outside the app records:
  - backend objects, union, manifests, device config, event existence and name, departed members, request
    counts,
  - the photo library (census, albums, original filenames),
  - OS upload jobs,
  - the download staging directory,
  - reporter dumps,
  - pushes sent,
  - logs.

**Never assert** ledger or download-store content (including the ledger counts in `RigState`, which are
for reading by hand), in-memory feature state, or call counts on a mock whose real system records nothing
a person could read. If the only possible assertion is internal, the test belongs elsewhere, as a unit test
or a contract clause. A seam with no presentation effect is fully covered by its outside outcomes: a
selection-policy exclusion is proved by missing bytes and a missing manifest entry.

A test that needs a missing lever or read adds it **to the vocabulary, classified on both hosts**. It
never reaches past the protocol.

### Journeys: the contracts' safety net

A few end-to-end runs with **everything real**: the rig build on **one** simulator, `api/` served locally,
and the real photo library. They cover creating and joining an event, the app's own photos reaching the
backend and the union, and the app receiving another member's photos into its library. That other member is
played by the journey itself (`Member`), over the backend's **public HTTP surface only**, with real JPEG bytes,
so the app's download ends in a real photo-library import. It is never a world lever or a private route,
because a member the backend could tell apart from a device is not a member. One simulator, because a second
fresh simulator's first-boot work swamped the hosted runner (it tripled the job and made the journeys flaky).

- Source set `journeys` in `:test:integration`, task `:test:integration:journeys`. It is **outside
  `build`**.
- They run only in the `ios-contracts` CI job (`scripts/sim-contracts` boots the simulator and the
  backend). There they run on a bare JVM, `java … org.junit.runner.JUnitCore app.snapsync.journeys.Journeys`
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

## 8. Desktop harnesses (`:app:desktop`)

Two Compose Desktop windows in the one `:app:desktop` module. They are **test equipment, not a product**.
Both mount the real `:ui:screens` `StatusScreen` in a phone frame (about 390×844) through the shared pane
library (`PhoneFrame`, `StatusPane`), so they cannot drift in how they mount it. The control panes are raw
Material 3, never `App*`, hold no logic, and carry no tests. The windows have different titles on purpose.
Only the title tells a forged pane from a real one, and mistaking them means mistaking a drawing for a
measurement.

| | forge | world |
|---|---|---|
| run | `./gradlew :app:desktop:runForge` (a `JavaExec`) | `./gradlew :app:desktop:run` (the Compose `application`) |
| title | `SnapSync` | `SnapSync — full-stack world` |
| what the screen shows | **forged**: any display state typed in through `PanelController` | **emergent**: counts from the real `AppCore` over `:test:world` |
| use it for | reviewing every UI state | watching the real stack behave |

**Forge.** The control panel forges permission (all four states, `LIMITED` included), sync, download,
creation, join-gate, switch-confirmation, not-started and unattested states, plus a Light/Dark toggle for
the phone pane. Rules:
- No preset forges a `SyncHealth` directly. It forges the underlying `SyncStatus`/counts, and the real
  reduction derives the mood. That is what makes a preset a preset rather than a forged answer.
- A sync preset also forces its preconditions (grant, config, attested), so the screen always shows what
  was asked for.
- A permission preset touches only the permission cell, so a forged sync state survives a
  revoke-and-restore walk.
- Affordances that need the live core (leave, invite, rename, bug report) are **rendered but inert**
  here.
- `:app:ios:forge` is the iOS counterpart for the marketing screenshots, built only under
  `-Psnapsync.forge=true`.

**World.** The inspector drives `:test:world` through a **single controller**, one named method per
control, with no world mutation inside composables. It offers:
- presets (Clean, Enrolled, Fresh join, Re-provision (dedup), Foreign download). Each builds a fresh
  world, because deposited state cannot be un-set.
- **Invoke extension**: one `process()`-shaped cycle plus a download reconcile.
- gallery and backend columns, the upload queue (Complete / Fail with an `UploadError`), downloads
  (Stage), failure levers, and Create event with a past or future start.
- an engine console that streams Kermit output and adds each invoke's `CycleResult`.
- a Light/Dark phone toggle.

Counts are pull-based: every mutating action ends with a status refresh, as the iOS foreground refresh
does. Rename and bug report run the **real** commands. The core is composed on a serial, non-UI scope,
matching the device shell's dispatcher lanes.

With `-Psnapsync.attach=<url>` the world harness instead **mirrors a remote rig host** (JVM host,
simulator or phone). It renders `StatusScreen` from the host's wire `UiState` and sends taps as `/user`
intents. A tap with no intent is inert and logged. Forward the port first, because hosts bind loopback
only.

**Headless:** to click or screenshot either harness without a display, load `.claude/skills/ui-harness`
(`:test:harness-driver`, `driveForge` / `driveWorld`). **Never** use `java.awt.Robot`, and never capture
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
  creation, imports, and both app-process `URLSession` transports over the default session (everything
  except the background session's lifecycle).
- **Device-only, reached through recordings replayed on every build:** the upload-job subsystem (recorded
  *inside the extension*, where production calls it), extension registration including its refusal under
  a partial grant, `BGTaskScheduler`, the Keychain and App Attest.
- **Device-only with no contract:**
  - the background session's lifecycle (survival across suspension, relaunch delivery, reattachment,
    invalidation),
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

---

## 11. Direction: the thin-ports re-cut (in progress)

This section describes where testing is **heading** as the ports are re-cut (`docs/architecture.md` §11). It does
not describe what runs today. Each phase moves its part into the sections above.

- **Contracts are the executable specification of the real system and the licence for its mocks.**
  - Only ports have contracts. Every clause runs against a real implementation somewhere, and a do-nothing
    implementation must fail.
  - Every measurement becomes a clause with a host and a committed recording. External OS stimuli are allowed
    (`simctl openurl/push/launch/terminate`, a BGTask simulation triggered by the rig, an XCUITest host).
  - Each port has a clause → host table. Anything unproven gets a probe first.
  - Behaviour of today's inbound ports is pinned by service tests over mocks.
- **Fewer, thinner contracts.** The ten backend contracts became one `Backend` contract in 11c (section 4).
- **Recordings.** When a phase converts a port whose device results are recorded, it keeps the adapter's OS call
  sequence identical and replays first; it re-records in a device session only if a replay diverges. 11b
  (SecureStore, AttestStore) and 11e (`LinkOpener`, now a contract over `SystemUi.openUrl` under its recorded
  name) replayed unedited; 11c re-recorded AttestKey as DeviceIntegrity. Still ahead: 11f (BackgroundScheduler,
  BackgroundTransfer on the extension, and UploadExtensionRegistry GRANTED + LIMITED, where an operator toggles the
  grant).
- **PlatformDeviceId has no contract until an Android host exists.** Its only implementation is a constant null.
- **Mocks, one per port.** Each lives in `:adapter:generic:mock` (renamed from `:adapter:generic:fake` in 11g)
  with durable state, a per-process face, and a separate operator-face type.
- **`:test:world` goes away** (11g). `:app:jvm` takes its place as a support module: it takes the adapter
  factory and offers `relaunch()`.
- **The rig becomes an adapter set.** It is chosen at build time, decorates the platform Ui
  (`RigUi(inner)`), implements the per-platform drivers declared in `:test:contracts`, and reaches the app only
  through ports.
- **A launch-time mock mix** (11h) will let a simulator or device run with some systems mocked and others
  real, for interactive investigation.
- **The forge and its marketing screenshots** are replaced by screenshots of the rig running on a simulator (12).
