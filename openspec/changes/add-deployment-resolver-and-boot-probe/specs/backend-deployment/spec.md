## ADDED Requirements

### Requirement: Deploy is gated on a post-publish boot probe

After publishing on `main`, the workflow SHALL probe the device-facing origin until it observes the bundle
it just deployed, and SHALL fail the run otherwise. This exists because `POST /code` + `POST /publish`
succeed whether or not the deployed bundle can boot, so a green deploy step is **not** evidence that the
script serves.

The probe SHALL be satisfied only by a response that identifies **the bundle this run deployed**. A bare
success is insufficient: it cannot distinguish the new deployment from the previous one still being served,
which is the failure the probe exists to catch.

The probe SHALL retry only causes that time can resolve — a connection failure, a server error, a
not-found, or a response identifying a *different* bundle — up to a bounded deadline, and SHALL fail
**immediately** on causes that waiting cannot fix, naming which. Retrying a terminal cause until a deadline
turns a specific bug into a timeout.

The probe SHALL target the **device-facing origin**, so a green probe also witnesses the DNS, certificate
and pull-zone path a device traverses, per "bunny Edge Scripting is the device-facing runtime". It SHALL
NOT target a runtime-provider hostname: that would report success while the device-facing path was broken,
and would place a provider-owned name in CI.

The probe SHALL NOT roll back. Bunny's release re-publish is authenticated by the **account** key, which
this workflow is forbidden to hold; the script-scoped deploy key is refused (`401`) by the release
endpoints. Detection is the deliverable and remediation is not attempted.

**What a green probe does not prove.** It witnesses that the script booted and that this bundle is serving.
It does **not** witness that any configured value is *correct*: a value that is present but wrong boots and
probes green. Probe coverage is exactly the set of faults that startup turns into a failure to boot.

Deploys SHALL be serialized, so a probe always observes its own deploy rather than a later one.

#### Scenario: A bundle that cannot boot fails the run

- **WHEN** the deployed bundle fails to start
- **THEN** the probe never observes it, and the workflow fails after its deadline naming what it last saw

#### Scenario: A stale bundle still serving fails the run

- **WHEN** the origin answers successfully but identifies a different bundle than the one just deployed
- **THEN** the probe retries until its deadline and then fails

#### Scenario: Propagation delay is not a failure

- **WHEN** the origin briefly refuses connections, errors, or serves the previous bundle immediately after
  publish, and then serves the new one within the deadline
- **THEN** the probe succeeds

#### Scenario: A terminal cause fails immediately

- **WHEN** the origin answers successfully but the response carries no usable bundle identity, or carries
  the identity used when CI supplied none
- **THEN** the probe fails at once, naming that cause, without waiting for the deadline

#### Scenario: A green probe is not a claim about configuration correctness

- **WHEN** the backend boots with a configured value that is present but wrong
- **THEN** the probe succeeds, and the failure is not detected by it

#### Scenario: Concurrent deploys do not produce a false failure

- **WHEN** two pushes to `main` touching the backend are deployed in quick succession
- **THEN** the deploys are serialized and each probe observes its own deploy

### Requirement: A health route reports the deployed bundle's identity

The backend SHALL serve an **unauthenticated** health route at the **root**, answering `GET` and `HEAD`
only, returning success with the identity of the bundle serving it, and carrying the listings' no-cache
directives so the pull zone cannot answer from a previous deployment's copy.

The route SHALL be **inert**: it SHALL read no storage, perform no cryptography, and contact no external
system. It SHALL have **no failure path** — configuration is validated once at startup, outside the request
handlers, so a configuration failure means the script does not serve at all rather than that this route
answers with an error. Distinguishing causes is the probe's responsibility, from the combination of
response status and body.

Serving it unauthenticated is accepted: it discloses only an identifier that is already public, and it does
strictly less work than routes that are already ungated and uncacheable.

#### Scenario: The health route is served without a token

- **WHEN** the health route is requested with no bearer token
- **THEN** it is answered successfully, carrying the identity of the bundle serving it

#### Scenario: The health route is not cached by the pull zone

- **WHEN** the health route is requested through the pull zone after a new bundle is published
- **THEN** the response is not served from a previous deployment's cached copy

#### Scenario: The health route touches nothing

- **WHEN** the health route is handled
- **THEN** no storage request, cryptographic operation, or external call is made

