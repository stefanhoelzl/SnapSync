## MODIFIED Requirements

### Requirement: Deploy is gated on green checks

The workflow SHALL resolve the deployment (capability `deployment-configuration`) before running any check,
because the checks type-check and exercise code that reads the resolved configuration. It SHALL then run,
before the deploy step, the full check set — `deno fmt --check`, `deno lint`, the type-check, and the
`Deno.test` suite — and the deploy step SHALL execute **only** when all of them pass. Any failing check
SHALL block deployment.

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

#### Scenario: A failing check blocks deploy

- **WHEN** any of `deno fmt --check`, `deno lint`, `deno task check`, or `deno task test` fails
- **THEN** the deploy step does not run and the workflow fails

#### Scenario: All checks green permit deploy

- **WHEN** every check passes
- **THEN** the deploy step runs

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
- **THEN** the type-check step fails and the deploy is blocked, even though nothing under `src/dev/`
  reaches the deployed bundle

#### Scenario: The type-check reaches the out-of-bundle programs

- **WHEN** a change breaks compilation anywhere under `api/src/scripts/`
- **THEN** the type-check step fails and the deploy is blocked, even though nothing under `src/scripts/`
  reaches the deployed bundle

#### Scenario: The type-check reaches the lint plugins

- **WHEN** a change breaks compilation anywhere under `api/src/lint/`
- **THEN** the type-check step fails and the deploy is blocked, even though nothing under `src/lint/`
  reaches the deployed bundle and no test imports it

#### Scenario: No test can reach the real storage zone

- **WHEN** a test attempts a network request
- **THEN** it fails as a Deno permission error, because the test task grants no `--allow-net`
