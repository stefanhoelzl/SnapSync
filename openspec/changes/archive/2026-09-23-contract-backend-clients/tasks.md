## 1. Ephemeral live edge

- [x] 1.1 `api/src/dev/serve.ts`: add `--ephemeral` (port `0`, no `.localdev/host` write, one last-act
      readiness line `LIVE-EDGE READY <origin>` written with a sync write so it survives a pipe); `deno task
      check` passes and `dev:local`'s behaviour without the flag is unchanged
- [x] 1.2 Measure the minimal permission set for ephemeral mode (`--allow-net=127.0.0.1`, read and write
      scoped to `api/` and the store, no `--allow-run`); record it beside the flag, and confirm that a
      request to a non-loopback host fails as a permission error
- [x] 1.3 `:adapter:generic:app` `jvmTest`: the `LiveEdge` fixture (lazy start, bounded readiness wait
      that fails with the captured stderr, temp store under `build/`, shutdown hook, loud failure naming
      the prerequisite when `deno` is absent), depending on nothing in `:test:world`
- [x] 1.4 Public-HTTP setup helpers (create an event, fill it to capacity, publish a manifest, `PUT` a
      resource's bytes), taking a base URL and an `HttpClient` only — `EdgeSetup` in `:test:contracts`, so the
      live and the mini-edge bindings enter states the same way
- [x] 1.5 Add a JVM Ktor client engine to the version catalog and to `:adapter:generic:app`'s `jvmTest` only
- [x] 1.6 Gradle: an `Exec` running `deno task config:local` that the JVM test task depends on; declare
      `api/src`, `api/migrations` and `deployments/` as that task's inputs

## 2. First contract end-to-end

- [x] 2.1 `:test:contracts`: `GateObservation` handle and the `EventDirectory` contract (states
      `EVENT_EXISTS`, `NO_SUCH_EVENT`, `VERSION_REFUSED`, `TOKEN_REJECTED`)
- [x] 2.2 Live binding (`JVM`, `Live`) over `HttpEventDirectory` + `withCredentialInterceptor` + `LiveEdge`;
      every declared state reached through public HTTP; green
- [x] 2.3 Confirm with the contract-coverage gate that `EventDirectory`'s clauses count as covered, and
      that a deliberately fake-only clause fails it (then remove the probe clause)

## 3. The remaining contracts and live bindings

- [x] 3.1 `EventCreation` contract + live binding
- [x] 3.2 `EventRename` contract + live binding
- [x] 3.3 `EventJoin` contract + live binding (`EVENT_FULL` via capacity joins; measure its cost)
- [x] 3.4 `ManifestPublisher` contract + live binding
- [x] 3.5 `EventUnionSource` contract + live binding (`COMPLETE_ASSET` / `INCOMPLETE_ASSET` via manifest +
      byte `PUT` setup)
- [x] 3.6 `DeviceFilesSource` contract + live binding
- [x] 3.7 `LeaveNotifier` contract + live binding; write the unknown-event clause from what the real edge
      answers and what the app relies on (design, Open Questions)
- [x] 3.8 `AttestClient` contract (`challenge()` only) + live binding; `InMemoryAttestClient` binding in
      `:adapter:generic:fake` `commonTest`
- [x] 3.9 `HttpAttestClient`'s documentation states that mint/renew have no JVM host, with the evidence
      (`port-contracts`' stated destination for an uncontractable belief)

## 4. Mini-edge binding

- [x] 4.1 `:test:world` `commonTest`: one `Fake` binding per contract over the real clients + `miniEdgeClient`,
      entering states through the same public HTTP setup; `TOKEN_REJECTED` declared unreachable unless the
      mini-edge is taught `401`
- [x] 4.2 Fix every clause the mini-edge fails, in the mini-edge (never by declaring the state unreachable);
      list each fix in the PR body as a drift the contracts found
- [x] 4.3 ~~Simulator run as an acceptance step~~ — dropped: the clients are `commonMain`, identical on every
      target, and their Kotlin/Native compilation is already covered by `:adapter:generic:app`'s `commonTest`.
      The mini-edge bindings still run in the PR's normal iOS test job, as every `commonTest` does (design D9)

## 5. CI and docs

- [x] 5.1 `build.yml` `build` job: `denoland/setup-deno@v2` at the version `api.yml` pins
- [x] 5.2 CLAUDE.md: the Build & test section states that `./gradlew build` needs `deno`; module list
      entries for `:test:contracts`, `:adapter:generic:app` and `:test:world` name the new contracts and bindings
- [x] 5.3 `local-backend` skill: note that `serve.ts --ephemeral` exists for tests and never writes the host
      file (if the skill is in-repo)
- [x] 5.4 `./gradlew build` and `./gradlew architectureDiagrams` (commit any regenerated diagrams);
      `deno task check` and `deno task test` in `api/`

## 6. Specs

- [x] 6.1 At sync, update `harness-world-model`'s Purpose sentence "(drift accepted, no golden fixture)"
      to match the modified requirement (a delta cannot carry a Purpose change)
- [x] 6.2 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` after sync
