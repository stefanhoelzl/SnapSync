## Why

The rig control channel (`:test:rig`) is SnapSync's one way to drive a *whole running application* from
outside it — `/os` entry points, `/user` intents, `/device` state and verbs — and it has exactly one host:
the iOS app, on a device or a simulator. Integration tests cannot stand on it: every one of them runs
in-process over `:test:world`'s `World`, so "the same test, fast on JVM and truthful on the simulator" has no
protocol to share. This change gives the protocol its second host — a JVM process whose `AppCore` is the
real one `World` already composes — and a typed client, so the next change can move the integration tests
onto it. It only BUILDS the surface; nothing existing is migrated here.

## What Changes

- **The rig gains a `jvm()` target and a JVM host.** A `jvmMain` hook composes a `World` (its `core` is the
  real `AppCore` from `snapSyncApp`), builds the `platformEntries` and `StatusContainerHost` over it, and
  hands `RigServer` a `RigHooks` — same server, same routes, same `RigState`. The platform-free command
  tables (`userCommands`, `excludedUserCommands`) move from the rig's `iosMain` to `commonMain`, unchanged.
- **One `/device` vocabulary, advertised per host.** New `GET /device` lists the verbs this host honours.
  A verb in the shared vocabulary that this host cannot honour answers `409` with a reason, never `404`
  and never a silent success. On JVM, `/contract` refuses naming its host. The iOS host's existing routes
  answer as today.
- **`World`'s backend becomes one seam with two implementations**: the mini-edge (today's `BackendStore` +
  `MockEngine`) and the real Deno `api/` served locally. The upload double completes a transfer with a
  real `PUT` through the chosen backend's client. **Additive**: beside the public `World.store` (kept,
  documented mini-edge-only) the world gains a backend-neutral inspection API and `provision` /
  `addForeignDevice` variants that return a backend-minted event id; a neutral read or lever the backend
  cannot honour answers "unavailable on this backend".
- **New module `:test:edge`** (JVM-only, support): the Deno process lifecycle (`LiveEdge`) and its
  `EdgeSetup` wiring, moved out of `:adapter:generic:app`'s `jvmTest`, which keeps its contract bindings
  and depends on it. `:test:world`'s `jvmMain` depends on it for the Deno backend.
- **New module `:test:control`** (JVM-only, support): a typed Ktor client for the protocol. The wire types
  stay in `:test:rig`'s `commonMain`; `:test:control` depends on the rig's JVM variant, and the rig's
  `api(...)` dependencies become `implementation`, so a client compiles against `model/` and `feature/`
  read-model types (through `:ui:presentation`) and never against `ports/`, `flow/` or `compose/`.
- **The JVM host is tested in `./gradlew build`**, through `:test:control`, over both backends. This ends
  the rig's documented no-tests exception for its `commonMain`; the iOS seeder and wiper stay the one
  untested exception.
- **The loopback-only bind rule the rig already cites becomes a real guard.** `RigServer` cites
  `architecture-guards`, "A dev/test control channel binds the loopback address only" — no such requirement
  or guard exists. The JVM host adds a second bind site, so the rule is written and gated here.

No user-visible change. Changelog label: `internal`.

## Capabilities

### New Capabilities

None. The protocol is test architecture, and lands in `testing-architecture`.

### Modified Capabilities

- `testing-architecture`: the control protocol — one vocabulary, two hosts, advertised verbs, refusals —
  and the test-only module list gains `:test:edge` and `:test:control`, while `:test:rig` stops being
  "non-gating, no spec".
- `module-architecture`: the module set's support group gains `:test:edge` and `:test:control`; the
  contained group's `:test:rig` entry states that its JVM target is never linked into a shipped binary.
- `harness-world-model`: the backend seam and its two implementations, the neutral inspection API and
  minted event ids beside the mini-edge-only surface, and the upload double's completion through the seam.
- `port-contracts`: the contract verb on a host with no in-app registry refuses naming that host.
- `architecture-guards`: the loopback-only bind guard for the control channel.

## Impact

- **Modules**: `:test:rig` (new `jvm()` target, `jvmMain` hook, command tables to `commonMain`,
  `GET /device`, api→implementation); `:test:world` (backend seam, `jvmMain` Deno backend, neutral API,
  `FakeBackgroundTransfer.completeJob` becomes `suspend`); `:adapter:generic:app` (`jvmTest` loses
  `LiveEdge.kt`, gains a test dependency on `:test:edge`); `:app:ios` (under `-Psnapsync.rig=true` only,
  declares `:test:contracts` itself, which it previously received through the rig's `api`); new
  `:test:edge`, `:test:control`; `settings.gradle.kts`; regenerated `architecture/`.
- **Not touched**: `:test:integration`'s tests and `:test:world`'s tests keep using `World.store` and
  caller-chosen event ids (the next change migrates them); `:test:harness-driver`; `:app:ios:forge`; the
  iOS host's route behaviour apart from `GET /device`. `:app:desktop` should need no edit, since it already calls
  `completeJob` inside a coroutine.
- **Build**: `./gradlew build` gains the JVM-host tests, which start a Deno process (Deno on PATH is
  already a prerequisite of the build).
- **Docs**: CLAUDE.md's module map, the `rig-channel` skill (a JVM host section), the rig's build-file
  header.
- **Ordering**: the upload-job-contracts work in flight adds a rig contract verb and an extension-side
  host; whichever lands second rebases over the other's rig edits.
