## Context

Phase 7 of the testing-concept sequence. Phase 3 (`changes/archive/2026-09-22-establish-port-contracts`)
built the mechanism: hand-written clause values, states entered at construction, bindings per
(implementation, host), explicit outcomes, and the gate that every clause runs against a real
implementation somewhere. The handoff was written in another workspace and was checked against the tree
before this proposal. Three of its claims did not hold, and this design is built on what the tree says:

- **The world has no port-level backend doubles.** `World.kt` builds the real `Http*` clients over the
  `MockEngine` mini-edge (`miniEdgeClient(store).withCredentialInterceptor(...)`). The only port-level fake
  is `InMemoryAttestClient`. Feature tests use one-off anonymous objects. **The double is the edge, not the
  client.**
- **The real-`api` binding is not separable.** `port-contracts`, "Every clause runs against a real
  implementation on some host", admits no clause that only a fake reaches. With the mini-edge as the fake,
  a change without the live binding can hold no clause at all.
- **The port list** is missing `ManifestPublisher` (declared in `JoinSeams.kt`). `AttestClient`'s mint
  and renew cannot reach a real backend from a JVM.

Measured facts this design stands on:

- `api/src/dev/serve.ts` already composes the production `createApp` over a filesystem store and SQLite
  (`--store=`, `--port=`). It refuses a bunny deployment. A request carrying no `authorization` header gets
  a dev bearer plus the enrolment it implies, while a request carrying its own token is untouched, so a
  foreign token still `401`s. `/attest/challenge` is ungated.
- The version gate refuses any declared version below `MIN_APP_VERSION` with
  `426 {error, minAppVersion}`. The client declares its version through an injected lambda, so a binding
  reaches "refused" by declaring `0.0`, with no backend configuration.
- The local deployment's event capacity is `10` (`deployments/components/policy.json`). The mini-edge's
  `BackendStore.capacity` also defaults to `10`, and answers `409` when full.
- `serve.ts` writes `.localdev/host` unconditionally. That file is how a developer's running rig
  publishes its origin.
- The CI `build` job (`build.yml`) installs no Deno. `api.yml`, `site.yml` and `nightly-cleanup.yml` use
  `denoland/setup-deno@v2`.
- `testing-architecture` cites a `ci-build` capability that does not exist in `openspec/specs/`. This is
  noted, not fixed here.

## Goals / Non-Goals

**Goals:**
- One contract per backend-talking port, run unchanged against the mini-edge and the real `api`.
- The real `api` exercised on every `./gradlew build`, offline and zone-safe.
- The mini-edge's drift on contracted routes becomes a failing clause instead of accepted drift.
- The edge's gate responses (`401`, `426` plus minimum) are checked end-to-end across the language
  boundary.

**Non-Goals:**
- A contract for `withCredentialInterceptor` itself (see D5).
- `PushHttpClient`, `AttestClient.mintToken`/`renewToken`, and the byte upload (see D7).
- A binding on `IOS_SIM_KEXE` against the real `api` (see D3). It would add nothing: the clients are
  `commonMain` code, the same on every target.
- **The Darwin engine against a real backend.** The one thing that differs on a device is the Ktor
  engine: the app uses Darwin (`darwinHttpClient`, `:adapter:ios:ext-safe`), while these bindings use CIO
  (live) and `MockEngine` (mini-edge). No automated test puts the Darwin engine in front of a real backend,
  and this change does not either. Closing it takes a device run against the local backend through the rig,
  which belongs to a device-facing phase of the sequence.
- Replacing the per-client `MockEngine` unit tests. They pin the client's reaction to responses the real
  edge cannot be made to produce on demand (a `502`, a malformed body), which no contract state reaches.
- Anything in phases 9–11. The live-edge fixture is built so that they can reuse it, not for them.

## Decisions

### D1. The implementation under contract is client + edge; the edge decides the kind

Every binding's subject is the same production `Http*` client, running through the same
`withCredentialInterceptor`. What differs is the edge behind it, and the behaviour a backend clause asserts
is almost entirely the edge's: status codes, read-model shape, capacity, the gate. So the mini-edge binding
is `Fake`, and the binding over the real `api` is `Live`.

