## Why

The api's checks are not a gate. `.github/rulesets/main.json` requires six status checks and
`test-and-deploy` — the job running `deno fmt --check`, `deno lint`, `deno task check`, and
`deno task test` — is not one of them. `/ship` arms auto-merge, which waits on **required** checks
only (`ship-wait.ts` filters the rollup to exactly that set, matching auto-merge's own criterion), so
a PR that breaks the backend goes red and merges anyway. `api-deploy` then fails on `main` before its
deploy step, leaving `main` broken with production still serving the previous bundle and nothing
watching.

Two sibling jobs have the same hole: `spec-validate` and `resolver-test` are also unrequired —
including `resolver-test`, which exists *because* "a test that can only fail after merge is not a
gate" (`build.yml:54-62`), and was then added as a non-required job, so it never became one.

The naive fix does not work: every required context in this repository comes from a job with **no
path filter**, deliberately (`build.yml:5-8`, `ios.yml:56-59` — "no merge can freeze"). `api-deploy`
is path-scoped, so requiring its context would leave every PR that does not touch `api/` waiting
forever on a check that never posts.

## What Changes

- **New `.github/workflows/api.yml`** — unfiltered (`push: branches: ["**"]`), its own concurrency
  group, one job `api-test` that resolves the `prod` deployment and then runs the full check set plus
  `deno task bundle` and the commit-stamp verification. Its context posts on every ref, so requiring
  it can never freeze a merge.
- **`api-deploy.yml` sheds its check steps** — the resolver suite and the four Deno checks move out.
  It keeps resolve → bundle → stamp-verify → migrate → deploy → probe, stays path-scoped, stays
  `main`-only, stays isolated from the Gradle/iOS workflows.
- **`.github/rulesets/main.json` gains three required contexts** — `api-test`, `resolver-test`,
  `spec-validate` — taking the gate from six to nine. `/ship` applies the committed ruleset when the
  PR is first in queue, so the new contexts gate this PR itself.
- **The deploy's gate moves from ordering to admission.** It was "these steps run before the deploy
  step in the same job"; it becomes "this commit could not have reached `main` without the checks
  passing" — enforced by a ruleset with an empty `bypass_actors` and `current_user_can_bypass: never`.
- **`backend-deployment`'s stale trigger list is corrected.** The spec still scopes the deploy
  workflow to `screenshots/**` and carries three scenarios about refreshing captures; that path was
  removed when the landing page moved to `site/` and `site-deploy.yml`.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `backend-deployment`: the requirement "Deploy is gated on green checks" is rewritten — the check
  set is unchanged and still runs on every push, but from the unfiltered `api.yml`, and the gate is
  the required status check enforced **pre-merge** rather than in-workflow steps ordered before the
  deploy. The requirement "Path-scoped, isolated workflow; deploy on main only" is corrected to the
  deploy workflow's real path scope (`screenshots/**` is gone) and extended to say the check workflow
  carries no path filter at all. Isolation survives and now covers both files.

### Removed Capabilities

- `ci-build`: removed. A line-by-line rescue pass over its four requirements finds **zero** claims
  stated only there — every one is in `build.yml`, which carries its own rationale in comments. It is
  the same shape as `ship-command` and `branch-protection`, removed by
  `changes/archive/2026-08-26-address-pr-titles-to-users` under the criterion *a spec exists where the
  contract is spread across artifacts and drift is invisible; where a single committed artifact IS the
  contract, the spec is a second copy*. That change named `ci-build` in its Non-Goals — "the criterion
  is recorded, not applied in a sweep" — so it is applied here, where this change touches the
  capability. The drift is already real: the spec describes one job, `build.yml` has four.

## Impact

- `.github/workflows/api.yml` — new file.
- `.github/workflows/api-deploy.yml` — five steps removed; header comment rewritten (it currently
  claims the deploy is "gated on green Deno fmt/lint/check/test").
- `.github/rulesets/main.json` — three contexts added. Applied to the live ruleset by `/ship`.
- `openspec/specs/backend-deployment/spec.md` — two requirements rewritten, three scenarios deleted.
- `openspec/specs/ci-build/` — deleted. No live reference to it exists outside `changes/archive/`.
- No product code, no runtime behavior, no App Store-visible change. Changelog label: `internal`.
