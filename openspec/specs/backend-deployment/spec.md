# backend deployment Specification

## Purpose

How the `api/` is **deployed** and how it is **configured** — one capability, because on this
platform they are the same argument (below). A path-scoped GitHub Actions workflow runs the Deno checks
on every branch, bundles to a single file, and on `main` deploys that bundle to **bunny Edge
Scripting** — the one runtime. The device-facing origin is the custom domain `snapsync.stho.net` (a zone
we control via Bunny DNS, publicly-trusted cert), `CNAME`'d to the bunny pull zone that fronts the Edge
Script. Isolated from the Gradle/iOS workflows; no Bunny credential in source.

**Why configuration lives here, and why it lives in source.** Bunny issues **no scoped API key**:
writing an Edge Script's environment variables requires the full-access **account** key, which also owns
the storage zone holding every user's photos and our DNS zone. CI therefore holds only the
*script-scoped deploy key* — and so **CI can ship code but cannot ship config**. That is not a
hypothetical: it is exactly how this backend died. A change added two required environment variables,
set them on the then-active second runtime only, and left the bunny script fail-closed at boot for two
weeks — with CI green throughout, because `POST /code` + `/publish` succeed whether or not the script
boots. Its `BUNNY_STORAGE_ZONE` even named a zone that does not exist, and every artifact in the repo
agreed with it; only the running system knew otherwise.

So the least-privilege rule and the source-owned config are **one argument, not two**: because CI may
not hold the account key, config cannot be CI-managed, therefore every non-secret value is a **source
constant** that ships in the same bundle as the code that reads it. Drift is not *detected* — it is
*impossible*. A future change that admits the account key to CI to "fix config drift" is trading that
blast radius away; a future change that moves a non-secret value back into the environment reopens the
silent-corpse failure with **nothing watching** (there is deliberately no boot probe).

The **owned domain** is the other standing property: the device-facing host is baked into the app at
compile time (the OS-driven upload extension permits exactly one upload host), so an app rebuild is the
one thing a runtime outage must never require. Owning the name is what let the previous runtime be
retired with a DNS repoint instead of a forced TestFlight round. Keep it that way.

Decision record: `changes/archive/2026-06-22-add-bunny-upload-endpoint` (the pipeline),
`changes/archive/2026-06-30-add-custom-domain` (the owned origin),
`changes/archive/2026-07-14-migrate-runtime-to-bunny` (bunny as the sole runtime; config into source;
the fold of the former `backend-config` capability into this one).
## Requirements
### Requirement: Path-scoped, isolated workflow; deploy on main only

The system SHALL provide a GitHub Actions workflow that runs the checks on **every push** touching the
backend sources or the assets the bundle embeds (path-scoped to `api/**`, `screenshots/**`, and the
workflow file itself, on any branch), and SHALL run the deploy step **only** when the ref is `main`. On
`main` it SHALL deploy the bundled backend to the **bunny Edge Script** — the single runtime. It SHALL be
isolated from the Gradle/iOS workflows (its own workflow file; it SHALL NOT couple to the Gradle build or
iOS jobs). The workflow SHALL NOT hold or use any Deno Deploy credential, and SHALL NOT configure platform
environment variables (there are none it can set — see "Non-secret configuration is source-owned").

`screenshots/**` is in scope because the served page embeds images **derived from those files at build
time**: were the filter to cover only `api/**`, refreshing a capture would leave the live page serving
the previous screenshots until an unrelated backend change happened to redeploy it. The widening is exact,
not a guess at a dependency graph — the named path *is* the derive's input.

Deriving the page's images SHALL NOT require any tool beyond the backend's own runtime, so that the checks
remain runnable from a fresh clone with no additional system dependency.

#### Scenario: Runs checks on any branch touching the backend

- **WHEN** a push to any branch touches files under `api/**`
- **THEN** the workflow runs the checks

#### Scenario: Runs checks on any branch touching the embedded captures

- **WHEN** a push to any branch touches files under `screenshots/**`
- **THEN** the workflow runs the checks

#### Scenario: Does not run when neither the backend nor the captures change

