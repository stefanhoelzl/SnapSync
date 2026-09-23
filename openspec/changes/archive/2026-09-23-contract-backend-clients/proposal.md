## Why

Nothing checks the app's backend clients against the real backend. Each `Http*` client is tested against
hand-written JSON in a `MockEngine`. The world harness's mini-edge is a second hand-written copy of the edge,
and its spec explicitly accepts drift ("no golden fixture"). `api/` tests itself in Deno. Three copies of
one wire contract, and nothing fails when they disagree: a renamed field, a changed status code or a moved
version prefix passes every test in the repository and 404s on a device. This is phase 7 of the
testing-concept sequence. It takes the backend through the port-contract mechanism, and it is the one
external system a CI runner can run for real with no device at all.

## What Changes

- **Backend port contracts** in `:test:contracts`, one per port that talks to the edge: `EventCreation`,
  `EventDirectory`, `EventRename`, `EventJoin`, `ManifestPublisher`, `EventUnionSource`,
  `DeviceFilesSource`, `LeaveNotifier`, and `AttestClient`'s `challenge()`. Each clause is hand-written and
  conditioned on a backend state (an event that exists, one that does not, a full event, a device holding
  uploads, a build the backend refuses).
- **The implementation under contract is client + edge.** The `Http*` client is the same in every
  binding; the edge behind it decides the binding's kind. The mini-edge is a `Fake`. The real `api/`
  (`src/dev/serve.ts` against a filesystem store) is `Live`.
- **A live-`api` binding on `JVM`.** `:adapter:generic:app`'s `jvmTest` starts one Deno process per test
  JVM, on loopback, against a temp store. Each clause gets a fresh client, a freshly created event and fresh
  device ids. This binding is required, not optional: `port-contracts` admits no clause that only a fake
  reaches, and the mini-edge is the fake.
- **A mini-edge binding** in `:test:world`'s `commonTest` (JVM and simulator). A clause the real `api`
  passes and the mini-edge fails is now a mini-edge defect, not accepted drift.
- **The edge's gate rules become clauses.** The server half of the credential interceptor's four rules
  (a rejected token answers `401`; a build below the minimum answers `426` naming the minimum the client
  parses) is asserted through an observation handle over the interceptor's callbacks. The interceptor
  itself gets **no contract of its own**. It is one client-side implementation, already covered by
  `CredentialInterceptorTest`, and it is not a port.
- **`dev/serve.ts` gains an ephemeral mode** for a test to launch: port `0`, no `.localdev/host` write
  (which would clobber a developer's running rig), and one greppable readiness line carrying the origin.
  It runs with `--allow-net` limited to loopback, so reaching the bunny zone is a permission error, as it
  already is for `api`'s own tests.
- **CI:** the `build` job installs Deno (the same `setup-deno` pin `api.yml` uses). `./gradlew build`
  now needs `deno` on `PATH`. A missing Deno fails the live binding loudly and never skips it.
- **Not contracted here, with reasons in design:** `PushHttpClient` (a generic `put`/`post`-by-URL
  transport, so the endpoint shape lives in `PushRegistration`, not in the port); `AttestClient`'s
  `mintToken`/`renewToken` (no JVM host can produce an App Attest attestation); the byte upload
  `PUT /files/devices/…` (driven by iOS-only transports).

## Capabilities

### New Capabilities

None. The contracts are code; `port-contracts` specifies the mechanism, and no spec restates clauses.

### Modified Capabilities

- `port-contracts`: a clause's subject MAY carry an observation handle whenever the outcome it asserts is
  not readable through the port (not only for ports that declare no reads). This admits the edge's gate
  outcomes, which reach the app only through the interceptor's callbacks. An external service a test
  launches is a `Live` implementation on the host that launched it, never a new host.
- `harness-world-model`: "Backend object store with faithful read-models" stops accepting drift on the
  routes the backend contracts cover. The mini-edge is bound as those contracts' `Fake`, and the real
  `api` passing the same clauses is its golden reference. "MockEngine mini-edge over the four common-Ktor
  seams" gains the v2 byte-upload route, so the contracts' setup reaches "a device holds uploads" on the
  mini-edge the same way it does on the real edge.
- `testing-architecture`: "Every test runs on every target its module declares" records
  `:adapter:generic:app`'s `jvmTest` live-edge binding as a genuine forgo (Kotlin/Native cannot launch a
  Deno process). "The canonical check and its Kotlin/Native half" states that the canonical check needs
  Deno.

## Impact

- **New code (test-only):** nine contracts plus state vocabularies in `:test:contracts`; the mini-edge
  binding in `:test:world` `commonTest`; the live-edge fixture and bindings in `:adapter:generic:app`
  `jvmTest` (adds a JVM Ktor client engine to that test set only); an `InMemoryAttestClient` binding in
  `:adapter:generic:fake` `commonTest`.
- **Changed:** `api/src/dev/serve.ts` (ephemeral mode; dev infrastructure, never bundled). `build.yml`
  (setup-deno). The `:adapter:generic:app` JVM test task declares `api/src` and `api/migrations` as
  inputs, so a backend change re-runs the contracts instead of being up-to-date.
- **No production behaviour changes.** Label `internal`. No module added, so `module-architecture` is
  untouched.
- **Never touched:** the `snap-sync-dev` zone. The live binding cannot reach it (filesystem store,
  loopback-only net) and creates its own events; no clause joins an event it did not create.
- **Sequence:** phase 11 deletes `:test:world` and with it the mini-edge binding. The contracts keep the
  live binding, so they survive that deletion. The live-edge fixture built here is the backend that
  phases 9–10's rig-based integration will need.
