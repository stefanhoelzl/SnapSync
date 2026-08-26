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

So the least-privilege rule and the artifact-owned config are **one argument, not two**: because CI may
not hold the account key, config cannot be CI-managed, therefore every non-secret value ships **in the
same bundle as the code that reads it**, resolved from a declared deployment (capability
`deployment-configuration`). Drift is not *detected* — it is *impossible*. A future change that admits
the account key to CI to "fix config drift" is trading that blast radius away.

**There IS a boot probe now, and this is what it does and does not restore.** That property was
originally bought by source constants alone, and a boot probe was declined *conditionally*: with the
failure class made impossible, prevention replaced detection, on the stated condition that anything
reintroducing platform-side required config would reopen the silent-corpse failure with nothing
watching. Deployment-resolved config keeps the ships-as-one-artifact property but makes the values
portable, and platform-side database credentials arrived — so the condition fired and detection is back
(see "Deploy is gated on a post-publish boot probe"). Be precise about which half it restores: the probe
witnesses that the script **booted** and that **this bundle** is serving. It does **not** witness that any
configured value is *correct* — a value that is present but wrong boots and probes green, which is the
other half of the original outage, where `BUNNY_STORAGE_ZONE` named a zone that does not exist. Probe
coverage is exactly the set of faults startup turns into a throw, so a new platform-side value is covered
only if it is declared in a deployment (readable, diffable) or made to fail closed at boot.

The **owned domain** is the other standing property: the device-facing host is baked into the app at
compile time (the OS-driven upload extension permits exactly one upload host), so an app rebuild is the
one thing a runtime outage must never require. Owning the name is what let the previous runtime be
retired with a DNS repoint instead of a forced TestFlight round. Keep it that way.

Decision record: `changes/archive/2026-08-25-add-deployment-resolver-and-boot-probe` (deployment-resolved config
and the boot probe),
`changes/archive/2026-06-22-add-bunny-upload-endpoint` (the pipeline),
`changes/archive/2026-06-30-add-custom-domain` (the owned origin),
`changes/archive/2026-07-14-migrate-runtime-to-bunny` (bunny as the sole runtime; config into source;
the fold of the former `backend-config` capability into this one),
`changes/archive/2026-08-27-make-api-tests-required` (the api check set moved to its own unfiltered
workflow and became a required status check, making the gate pre-merge rather than pre-deploy).
## Requirements
### Requirement: Path-scoped, isolated workflow; deploy on main only

The system SHALL provide **two** GitHub Actions workflows for the backend, both isolated from the
Gradle/iOS workflows (each its own workflow file; neither SHALL couple to the Gradle build or iOS jobs):

- a **check workflow** that runs the check set (below) on **every push to any branch**, carrying **no
  path filter at all**, so the status check it posts appears on every ref. A required status check that
  is never posted is permanently pending, so a path-filtered check workflow would freeze every merge
  whose diff falls outside the filter;
- a **deploy workflow**, path-scoped to the backend sources and everything that decides what ships
  inside the bundle (`api/**`, the workflow file itself, `deployments/**`, and
  `scripts/resolve-deployment.py`), which SHALL run its deploy step **only** when the ref is `main`.