- **WHEN** a push touches only files outside `api/**` and `screenshots/**`
- **THEN** the workflow does not run

#### Scenario: A capture refresh redeploys the page

- **WHEN** a push to `main` touches only files under `screenshots/**`
- **THEN** the checks run and the deploy step ships a bundle embedding images derived from those captures

#### Scenario: The checks need no tool beyond the backend runtime

- **WHEN** the checks run on a fresh clone with only the backend runtime installed
- **THEN** the page's images are derived and the checks pass, with no additional system dependency required

#### Scenario: Deploys to bunny only on main

- **WHEN** the checks pass on a push to `main`
- **THEN** the deploy step runs, shipping the bundle to the bunny Edge Script
- **AND WHEN** the checks pass on a push to a non-`main` branch
- **THEN** the deploy step is skipped

### Requirement: Device-facing origin is a custom domain under our control

The device-facing origin SHALL be a **custom domain we control** through our own DNS (a Bunny DNS
zone) — not a runtime-provider vanity hostname. It SHALL be `CNAME`'d to the bunny pull zone fronting
the Edge Script and served with a **publicly-trusted TLS certificate** (default ATS applies; no
`NSAppTransportSecurity` exception ships, so a non-HTTPS or privately-signed origin is unacceptable).
The compile-time baked host (`BACKGROUND_UPLOAD_URL_BASE` / `BackgroundUploadURLBase`) SHALL be **this
custom domain**, so device→backend traffic for uploads, event creation, and listings shares one origin
we own. Photo **download** bytes do **not** share this origin — they are served by bunny's S3 endpoint
(`<region>-s3.storage.bunnycdn.com`) against a presigned URL, itself a publicly-trusted HTTPS host
covered by default ATS with no exception.

#### Scenario: App reaches the backend over the custom domain via HTTPS

- **WHEN** the app issues an upload, event-creation, or list request
- **THEN** it targets the custom domain over HTTPS, which presents a publicly-trusted certificate

#### Scenario: The baked host names the custom domain, not a bunny hostname

- **WHEN** the baked `BackgroundUploadURLBase` is inspected
- **THEN** it names the custom-domain origin we control, not the pull zone's bunny-provided hostname

#### Scenario: Download bytes come from bunny's S3 endpoint, not the custom domain

- **WHEN** the app downloads a photo's bytes via a presigned `url`
- **THEN** the request targets bunny's S3 endpoint over HTTPS (default ATS, no exception), not the
  custom-domain origin

### Requirement: Runtime swaps are a DNS repoint, not an app rebuild

Changing **which runtime** serves the device-facing origin SHALL remain achievable by repointing DNS,
**without** changing the baked host literal or shipping a new app build — because that origin is a
custom domain we control. The baked `BackgroundUploadURLBase` SHALL NOT be a runtime-provider-owned
vanity hostname, since that would couple a runtime swap to a forced rebuild.

This is a **standing property**, not a description of a live failover: only one runtime is deployed.
Its purpose is to keep the escape hatch open. Baking a bunny-owned hostname would close it, and the one
thing a runtime outage must never require is an app rebuild — the OS-driven upload extension permits
exactly one upload host, fixed at compile time.

#### Scenario: Switching the runtime would require no new build

- **WHEN** the runtime serving the device-facing origin is changed
- **THEN** the change is achieved by repointing the custom domain via DNS
- **AND** no new IPA is built and no reinstall is required for already-installed devices to follow

#### Scenario: The baked host is never a provider vanity hostname

- **WHEN** the compile-time upload host is set
- **THEN** it is a domain we control, never a hostname owned by whichever runtime currently serves it

### Requirement: Deploy is gated on green checks

The workflow SHALL run, before the deploy step, the full check set — `deno fmt --check`, `deno lint`,
`deno check src/*.ts` (type-checks all source, incl. `main.ts`/SDK wiring the test run does not
reach), and the `Deno.test` suite — and the deploy step SHALL execute **only** when all of them
pass. Any failing check SHALL block deployment.

#### Scenario: A failing check blocks deploy

- **WHEN** any of `deno fmt --check`, `deno lint`, `deno check src/*.ts`, or `deno test` fails
- **THEN** the deploy step does not run and the workflow fails

