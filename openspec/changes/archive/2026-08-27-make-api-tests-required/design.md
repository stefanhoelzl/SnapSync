## Context

`.github/rulesets/main.json` requires six status checks: `build`, `ios-build`, `ios-test`,
`appstore-metadata-validate`, `diagrams`, `check-label`. Everything else that runs in CI is advisory.

That set is not the set of checks that exist. `build.yml` declares four jobs and only one of them is
required. `api-deploy.yml` declares `test-and-deploy`, which runs `deno fmt --check`, `deno lint`,
`deno task check` and `deno task test` before it ships anything — and is required by nothing.

Advisory is weaker than it sounds here, because of how merges happen. `/ship` arms GitHub auto-merge
and then watches; auto-merge waits on required checks only, and `ship-wait.ts` mirrors that criterion
deliberately (`classifyAhead` drops any rollup entry outside the required set, and the watcher passes
`--required` to `gh pr checks`) so that its verdict matches the merge that will actually happen. A red
`test-and-deploy` is therefore visible on the PR and inert.

What follows from that is a specific, already-realised failure shape: the PR merges, `api-deploy` runs
on `main`, its check steps fail, and because the deploy step is ordered after them and gated on
`github.ref == 'refs/heads/main'`, nothing ships. `main` is broken, production silently keeps serving
the previous bundle, and the only signal is a red run on a branch nobody is watching. `build.yml:54-62`
records the same shape from the resolver's history — "adding two required inventory keys broke 20 of
these 30 tests, the PR merged clean, and the deploy failed on main with production still serving the
previous bundle" — and concludes "A test that can only fail after merge is not a gate." The fix that
comment introduced was a new **non-required** job, so the conclusion was written down and not applied.

The obvious remedy — add `test-and-deploy` to the ruleset — is unavailable. `api-deploy.yml` is
path-scoped to `api/**`, `deployments/**`, `scripts/resolve-deployment.py` and itself. GitHub treats a
required context that is never posted as permanently pending, so requiring a path-filtered job freezes
every PR outside its filter. Every required context today comes from an unfiltered job, and two of the
workflows say so in comments written for exactly this reason (`build.yml:5-8`, `ios.yml:56-59`).

Two constraints from the repository's own contracts shape the rest. `backend-deployment` requires the
backend workflow to be **isolated from the Gradle/iOS workflows** — "its own workflow file; it SHALL
NOT couple to the Gradle build or iOS jobs" — which rules out hosting the api checks in `build.yml`.
And `openspec/config.yaml`'s criterion for what earns a spec rules on `ci-build`, which this change
would otherwise have extended.

## Goals / Non-Goals

**Goals:**

- A broken api cannot reach `main`. The Deno check set gates the PR, not the deploy.
- `resolver-test` and `spec-validate` become gates too, closing the same hole in `build.yml`.
- No merge can freeze: every newly required context comes from a job with no path filter.
- `backend-deployment` stops describing a trigger (`screenshots/**`) that was removed months ago.
- One home per contract: the ruleset stays the sole authority for which checks gate a merge.

**Non-Goals:**

- Re-verifying the deployed commit. See D4 — the residual is accepted and stated, not engineered away.
- A mechanical guard asserting "every required context comes from an unfiltered job". See D5.
- Touching `ios-ci`, `architecture-guards`, or any other process capability. `ci-build` is removed
  because this change touches it; the criterion is still not applied as a sweep.
- Changing what the checks *are*. The same four commands run, invoked the same way, with the same
  permissions.
- Changing the deploy, the migration, or the boot probe.

## Decisions

### D1 — A new unfiltered `api.yml`, not a job in `build.yml`

The required context must come from a job with no path filter, and `build.yml` is the obvious host —
it already carries `resolver-test`, itself a deliberate duplicate of a step in `api-deploy.yml`, added
for precisely this reason. It is nonetheless the wrong host: `backend-deployment` requires the backend
workflow to be isolated from the Gradle build and the iOS jobs, in its own file. Putting the Deno
checks into the Gradle workflow contradicts a live requirement in order to satisfy another.

So the checks get their own unfiltered workflow, `api.yml`, holding one job named `api-test`. That
honours isolation, keeps Deno tooling out of the Gradle workflow, and buys a third thing: its own
concurrency group. `build.yml` runs with `cancel-in-progress: true` on `${{ github.ref }}`, so two
quick merges to `main` cancel the first run's jobs — a check hosted there could be cancelled out from
under a deploy that `api-deploy` (which uses `cancel-in-progress: false`) runs to completion.

