## MODIFIED Requirements

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

## ADDED Requirements

### Requirement: Deno Deploy is the active device-facing runtime

While bunny.net drops iOS's zero-window upload SYNs, **Deno Deploy** SHALL be the **active
device-facing runtime** — the runtime the device-facing origin resolves to and that serves the app's
uploads, event creation, list, and downloads. The bunny Edge Scripting deploy SHALL continue (the
intended long-term runtime) but SHALL NOT be the device-facing origin until that SYN-drop is resolved.
Both runtimes serve the identical bundle, so the active runtime is selected by **where the
device-facing origin points**, not by which deploy runs.

#### Scenario: The device-facing origin resolves to the active runtime

- **WHEN** the app reaches the device-facing origin for an upload, event creation, list, or download
- **THEN** the request is served by Deno Deploy (the active runtime), not by bunny Edge Scripting

### Requirement: Device-facing origin is a custom domain under our control

The device-facing origin SHALL be a **custom domain we control** through our own DNS (a Bunny DNS
zone) — not a runtime-provider vanity hostname. It SHALL be `CNAME`'d to the active runtime and served
with a **publicly-trusted TLS certificate** (default ATS applies; no `NSAppTransportSecurity`
exception ships, so a non-HTTPS or privately-signed origin is unacceptable). The compile-time baked
host (`BACKGROUND_UPLOAD_URL_BASE` / `BackgroundUploadURLBase`) **and** the backend's
`PUBLIC_BASE_URL` SHALL both be **this same custom domain**, so device→backend traffic and the list
endpoint's download URLs share one origin we own.

#### Scenario: App reaches the backend over the custom domain via HTTPS

- **WHEN** the app issues an upload, event-creation, list, or download request
- **THEN** it targets the custom domain over HTTPS, which presents a publicly-trusted certificate

#### Scenario: Baked host and PUBLIC_BASE_URL name the same custom domain

- **WHEN** the baked `BackgroundUploadURLBase` and the backend's `PUBLIC_BASE_URL` are compared
- **THEN** both name the same custom-domain origin we control

### Requirement: Runtime swaps are a DNS repoint, not an app rebuild

Because the device-facing origin is a custom domain we control, changing **which runtime** serves it
SHALL be achievable by repointing DNS (and flipping the server-side `PUBLIC_BASE_URL`) **without**
changing the baked host literal or shipping a new app build. The baked `BackgroundUploadURLBase` SHALL
NOT be a runtime-provider-owned vanity hostname, since that would couple a runtime swap to a forced
rebuild.

#### Scenario: Switching the active runtime requires no new build

- **WHEN** the active runtime is changed (e.g. from Deno Deploy back to bunny Edge Scripting)
- **THEN** the device-facing origin is repointed via DNS and `PUBLIC_BASE_URL` is updated server-side
- **AND** no new IPA is built and no reinstall is required for already-installed devices to follow