#### Scenario: A mutating method is not served

- **WHEN** the health route is requested with a method other than `GET` or `HEAD`
- **THEN** it is not served by that route

## MODIFIED Requirements

### Requirement: Non-secret configuration is deployment-resolved, not environment-owned

Every **non-secret** runtime configuration value SHALL come from the resolved deployment (capability
`deployment-configuration`) and SHALL be present in the deployed bundle — the storage zone name, the storage
native host, the S3 region, the S3 endpoint host, and the APNs key id, team id, and topic. These are public
facts, not credentials. **The resolved deployment wins**: the backend SHALL NOT consult the environment for
any of them, so a stale or wrong platform variable cannot override the deployed value.

This exists because bunny offers **no scoped API key** — writing an Edge Script's variables requires the
full-access account key, which the deploy workflow is forbidden to hold (see "Deploy with secret-held,
script-scoped credentials"). CI can therefore ship code but not platform config. Resolving the deployment at
build time closes that gap structurally, exactly as source constants did: a newly-required non-secret value
cannot ship without its value, because they ship as one artifact. What changes is only *where the value is
authored* — a declared deployment rather than a constant in backend source — so the same deployment
mechanism can serve a different account without editing code.

#### Scenario: A non-secret value is read from the bundle, not the environment

- **WHEN** the backend boots
- **THEN** it takes the zone, native host, S3 region, S3 host, and APNs key id / team id / topic from the
  resolved deployment shipped in the bundle, and reads no environment variable for any of them

#### Scenario: A platform variable cannot override a resolved value

- **WHEN** an Edge Script environment variable is set whose name matches a non-secret config value
- **THEN** the backend ignores it and uses the resolved value

#### Scenario: A new non-secret config value cannot drift

- **WHEN** a change introduces a new non-secret configuration value
- **THEN** its value ships in the same bundle as the code that reads it, and no platform-side step is
  required for the deployment to boot

#### Scenario: A different account needs no code change

- **WHEN** the backend is deployed against a different storage account
- **THEN** it is done by declaring and selecting a different deployment, with no change to backend source

### Requirement: Secrets-only environment, fail-closed

The backend SHALL read from the Edge Script environment **only** the values the resolved deployment declares
as runtime environment references, all of them genuine credentials: the storage-zone `AccessKey`
(`BUNNY_STORAGE_ACCESS_KEY`, which doubles as the S3 secret), the APNs Auth Key PEM (`APNS_PRIVATE_KEY`),
and the device-token signing key (`ATTEST_TOKEN_KEY`, which signs and verifies the bearer tokens of
capability `device-attestation`). **No secret SHALL appear in any authored file**; the deployment declares
the variable's **name**, never its value, and the bundle carries the name alone.

The set of required secrets SHALL be **derived from that declaration** rather than restated in code, so it
cannot drift from what the deployment actually needs. All SHALL be validated **once at startup**; a missing
or blank value SHALL cause startup to fail (the parse throws), so a misconfigured deployment does not serve
and never operates against an unauthenticated target. The validated config SHALL be injected into the
request handlers, which therefore have no per-request configuration failure path.

Because CI holds only the script-scoped deploy key and **cannot write the script's environment**, a new
secret SHALL be set in the Edge Script environment **before** the code that reads it is merged to `main`.
Merging first makes the script fail to boot on the next deploy — a total outage until the secret is set
by hand, now detected by the boot probe rather than silently. (This ordering is not hypothetical: a change
that added required env vars without setting them left this backend fail-closed at boot for two weeks, with
CI green throughout.) **Removing** a secret is safe in either order, since a value that is no longer read
cannot fail validation.

The scheduled cleanup (capability `scheduled-cleanup`) runs **outside** the Edge Script and holds
`BUNNY_STORAGE_ACCESS_KEY` as **its own workflow's** GitHub Actions secret — and nothing else. It makes no
request to the Edge Script, so it needs no credential authorizing one. This does not admit the Bunny
**account** key to CI (the prohibition that keeps config CI-unmanageable is unchanged): it grants only the
storage-zone key to one non-deploy workflow.

There SHALL be **no admin, master, or route-scoped bypass credential** anywhere in the backend. The former
notify admin key existed solely so the sweep could announce an expiring event before deleting it; with
that notify removed, no caller remains and the credential is retired rather than left as a standing
authorization path nobody exercises.

#### Scenario: Missing storage AccessKey fails the boot

- **WHEN** `BUNNY_STORAGE_ACCESS_KEY` is absent or blank at startup
- **THEN** config parsing throws, the backend does not start, and no request is ever served

#### Scenario: Missing APNs private key fails the boot

- **WHEN** `APNS_PRIVATE_KEY` is absent or blank at startup
- **THEN** config parsing throws, the backend does not start, and no request is ever served

#### Scenario: Missing token signing key fails the boot

- **WHEN** `ATTEST_TOKEN_KEY` is absent or blank at startup
- **THEN** config parsing throws, the backend does not start, and no request is ever served — the gate
  can never be silently absent

#### Scenario: The required set follows the deployment's declaration

- **WHEN** a deployment declares a runtime environment reference for a new secret
- **THEN** startup validation requires it without any separate list being edited

#### Scenario: The bundle carries names, never values

- **WHEN** the deployed bundle is inspected
- **THEN** it contains the environment variable names the deployment declared and none of their values

#### Scenario: No other variable is required to boot

- **WHEN** the declared secrets are present and no other environment variable is set
- **THEN** the backend boots and serves

#### Scenario: A retired secret left set is simply unread

- **WHEN** the Edge Script environment still carries a secret the deployment no longer declares
- **THEN** the backend boots and serves, reading it for nothing and authorizing nothing with it

#### Scenario: The sweep holds one credential

- **WHEN** the scheduled cleanup workflow's secrets are enumerated
- **THEN** the storage-zone `AccessKey` is the only one, and no credential grants access to the Edge
  Script

#### Scenario: Configuration is injected, not read per-request

- **WHEN** a request is handled
- **THEN** it uses the config validated at startup and has no per-request configuration failure path

### Requirement: Apple's App Attest root CA is deployment-declared, never environment-read

Apple's App Attest **root certificate** SHALL be declared in the deployment (capability
`deployment-configuration`), resolved into the deployed bundle, and SHALL NOT be read from the environment.
It is the trust anchor every attestation's certificate chain is verified against (capability
`device-attestation`).

