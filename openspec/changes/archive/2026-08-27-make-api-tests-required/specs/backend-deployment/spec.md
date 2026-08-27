## MODIFIED Requirements

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

#### Scenario: The type-check reaches the dev-only tree

- **WHEN** a change breaks compilation anywhere under `api/src/dev/`
- **THEN** the type-check step fails and the pull request is blocked, even though nothing under
  `src/dev/` reaches the deployed bundle

#### Scenario: The type-check reaches the out-of-bundle programs

- **WHEN** a change breaks compilation anywhere under `api/src/scripts/`
- **THEN** the type-check step fails and the pull request is blocked, even though nothing under
  `src/scripts/` reaches the deployed bundle

#### Scenario: No test can reach the real storage zone

- **WHEN** a test attempts a network request
- **THEN** it fails as a Deno permission error, because the test task grants no `--allow-net`

#### Scenario: The deployed commit is not the checked commit

- **WHEN** a pull request is merged by rebase and the resulting commit on `main` is deployed
- **THEN** no step re-runs the check set at that commit, and the guarantee that backs the deploy is the
  ruleset's admission of the content plus the post-publish boot probe