This differs from the inbound-port precedent (`EntryContractsJvmTest` binds the core `Live` over a world of
fakes), and deliberately so. There, the core **is** the implementation and the fakes are collaborators
beneath it. Here the edge is the thing the clauses are about. Calling the mini-edge binding `Live` because
the client is real would satisfy the coverage gate while no clause had ever met the real backend.

*Alternatives:* contract the clients alone against canned responses. This is a unit test of our own logic
with one implementation per port, which is what `port-contracts` sends to ordinary tests.

### D2. One contract per port; states are backend states

| contract | states | indicative clauses |
|---|---|---|
| `EventCreation` | `SERVING` | a valid create is `Created` with a non-blank id and the name echoed; a blank name is `InvalidName`; an end before the start is refused |
| `EventDirectory` | `EVENT_EXISTS`, `NO_SUCH_EVENT`, `VERSION_REFUSED`, `FOREIGN_TOKEN` | an existing event is `Found` with its name, start, end and a `deletesAt` 30 days from `max(createdAt, startsAt)`; an unknown id is `NotFound`; a refused build learns the minimum; the read is public whatever the credential (D5) |
| `EventRename` | `EVENT_EXISTS`, `NO_SUCH_EVENT` | a rename is `Renamed` and a following directory fetch agrees; a blank name is `InvalidName`; an unknown event is not `Renamed` |
| `EventJoin` | `EVENT_OPEN`, `EVENT_FULL`, `NO_SUCH_EVENT`, `FOREIGN_TOKEN` | `JOINED`, `EVENT_FULL`, `EVENT_NOT_FOUND` respectively; a rejoin by the same device is `JOINED`; a foreign token is rejected and starts recovery (D5) |
| `ManifestPublisher` | `MEMBER`, `NON_MEMBER`, `NO_SUCH_EVENT` | publish as a member is `true`; as a non-member and to an unknown event is `false` (publishing never enrolls) |
| `EventUnionSource` | `NO_SUCH_EVENT`, `EMPTY_EVENT`, `COMPLETE_ASSET`, `INCOMPLETE_ASSET` | unknown is a failed `Result`, empty is an empty list, a complete asset appears tagged with its device, an incomplete one is omitted |
| `DeviceFilesSource` | `NO_UPLOADS`, `UPLOADED` | empty list; one `StoredResource` per uploaded resource with its asset id |
| `LeaveNotifier` | `MEMBER`, `NO_SUCH_EVENT` | a member's leave succeeds; leaving an unknown event is a failure |
| `AttestClient` | `SERVING` | `challenge()` is non-null and non-blank (freshness is the edge's security property, not a promise the app reads, and the honest fake answers a constant) |

The code is authoritative once written, per `port-contracts`. This table is the starting brief, and a
clause the real `api` refuses is fixed in the clause or in the code, never by recording what happened.

A binding enters a state **through the backend's public HTTP surface** (create an event, join N devices,
publish a manifest, `PUT` bytes). The mini-edge can do the same through its `MockEngine`. So the setup
path is identical, and the bindings differ only in the edge. Seeding the mini-edge's `BackendStore`
directly was rejected: the binding would enter a state the real edge has no way to reach by that route,
and a divergence in setup would hide as a clause failure.

*Alternatives:* one `Backend` contract whose subject bundles every client. The subject is then not "the
port", every clause pays for every client, and one failing route reads as a failure of the whole backend.

### D3. The live edge: one Deno process per test JVM, fresh event per clause

The fixture (`LiveEdge`, in `:adapter:generic:app` `jvmTest`) starts lazily on first use and stops in a
shutdown hook. It runs `deno run` over `src/dev/serve.ts --ephemeral --store=<build/tmp/live-edge-<pid>>`.
"A fresh instance per clause" is met by a fresh `HttpClient` plus client, a freshly created event, and
random device UUIDs. The server process does not need to be fresh, because nothing a clause does is
visible to another clause's event or devices. A process per clause was rejected on cost: Deno start plus
migration replay, 9 contracts × up to ~6 clauses.

