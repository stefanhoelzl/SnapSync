## Context

Two earlier changes turned every CI check into a required, unfiltered gate:
`changes/archive/2026-08-27-make-api-tests-required` created `api.yml`, and a later split created `site.yml`.
The deployers stayed in three different places:

| deployer | where | trigger | notes |
|---|---|---|---|
| api publish | `api-deploy.yml`, job `test-and-deploy` | push, **any branch**, path-filtered | every deploy step guarded `if: main`; on a branch it only bundles and stamps, which duplicates `api.yml`; group `api-deploy`, `cancel-in-progress: false`; `actions: read` workflow-wide |
| site mirror | `site-deploy.yml`, job `build-and-deploy` | push to `main`, path-filtered | idempotent mirror, about 25 s |
| App Store metadata | `appstore.yml`, job `appstore-metadata-apply` | push, `if: main`, `needs:` validate | "file wins" on every main push |
| TestFlight | `ios.yml`, job `ios-deliver` | main or dispatch, `needs:` both gates | consumes the in-run Release archive |

The live ruleset is rebase-only and **strict** (the branch must be up to date), with no bypass actor. The
content on `main` has therefore always passed every required gate. A deploy has no need to re-gate, which is
the stance `backend-deployment` already records.

## Goals / Non-Goals

**Goals:**
- One workflow, triggered only by `main`, holding every deployer that has no reason to live elsewhere.
- No regression in *when* each deployer runs, and no new way for a change to silently never ship.
- Least privilege per job, now that the deployers share a file.

**Non-Goals:**
- Moving TestFlight delivery (see Decision 1).
- Changing any gate, the ruleset, or what any deployer does once it runs. The api job's migration,
  maintenance-window, rollback and probe steps move as they are, minus the `if: main` guards.
- Re-running the check set on the `main` commit.

## Decisions

### 1. TestFlight stays in `ios.yml`

`ios-deliver` is tied to its run three ways. It consumes that run's Release archive (a separate workflow
would re-archive, 5–10 min of macOS time, or fetch a cross-run artifact through `workflow_run`). Its
`CFBundleVersion` is `ios.yml`'s `github.run_number`, which `ios-appstore-promote.yml` resolves back to a
commit; a new workflow's run numbers restart at 1, below every build TestFlight already holds. And it also
delivers on a branch `workflow_dispatch`, which contradicts "main only".
*Alternative:* move it anyway, with a build-number offset and a `workflow_run` trigger. Rejected: that
buys uniformity at the cost of the most fragile path in the repository.

### 2. api's baseline is the live commit, not `github.event.before`

A concurrency group keeps **one** pending run, and a newer pending run cancels the older one even under
`cancel-in-progress: false`. Today that is harmless, because `api-deploy.yml` only enters its group on api
pushes. In a shared workflow every `main` push enters the group. So: an api merge queues behind a running
deploy, a docs merge queues behind it and cancels it, and the docs run's `before..sha` diff contains no api
paths. The api change would never deploy, with nothing red anywhere.

The `changes` job therefore reads the live sha from `https://<domain>/health` (the same read the rollback
capture step already makes, with the domain taken from `build/deployment.properties`). It then runs
`git diff --name-only <live> HEAD -- <paths>` over a full-history checkout and emits `api=true|false`. Any
later run catches up on whatever a cancelled one missed. The api job also becomes self-healing: a deploy that
went red before publishing leaves the old sha live, so the next `main` push retries it.

Failing open means `api=true` when `/health` is unreachable, the sha is `dev` or empty, or the sha is not an
ancestor of HEAD (`git merge-base --is-ancestor`). A redundant deploy is probed, while a skipped one leaves
production behind with no signal.

*Alternatives:* `event.before` plus per-job concurrency groups was rejected, because it relies on whether a job
skipped by `if:` enters its group, which is unverified. The last successful `deploy.yml` run's `head_sha`
was rejected because it is workflow-level, so a run where site succeeded and api failed would hide the api
change. `dorny/paths-filter` was rejected because it adds a third-party action with repository read access,
and it still diffs against `before`.

