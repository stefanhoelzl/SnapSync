## Context

`:test:rig` is the control channel: a Ktor CIO server linked into the iOS app only under
`-Psnapsync.rig=true`, with routes `GET /health`, `POST /os/{root}/{member}`, `POST /user/{name}`,
`GET /device/state|logs|gallery`, `POST /device/{name...}`, `GET /contract`, `POST /contract/{name}`.
Its `commonMain` (`RigServer`, `RigHooks`, `RigState`, `GalleryView`) names no platform API; the iOS half
(`iosMain`, the `hook/` directory compiled into `:app:ios`) supplies facts as data and verbs as lambdas.
`RigHooks`' KDoc already states the intended extension: "a second platform brings its own hook; the
server, the routes and the state projection are unchanged". It targets `iosArm64` and `iosSimulatorArm64`
only, and it has no tests — an exception recorded in `…-retire-launch-env-triggers` D9, whose own escape
hatch is "it needs a `jvm()` target and tests with it (`:test:world`'s `World.core` is the same `AppCore`,
so that path is open)".

`:test:world`'s `World` composes the real `AppCore` through `snapSyncApp` over in-memory ports. Its backend
is hard-wired: `val store = BackendStore()`, `val client = miniEdgeClient(store).withCredentialInterceptor(…)`,
and `FakeBackgroundTransfer(store, …)` deposits completed objects store-direct. `store` is public, and about
127 references outside `World.kt` (in `:test:integration`, `:test:world`'s tests, `:app:desktop`) read it
directly. Operator helpers (`provision(eventId)`, `addForeignDevice(…, eventId, …)`) choose their own event
ids.

The real backend is already runnable in a test: `LiveEdge` (`adapter/generic/app/src/jvmTest`) starts
`api/src/dev/serve.ts --ephemeral` — the production `createApp` over a filesystem store and SQLite, on a
loopback port, `--allow-net=127.0.0.1` only. Its dev wrapper attaches a fallback bearer to any request with
no `authorization` header, so an unauthenticated client is served, and `PUT /api/v2/files/devices/<id>/
<asset>/<role>?filename=` accepts bytes for any device id (`EdgeSetup.upload` relies on this). The real
backend mints event ids (`crypto.randomUUID()`) and refuses anything that is not a UUID. It serves no
route that reads a device manifest back.

This change is the "JVM host" step of the testing programme's integration plan: one protocol, served by a
fast host (JVM, mocks) and a truthful one (the simulator app, real ports). It BUILDS the surface; moving
the integration tests onto it is the next change.

## Goals / Non-Goals

**Goals:**
- A JVM host for the rig protocol, composed over `World`, with the same server, routes and `RigState`.
- A per-host verb advertisement (`GET /device`) and explicit refusals (`409` + reason) for shared-vocabulary
  verbs a host cannot honour.
- `World`'s backend as one seam with two implementations — mini-edge and the real Deno `api/` — added
  beside today's surface, not replacing it.
- A typed JVM client (`:test:control`) and tests of the JVM host in `./gradlew build`, over both backends.
- `LiveEdge` moved to a support module both its consumers can depend on.

**Non-Goals:**
- Migrating `:test:integration` or `:test:world`'s tests onto the protocol, the neutral API or minted ids;
  retiring `World.store` or the caller-chosen-id helpers (the next change).
- `@Real(...)` port selection, skip reporting, mocks inside the simulator-app host, and a desktop harness
  attached to a remote host as a mirror (the next change).
- Deleting or redesigning `:test:harness-driver` / `:test:world` (a later change).
- Any click, tree or pixel verb. Taps and pixels belong to the UI tier.
- Any change to the iOS host's route behaviour beyond `GET /device`.

## Decisions

### D1 — The JVM host is a hook, not a second server

`test/rig/src/jvmMain` holds a `jvmRigHooks(world, …)` builder and a small `JvmRigHost` that composes a
`World`, builds the `StatusContainerHost` and `platformEntries` over it, and starts the unchanged
`RigServer`. The `StatusContainerHost` and `platformEntries` are wired the same way as the integration tests
already wire them (`EntryContractFixtures`); the host keeps its own copy for now, and the next change
decides whether that assembly moves into `:test:world`.

The platform-free iOS command tables — `userCommands(host)` and `excludedUserCommands()` — move from
`iosMain` to `commonMain` verbatim, so both hosts invoke the same `/user` table.

`RigHooks` is iOS-flavoured in two places, and both get the smallest correction:
- the `osExtension` not-applicable reason is iOS text ("a 26.1 selector"). It becomes a hook-supplied
  value, and the iOS hook passes today's string, so the iOS response does not change.
- `bindingCaveat` fires for any binding other than `"background"`. It is restated to fire only for
  `"default"`. The iOS values are `background`|`default`, so on iOS this is the same predicate, and the JVM
  host's binding value (`"world"`) earns no iOS caveat.

`RigServer` gains `stop()` (cancel its scope, close its lane), used only by the JVM host.

*Alternative rejected:* a JVM fork of `RigServer`. It would make "same protocol" a property of review
instead of compilation.

### D2 — Launch: an in-process fixture, plus a Gradle task for an agent

- **Tests** start a `JvmRigHost` in-process, bound to port `0` on loopback, and read the bound port from
  the `publishBoundPort` hook. Tests get a fresh host (fresh `World`) per test. The Deno process is shared
  per test JVM, as `LiveEdge` already shares it; isolation there is by fresh event and device ids.
- **An agent** runs `./gradlew :test:rig:runJvmHost -Psnapsync.rigBackend=mini|deno [-Psnapsync.rigPort=N]`,
  a `JavaExec` over the rig's JVM classpath, printing one `RIG-JVM READY <port>` line.
- The next change owns the lifecycle policy for integration tests. This is the minimum that lets this
  change's own tests and an agent drive the host.

### D3 — One vocabulary, advertised per host, refused with a reason

`commonMain` declares the **closed** shared vocabulary of `/device` verbs and `/os` roots as data. Each
host's hooks classify every entry as **honoured** or **refused, with a reason**.
- `GET /device` answers this host's classification as JSON: the host name, the honoured verbs, and each
  refused verb with its reason.
- A request for a refused verb answers `409` with the reason.
- A verb outside the vocabulary answers `404`, as today.
- An entry a host leaves **unclassified** makes `GET /device` answer `500` naming it, so the gap is loud.
  It does not make the rig fail to start, because the rig must never break the app under test.

The JVM tests assert that the JVM host's classification is complete. The iOS host's classification is
kept complete by the same `500`, the first time anyone calls `GET /device`.

The vocabulary, in outline (the full table is fixed in the code and listed by `GET /device`):

| verb | iOS host | JVM host |
|---|---|---|
| `GET /health`, `GET /device/state`, `POST /user/*`, `POST /os/app/*` | honoured | honoured |
| `POST /os/photokit-ext/*` (invoke extension) | honoured | honoured (the world's `uploadCore` cycle via `extensionEntries`) |
| `GET /device/logs` | honoured | `process=app` honoured (the world's captured log); `process=extension` → "no log" as today |
| `GET /device/gallery`, `POST /device/gallery/seed`, `POST /device/gallery/wipe`, `POST /device/reset` | honoured | honoured over the world gallery, same parameters and response shape |
| `POST /device/uploaders`, `/device/process-metrics`, the upload-job verbs | honoured (per build) | refused: they name the OS upload-job registry and MetricKit |
| world levers (below) | refused: "a device cannot fake another backend, OS or member" | honoured |
| `GET /contract`, `POST /contract/*` | honoured | refused naming `JVM`: JVM contracts run under Gradle |

The world levers are the full-stack inspector's (`full-stack-harness`) set, one verb each:
- backend offline;
- job limit;
- complete or fail a job (`world.platform`);
- fail the next import;
- make the membership unreadable;
- set the photo permission;
- stage all downloads;
- place an asset in an album;
- add a foreign device, whose event id is minted per D5;
- run an upload cycle;
- refresh status;
- run a download reconcile.

A backend lever the chosen backend cannot honour answers `409` "unavailable on this backend" (D5).

*Alternative rejected:* separate `/world/*` namespaces. A test that seeds a photo would then have to know
its host.

### D4 — `:test:control` is a JVM-only support module; the compile boundary is the read-model rule

The wire types stay in `:test:rig`'s `commonMain`, where the iOS rig build needs them without a client.
`:test:control` is JVM-only, depends on `:test:rig` (resolving its JVM variant) plus the Ktor client, and
holds a typed client: `health()`, `device()` (the advertisement), `state(): RigState`, `user(name, params)`,
`os(root, member, arg, body)`, `deviceVerb(name, params, body)`. It returns a typed refusal for a `409`
and never throws for one.

The rig's `api(project(...))` dependencies become `implementation`. Its public signatures still expose
`RigState` → `UiState`, so `:test:control` declares `:ui:presentation` itself. That puts `model/` and
`feature/` on the client's compile classpath, because `:ui:presentation` exports both. It keeps `ports/`,
`flow/` and `compose/` off it, because nothing on that path exports them. **That boundary is the rule**:
a client and its tests may name read-model types from `model/` and `feature/`, and cannot name a port, a
flow or the composition. No zone gate is added.

Consequence for `:app:ios`: the hook receives `List<InAppContract>` from the iOS adapter modules' rig
source sets. It only had `:test:contracts` on its compile path through the rig's `api`, so `:app:ios`
declares `if (rigEnabled) implementation(project(":test:contracts"))` itself. That is allowed under the
same property by the containment requirement, and checked with
`./gradlew compileIosMainKotlinMetadata -Psnapsync.rig=true` on the command line, never in a tracked file.

*Alternatives rejected:* a contained `:test:control` holding the types (a contained module must still
withhold a dependency, and this one withholds none); a DTO copy of `UiState` (a second rendering of the
state that could disagree with the screen); a zone gate on the client (the compile boundary already
enforces all but `feature/`, and `feature/` read-models are what the state is made of).

### D5 — The backend seam: add beside `store`, never replace it

`:test:world` `commonMain` declares `WorldBackend`:
- the device-facing `base`, and `newClient()`, which returns a fresh bare client on every call. A fresh
  instance is forced: `withCredentialInterceptor` installs itself into the client it is given. A shared
  instance would stack every world's interceptor, and the OS's own upload request, which already declares
  the app version, would reach the backend declaring it twice. The real edge answered `426` to that
  (measured). Deno builds each client over one shared CIO engine per JVM;
- `suspend createEvent(name, startsAt, endsAt): String`, returning the minted id;
- `suspend join`, `suspend putManifest`, and `suspend seedForeign(eventId, deviceId, assets)` (join,
  bytes, manifest);
- the neutral reads `objectsOf`, `manifestOf`, `unionOf` and `isRegistered`;
- the levers.

Every read and lever returns `Answer<T> = Available(value) | Unavailable(reason)`. **Unavailable is never a
silent no-op or an empty value.**

- **`MiniEdgeBackend`** (`commonMain`) wraps today's `BackendStore` and `miniEdgeClient`. It mints UUID event
  ids for the new API and still accepts caller-chosen ids through the old one.
- **`DenoBackend`** (`jvmMain`, over `:test:edge`) speaks HTTP only: `EdgeSetup` for seeding, the v2 listing
  and union routes for reads. It never reads the Deno store's directory, which would make the tests depend
  on `api/`'s storage layout.

| operation | mini-edge | Deno |
|---|---|---|
| create event (minted id), join, publish manifest, deposit bytes | ✓ | ✓ over HTTP |
| `objectsOf` (per-device listing), `unionOf`, `isRegistered` | ✓ | ✓ (`GET /files/devices/<id>`, `GET /events/<id>/files`, `GET /events/<id>`) |
| `manifestOf` | ✓ | **Unavailable**: `api/` serves no manifest read route |
| `publishesOf`, `refusedPublishesOf`, `manifestVersionOf` | ✓ | Unavailable: not on the HTTP surface |
| `offline`, `minAppVersion` | ✓ | Unavailable: nothing makes the real edge answer `502`, and its minimum version is fixed at start |
| `sweepEvent`, `wipeBytes`, `collectBytes`, capacity | ✓ | Unavailable: the nightly sweep and storage reset are not runtime-drivable |

`manifestOf` is one entry beyond the list agreed with the design session. It follows the same rule, and
is recorded here so it is not a surprise.

`World(scope, …, backend: WorldBackend = MiniEdgeBackend())` keeps every existing default. `World.store` stays
public, typed `BackendStore`, **documented mini-edge-only**, and throws a stated error on a Deno world, so a
test that reaches for it on the wrong backend fails loudly. The new `provisionMinted(...)` and
`addForeignDeviceMinted(...)` return the minted id. `provision(eventId)` and `addForeignDevice(…, eventId, …)`
stay, documented mini-edge-only.

**Upload completion by a real PUT.** `FakeBackgroundTransfer.completeJob` becomes `suspend` and PUTs the
job's bytes to the device-facing upload route through the backend's transfer client, addressed exactly as
`EdgeSetup.upload` and the real uploaders address it. The mini-edge already serves that route
(`PUT /files/devices/<id>/<asset>/<role>`), so the mini-edge world deposits through its own route rather
than store-direct. The byte model behind it is unchanged, so every existing assertion on `store.objectsOf`
still holds.

*Alternative rejected:* pointing only the `Http*` clients at Deno. The server would see joins and manifests
but never bytes, so any event-union assertion over Deno would be wrong by construction.

### D6 — The transfer-contract `Fake` binding stays green

`TransferContractsTest` used to bind `FakeBackgroundTransfer` over a bare `BackendStore` and observe completion
with `store.objectsOf`. The double now takes the network it transfers over rather than a store, so the binding
hands it a **recording `MockEngine`**. That engine is the network the binding already plays: every request is
accepted, and its route is recorded. The binding reads "what landed at this route" from that record. Its
clauses and its "no new lever" rule are unchanged. The binding still completes only jobs whose fixture route
accepts, so an accepting network is the fixture's answer and not a lever. A clause that fails after the
rebinding is a defect in the double, fixed in the double.

*As built:* this replaces the drafted "rebind over a `MiniEdgeBackend` and observe through its store". The
fixture URLs are not the upload route, so a mini-edge would have answered `404` to every one of them.

### D7 — `:test:edge` holds `LiveEdge`

JVM-only support module: `LiveEdge` (process start, ready line, shutdown hook, zone-safe `--allow-net`) and
its `EdgeSetup` factory, depending on `:test:contracts` (for `EdgeSetup`) and `:adapter:generic:app` (for
`withCredentialInterceptor`). Its consumers pass the `api/` directory and a store root as system properties,
as `:adapter:generic:app` does today. That module's `jvmTest` keeps `LiveEdgeContractsTest` unchanged apart
from the import.

*Alternatives rejected:* housing it in `:test:world`, which would make the adapter's contract test depend
on the module a later change takes apart; or in `:test:contracts`, which is contained and links into rig
builds, so it must not grow process spawning.

### D8 — Where the JVM host's tests live, and what they prove

In `:test:control`'s `src/test`: JVM-only module, so its test set is its common set and forgoes nothing.
They drive a `JvmRigHost` through the client, over both backends, and assert:
- `GET /device` completeness (every vocabulary entry classified) and its per-host shape;
- a refused verb answering `409` with its reason, and the `/contract` refusal naming `JVM`;
- `/device/state` decoding to the real `UiState`, and following a `/user` create → `/os/app/onOpenUrl` →
  `/user/confirmJoin` round trip to the joined layer;
- `/os/photokit-ext/*` invoking one cycle;
- a completed job's object visible through the neutral `objectsOf`, over both backends;
- a Deno-unavailable lever answering "unavailable on this backend".

**What they prove that the in-process tests do not:** that the protocol carries the app faithfully. That
covers routing and path parsing, the compiler-generated `RigState` encoder, the lanes entry points run on,
the verb advertisement and refusal statuses, the client, and the whole stack against the real `api/`. They
prove **no** new app behaviour; the 117 in-process tests remain the behavioural suite until the next change
moves them.

### D9 — The loopback bind guard the rig already cites

`RigServer.LOOPBACK` cites `architecture-guards`, "A dev/test control channel binds the loopback address
only", as a guard. Neither the requirement nor the guard exists. The JVM host is a second bind site, so the
rule is written now and gated by a small text guard over `test/rig/src`: every `embeddedServer(` call binds
`host = LOOPBACK`, and no other address literal appears.

## Risks / Trade-offs

- [The upload-job-contracts work in flight edits the rig (a contract verb, an extension-side host)] →
  Whichever lands second rebases. D3's vocabulary makes its new verb one table entry per host.
- [A `suspend` `completeJob` reaches call sites] → All 52 are in tests or `:app:desktop`'s
  `launchMutation { … }`, both already coroutine contexts; a non-coroutine caller is a compile error, not a
  silent change.
- [The Deno arm has holes, listed in D5] → They are refusals, not silent no-ops, and `GET /device` lists them.
  All non-backend levers work on both backends, so the Deno arm is still useful for what integration tests
  mostly assert: joins, bytes and unions.
- [JVM host tests start Deno] → The Deno process is shared per test JVM, as the contract bindings share it
  today. The build already requires Deno and fails, never skips, without it.
- [`World.store` throws on a Deno world] → Intentional: it keeps "unavailable" loud for the old surface too,
  until the next change retires it.
- [The iOS classification is only checked when `GET /device` is called] → No iOS build runs tests of the rig.
  The `500` makes a gap loud to the first caller, and the simulator job can call `GET /device` once per run
  if that proves necessary.

## Migration Plan

Additive. No existing caller changes behaviour: `World()`'s defaults are the mini-edge, `store` and the
caller-chosen-id helpers keep working, and the iOS host's routes answer as before. Rollback is a revert.
The next change migrates callers and retires the old surface.

## Open Questions

None blocking. Whether the `StatusContainerHost` / `platformEntries` assembly moves into `:test:world` is
deferred to the next change, which will have the integration tests as its second consumer.