#### Scenario: All checks green permit deploy

- **WHEN** every check passes
- **THEN** the deploy step runs

### Requirement: Deploy a bundled single file

The deploy action uploads the given `file` **verbatim** — it does NOT bundle — so the workflow SHALL
bundle the project entry and all its imports into one self-contained file (`deno bundle src/main.ts -o
dist/main.js`) and deploy **that bundle**. The bundle SHALL stay within the Edge Scripting **10 MB**
script limit. (Deploying the raw entry leaves `import` specifiers unresolved and the script errors on
every request.)

#### Scenario: A single bundled file is deployed

- **WHEN** the deploy step runs
- **THEN** it uploads one self-contained bundle (no unresolved imports), not the raw entry file

### Requirement: Deploy with secret-held, script-scoped credentials

The workflow SHALL deploy the bundled file to the configured Edge Scripting app using a
**script-scoped deploy key** and the **script id**, each supplied **only** as a GitHub Actions secret.
The Bunny **account API key** SHALL NOT be used by the deploy workflow (it is needed only to provision
the zone/app and to set the two runtime secrets). No Bunny credential SHALL appear in source or in the
workflow file.

This is load-bearing, not hygiene. Bunny issues **no scoped API keys**: the account key that could
write an Edge Script's environment variables also owns the storage zone holding every user's photos and
the DNS zone serving the device-facing origin. Keeping it out of CI is therefore why CI cannot manage
platform config at all — which is why non-secret config lives in source. A future change that admits
the account key to CI to "fix config drift" SHALL be understood to be trading that blast radius away.

The two runtime secrets (`BUNNY_STORAGE_ACCESS_KEY`, `APNS_PRIVATE_KEY`) SHALL be configured as Edge
Script environment values, **not** as deploy-workflow secrets — they are the endpoint's runtime config,
not CI credentials.

#### Scenario: Deploy uses secret-held, script-scoped credentials

- **WHEN** the deploy step runs
- **THEN** it authenticates using a script id and deploy key sourced from GitHub Actions secrets, and
  no credential is present in the repository or workflow file

#### Scenario: The account API key is absent from CI

- **WHEN** the deploy workflow is inspected
- **THEN** it holds no bunny account API key, and performs no platform-configuration write

### Requirement: Idempotent deploy target

Each deploy SHALL target the same Edge Scripting app/script, overwriting the prior deployment;
repeated deploys SHALL NOT create new or versioned scripts.

#### Scenario: Redeploy overwrites

- **WHEN** the workflow deploys a second time
- **THEN** it updates the same Edge Scripting app rather than creating an additional one

### Requirement: bunny Edge Scripting is the device-facing runtime

**bunny Edge Scripting** SHALL be the device-facing runtime — the runtime the device-facing origin
resolves to and that serves the app's uploads, event creation, listings, device-config writes, and
notify fan-out. It SHALL be the **only** runtime the backend is deployed to; no second runtime SHALL be
deployed or kept warm. Photo **downloads** are **not** served by the runtime: the list/union `url` is a
presigned S3 GET URL and the device fetches those bytes **directly from bunny's S3 endpoint**, off the
runtime entirely.

The Edge Script is fronted by a bunny **CDN pull zone**, so every device-facing request traverses the
CDN before reaching the script. Any endpoint behavior the device depends on SHALL hold **as observed
through the pull zone**, not merely at the origin.

#### Scenario: The device-facing origin resolves to bunny Edge Scripting

- **WHEN** the app reaches the device-facing origin for an upload, event creation, or list request
- **THEN** the request is served by the bunny Edge Script, fronted by its pull zone

#### Scenario: Downloads bypass the runtime

- **WHEN** the app downloads a collected photo's bytes
- **THEN** it fetches them directly from bunny's S3 endpoint via the presigned `url`, not from the
  runtime

#### Scenario: No second runtime is deployed

- **WHEN** the deploy step runs on `main`
- **THEN** the bundle is shipped to the bunny Edge Script and to no other runtime

### Requirement: Non-secret configuration is source-owned, not environment-owned