It meets the existing criterion exactly: it is a **public fact** (Apple publishes it), so committing it
exposes nothing, and shipping it in the same bundle as the code that reads it means a verification change
cannot be deployed without its trust anchor.

#### Scenario: The trust anchor ships with the code that uses it

- **WHEN** the backend bundle is deployed
- **THEN** Apple's App Attest root CA is present in the bundle, and no environment variable is consulted
  for it

#### Scenario: A platform variable never overrides the trust anchor

- **WHEN** an environment variable naming a root CA is set on the Edge Script
- **THEN** it is ignored; the deployment-declared value is used

### Requirement: Device-API routes are served under a versioned prefix

The device-API routes SHALL be served under a **versioned path prefix** of the form `/api/vN`, where the
current version is **`/api/v1`** (e.g. `POST /api/v1/events`, `PUT /api/v1/files/devices/:id/:filename`,
`GET /api/v1/attest/challenge`). The routing SHALL be structured so that additional versions can be mounted
alongside `/api/v1` without restructuring the existing version's routes.

The **web/link paths** — `/`, `/join`, and `/.well-known/apple-app-site-association` — and the **operational
health route** SHALL remain at the **root, un-prefixed**, and SHALL NOT be served under `/api/v1`. The
web/link paths are not device-API routes; Apple's CDN and browsers require fixed paths for the AASA and the
`/join` universal link. The health route is not a device-API route either — no device calls it — and keeping
it out of the versioned mount means a future `/api/vN` neither duplicates nor strands it.

The **auth gate** SHALL apply to the `/api/v1` routes: authenticated routes SHALL require a valid bearer
token, and the ungated attest bootstrap routes (`attest/*`) SHALL remain ungated under
`/api/v1/attest/*` — so that token issuance is never gated on possessing a token. The gate SHALL resolve
the prefix version-agnostically, so a future `/api/vN` mount is gated identically with no change to it.

The compile-time device-facing base host baked into the app
(`BACKGROUND_UPLOAD_URL_BASE` / `BackgroundUploadURLBase`) SHALL carry the current version prefix, so that
every device-API request the app and upload extension compose from that base targets `/api/v1`. The
separate web/link origin constant SHALL NOT carry the prefix.