Only `JVM`: a Kotlin/Native `test.kexe` under `simctl` cannot launch a host process. It could reach one
started by Gradle, but that adds lifecycle across a process boundary for no clause the JVM does not
already cover. The binding is `jvmTest`-only, and the coverage it forgoes is stated where declared
(`testing-architecture`, "Every test runs on every target its module declares").

An external service is **not a host** (`port-contracts`, "Hosts are a closed set of what changes reachable
states"). What changes the reachable states is the process the binding runs in, and that is `JVM`.

### D4. Zone safety is structural, not procedural

- `serve.ts` already refuses any non-filesystem deployment.
- The fixture passes `--allow-net=127.0.0.1` (listen and connect on loopback only), `--allow-read` and
  `--allow-write` scoped to `api/` and the temp store, and **no** `--allow-run` (only the tunnel needs it).
  A code path that reached for bunny would fail as a permission error, the same guarantee `api/deno.json`
  gives its own tests by withholding `--allow-net`.
- Every event a clause touches is one the binding created in that run. No clause takes an event id from
  outside, so "never join an event you did not create" holds by construction.

### D5. The credential interceptor is not a port; the edge's gate is a clause

`withCredentialInterceptor` has one implementation and embodies client-side rules. `CredentialInterceptorTest`
already pins its reaction to `401`/`426`, and `CredentialRejectionWiringTest` pins the shell wiring. There
is no second implementation to hold to a contract, so contracting it would be a unit test in contract
clothing.

What is uncovered is the **other side**: does the real edge answer a foreign token with `401`, and an old
build with `426` whose body the client's `minAppVersionFromRefusal` can read, under the header name the
client sends? That is a cross-language contract with a real host. The version gate precedes every route, so
`VERSION_REFUSED` is a state of `EventDirectory`, the first call a joining device makes. The credential gate
does NOT cover the event read: `GET /events/<id>` is public by design (`web-event-download`, the id is the
read capability). So `EventDirectory`'s `FOREIGN_TOKEN` clause asserts the read is served and recovery does
not start, and the rejection clause is `EventJoin`'s `FOREIGN_TOKEN`, the first gated call. (The first draft
put the rejection on the directory. The live binding failed it on its first run, and the clause was wrong,
not the edge.) The outcome reaches the app only through the interceptor's
`onRejected`/`onVersionRefused`, so the subject carries an observation handle
(`GateObservation { rejected: Boolean; refusedMinimum: String? }`) implemented by each binding over the
callbacks it wired. `port-contracts` currently admits a handle only for a port that declares no reads. The
delta widens it to any outcome not readable through the port, and keeps the rule that a handle reports
outcomes (the refusal the app now holds), never which collaborator was called.

The mini-edge models `426` but not `401`. Its `EventJoin` binding declares `FOREIGN_TOKEN` unreachable (an
honest `NotRunHere`). Teaching it `401` is optional, and `World.kt` records why it has not so far.

*Alternatives:* a separate `EdgeGate` contract whose subject is the handle alone. There is no port behind
it, which is a larger departure from the spec than widening the handle rule.

### D6. Where each piece lives

| piece | home | why |
|---|---|---|
| contracts, state enums, `GateObservation`, `EdgeSubject`, `EdgeSetup` | `:test:contracts` `commonMain` | every contract lives there, and both edges' bindings enter states through the same `EdgeSetup` |
| mini-edge binding | `:test:world` `commonTest` | beside its implementation; runs on JVM and the simulator |
| `LiveEdge` fixture + live bindings | `:adapter:generic:app` `jvmTest` | beside the clients; process launch is JVM-only |
| `InMemoryAttestClient` binding | `:adapter:generic:fake` `commonTest` | the fake is `internal` there |
| ephemeral mode | `api/src/dev/serve.ts` | the rig entry point, never bundled (`main.ts` reaches nothing under `src/dev/`) |