**Alternative considered:** `api-test` as a job in `build.yml`, with the isolation requirement relaxed
to cover only the deploy. Rejected: it retires a requirement written on purpose, and inherits the
cancellation semantics above.
**Alternative considered:** removing the path filter from `api-deploy.yml` so its existing context
always posts. Rejected: its `concurrency: api-deploy` group has `cancel-in-progress: false`, so every
branch push in the repository would serialise behind one global queue, and a workflow named for
deploying would run on every commit that touches nothing it deploys.

### D2 — The check steps move out of `api-deploy.yml` rather than being duplicated

`api-test` could have been additive, leaving `api-deploy` to re-run the same four commands. That is
what `resolver-test` does today, and the duplication is the reason its comment ("Nothing else runs it")
is now false in the file it was written in.

Once the context is required and the ruleset has no bypass actor, a commit on `main` cannot have
skipped the checks. Re-running them at deploy time is then a second copy of a gate that has already
fired — the same failure mode as a second copy of a contract, one workflow's worth further down. So
the four Deno steps and the `resolve_deployment_test.py` step leave `api-deploy.yml` entirely.

`bundle` and the commit-stamp grep are the exception, and they do **not** move: `api-deploy` must
produce `api/dist/main.js` for the deploy action, and artifacts do not cross workflows. They appear in
both files for different reasons — in `api-test` as a check that the bundle builds and carries its
commit, in `api-deploy` as the artifact build itself. `GITHUB_SHA` is set in every Actions job and is
what `deployments/components/build.json` reads, so the stamp works in `api-test` with no extra wiring.

**Alternative considered:** keep `deno task test` alone in `api-deploy` as a cheap re-verify. Rejected
under D4.

### D3 — The gate becomes admission, not ordering

`backend-deployment`'s requirement said the checks run **before the deploy step**, and the deploy runs
**only** when all of them pass — a within-job ordering property. After this change the guarantee is
different in kind: the commit could not have reached `main` at all without the checks passing, because
`main.json` has `bypass_actors: []` and `current_user_can_bypass: never`, and `/ship` applies the
committed ruleset when the PR is first in queue. The requirement is rewritten to state that, rather
than deleted — the property it names is stronger, not absent.

What survives verbatim, because it is about the checks themselves and not where they run: the
deployment is resolved before any check (the checks type-check and exercise generated configuration);
the type-check and test are invoked through their `deno.json` tasks rather than restated, so CI cannot
drift from what a developer runs locally; the type-check covers `src/`, `src/dev/` and `src/scripts/`;
and the test task grants no `--allow-net`, which is what makes it impossible for a test to reach the
real storage zone.

### D4 — The rebase-SHA gap is accepted and stated, not closed

Merges are rebase-only (`allowed_merge_methods: ["rebase"]`), so the commit that lands on `main` is not
the commit the required checks ran against. Nothing re-runs the suite at the deployed SHA.

This is accepted. What backs the deploy is the ruleset — the *content* was tested, and a rebase changes
parentage and committer metadata, not the tree — plus the post-publish boot probe, which witnesses that
this bundle actually booted and is serving. The probe is the instrument for "the thing that shipped is
wrong", and it already exists. Adding a re-verify step would buy coverage of a fault mode nobody has
observed, at the cost of restoring the duplication D2 removes.

The gap is stated in the spec rather than left implicit, because a reader who assumes the deployed SHA
was tested would be wrong, and "absence is never silent" applies to guarantees as much as to values.

### D5 — The unfiltered-job rule stays a written requirement, with no mechanical gate

A checker asserting that every context in `main.json` resolves to a job with no `paths:` filter is
buildable — `resolve_deployment_test.py` is the shape it would take. It is not worth it. The rule has
never been broken; every currently required job is unfiltered. And its failure mode is loud and
immediate: the first PR after the mistake sits pending forever, visibly, within minutes. That is the
opposite of the silent-until-a-release drift that justifies a gate elsewhere in this repository. A
guard would also need to be a required context itself, which is a fourth thing to keep in sync.

The rule is stated in `backend-deployment` where it is load-bearing (it is *why* `api.yml` carries no
filter) and remains in the comments already present in `build.yml` and `ios.yml`.