#### Scenario: A device-API route resolves under the version prefix

- **WHEN** the app issues a device-API request under `/api/v1` (e.g. `POST /api/v1/events`) through the
  pull zone
- **THEN** it is routed to that device-API route and served with its documented status and upstream effect

#### Scenario: Web/link paths stay at the root, never under the prefix

- **WHEN** Apple's CDN fetches `/.well-known/apple-app-site-association`, a browser opens `/join`, or the
  marketing page is requested at `/`
- **THEN** each is served at its bare root path
- **AND** the same web/link paths are NOT served under `/api/v1`

#### Scenario: The health route stays at the root, never under the prefix

- **WHEN** the health route is requested at its bare root path
- **THEN** it is served
- **AND** it is NOT served under `/api/v1`

#### Scenario: Attest bootstrap stays ungated under the prefix

- **WHEN** the app requests `GET /api/v1/attest/challenge` or `POST /api/v1/attest/token` with no bearer
  token
- **THEN** the request is served (the attest routes are ungated)

#### Scenario: An authenticated route requires a token under the prefix

- **WHEN** a non-attest device-API request is made under `/api/v1/…` without a valid bearer token
- **THEN** it is rejected by the auth gate

#### Scenario: The baked base host carries the version prefix

- **WHEN** the compile-time `BackgroundUploadURLBase` is inspected
- **THEN** it carries the current version prefix (`/api/v1`), so every device-API URL the app and upload
  extension compose targets `/api/v1`
- **AND** the separate web/link origin constant does not carry the prefix

#### Scenario: The routing admits a future version without restructuring

- **WHEN** a future API version is introduced
- **THEN** it is added as an additional versioned mount alongside `/api/v1`, without changing `/api/v1`'s
  routes

### Requirement: Deploy is gated on green checks

The workflow SHALL resolve the deployment (capability `deployment-configuration`) before running any check,
because the checks type-check and exercise code that reads the resolved configuration. It SHALL then run,
before the deploy step, the full check set — `deno fmt --check`, `deno lint`, the type-check, and the
`Deno.test` suite — and the deploy step SHALL execute **only** when all of them pass. Any failing check
SHALL block deployment.

The type-check and test steps SHALL be invoked **through their `deno.json` tasks** (`deno task check`,
`deno task test`) rather than by restating the commands in the workflow, so the set of type-checked
directories and the permissions the suite runs under are defined in exactly one place and cannot drift
between CI and what a developer runs locally. The type-check SHALL cover **all** source — `src/` including
`main.ts`/SDK wiring the test run does not reach, the dev-only `src/dev/` tree (the local backend rig), and
the out-of-bundle `src/scripts/` tree (the programs other workflows invoke) — so a broken rig or a broken
job fails CI rather than only surfacing when someone next tries to use it.

The test task SHALL carry **only** the filesystem permissions its own suite needs (`--allow-read`,
`--allow-write`, for the dev storage shim's contract test). It SHALL NOT grant `--allow-net`: that absence
is what makes it impossible for a test to reach the real storage zone — a network call fails as a
permission error rather than silently becoming a live request against the zone holding real users' photos.
Resolving the deployment SHALL be a separate invocation, so it cannot widen the permissions the suite runs
under.

#### Scenario: A failing check blocks deploy

- **WHEN** any of `deno fmt --check`, `deno lint`, `deno task check`, or `deno task test` fails
- **THEN** the deploy step does not run and the workflow fails

#### Scenario: All checks green permit deploy

- **WHEN** every check passes
- **THEN** the deploy step runs

#### Scenario: The type-check reaches the dev-only tree

- **WHEN** a change breaks compilation anywhere under `api/src/dev/`
- **THEN** the type-check step fails and the deploy is blocked, even though nothing under `src/dev/`
  reaches the deployed bundle

#### Scenario: The type-check reaches the out-of-bundle programs

- **WHEN** a change breaks compilation anywhere under `api/src/scripts/`
- **THEN** the type-check step fails and the deploy is blocked, even though nothing under `src/scripts/`
  reaches the deployed bundle

#### Scenario: No test can reach the real storage zone

- **WHEN** a test attempts a network request
- **THEN** it fails as a Deno permission error, because the test task grants no `--allow-net`
