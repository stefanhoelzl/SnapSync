# backend deployment Specification

## Purpose

The CI pipeline that ships the `backend/` Edge Scripting endpoint (capability
`bunny-upload-endpoint`) to bunny.net: a path-scoped GitHub Actions workflow that runs the Deno
checks on every branch, bundles to a single file, and deploys to the one live Edge Script on `main`
only. Isolated from the Gradle/iOS workflows; no Bunny credential in source. Authoritative design:
docs/design.md §4, §7.

## Requirements

### Requirement: Path-scoped, isolated workflow; deploy on main only

The system SHALL provide a GitHub Actions workflow that runs the checks on **every push** touching the
backend sources (path-scoped to `backend/**` and the workflow file itself, on any branch), and SHALL
run the deploy step **only** when the ref is `main`. It SHALL be isolated from the Gradle/iOS
workflows (its own workflow file; it SHALL NOT couple to the Gradle build or iOS jobs).

#### Scenario: Runs checks on any branch touching the backend

- **WHEN** a push to any branch touches files under `backend/**`
- **THEN** the workflow runs the checks

#### Scenario: Does not run when the backend is unchanged

- **WHEN** a push touches only files outside `backend/**`
- **THEN** the workflow does not run

#### Scenario: Deploys only on main

- **WHEN** the checks pass on a push to `main`
- **THEN** the deploy step runs
- **AND WHEN** the checks pass on a push to a non-`main` branch
- **THEN** the deploy step is skipped

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