**`LiveEdge` depends on nothing in `:test:world`.** It holds only process lifecycle and the public-HTTP
setup helpers, and those take a base URL and an `HttpClient`. Phases 9–10 will need a real backend for
rig-based integration, and phase 11 deletes `:test:world`. The fixture is the piece that outlives it. If a
second module needs it before then, it moves into `:test:contracts`' JVM source set (a relocation of test
infrastructure, not a new module).

No new module, so `module-architecture`'s "The module set withholds; packages organize" is untouched.
`:adapter:generic:app`'s `jvmTest` gains a JVM Ktor engine (`ktor-client-cio` or `-java`, whichever the
catalog pins) as a test-only dependency.

### D7. What is left out, and why

- **`PushHttpClient`**: `put(url, body)` / `post(url)` with the URL built by `PushRegistration`. A clause
  would have to construct `/devices/<id>` itself, restating the feature's knowledge in the test. The
  honest follow-up is to narrow the port to what it means (register a push token), then contract that.
- **`AttestClient.mintToken`/`renewToken`**: a real edge verifies an App Attest attestation, and no JVM can
  produce one. The dev rig deliberately fakes enrolment, not attestation. A clause here would only be
  reached by `InMemoryAttestClient`, which the coverage gate refuses. Per `port-contracts`, "Every clause
  runs against a real implementation on some host", it is not dropped: it goes to its stated destination.
  The beliefs stay in `HttpAttestClient`'s documentation with their evidence, and the behaviour stays in
  `HttpAttestClientTest`. A later phase of the sequence contracts App Attest together with the other
  device-only systems.
- **Byte upload `PUT /files/devices/…`**: driven by `URLSession`/PhotoKit. The live binding does issue
  raw `PUT`s, but only as **setup** for `UPLOADED`/`COMPLETE_ASSET`, not as a contracted port.

### D8. Deno on the build path

`./gradlew build` now needs `deno` on `PATH`. The fixture fails with a message naming the prerequisite.
It never answers `Unreachable`, because a missing tool is not a state the host cannot reach, and treating
it as one would let a machine without Deno report the whole backend as `NotRunHere`. The `build` job gains
`denoland/setup-deno@v2` with the version `api.yml` pins. The JVM test task declares `api/src`,
`api/migrations` and `deployments/` as inputs, so a backend-only change re-runs the contracts instead of
being up-to-date. The resolver (`deno task config:local`) runs once as a Gradle `Exec` the test task
depends on.

### D9. The mini-edge binding is worth declaring, even though the live one covers every clause

The coverage gate only needs the live binding. The mini-edge is bound anyway because, until phase 11,
every integration test and both desktop harnesses stand on it. A clause the real `api` passes and the
mini-edge fails is then a red build that names the divergence. Today it is invisible drift that
`harness-world-model` explicitly accepts. That is the only mechanical check the mini-edge's fidelity will
ever have, and it costs nothing at phase 11: the binding is deleted with its module, and nothing else
depends on it.

It lives in `commonTest` because `testing-architecture` puts logic tests there, so CI also runs it on the
simulator. That run adds no coverage: the `Http*` clients are `commonMain` code, identical on every target,
and their Kotlin/Native compilation is already exercised by `:adapter:generic:app`'s own `commonTest`
(the per-client `MockEngine` tests), which runs on the simulator today.

## Risks / Trade-offs

- **[Flaky process lifecycle in CI]** → a readiness line with a bounded wait (fail with the captured
  stderr), an ephemeral port chosen by Deno and printed back, and a shutdown hook. No retries: a flaky
  start is a finding.
- **[Build time]** → one process per test JVM; clause setup is a few local HTTP calls. `EVENT_FULL` costs
  ten joins. Measure in implementation; if it dominates, lower the local deployment's capacity rather than
  skip the clause.
- **[A backend change silently skips the contracts via Gradle up-to-date]** → declared task inputs (D8).
- **[Contributors without Deno]** → loud failure naming the fix. `local-backend` already requires Deno for
  anyone touching `api/`.
