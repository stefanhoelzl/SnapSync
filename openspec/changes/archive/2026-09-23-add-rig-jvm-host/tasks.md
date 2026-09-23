## 1. `:test:edge` — the real backend as a support module

- [x] 1.1 Create JVM-only `:test:edge` (`settings.gradle.kts`, build file stating its support-group argument); move `LiveEdge` from `adapter/generic/app/src/jvmTest/.../liveedge/` into it, public where its consumers need it, depending on `:test:contracts` and `:adapter:generic:app`
- [x] 1.2 Keep the `snapsync.apiDir` / `snapsync.liveEdgeStore` system-property contract; wire both properties (and `api/src` as a task input) for every consumer's test task
- [x] 1.3 Point `:adapter:generic:app`'s `jvmTest` at `:test:edge`; `LiveEdgeContractsTest` changes only its import; run `:adapter:generic:app:jvmTest` green

## 2. `World`'s backend seam (additive)

- [x] 2.1 Add `WorldBackend` + `Answer<T>` (`Available` | `Unavailable(reason)`) in `:test:world` `commonMain`: base, engine, `createEvent` (minted id), `join`, `putManifest`, `seedForeign`, neutral reads (`objectsOf`, `manifestOf`, `unionOf`, `isRegistered`), levers
- [x] 2.2 `MiniEdgeBackend` over today's `BackendStore` + `miniEdgeClient`, minting UUIDs for the new API while still accepting caller-chosen ids through the old one
- [x] 2.3 `DenoBackend` in `:test:world` `jvmMain` over `:test:edge` — HTTP only (`EdgeSetup` for seeding, v2 listing/union/event routes for reads); `Unavailable` with a reason for `manifestOf`, the publish counters, `manifestVersionOf`, `offline`, `minAppVersion`, sweep/wipe/collect, capacity
- [x] 2.4 `World(…, backend = MiniEdgeBackend())`: the shared client, the seeding helpers and the new neutral API route through the seam; `store` stays public, documented mini-edge-only, and throws a stated error on a Deno world
- [x] 2.5 Add `provisionMinted` / `addForeignDeviceMinted` returning the minted id; document `provision(eventId)` / `addForeignDevice(…, eventId, …)` as mini-edge-only, failing with a stated error on Deno
- [x] 2.6 `FakeBackgroundTransfer.completeJob` becomes `suspend` and PUTs the job's bytes to the device-facing upload route through the backend (no store-direct deposit); fix any non-coroutine caller the compiler names
- [x] 2.7 Rebind `TransferContractsTest` over a recording `MockEngine` network (the double now takes a network, not a store); its clauses and "no new lever" rule unchanged
- [x] 2.8 Add `:test:world` tests: the neutral API over the mini-edge (commonTest), and one jvmTest over Deno covering provision (minted) → complete job → foreign device → union, and one `Unavailable` lever
- [x] 2.9 Run `:test:world` and `:test:integration` tests unchanged and green (no existing test edited beyond the `suspend` call)

## 3. The rig: `jvm()` target and JVM host

- [x] 3.1 Add `jvm()` to `:test:rig`; turn its `api(project(...))` dependencies into `implementation`; add `jvmMain` dependencies on `:test:world` and `:test:edge`; rewrite the build-file header (JVM host, tests now exist for `commonMain`, the seeder and wiper remain the one untested exception)
- [x] 3.2 Under the rig property only, have `:app:ios` declare `implementation(project(":test:contracts"))` itself; verify with `./gradlew compileIosMainKotlinMetadata -Psnapsync.rig=true` (property on the command line, never in a tracked file)
- [x] 3.3 Move `userCommands` / `excludedUserCommands` (and their private helpers) from `iosMain` to `commonMain`, verbatim
- [x] 3.4 `RigHooks`: make the osExtension not-applicable reason a hook-supplied value (the iOS hook passes today's text); restate `bindingCaveat` to fire only for `"default"`; add `RigServer.stop()`
- [x] 3.5 Declare the closed `/device` + `/os` vocabulary in `commonMain`, and add each host's honoured/refused classification; `GET /device` answers it; a refused verb answers `409` + reason, an unclassified one makes `GET /device` answer `500` naming it; route `/contract` and `GET /contract` refusals through the same mechanism on JVM, naming `JVM` with the refusal marker
- [x] 3.6 Classify every entry in the iOS hook (`Boot.kt` / `IosRigBuilders.kt`), keeping every existing iOS route's answer byte-identical; world levers refused with a reason
- [x] 3.7 `jvmMain`: `JvmRigHost` composes a `World` over a chosen backend, builds `StatusContainerHost` + `platformEntries` / `extensionEntries` the way the integration fixtures do, on the full-stack harness's lane structure, and supplies `RigHooks` (JVM build facts, the world's log as the `app` log, `/device` reset + gallery read/seed over the world gallery with the iOS parameter and response shapes via shared `commonMain` builders — `gallery/wipe` refused with a reason — and the world levers as `/device` verbs)
- [x] 3.8 Bind port `0` on loopback in tests; add `:test:rig:runJvmHost` (`JavaExec`, `-Psnapsync.rigBackend=mini|deno`, optional `-Psnapsync.rigPort`) printing one `RIG-JVM READY <port>` line

## 4. `:test:control` — the typed client, and the JVM host's tests

- [x] 4.1 Create JVM-only `:test:control` (support group), depending on `:test:rig` (JVM variant), `:ui:presentation`, and the Ktor client; typed calls `health`, `device`, `state`, `user`, `os`, `deviceVerb`, with a typed refusal for `409`
- [x] 4.2 Tests in `:test:control`'s `src/test`, over both backends: `GET /device` classification complete; a refused verb answers `409` with its reason; `/contract` and `GET /contract` refuse naming `JVM`; `/device/state` decodes to the real `UiState`; `/user/create` → `/os/app/onOpenUrl` → `/user/confirmJoin` reaches the joined layer; `/os/photokit-ext/*` runs one cycle; a completed job is visible through the neutral `objectsOf`; a Deno-unavailable lever answers "unavailable on this backend"
- [x] 4.3 Confirm the compile boundary: a scratch reference to a `ports/` type from `:test:control` fails to compile (not committed)

## 5. Guards, specs of record, docs

- [x] 5.1 Add the loopback bind guard to `:test:architecture` (every `embeddedServer(` in `test/rig/src` binds `LOOPBACK`; no other address literal; fails on an empty result); make `RigServer`'s citation match the requirement name
- [x] 5.2 `DetektTierCoverageTest` green with the two new modules (both in the `harness` tier); `./gradlew architectureDiagrams` committed. `ModuleSetTest` reads the MAIN `module-architecture` spec, so it turns green only when this change's delta is synced
- [x] 5.3 CLAUDE.md module map: add `:test:edge` and `:test:control`, update `:test:rig` (JVM host) and `:test:world` (backend seam) and `:adapter:generic:app` (LiveEdge moved); no law digest
- [x] 5.4 `rig-channel` skill: a JVM-host section (`runJvmHost`, backends, `GET /device`, refusals)
- [x] 5.5 `./gradlew build` green (Deno on PATH) apart from `ModuleSetTest`, which turns green at sync (see 5.2); `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` and `validate add-rig-jvm-host --strict` green