Every **non-secret** runtime configuration value SHALL be a constant in the backend source — the
storage zone name, the storage native host, the S3 region, the S3 endpoint host, and the APNs key id,
team id, and topic. These are public facts, not credentials. **Source wins**: the backend SHALL NOT
consult the environment for any of them, so a stale or wrong platform variable cannot override the
committed value.

This exists because bunny offers **no scoped API key** — writing an Edge Script's variables requires
the full-access account key, which the deploy workflow is forbidden to hold (see "Deploy with
secret-held, script-scoped credentials"). CI can therefore ship code but not config. Source-owned
config closes that gap structurally: a newly-required non-secret value cannot ship without its value,
because they ship as one artifact.

#### Scenario: A non-secret value is read from source, not the environment

- **WHEN** the backend boots
- **THEN** it takes the zone, native host, S3 region, S3 host, and APNs key id / team id / topic from
  source constants, and reads no environment variable for any of them

#### Scenario: A platform variable cannot override a source constant

- **WHEN** an Edge Script environment variable is set whose name matches a non-secret config value
- **THEN** the backend ignores it and uses the source constant

#### Scenario: A new non-secret config value cannot drift

- **WHEN** a change introduces a new non-secret configuration value
- **THEN** its value ships in the same bundle as the code that reads it, and no platform-side step is
  required for the deployment to boot

### Requirement: Secrets-only environment, fail-closed

The backend SHALL read exactly **four** values from the Edge Script environment, all of them genuine
credentials: the storage-zone `AccessKey` (`BUNNY_STORAGE_ACCESS_KEY`, which doubles as the S3 secret),
the APNs Auth Key PEM (`APNS_PRIVATE_KEY`), the device-token signing key (`ATTEST_TOKEN_KEY`, which
signs and verifies the bearer tokens of capability `device-attestation`), and the notify **admin key**
(`ADMIN_NOTIFY_KEY`, which authorizes the scheduled cleanup's silent-push notify of an expiring event's members;
capabilities `event-notify-endpoint`, `scheduled-cleanup`). **No secret SHALL appear in source.** All
SHALL be validated **once at startup**; a missing or blank value SHALL cause startup to fail (the parse
throws), so a misconfigured deployment does not serve and never operates against an unauthenticated
target. The validated config SHALL be injected into the request handlers, which therefore have no
per-request configuration failure path.

Because CI holds only the script-scoped deploy key and **cannot write the script's environment**, a new
secret SHALL be set in the Edge Script environment **before** the code that reads it is merged to `main`.
Merging first makes the script fail to boot on the next deploy — a total outage until the secret is set
by hand. (This ordering is not hypothetical: a change that added required env vars without setting them
left this backend fail-closed at boot for two weeks, with CI green throughout.)

The scheduled cleanup (capability `scheduled-cleanup`) runs **outside** the Edge Script and holds
`BUNNY_STORAGE_ACCESS_KEY` and `ADMIN_NOTIFY_KEY` as **its own workflow's** GitHub Actions secrets. This does
not admit the Bunny **account** key to CI (the prohibition that keeps config CI-unmanageable is
unchanged) — it grants only the storage-zone key and the notify-only admin key to one non-deploy
workflow.

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

#### Scenario: Missing admin key fails the boot

- **WHEN** `ADMIN_NOTIFY_KEY` is absent or blank at startup
- **THEN** config parsing throws, the backend does not start, and no request is ever served

#### Scenario: No other variable is required to boot

- **WHEN** the four secrets are present and no other environment variable is set
- **THEN** the backend boots and serves

#### Scenario: Configuration is injected, not read per-request

- **WHEN** a request is handled
- **THEN** it uses the config validated at startup and has no per-request configuration failure path

### Requirement: Apple's App Attest root CA is a source constant

Apple's App Attest **root certificate** SHALL be a **source constant**, committed in the backend source,
and SHALL NOT be read from the environment. It is the trust anchor every attestation's certificate chain
is verified against (capability `device-attestation`).

It meets the existing criterion exactly: it is a **public fact** (Apple publishes it), so committing it
exposes nothing, and shipping it in the same bundle as the code that reads it means a verification change
cannot be deployed without its trust anchor.

#### Scenario: The trust anchor ships with the code that uses it

- **WHEN** the backend bundle is deployed
- **THEN** Apple's App Attest root CA is present in the bundle, and no environment variable is consulted
  for it

#### Scenario: A platform variable never overrides the trust anchor

- **WHEN** an environment variable naming a root CA is set on the Edge Script
- **THEN** it is ignored; the source constant is used

### Requirement: Device-API routes are served under a versioned prefix, with bare paths a deprecated grace alias

The device-API routes SHALL be served under a **versioned path prefix** of the form `/api/vN`, where the
current version is **`/api/v1`** (e.g. `POST /api/v1/events`, `PUT /api/v1/files/devices/:id/:filename`,
`GET /api/v1/attest/challenge`). The routing SHALL be structured so that additional versions can be mounted
alongside `/api/v1` without restructuring the existing version's routes.

For a grace period, the **bare (un-prefixed) paths** SHALL remain served as a **deprecated alias** of the
current version, returning behavior identical to their `/api/v1` counterparts, so that already-installed
apps — whose device-facing host is baked at compile time and cannot be force-updated — are not broken.
Removing the bare alias is a separate, later change; until then it SHALL keep working.

The **web/link paths** — `/`, `/join`, and `/.well-known/apple-app-site-association` — SHALL remain at the
**root, un-prefixed**, and SHALL NOT be served under `/api/v1`. They are not device-API routes; Apple's CDN
and browsers require fixed paths for the AASA and the `/join` universal link.

The **auth gate** SHALL apply identically to the `/api/v1` routes and their bare aliases: authenticated
routes SHALL require a valid bearer token under both, and the ungated attest bootstrap routes (`attest/*`)
SHALL remain ungated under both `/api/v1/attest/*` and the bare `/attest/*` — so that token issuance is
never gated on possessing a token.

The compile-time device-facing base host baked into the app
(`BACKGROUND_UPLOAD_URL_BASE` / `BackgroundUploadURLBase`) SHALL carry the current version prefix, so that
every device-API request the app and upload extension compose from that base targets `/api/v1`. The
separate web/link origin constant SHALL NOT carry the prefix.

#### Scenario: A device-API route resolves under the version prefix

- **WHEN** the app issues a device-API request under `/api/v1` (e.g. `POST /api/v1/events`) through the
  pull zone
- **THEN** it is served identically to the corresponding bare route, with the same status and the same
  upstream effect

#### Scenario: The bare path still resolves as a deprecated alias

- **WHEN** an already-installed app issues the same request at the bare path (e.g. `POST /events`)
- **THEN** it is served identically to the `/api/v1` route, so no installed app is broken during the grace
  period

#### Scenario: Web/link paths stay at the root, never under the prefix

- **WHEN** Apple's CDN fetches `/.well-known/apple-app-site-association`, a browser opens `/join`, or the
  marketing page is requested at `/`
- **THEN** each is served at its bare root path
- **AND** the same web/link paths are NOT served under `/api/v1`

#### Scenario: Attest bootstrap stays ungated under the prefix

- **WHEN** the app requests `GET /api/v1/attest/challenge` or `POST /api/v1/attest/token` with no bearer
  token
- **THEN** the request is served (the attest routes are ungated), just as the bare `/attest/*` routes are

#### Scenario: An authenticated route requires a token under both forms

- **WHEN** a non-attest device-API request is made under either `/api/v1/…` or the bare path without a
  valid bearer token
- **THEN** it is rejected by the auth gate under both forms

#### Scenario: The baked base host carries the version prefix

- **WHEN** the compile-time `BackgroundUploadURLBase` is inspected
- **THEN** it carries the current version prefix (`/api/v1`), so every device-API URL the app and upload
  extension compose targets `/api/v1`
- **AND** the separate web/link origin constant does not carry the prefix

#### Scenario: The routing admits a future version without restructuring

- **WHEN** a future API version is introduced
- **THEN** it is added as an additional versioned mount alongside `/api/v1`, without changing `/api/v1`'s
  routes

