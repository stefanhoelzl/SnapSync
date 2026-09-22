## RENAMED Requirements

- FROM: `### Requirement: Path-scoped, isolated workflow; deploy on main only`
- TO: `### Requirement: Unfiltered check workflow; main-only deploy job gated on the live commit`

## MODIFIED Requirements

### Requirement: Unfiltered check workflow; main-only deploy job gated on the live commit

The system SHALL provide the backend's CI as two separate things, neither coupled to the Gradle build or
the iOS jobs:

- a **check workflow**, its own workflow file, that runs the check set (below) on **every push to any
  branch** and carries **no path filter at all**, so the status check it posts appears on every ref. A
  required status check that is never posted stays pending forever, so a path-filtered check workflow would
  freeze every merge whose diff falls outside the filter;
- a **deploy job** in the repository's single **deploy workflow**. That workflow SHALL be triggered **only**
  by a push to `main` and SHALL carry no workflow-level path filter, because it also hosts deployers with
  other trigger sets. The deploy job SHALL NOT run on any other ref.

On `main` the deploy job SHALL deploy the bundled backend to the **bunny Edge Script**, the single runtime.
Neither the check workflow nor the deploy job SHALL hold or use any Deno Deploy credential, and neither
SHALL configure platform environment variables (there are none they can set; see "Non-secret configuration
is deployment-resolved, not environment-owned").

**When the deploy job runs.** It SHALL run when any file in its path set differs between the commit
currently **live**, as the deployed bundle's health route reports it (see "A health route reports the
deployed bundle's identity"), and the pushed commit. The path set is `api/**`, the deploy workflow file,
`deployments/**` and `scripts/resolve-deployment.py`. The resolver and the authored deployments decide what
ships inside the bundle (capability `deployment-configuration`), so a change to either SHALL redeploy. The
set does **not** cover `screenshots/**` or `site/**`: the site deploy ships those.

The baseline SHALL be the live commit, **not** the push's previous head (`github.event.before`). A pending
run can be displaced: a concurrency group holds one pending run, and a newer one cancels it. Against
`github.event.before`, a displaced run's backend change would be absent from every later run's diff and
would never deploy. Against the live commit, any later run still sees it. If the health route cannot be
read, or names a commit that is not in the pushed commit's history, the deploy job SHALL **run** (fail open):
a redundant deploy is probed and harmless, while a skipped one leaves production silently behind `main`.

**Deploys are serialized.** The deploy workflow SHALL run in a single concurrency group with
`cancel-in-progress: false`. A deploy that has published must still be probed, and two deploys running at
once would let one run's probe observe the other's bundle, a false red.

**Not a required check.** The deploy job, like every job in the deploy workflow, SHALL NOT be a required
status check. It never runs on a pull request, so as a required check it would never be posted and every
merge would freeze.

#### Scenario: The check workflow runs on any branch, whatever the diff touches

- **WHEN** a commit is pushed to any branch
- **THEN** the check workflow runs and posts its status check, whether or not the push touches `api/**`

#### Scenario: A required check can never freeze a merge

- **WHEN** a pull request's diff touches nothing under `api/**`
- **THEN** the check workflow still runs on that branch and its status check is posted, so a merge
  waiting on it is not blocked forever

#### Scenario: The deploy job runs on a backend change

- **WHEN** a push to `main` touches files under `api/**` that differ from the live commit
- **THEN** the deploy job runs and ships the bundle to the bunny Edge Script

#### Scenario: The deploy job runs on a configuration change

- **WHEN** a push to `main` touches files under `deployments/**` or `scripts/resolve-deployment.py`
- **THEN** the deploy job runs, so a change to what ships inside the bundle redeploys

#### Scenario: A change outside the path set does not deploy the backend

- **WHEN** a push to `main` touches only files outside the path set (e.g. only `screenshots/**` or `docs`)
  and the live commit's api paths already equal the pushed commit's
- **THEN** the deploy job is skipped

#### Scenario: A displaced run's backend change still deploys

- **WHEN** a push to `main` changing `api/**` queues behind a running deploy, and its pending run is then
  cancelled by a later docs-only push
- **THEN** the later run diffs against the live commit, which predates the backend change, so the deploy
  job runs and ships it

#### Scenario: An unreadable live commit fails open

- **WHEN** the health route cannot be read, or reports a commit not in the pushed commit's history
- **THEN** the deploy job runs

#### Scenario: Nothing deploys the backend off main

- **WHEN** a commit is pushed to any branch other than `main`
- **THEN** the deploy workflow does not run, and nothing bundles or deploys the backend outside the check
  workflow

#### Scenario: Two merges in quick succession do not race

- **WHEN** two pushes land on `main` while a deploy is running
- **THEN** their deploy runs wait for it rather than cancelling it, so every published bundle is probed by
  the run that published it
