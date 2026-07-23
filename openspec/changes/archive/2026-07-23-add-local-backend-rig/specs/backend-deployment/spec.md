## MODIFIED Requirements

### Requirement: Deploy is gated on green checks

The workflow SHALL run, before the deploy step, the full check set — `deno fmt --check`, `deno lint`,
the type-check, and the `Deno.test` suite — and the deploy step SHALL execute **only** when all of them
pass. Any failing check SHALL block deployment.

The type-check and test steps SHALL be invoked **through their `deno.json` tasks** (`deno task check`,
`deno task test`) rather than by restating the commands in the workflow, so the set of type-checked
directories and the permissions the suite runs under are defined in exactly one place and cannot drift
between CI and what a developer runs locally. The type-check SHALL cover **all** source — `src/` including
`main.ts`/SDK wiring the test run does not reach, **and** the dev-only `src/dev/` tree (the local backend
rig), so a broken rig fails CI rather than only surfacing when someone next tries to use it.

The test task SHALL carry **only** the filesystem permissions its own suite needs (`--allow-read`,
`--allow-write`, for the dev storage shim's contract test). It SHALL NOT grant `--allow-net`: that absence
is what makes it impossible for a test to reach the real storage zone — a network call fails as a
permission error rather than silently becoming a live request against the zone holding real users' photos.

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

#### Scenario: No test can reach the real storage zone

- **WHEN** a test attempts a network request
- **THEN** it fails as a Deno permission error, because the test task grants no `--allow-net`
