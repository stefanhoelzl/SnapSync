# backend deployment Specification

## Purpose

The CI pipeline that ships the `backend/` upload endpoint (capability `bunny-upload-endpoint`): a
path-scoped GitHub Actions workflow that runs the Deno checks on every branch, bundles to a single
file, and on `main` deploys that bundle to **both** runtimes — bunny Edge Scripting and Deno Deploy.
The device-facing origin is the custom domain `snapsync.stho.net` (a zone we control via Bunny DNS,
served with a publicly-trusted cert), `CNAME`'d to the **active** runtime — Deno Deploy today, while
bunny investigates dropping iOS's zero-window upload SYNs. Because the origin is a domain we own,
swapping the active runtime is a DNS repoint, not an app rebuild. Isolated from the Gradle/iOS
workflows; no Bunny credential in source. Authoritative design: docs/design.md §4, §7.

## Requirements

### Requirement: Path-scoped, isolated workflow; deploy on main only

The system SHALL provide a GitHub Actions workflow that runs the checks on **every push** touching the
backend sources (path-scoped to `backend/**` and the workflow file itself, on any branch), and SHALL
run the deploy steps **only** when the ref is `main`. On `main` it SHALL deploy the **same** bundled
backend to **both** runtimes — bunny Edge Scripting **and** Deno Deploy — from the one source
(`backend/main.ts` is runtime-aware). It SHALL be isolated from the Gradle/iOS workflows (its own
workflow file; it SHALL NOT couple to the Gradle build or iOS jobs).

#### Scenario: Runs checks on any branch touching the backend

- **WHEN** a push to any branch touches files under `backend/**`
- **THEN** the workflow runs the checks

#### Scenario: Does not run when the backend is unchanged

- **WHEN** a push touches only files outside `backend/**`
- **THEN** the workflow does not run

#### Scenario: Deploys to both runtimes only on main

- **WHEN** the checks pass on a push to `main`
- **THEN** the deploy steps run, shipping the bundle to both bunny Edge Scripting and Deno Deploy
- **AND WHEN** the checks pass on a push to a non-`main` branch
- **THEN** the deploy steps are skipped

### Requirement: Deno Deploy is the active device-facing runtime

While bunny.net drops iOS's zero-window upload SYNs, **Deno Deploy** SHALL be the **active
device-facing runtime** — the runtime the device-facing origin resolves to and that serves the app's
uploads, event creation, and list requests. Photo **downloads** are **not** served by the runtime: the
list/union `url` is a presigned S3 GET URL and the device fetches those bytes **directly from bunny's S3
endpoint**, off the runtime entirely. The bunny Edge Scripting deploy SHALL continue (the intended
long-term runtime) but SHALL NOT be the device-facing origin until that SYN-drop is resolved. Both
runtimes serve the identical bundle, so the active runtime is selected by **where the device-facing
origin points**, not by which deploy runs.

#### Scenario: The device-facing origin resolves to the active runtime

- **WHEN** the app reaches the device-facing origin for an upload, event creation, or list request
- **THEN** the request is served by Deno Deploy (the active runtime), not by bunny Edge Scripting

#### Scenario: Downloads bypass the runtime

- **WHEN** the app downloads a collected photo's bytes
- **THEN** it fetches them directly from bunny's S3 endpoint via the presigned `url`, not from the
  active runtime

### Requirement: Device-facing origin is a custom domain under our control

The device-facing origin SHALL be a **custom domain we control** through our own DNS (a Bunny DNS
zone) — not a runtime-provider vanity hostname. It SHALL be `CNAME`'d to the active runtime and served
with a **publicly-trusted TLS certificate** (default ATS applies; no `NSAppTransportSecurity`
exception ships, so a non-HTTPS or privately-signed origin is unacceptable). The compile-time baked
host (`BACKGROUND_UPLOAD_URL_BASE` / `BackgroundUploadURLBase`) **and** the backend's
`PUBLIC_BASE_URL` SHALL both be **this same custom domain**, so device→backend traffic for uploads,
event creation, and listings shares one origin we own. Photo **download** bytes do **not** share this
origin — they are served by bunny's S3 endpoint (`<region>-s3.storage.bunnycdn.com`) against a
presigned URL, itself a publicly-trusted HTTPS host covered by default ATS with no exception.

#### Scenario: App reaches the backend over the custom domain via HTTPS

- **WHEN** the app issues an upload, event-creation, or list request
- **THEN** it targets the custom domain over HTTPS, which presents a publicly-trusted certificate

#### Scenario: Baked host and PUBLIC_BASE_URL name the same custom domain

- **WHEN** the baked `BackgroundUploadURLBase` and the backend's `PUBLIC_BASE_URL` are compared
- **THEN** both name the same custom-domain origin we control

#### Scenario: Download bytes come from bunny's S3 endpoint, not the custom domain

- **WHEN** the app downloads a photo's bytes via a presigned `url`
- **THEN** the request targets bunny's S3 endpoint over HTTPS (default ATS, no exception), not the
  custom-domain origin

### Requirement: Runtime swaps are a DNS repoint, not an app rebuild

Changing **which runtime** serves the device-facing origin SHALL be achievable by repointing DNS
(and flipping the server-side `PUBLIC_BASE_URL`) **without** changing the baked host literal or
shipping a new app build — because that origin is a custom domain we control. The baked
`BackgroundUploadURLBase` SHALL NOT be a runtime-provider-owned vanity hostname, since that would
couple a runtime swap to a forced rebuild.

#### Scenario: Switching the active runtime requires no new build

- **WHEN** the active runtime is changed (e.g. from Deno Deploy back to bunny Edge Scripting)
- **THEN** the device-facing origin is repointed via DNS and `PUBLIC_BASE_URL` is updated server-side
- **AND** no new IPA is built and no reinstall is required for already-installed devices to follow

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
bundle the project entry and all its imports into one self-contained file (`deno bundle src/main.ts
-o dist/main.js`) and deploy **that bundle**. The bundle SHALL stay within the Edge Scripting 1 MB
script limit. (Deploying the raw entry leaves `import` specifiers unresolved and the script errors on
every request.)

#### Scenario: A single bundled file is deployed

- **WHEN** the deploy step runs
- **THEN** it uploads one self-contained bundle (no unresolved imports), not the raw entry file

### Requirement: Deploy with secret-held, script-scoped credentials

The workflow SHALL deploy the bundled file to the configured Edge Scripting app using a
**script-scoped deploy key** and the **script id**,
each supplied **only** as a GitHub Actions secret. The Bunny **account API key** SHALL NOT be used
by the deploy workflow (it is needed only to provision the zone/app). No Bunny credential SHALL
appear in source or in the workflow file. The storage-zone `AccessKey` SHALL be configured as an
Edge Script environment variable, **not** as a deploy-workflow secret (it is the endpoint's runtime
config, not a CI credential).

#### Scenario: Deploy uses secret-held credentials

- **WHEN** the deploy step runs
- **THEN** it authenticates using a script id and deploy key sourced from GitHub Actions secrets,
  and no credential is present in the repository or workflow file

### Requirement: Idempotent deploy target

Each deploy SHALL target the same Edge Scripting app/script, overwriting the prior deployment;
repeated deploys SHALL NOT create new or versioned scripts.

#### Scenario: Redeploy overwrites

- **WHEN** the workflow deploys a second time
- **THEN** it updates the same Edge Scripting app rather than creating an additional one