### 3. site and metadata get no gate at all

Site mirroring is idempotent (upload, then delete stale keys) and takes about 25 s, so running it on every
push costs less than a baseline mechanism would. The metadata apply already runs on every `main` push,
because `ios-appstore-metadata` requires that the files overwrite console edits on each push, so it is
unchanged. Neither needs the `changes` job.

### 4. One workflow-level concurrency group

`group: deploy`, `cancel-in-progress: false`. It serializes whole runs, keeps api's probe from observing a
sibling run's bundle, and, with Decisions 2 and 3, makes displacement lossless.
*Trade-off accepted:* a site or metadata apply can wait a few minutes behind an api maintenance window.
*Alternative:* per-job groups. Rejected: more YAML, and the same unverified interaction with skipped jobs.

### 5. `appstore-metadata-apply` drops `needs:` and keeps its name

Its validator is now in another workflow, so `needs:` cannot reach it. The strict required check has
already passed on this content, the same trust the api and site deploys rely on. It keeps its job name
because `ios-appstore-metadata` names it in five requirements, and all of them stay true. The other jobs get
short names (`changes`, `api`, `site`), shown as `deploy / api`.

### 6. Per-job permissions

`permissions: {}` at the workflow level. `api` gets `contents: read` and `actions: read`: it reads earlier
runs' `bundle-<sha>` artifacts, which it looks up repo-wide by name, so the lookup survives the file
rename. `changes`, `site` and `appstore-metadata-apply` get `contents: read`. Secrets stay step-scoped
as they are today, so the site job still holds only the storage password
(`backend-deployment`: "The browser-facing site is deployed by a second, account-key-free path").

### 7. Nothing in `deploy.yml` is ever required

It never posts on a pull request. The header states this the way `ios.yml` does for `ios-deliver`.

## Risks / Trade-offs

- [The `deploy.yml` file itself is in api's path set, so editing the site or metadata job redeploys api]
  → Accepted. Every deploy is probed, and the path set must include the file that decides how api ships.
- [A persistently failing api deploy re-runs on every `main` push until it is fixed] → Accepted, and
  arguably better than today, where it went red once and then sat silently behind `main`.
- [`/health` reports the maintenance bundle's sha during an open window] → It is the same commit as the
  real bundle, and a stuck window is handled by the existing restore path. The baseline stays correct.
- [Full-history checkout in `changes`] → `fetch-depth: 0` on a small repository takes seconds.

## Migration Plan

It lands as one PR. Merging it to `main` is itself the first `deploy.yml` run. The run diffs from the live
commit, which predates this PR, and `deploy.yml` is in the path set, so the api job runs and deploys an
unchanged backend: a live smoke test of the new path. The deleted workflows simply stop triggering. To roll
back, revert the PR.

## Delta completeness (archive gate 2)

No Gradle module is touched. Each area the diff touches, and its owning capability:

- `.github/workflows/deploy.yml`, `api-deploy.yml` (deleted), `api.yml`: `backend-deployment`, which has the delta.
- `site-deploy.yml` (deleted), `site.yml`: `web-site`. No delta: the spec states how the site mirrors and
  which credential it holds, never what triggers the deploy, and both are unchanged.
- `appstore.yml`: `ios-appstore-metadata`. No delta: the spec names `appstore-metadata-apply` and its
  main-only, never-required, red-on-failure contract, all of which still hold. The job keeps its name,
  and the spec never required the in-run `needs:`.
- `build.yml`, `scripts/resolve-deployment.py`, `scripts/resolve_deployment_test.py`: `deployment-configuration`.
  Comment-only, behavior-preserving.
- `api/**` (`README.md`, `deno.json`, `src/*`, `test/*`): `backend-deployment` / `database` / `min-app-version`.
  Comment-only, behavior-preserving. Two of the edited comments were already false and now name the gate that
  really asserts the claim (`api.yml`).
- `iosApp/Configuration/Config.xcconfig`: `min-app-version`. Comment-only. The `MARKETING_VERSION` line the
  floor test parses is untouched.
- `CLAUDE.md`: docs, not a capability.
