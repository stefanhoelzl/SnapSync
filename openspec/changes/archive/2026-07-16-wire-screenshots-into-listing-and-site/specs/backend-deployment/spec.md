## MODIFIED Requirements

### Requirement: Path-scoped, isolated workflow; deploy on main only

The system SHALL provide a GitHub Actions workflow that runs the checks on **every push** touching the
backend sources or the assets the bundle embeds (path-scoped to `backend/**`, `screenshots/**`, and the
workflow file itself, on any branch), and SHALL run the deploy step **only** when the ref is `main`. On
`main` it SHALL deploy the bundled backend to the **bunny Edge Script** — the single runtime. It SHALL be
isolated from the Gradle/iOS workflows (its own workflow file; it SHALL NOT couple to the Gradle build or
iOS jobs). The workflow SHALL NOT hold or use any Deno Deploy credential, and SHALL NOT configure platform
environment variables (there are none it can set — see "Non-secret configuration is source-owned").

`screenshots/**` is in scope because the served page embeds images **derived from those files at build
time**: were the filter to cover only `backend/**`, refreshing a capture would leave the live page serving
the previous screenshots until an unrelated backend change happened to redeploy it. The widening is exact,
not a guess at a dependency graph — the named path *is* the derive's input.

Deriving the page's images SHALL NOT require any tool beyond the backend's own runtime, so that the checks
remain runnable from a fresh clone with no additional system dependency.

#### Scenario: Runs checks on any branch touching the backend

- **WHEN** a push to any branch touches files under `backend/**`
- **THEN** the workflow runs the checks

#### Scenario: Runs checks on any branch touching the embedded captures

- **WHEN** a push to any branch touches files under `screenshots/**`
- **THEN** the workflow runs the checks

#### Scenario: Does not run when neither the backend nor the captures change

- **WHEN** a push touches only files outside `backend/**` and `screenshots/**`
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