On `main` the deploy workflow SHALL deploy the bundled backend to the **bunny Edge Script** — the single
runtime. Neither workflow SHALL hold or use any Deno Deploy credential, and neither SHALL configure
platform environment variables (there are none they can set — see "Non-secret configuration is
source-owned").

The deploy workflow's path scope covers `deployments/**` and `scripts/resolve-deployment.py` because
the resolver and the authored deployments decide what ships inside the bundle (capability
`deployment-configuration`), so a change to either SHALL redeploy. It does **not** cover
`screenshots/**`: the marketing page moved to the Astro `site/` module, which `site-deploy.yml` ships,
so a capture refresh triggers that workflow and not this one.

#### Scenario: The check workflow runs on any branch, whatever the diff touches

- **WHEN** a commit is pushed to any branch
- **THEN** the check workflow runs and posts its status check, whether or not the push touches `api/**`

#### Scenario: A required check can never freeze a merge

- **WHEN** a pull request's diff touches nothing under `api/**`
- **THEN** the check workflow still runs on that branch and its status check is posted, so a merge
  waiting on it is not blocked forever

#### Scenario: The deploy workflow runs on a backend change

- **WHEN** a push to any branch touches files under `api/**`
- **THEN** the deploy workflow runs

#### Scenario: The deploy workflow runs on a configuration change

- **WHEN** a push to any branch touches files under `deployments/**` or `scripts/resolve-deployment.py`
- **THEN** the deploy workflow runs, so a change to what ships inside the bundle redeploys

#### Scenario: A capture refresh does not trigger the deploy workflow

- **WHEN** a push touches only files under `screenshots/**`
- **THEN** the deploy workflow does not run, because the marketing page is shipped by `site-deploy.yml`

#### Scenario: Deploys to bunny only on main

- **WHEN** a push lands on `main`
- **THEN** the deploy step runs, shipping the bundle to the bunny Edge Script
- **AND WHEN** a push lands on a non-`main` branch
- **THEN** the deploy step is skipped

### Requirement: Device-facing origin is a custom domain under our control

The device-facing origin SHALL be a **custom domain we control** through our own DNS (a Bunny DNS
zone) — not a runtime-provider vanity hostname. It SHALL be `CNAME`'d to the bunny pull zone fronting
the Edge Script and served with a **publicly-trusted TLS certificate** (default ATS applies; no
`NSAppTransportSecurity` exception ships, so a non-HTTPS or privately-signed origin is unacceptable).
The compile-time baked host (`uploadBase` in the generated `Deployment.plist`) SHALL be **this
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

The check workflow SHALL resolve the deployment (capability `deployment-configuration`) before running
any check, because the checks type-check and exercise code that reads the resolved configuration. It
SHALL then run the full check set — `deno fmt --check`, `deno lint`, the type-check, and the
`Deno.test` suite.

The status check the check workflow posts SHALL be a **required** status check on the default branch
(`.github/rulesets/main.json`, the sole authority for which contexts gate a merge). That is what gates
the backend: a commit SHALL NOT be able to reach `main` unless the full check set passed on the pull
request that carried it. The check set therefore SHALL NOT be repeated in the deploy workflow — the
gate has already fired, and a second copy of a gate is a drift source like any other. **A check that
can only fail after merge is not a gate**: before this arrangement the check set ran only in the
path-scoped deploy workflow and was required by nothing, so a pull request that broke the backend
merged green and the failure surfaced on `main`, with the deploy skipped and production silently
serving the previous bundle.

The `deno lint` invocation SHALL run the project's **local lint plugins** in addition to Deno's built-in
rules, declared in `deno.json` so that CI and a developer's local `deno lint` run the same rule set. The
complexity ceiling on this backend's TypeScript (capability `complexity-budgets`) is delivered as one such
plugin, because `deno lint` ships no complexity rule and no published plugin provides one. Adding it
therefore requires **no new workflow step and no second toolchain**: the gate this requirement already
describes is the gate that enforces it.

The type-check and test steps SHALL be invoked **through their `deno.json` tasks** (`deno task check`,
`deno task test`) rather than by restating the commands in the workflow, so the set of type-checked
directories and the permissions the suite runs under are defined in exactly one place and cannot drift
between CI and what a developer runs locally. The type-check SHALL cover **all** source — `src/` including
`main.ts`/SDK wiring the test run does not reach, the dev-only `src/dev/` tree (the local backend rig), the
out-of-bundle `src/scripts/` tree (the programs other workflows invoke), and the `src/lint/` tree (the local
lint plugins, which no test imports and no bundle contains) — so a broken rig, a broken job, or a broken
lint rule fails CI rather than only surfacing when someone next tries to use it.

The test task SHALL carry **only** the filesystem permissions its own suite needs (`--allow-read`,
`--allow-write`, for the dev storage shim's contract test). It SHALL NOT grant `--allow-net`: that absence
is what makes it impossible for a test to reach the real storage zone — a network call fails as a
permission error rather than silently becoming a live request against the zone holding real users' photos.
Resolving the deployment SHALL be a separate invocation, so it cannot widen the permissions the suite runs
under.

Because merges are rebase-only, the commit that lands on `main` is **not** the commit the checks ran
against, and nothing re-runs the check set at the deployed commit. This is deliberate and is stated
rather than left implicit. What backs the deploy is the ruleset — which admits no bypass actor, so the
content was checked before it was admitted — together with the post-publish boot probe, which witnesses
that the bundle that shipped actually boots and serves.

#### Scenario: A failing check blocks the merge

- **WHEN** any of `deno fmt --check`, `deno lint`, `deno task check`, or `deno task test` fails on a
  pull request
- **THEN** the check workflow's status check is red, and because it is required the pull request cannot
  merge — so the failure is caught before `main`, not after

#### Scenario: The check set is not repeated at deploy time

- **WHEN** the deploy workflow runs on `main`
- **THEN** it does not re-run `deno fmt --check`, `deno lint`, the type-check, or the test suite,
  because no commit could have reached `main` without them passing

#### Scenario: A local lint plugin's rule blocks deploy

- **WHEN** source violates a rule supplied by a project-local lint plugin declared in `deno.json`
- **THEN** `deno lint` fails and the deploy is blocked, with no workflow step beyond the existing
  `deno lint` invocation

#### Scenario: CI and a local run enforce the same rules

- **WHEN** a developer runs `deno lint` locally
- **THEN** the project's local plugins run too, because they are declared in `deno.json` rather than on the
  workflow's command line

#### Scenario: The type-check reaches the dev-only tree

- **WHEN** a change breaks compilation anywhere under `api/src/dev/`
- **THEN** the type-check step fails and the pull request is blocked, even though nothing under
  `src/dev/` reaches the deployed bundle

#### Scenario: The type-check reaches the out-of-bundle programs

- **WHEN** a change breaks compilation anywhere under `api/src/scripts/`
- **THEN** the type-check step fails and the pull request is blocked, even though nothing under
  `src/scripts/` reaches the deployed bundle

#### Scenario: The type-check reaches the lint plugins

- **WHEN** a change breaks compilation anywhere under `api/src/lint/`
- **THEN** the type-check step fails and the deploy is blocked, even though nothing under `src/lint/`
  reaches the deployed bundle and no test imports it

#### Scenario: No test can reach the real storage zone

- **WHEN** a test attempts a network request
- **THEN** it fails as a Deno permission error, because the test task grants no `--allow-net`

#### Scenario: The deployed commit is not the checked commit

- **WHEN** a pull request is merged by rebase and the resulting commit on `main` is deployed
- **THEN** no step re-runs the check set at that commit, and the guarantee that backs the deploy is the
  ruleset's admission of the content plus the post-publish boot probe

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

The **database** credentials are a third category and SHALL be held by the deploy workflow, because CI —
not the endpoint — is what applies schema migrations (capability `database`). The migration step SHALL run
**before** the bundle is published and SHALL fail the run without publishing if it fails, so a bundle is
never served against a store it does not expect. Holding them widens what a compromised deploy path can
reach to the relational store; it SHALL NOT be widened further to the storage access key, which would
extend that reach to every user's photos and is what the exclusion above exists to prevent.

#### Scenario: Deploy uses secret-held, script-scoped credentials

- **WHEN** the deploy step runs
- **THEN** it authenticates using a script id and deploy key sourced from GitHub Actions secrets, and
  no credential is present in the repository or workflow file

#### Scenario: The account API key is absent from CI

- **WHEN** the deploy workflow is inspected
- **THEN** it holds no bunny account API key, and performs no platform-configuration write

#### Scenario: The storage access key is absent from the deploy workflow

- **WHEN** the deploy workflow is inspected
- **THEN** it holds no storage access key, and performs no read or write against the storage zone

#### Scenario: A failed migration publishes nothing

- **WHEN** the migration step fails
- **THEN** the run fails and the bundle is not published

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

The device-API routes SHALL be served under a **versioned path prefix** of the form `/api/vN`. **More than
one version MAY be served simultaneously**, each as its own mount, and a version already released SHALL
NOT be restructured when a later one is added.

The **web/link paths** — `/`, `/join`, and `/.well-known/apple-app-site-association` — and the **operational
health route** SHALL remain at the **root, un-prefixed**, and SHALL NOT be served under any `/api/vN`. The
web/link paths are not device-API routes; Apple's CDN and browsers require fixed paths for the AASA and the
`/join` universal link. The health route is not a device-API route either — no device calls it — and keeping
it out of the versioned mount means an additional `/api/vN` neither duplicates nor strands it.

The **auth gate** SHALL apply to every `/api/vN` mount: authenticated routes SHALL require a valid bearer
token, and the ungated attest bootstrap routes (`attest/*`) SHALL remain ungated under every version's
`attest/*` — so that token issuance is never gated on possessing a token. The gate SHALL resolve the
prefix **version-agnostically**, so an additional `/api/vN` mount is gated identically with no change to
it. Where more than one middleware resolves the prefix, they SHALL share **one** implementation of that
resolution rather than each carrying its own copy.

The compile-time device-facing base host baked into the app
(`uploadBase` in the generated `Deployment.plist`) SHALL carry **exactly one** version prefix, so that
every device-API request the app and upload extension compose from that base targets that single version.
A build's API version is therefore a property of the build, never of the request path it composes — there
is no per-route version selection, and moving a build to a later version moves every one of its requests
at once. The separate web/link origin constant SHALL NOT carry the prefix.

#### Scenario: A device-API route resolves under the version prefix

- **WHEN** the app issues a device-API request under its baked version prefix through the pull zone
- **THEN** it is routed to that version's device-API route and served with its documented status and
  upstream effect

#### Scenario: Two versions are served side by side

- **WHEN** the deployment serves more than one `/api/vN` mount and a request arrives for each
- **THEN** each is routed to its own version's routes, and the earlier version's routes are unchanged by
  the presence of the later one

#### Scenario: Web/link paths stay at the root, never under a prefix

- **WHEN** Apple's CDN fetches `/.well-known/apple-app-site-association`, a browser opens `/join`, or the
  marketing page is requested at `/`
- **THEN** each is served at its bare root path
- **AND** the same web/link paths are NOT served under any `/api/vN`

#### Scenario: The health route stays at the root, never under a prefix

- **WHEN** the health route is requested at its bare root path
- **THEN** it is served
- **AND** it is NOT served under any `/api/vN`

#### Scenario: Attest bootstrap stays ungated under every prefix

- **WHEN** the app requests `attest/challenge` or `attest/token` under any served version with no bearer
  token
- **THEN** the request is served (the attest routes are ungated)

#### Scenario: An authenticated route requires a token under every prefix

- **WHEN** a non-attest device-API request is made under any served version without a valid bearer token
- **THEN** it is rejected by the auth gate

#### Scenario: The baked base host carries exactly one version prefix

- **WHEN** the compile-time device-facing base is inspected
- **THEN** it carries exactly one version prefix, so every device-API URL the app and upload extension
  compose targets that same version
- **AND** the separate web/link origin constant does not carry the prefix

#### Scenario: The routing admits a further version without restructuring

- **WHEN** a further API version is introduced
- **THEN** it is added as an additional versioned mount alongside the existing ones, without changing any
  existing version's routes

### Requirement: The browser-facing site is deployed by a second, account-key-free path

The `site/` build (per `web-site`) SHALL be deployed to the storage `site/` prefix by a CI path that
authenticates with the **storage-zone password only** — the same secret the nightly sweep already holds —
and SHALL NOT require or hold the bunny **account key**. This preserves the standing least-privilege rule:
CI ships code (the api bundle) and now also site assets, but never holds the account key that owns the
storage zone and DNS.

#### Scenario: The site deploy holds no account key

- **WHEN** the site build+deploy runs in CI
- **THEN** it authenticates to storage with the storage-zone password, and the bunny account key is absent
  from the job

#### Scenario: Code and site are separate CI deploys

- **WHEN** the api bundle is deployed and the site is deployed
- **THEN** the api bundle ships via the script-scoped deploy key and the site ships via the storage
  password, and neither requires the account key

### Requirement: Static-site routing is source-owned; no pull-zone edge rules

The static-site routing SHALL live **in the deployed api bundle as source-owned code** — the closed
static-path allowlist and its proxy of the storage `site/` prefix (per `web-site`). The site SHALL be
reachable with **no pull-zone edge rule** and **no account-key configuration** of the pull zone. Disaster recovery therefore remains "redeploy the bundle and repoint DNS": no out-of-band
routing state exists that a rebuilt environment could lose.

#### Scenario: No edge rule is required for the site to be reachable

- **WHEN** the api bundle is deployed and the pull zone points at it (default configuration)
- **THEN** `/`, `/join`, and the site's assets are reachable with no pull-zone edge rule configured

#### Scenario: Routing is recoverable from source

- **WHEN** the runtime is rebuilt from the repo and DNS is repointed
- **THEN** the static-site routing is present because it shipped in the bundle, with no manual pull-zone
  configuration step

### Requirement: The storage zone hosts a public `site/` prefix co-tenant with private data

The backend storage zone SHALL host a `site/` prefix holding the built browser-facing site, co-tenant with
the private `files/`, `events/`, and `devices/` prefixes. Public reachability SHALL be scoped by the api
proxy, which serves **only** the `site/` prefix publicly; the private prefixes SHALL NOT be publicly
reachable through the api and SHALL continue to be accessed as before (presigned URLs for photos, the
account/sweep for management).

#### Scenario: Only site/ is publicly reachable

- **WHEN** a public request arrives for a path outside the static-site allowlist (e.g. a `files/` object)
- **THEN** the api does not proxy it from storage; private prefixes are not publicly served

### Requirement: The nightly sweep is prefix-scoped and never touches `site/`

The nightly cleanup sweep SHALL enumerate and reclaim **only** the `events/`, `files/devices/`, and
`devices/` prefixes; it SHALL NOT enumerate or delete anything under the `site/` prefix, and it SHALL NOT
be changed into a whole-zone walk. Hygiene of the `site/` prefix is owned by the mirror deploy (per
`web-site`), not the sweep.

#### Scenario: The sweep ignores site/

- **WHEN** the nightly sweep runs against the storage zone
- **THEN** it lists and reclaims only `events/`, `files/devices/`, and `devices/`, and leaves every
  `site/` object untouched

### Requirement: Deploy is gated on a post-publish boot probe

After publishing on `main`, the workflow SHALL probe the device-facing origin until it observes the bundle
it just deployed, and SHALL fail the run otherwise. This exists because `POST /code` + `POST /publish`
succeed whether or not the deployed bundle can boot, so a green deploy step is **not** evidence that the
script serves.

The probe SHALL be satisfied only by a response that identifies **the bundle this run deployed**. A bare
success is insufficient: it cannot distinguish the new deployment from the previous one still being served,
which is the failure the probe exists to catch.

Because the maintenance bundle and the real bundle are built from the **same commit**, bundle identity
alone no longer distinguishes them. The probe SHALL therefore also be told which **maintenance state** it
expects, and SHALL be satisfied only by a response reporting that state: the probe before a migration
expects the window **open**, and the probe after the publish that lifts it expects the window **closed**.
Without the second assertion a run that failed to lift the window would report success.

The probe SHALL additionally witness that the deployed bundle can reach both of its dependencies — the
relational store and the storage zone. Storage reachability is new coverage, and it closes the half of the
2026-07 outage this probe previously could not see: a `BUNNY_STORAGE_ZONE` that is present but names a zone
that does not exist boots and probes green.

The probe SHALL retry only causes that time can resolve — a connection failure, a server error, a
not-found, a response identifying a *different* bundle, or a response reporting the wrong maintenance state
— up to a bounded deadline, and SHALL fail **immediately** on causes that waiting cannot fix, naming which.
Retrying a terminal cause until a deadline turns a specific bug into a timeout.

The probe SHALL target the **device-facing origin**, so a green probe also witnesses the DNS, certificate
and pull-zone path a device traverses, per "bunny Edge Scripting is the device-facing runtime". It SHALL
NOT target a runtime-provider hostname: that would report success while the device-facing path was broken.

#### Scenario: A deploy that cannot boot fails the run

- **WHEN** the publish succeeds but the deployed bundle does not serve
- **THEN** the probe exhausts its deadline and the run fails

#### Scenario: A stale bundle does not satisfy the probe

- **WHEN** the origin still serves the previous bundle
- **THEN** the probe keeps retrying rather than passing, because the response does not identify this run's
  bundle

#### Scenario: The right bundle in the wrong maintenance state does not satisfy the probe

- **WHEN** the origin serves this run's bundle but reports a maintenance state other than the one expected
- **THEN** the probe keeps retrying rather than passing

#### Scenario: A run that fails to lift the window fails

- **WHEN** the final probe observes this run's bundle still reporting the window open
- **THEN** the run fails rather than reporting success with the API serving maintenance

#### Scenario: An unreachable dependency fails the deploy

- **WHEN** the deployed bundle cannot reach its relational store or its storage zone
- **THEN** the probe fails the run rather than leaving a deployment that accepts requests it cannot serve
  or record

### Requirement: A health route reports the deployed bundle's identity

The backend SHALL serve an **unauthenticated** health route at the **root**, answering `GET` and `HEAD`
only, and carrying the listings' no-cache directives so the pull zone cannot answer from a previous
deployment's copy.

On success it SHALL report the identity of the bundle serving it, and SHALL report that the maintenance
window is open **when, and only when, it is**. Both facts are needed because the two bundles a migrating
deploy publishes carry the **same** identity, so identity alone cannot tell them apart.

The window's absence from the response SHALL mean the window is **closed**. This collapse is deliberate and
it is safe because every cause it absorbs is the same answer: a bundle whose flag is off, and a bundle
built before the flag existed — which was built before maintenance mode existed at all, and is therefore
necessarily serving the device API. Nothing else can produce the absence, because a response that is not
this backend's fails the identity check first.

The route SHALL verify that the bundle can reach both of its dependencies — the relational store and the
storage zone — and SHALL answer a **non-success status** when either is unreachable. Distinguishing a
retryable cause from a terminal one is no longer the body's job: the only dependency condition it now
reports is unreachability, which is retryable, and a non-success status already carries that.

The route SHALL NOT require authentication. Serving it unauthenticated is accepted although it now performs
real upstream work, because the alternative is worse in kind: the probe carries no credential the backend
accepts, so any route it can reach is equally ungated, and moving the checks elsewhere would relocate the
exposure rather than remove it. What it discloses remains only an identifier that is already public.

#### Scenario: The health route is served without a token

- **WHEN** the health route is requested with no bearer token
- **THEN** it is answered successfully, carrying the identity of the bundle serving it

#### Scenario: The health route reports an open maintenance window

- **WHEN** the maintenance bundle is serving
- **THEN** the health route reports that the window is open, alongside the bundle's identity

#### Scenario: A response that does not mention the window means it is closed

- **WHEN** the health route's response carries no window field
- **THEN** the probe reads the window as closed, rather than as an answer it cannot interpret

#### Scenario: An unreachable dependency is a non-success status

- **WHEN** the relational store or the storage zone cannot be reached
- **THEN** the health route answers a non-success status rather than a success carrying a state description

#### Scenario: The health route is not cached by the pull zone

- **WHEN** the health route is requested through the pull zone after a new bundle is published
- **THEN** the response is not served from a previous deployment's cached copy

#### Scenario: A mutating method is not served

- **WHEN** the health route is requested with a method other than `GET` or `HEAD`
- **THEN** it is not served by that route

### Requirement: The relational store is a deployment-declared, secret-held dependency

The backend's relational store (capability `database`) SHALL be reached through credentials the resolved
deployment declares as **runtime environment references** — the database URL and its access token — exactly
as the storage `AccessKey`, the APNs key, and the device-token signing key are. No connection string or
token SHALL appear in any authored file; the deployment declares the variable's **name**, never its value.

Both SHALL be validated once at startup with every other secret, so a deployment that cannot reach its
store fails to boot rather than serving requests that silently lose relational writes.

Because CI holds only the script-scoped deploy key and cannot write the script's environment, these
variables SHALL be set in the Edge Script environment **before** the code that reads them is merged to
`main`, per this capability's existing ordering rule.

Each deployment SHALL address its **own** database. The `local` deployment SHALL NOT be resolvable against
the production store: a dev run that wrote or deleted rows there would corrupt live events, and unlike the
storage zone there is no per-object blast radius to fall back on.

#### Scenario: A missing database credential fails startup

- **WHEN** the deployment resolves with the database URL or token absent or blank
- **THEN** startup fails and the script does not serve, rather than accepting writes it cannot record

#### Scenario: The credentials are declared, never authored

- **WHEN** the deployment files are inspected
- **THEN** they carry the environment variable **names** for the database URL and token and neither value

### Requirement: A migrating deploy serves maintenance while the schema and the bundle disagree

When a deploy would apply one or more schema migrations, the workflow SHALL publish a **maintenance
bundle** — the same commit, resolved from a deployment that sets the maintenance flag — and SHALL confirm
it is serving **before** applying any migration. Only after the migration succeeds SHALL it publish the
real bundle and lift the window.

This exists because the migrate-then-publish ordering, which is correct and stays, leaves an interval in
which the **previous** bundle answers requests against the **migrated** store, with statements written for
a schema shape that no longer exists.

The workflow SHALL determine whether any migration is pending **before** opening the window, and SHALL
leave the pipeline otherwise unchanged when none is. A deploy that changes no schema SHALL cost one publish
and no window.

The pending check SHALL distinguish **no migrations pending**, **migrations pending**, and **the check
failed** as three outcomes, and SHALL treat the third as fatal. Collapsing a failed check into "none
pending" would publish the new bundle onto an un-migrated store — the exact outcome this requirement
exists to prevent.

The maintenance flag SHALL reach the bundle as **resolved configuration shipped inside it** (capability
`deployment-configuration`), never as an Edge Script environment value: CI holds only the script-scoped
deploy key and cannot write the script's environment, so what code is published is the only lever it has.

#### Scenario: A deploy with no pending migration opens no window

- **WHEN** the pending check reports that every migration is already applied
- **THEN** the workflow publishes once and probes once, with no maintenance bundle and no window

#### Scenario: A migrating deploy gates the window on the maintenance bundle serving

- **WHEN** a migration is pending
- **THEN** the maintenance bundle is published and observed serving before any migration statement runs

#### Scenario: A failed pending check fails the run

- **WHEN** the pending check exits with any outcome other than "none pending" or "pending"
- **THEN** the run fails without publishing, rather than proceeding as if no migration were pending

#### Scenario: The window is lifted only by publishing the real bundle

- **WHEN** the migration succeeds
- **THEN** the real bundle is published and observed serving with the maintenance flag clear

### Requirement: Every successful deploy archives its bundle, and a failed migrating deploy republishes the previous one

Each successful deploy SHALL archive the published bundle, keyed by the commit it was built from, in a
store **independent of the deploy target**. A migrating deploy SHALL capture the commit currently serving
before opening the window, and on failure SHALL republish that commit's archived bundle.

Before this, the pipeline had no rollback at all: bunny's release re-publish needs the account key, which
CI is forbidden to hold (measured — the deploy key answers `401` on the release endpoints). Without an
archive, a failed migrating deploy would leave the maintenance bundle live with nothing able to lift it.

The archive SHALL NOT live in the storage zone. `BUNNY_STORAGE_ACCESS_KEY` SHALL NOT be granted to the
deploy workflow (see "Deploy with secret-held, script-scoped credentials"): that key owns the zone holding
every user's photos, and the deploy path already reaches the relational store. Independence also matters on
its own terms — a rollback must work when the deploy target is the thing misbehaving.

There SHALL be no rebuild fallback. When the archived bundle for the captured commit is absent or expired,
the workflow SHALL fail **loudly, naming that commit**, leaving a bounded manual recovery rather than an
unexplained outage.

#### Scenario: A failed migration lifts the window by republishing

- **WHEN** the migration fails while the maintenance bundle is live
- **THEN** the workflow republishes the archived bundle for the commit that was serving before the window
  opened

#### Scenario: A missing archive fails loudly

- **WHEN** the archived bundle for the captured commit cannot be retrieved
- **THEN** the run fails naming that commit, rather than leaving the API serving maintenance silently

#### Scenario: The deploy workflow holds no storage credential

- **WHEN** the archive is written or read
- **THEN** no storage-zone credential is used, and none is present in the deploy workflow

### Requirement: The maintenance guarantee is bounded by what one probe can witness

The guarantee this pipeline provides SHALL be stated as **"very likely no request met a bundle whose
schema assumptions did not match the store"**, and SHALL NOT be stated as a certainty.

The reason is structural: the probe observes the device-facing origin, which resolves to **one** of the
runtime's points of presence. Nothing available to CI observes the others, and the platform publishes no
propagation contract — its own statements range from seconds to minutes. A future
measurement — observing how long the origin reports a split bundle identity from several networks — is what
would tighten it.

Relatedly, `migrate()` is atomic **per migration**, not per run. A run applying several migrations in which
a later one fails leaves the store at an intermediate version that no bundle is written against. This is
**roll-forward only**, repaired by hand, and SHALL be stated rather than implied.

#### Scenario: The residual is documented, not implied

- **WHEN** the maintenance window's guarantee is described
- **THEN** it names the single-observation limit rather than claiming every request was covered

#### Scenario: A partially applied run is a manual repair

- **WHEN** a run applies one migration and a later one fails
- **THEN** the store remains at the intermediate version and is repaired by rolling forward, not by the
  pipeline
