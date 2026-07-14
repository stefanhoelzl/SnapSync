## ADDED Requirements

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

The backend SHALL read exactly **two** values from the Edge Script environment, both of them genuine
credentials: the storage-zone `AccessKey` (`BUNNY_STORAGE_ACCESS_KEY`, which doubles as the S3 secret)
and the APNs Auth Key PEM (`APNS_PRIVATE_KEY`). **No secret SHALL appear in source.** Both SHALL be
validated **once at startup**; a missing or blank value SHALL cause startup to fail (the parse throws),
so a misconfigured deployment does not serve and never operates against an unauthenticated target. The
validated config SHALL be injected into the request handlers, which therefore have no per-request
configuration failure path.

#### Scenario: Missing storage AccessKey fails the boot

- **WHEN** `BUNNY_STORAGE_ACCESS_KEY` is absent or blank at startup
- **THEN** config parsing throws, the backend does not start, and no request is ever served

#### Scenario: Missing APNs private key fails the boot

- **WHEN** `APNS_PRIVATE_KEY` is absent or blank at startup
- **THEN** config parsing throws, the backend does not start, and no request is ever served

#### Scenario: No other variable is required to boot

- **WHEN** the two secrets are present and no other environment variable is set
- **THEN** the backend boots and serves

#### Scenario: Configuration is injected, not read per-request

- **WHEN** a request is handled
- **THEN** it uses the config validated at startup and has no per-request configuration failure path

## MODIFIED Requirements

### Requirement: Path-scoped, isolated workflow; deploy on main only

The system SHALL provide a GitHub Actions workflow that runs the checks on **every push** touching the
backend sources (path-scoped to `backend/**` and the workflow file itself, on any branch), and SHALL
run the deploy step **only** when the ref is `main`. On `main` it SHALL deploy the bundled backend to
the **bunny Edge Script** — the single runtime. It SHALL be isolated from the Gradle/iOS workflows (its
own workflow file; it SHALL NOT couple to the Gradle build or iOS jobs). The workflow SHALL NOT hold or
use any Deno Deploy credential, and SHALL NOT configure platform environment variables (there are none
it can set — see "Non-secret configuration is source-owned").

#### Scenario: Runs checks on any branch touching the backend

- **WHEN** a push to any branch touches files under `backend/**`
- **THEN** the workflow runs the checks

#### Scenario: Does not run when the backend is unchanged

- **WHEN** a push touches only files outside `backend/**`
- **THEN** the workflow does not run

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

## REMOVED Requirements

### Requirement: Deno Deploy is the active device-facing runtime

**Reason**: bunny has fixed the zero-window upload SYN drop that benched its Edge Scripting runtime.
Deno Deploy was a temporary failover, never the intended runtime, and keeping a second deployed runtime
proved worse than useless: the benched bunny script silently failed to boot for two weeks (seven of ten
required environment variables absent) while CI reported green, because nothing pointed at it and
nothing probed it. An unexercised fallback is not a fallback.

**Migration**: The device-facing origin `snapsync.stho.net` is repointed from Deno Deploy to the bunny
pull zone fronting the Edge Script, with its TLS certificate pre-provisioned via bunny's DNS-01 flow so
no request sees an invalid cert. The Deno Deploy deploy steps, the `DENO_DEPLOY_TOKEN` secret, the
`deploy:` block in `backend/deno.json`, the runtime branch in `main.ts`, and the Deno Deploy app itself
are all removed. No iOS build is required: the baked host is the custom domain, so installed devices
follow DNS. Accepted consequence: bunny is now load-bearing with no fallback runtime — a bunny outage
is a SnapSync outage, mitigated only by the ledger's retry-forever semantics (uploads are delayed,
never lost).