- **[The mini-edge fails clauses on its first run]** → expected, and the point. Each failure is fixed in
  the mini-edge (it is the fake), or the clause is narrowed if the real edge's behaviour is not a promise
  the app relies on. Neither is resolved by marking the state unreachable, which the gate would catch
  anyway.
- **[Widening the observation-handle rule invites call transcripts]** → the delta keeps "outcomes, never
  a record of which collaborator was called". `GateObservation` reports the refusal state the app holds,
  which is what `min-app-version` renders.
- **[Phase 11 deletes `:test:world`]** → the mini-edge binding goes with it. Every clause keeps its live
  binding, so the coverage gate stays green through that deletion.

## Migration Plan

Additive. Order: ephemeral `serve.ts` mode → `LiveEdge` fixture with one smoke contract (`EventDirectory`)
green on the JVM → the remaining contracts and live bindings → the mini-edge binding (fix what it fails) →
the `AttestClient` fake binding → `build.yml` Deno → spec deltas synced at archive. Rollback is a revert.
No production code changes.

## Implementation findings

- **Setup lives in `:test:contracts`** (`EdgeSetup`, over a plain `HttpClient` and base URL), not on the
  fixture. The mini-edge binding in `:test:world` must enter states the same way, and it cannot depend on
  another module's test source set. `:test:contracts` gains `ktor-client-core` (API: it is in
  `EdgeSetup`'s constructor) and `kotlinx-serialization-json`.
- **A clause's subject carries the addresses its state was entered at** (`Seeded`: the event id the edge
  minted, the device, the seeded asset). An edge mints event ids, so, unlike the Keychain's, they cannot
  be derived from the clause id.
- **`EVENT_FULL` is entered by joining until the edge answers `409`**, not by joining a restated capacity
  (which would drift from the deployment it came from). It costs about 0.4 s on the live edge.
- **The live run:** all nine contracts in about 2.5 s, plus process start.
- **Drift the contracts found in the mini-edge:** it accepted an event window longer than 30 days; the real
  edge refuses it. Fixed in the mini-edge. The mini-edge also gained the v2 byte-upload route
  (`PUT /files/devices/<d>/<asset>/<role>?filename=`), without which it could not enter "a device holds
  uploads" through its public surface.
- **Resolved:** leaving an unknown event is a failure (the edge answers `404`, and the port surfaces it). The
  mini-edge is not taught `401`: rejection stays live-only, which the coverage gate accepts.

## Archive gate: delta completeness

| module touched | capability | delta or reason |
|---|---|---|
| `:test:contracts` | `port-contracts` | delta: the observation handle over outcomes a port cannot return; an external service is not a host. The nine contracts are code, which is their specification (no spec restates clauses) |
| `:adapter:generic:app` (`jvmTest`, build script, `HttpAttestClient` KDoc) | `port-contracts`, `testing-architecture` | deltas: the live binding's JVM-only forgo; the canonical check needs Deno and declares the backend's sources as inputs. The KDoc change is documentation only |
| `:test:world` (mini-edge + `commonTest`) | `harness-world-model` | deltas: drift is no longer accepted on contracted routes; the v2 byte-upload route. The window-maximum fix brings the mini-edge in line with `event-limits` as the real edge already implements it, so `event-limits` needs no delta |
| `:test:architecture` (`MainLaneContainmentTest`) | `architecture-guards` | none: behaviour-preserving to the spec, which already exempts "test source sets"; the guard's path heuristic now recognises `src/<name>Test/` as one |
| `api/src/dev/serve.ts` | none | dev infrastructure, non-gating, with no spec (`local-backend`), never bundled. Its use by the canonical check is stated in `testing-architecture` |
| `.github/workflows/build.yml` | `testing-architecture` | covered by its delta (Deno for the canonical check). `ci-build`, which that spec cites, does not exist and is not created here |
| `gradle/libs.versions.toml`, `architecture/` | none | a test-only engine entry; regenerated diagrams (the new `:test:world → :test:contracts` edge) |
| `CLAUDE.md`, `.claude/skills/local-backend` | none | documentation |
