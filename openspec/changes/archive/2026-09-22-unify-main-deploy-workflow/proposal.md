## Why

What ships on a merge to `main` is spread over three workflows with three different shapes. The api
publish is a path-filtered workflow whose job is still named `test-and-deploy`, although it tests nothing
since `api.yml` became the required gate. It also still runs on every branch push that touches the api,
only to bundle and discard. The site mirror is a second path-filtered workflow. The App Store metadata
apply is a `needs:`-chained job inside `appstore.yml`. Now that every check is a required, unfiltered gate
of its own, the deploy half has nothing left to share with the gates. It can be one workflow that runs only
on `main`, so there is one place to read what a merge ships.

## What Changes

- **New `.github/workflows/deploy.yml`**, triggered only by a push to `main`. It has no workflow-level
  `paths:` filter. It holds four jobs: `changes`, `api`, `site` and `appstore-metadata-apply`.
- **api deploys when api-relevant paths changed since the commit that is LIVE**, as reported by `/health`,
  not since `github.event.before`. A small `changes` job computes this. If `/health` is unreachable, or its
  sha is not in history, it fails open and deploys. The path set is today's (`api/**`, `deployments/**`,
  `scripts/resolve-deployment.py`, the workflow file).
- **site deploys on every `main` push.** The mirror is idempotent and the build takes about 25 s, so it
  needs no path gate and no baseline.
- **`appstore-metadata-apply` moves unchanged** from `appstore.yml`, keeping its name. It still runs on
  every `main` push, as the "file wins" contract requires. It drops its in-run `needs:` on the validator,
  because the strict required check has already passed on this content.
- **One workflow-level concurrency group, `deploy`, with `cancel-in-progress: false`.** Runs are
  serialized, and api's probe never observes a sibling run's bundle.
- **Per-job permissions:** `permissions: {}` at the workflow level. Only `api` gets `actions: read`, for its
  `bundle-<sha>` rollback artifacts.
- **None of `deploy.yml`'s jobs is ever a required check.** They never post on a pull request, and the
  workflow's header says so. The ruleset is untouched.
- **Removed:** `api-deploy.yml` and `site-deploy.yml`, including api-deploy's branch-push runs and its
  roughly eight `if: github.ref == 'refs/heads/main'` guards. `appstore.yml` keeps only
  `appstore-metadata-validate`.
- **Unchanged:** TestFlight delivery (`ios-deliver`) stays in `ios.yml`. It consumes that run's Release
  archive, its `CFBundleVersion` is `ios.yml`'s `run_number` (the value `ios-appstore-promote.yml` resolves
  back to a commit), and it also delivers on a branch `workflow_dispatch`.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `backend-deployment`: the requirement "Path-scoped, isolated workflow; deploy on main only" changes. The
  deploy is a job in a shared, main-only deploy workflow rather than its own path-filtered workflow run on
  every branch. Its trigger is a path diff against the live commit, and the serialization that makes this
  safe becomes part of the contract. The check workflow half is unchanged.

`web-site` states no trigger for the site deploy, only how it mirrors and which credential it uses, so it
needs no delta. `ios-appstore-metadata` names `appstore-metadata-apply` and its main-only, never-required,
red-on-failure contract, not the file it lives in, so it needs no delta while the job keeps its name.

## Impact

- `.github/workflows/`: `deploy.yml` added; `api-deploy.yml` and `site-deploy.yml` deleted; `appstore.yml`
  reduced to its gate; header comments in `api.yml`, `site.yml` and `build.yml` that explain the split by
  naming the old files.
- Docs and comments naming the old files: `CLAUDE.md`, `api/README.md`, `api/deno.json`, `api/src/config.ts`,
  `api/src/app.ts`, `api/src/scripts/{assert-schema,probe,migration-plan}.ts`.
- No app, api-runtime or user-visible change. The label is `internal`.
- Branch ruleset: no change. None of the removed contexts was required, and none of the new ones may be.