### D6 — `ci-build` is removed

This change would otherwise have extended `ci-build` to name `build.yml`'s other jobs. Applying the
criterion from `changes/archive/2026-08-26-address-pr-titles-to-users` — *a spec exists where the
contract is spread across artifacts and drift is invisible; where a single committed artifact IS the
contract and carries its own rationale, the spec is a second copy* — the extension is the wrong move
and so is the spec.

A rescue pass over its four requirements finds zero claims stated only there: "runs on every push" is
`build.yml:4-8` with its own comment on why tags are excluded; `./gradlew build` on JDK 25 /
`ubuntu-latest` is lines 19-30; the check's name is the job's name; cancel-in-progress is lines 13-15;
and "green on success, red on failure" is GitHub Actions' semantics, not a project claim. The second
copy has already drifted — the spec describes one job where the file has four, and omits the
`compileIosMainKotlinMetadata -Psnapsync.rig=true` step guarding the build-property-gated trees — and
nothing caught it, which is what "a drift source with no gate behind it" means.

That change's Non-Goals named `ci-build` explicitly and deferred it: "the criterion is recorded, not
applied in a sweep." This is not a sweep — the capability is touched here, which is the condition the
criterion states for applying it.

**Alternative considered:** keep `ci-build` untouched. Rejected: it would leave a spec that omits three
of `build.yml`'s four jobs in a change that makes two of those three into required gates — the drift
made worse by the very change that noticed it.

### D7 — `backend-deployment`'s stale `screenshots/**` scope is corrected here

The spec scopes the deploy workflow to `api/**`, `screenshots/**` and the workflow file, and carries
three scenarios built on the capture path: checks run when captures change, a capture refresh
redeploys the page, and the checks need no tool beyond the backend runtime (because the page derived
its images at build time). None of that is true — the landing page moved to the Astro `site/` module
and `site-deploy.yml` ships it, and `api-deploy.yml:36-40` says so. The real filter also includes
`deployments/**` and `scripts/resolve-deployment.py`, which the spec never gained.

This change is rewriting that requirement's trigger list to add "and the check workflow has no filter
at all", so the correction lands in the same edit. Leaving it would mean writing a new sentence into a
requirement whose neighbouring sentences are known to be false.

## Risks / Trade-offs

- **A renamed job silently stops gating.** A job's display name IS its status-check context; renaming
  `api-test` without updating `main.json` leaves a required context that never posts → every PR
  freezes. → Loud by construction, and mitigated the way the repository already mitigates it: the job
  carries the same "this display name is the context required by `.github/rulesets/main.json`" comment
  that `ios.yml:111`, `appstore.yml:32` and `check-label.yml:27` carry.
- **The deployed SHA is not the tested SHA.** → Accepted; see D4. Backed by the ruleset's zero bypass
  actors and the post-publish boot probe.
- **`api-deploy` no longer fails closed on its own.** If the ruleset were weakened — a bypass actor
  added, a context dropped — a broken bundle could reach the deploy with nothing between it and
  publish. → The ruleset is committed at `.github/rulesets/main.json` and re-applied by `/ship` on
  every merge, so a live-side weakening is reverted at the next ship rather than persisting.
- **Three new required contexts can wedge the queue on a flake.** Nine required checks is more surface
  for an infrastructure hiccup to block a merge. → All three already run on every branch push today
  and their observed failure rate is the reason this change exists; none is a new execution, only a
  new consequence.
- **`bundle` runs twice on an api change** — once in `api-test`, once in `api-deploy`. → Structural
  (artifacts do not cross workflows), bounded (`deno bundle` on a single entrypoint), and the two runs
  answer different questions.
- **Removing `ci-build` loses a reader's entry point to CI.** → The four requirements it held are all
  restated in `build.yml`'s own comments, which is where a reader of CI already looks; no live
  reference to the capability exists outside `changes/archive/`.

## Migration Plan

The ruleset and the workflows must land together, and `/ship` sequences that for free: it applies
every `.github/rulesets/*.json` when the PR is first in queue — after the rebase, before CI — so the
PR's own head already contains `api.yml` and the three new contexts post on it. The change gates
itself.

Rollback is a revert: the ruleset returns to six contexts at the next ship, and `api-deploy.yml`
regains its check steps in the same commit. No deployed artifact and no store state is touched, so
there is nothing to undo outside git.

## Open Questions

None.
