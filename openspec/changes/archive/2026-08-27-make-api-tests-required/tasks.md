## 1. The unfiltered check workflow

- [x] 1.1 Create `.github/workflows/api.yml`: `name: api`, `on: push: branches: ["**"]` with **no**
      `paths:` filter, a comment stating that the absence of the filter is what lets the context be
      required without freezing merges, `permissions: contents: read`, and
      `concurrency: group: api-${{ github.ref }}` / `cancel-in-progress: true`.
- [x] 1.2 Add the single job `api-test` with an explicit `name: api-test`, carrying the same comment
      `ios.yml:111` / `appstore.yml:32` / `check-label.yml:27` carry — the display name IS the
      status-check context required by `.github/rulesets/main.json`, and renaming it without updating
      the ruleset freezes merges.
- [x] 1.3 Add its steps in order, moving the prose rationale across from `api-deploy.yml` rather than
      re-inventing it: checkout (`actions/checkout@v7.0.1`), `denoland/setup-deno@v2` (`v2.x`),
      `python3 scripts/resolve-deployment.py prod`, then with `working-directory: api` —
      `deno fmt --check`, `deno lint`, `deno task check`, `deno task test`, `deno task bundle`, and the
      `grep -q "${{ github.sha }}" dist/main.js` stamp verification.
- [x] 1.4 Note in a comment that `GITHUB_SHA` is set by Actions and is what
      `deployments/components/build.json` reads, so the stamp check needs no extra wiring here.

## 2. Shedding the duplicate gate from the deploy workflow

- [x] 2.1 Delete from `.github/workflows/api-deploy.yml` the `Test the deployment resolver` step and
      the four check steps (`Format check`, `Lint`, `Type check`, `Test`), with their comment blocks.
- [x] 2.2 Rewrite the workflow's header comment: it currently says the workflow is "gated on green Deno
      fmt/lint/check/test". State instead that the gate is the required `api-test` status check, that a
      commit cannot reach `main` without it, and why the check set is deliberately not repeated here.
- [x] 2.3 Verify the surviving step order is intact and self-consistent: Resolve the deployment →
      Bundle → Verify the bundle carries this commit → Migrate (main only) → Deploy (main only) →
      Probe (main only). Confirm the `Resolve the deployment` step's comment still reads correctly now
      that no check step follows it.
- [x] 2.4 Confirm the path filter is unchanged (`api/**`, the workflow file, `deployments/**`,
      `scripts/resolve-deployment.py`) and that `screenshots/**` is still absent.

## 3. Making the checks gate

- [x] 3.1 Add three entries to `.github/rulesets/main.json`'s `required_status_checks`: `api-test`,
      `resolver-test`, `spec-validate`, each with `integration_id: 15368` to match the existing six.
- [x] 3.2 Confirm each of the three comes from a job with no `paths:` filter — `api.yml` (new),
      and `build.yml`'s `spec-validate` / `resolver-test`, which inherit that workflow's unfiltered
      `push: branches: ["**"]`.
- [x] 3.3 Confirm each job's display name equals the context string exactly.

## 4. Specs

- [x] 4.1 Apply the `backend-deployment` delta: replace the two requirements
      ("Path-scoped, isolated workflow; deploy on main only" and "Deploy is gated on green checks")
      with the rewritten versions. Diff the result against the current spec and confirm the removed
      lines are only the intended ones — the `screenshots/**` scope and its three scenarios, and the
      before-the-deploy-step ordering language.
- [x] 4.2 Delete `openspec/specs/ci-build/` per the `ci-build` removal delta.
- [x] 4.3 Grep the tree for `ci-build` outside `openspec/changes/archive/` and confirm nothing live
      references the removed capability.
- [x] 4.4 Run `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` and confirm it passes
      with one fewer capability.

## 5. Verification

These are POST-SHIP observations, not work items: they can only be made once the PR exists and
then once it has merged, so they are recorded here rather than checked off before archiving.

- [ ] 5.1 Open the PR and confirm `api-test`, `resolver-test` and `spec-validate` all post a status
      check on it.
- [ ] 5.2 Verify the freeze-proofing on a ref whose diff falls outside the deploy filter. This PR
      edits `.github/workflows/api-deploy.yml`, which IS in that filter, so `api-deploy` runs here and
      proves nothing; check the next unrelated PR instead — `api-test` must post while `api-deploy`
      is absent.
- [ ] 5.3 Confirm `gh pr checks --required` on the PR lists nine contexts, not six, once `/ship` has
      applied the committed ruleset at first-in-queue.
- [ ] 5.4 After merge, confirm `api-deploy` on `main` runs Resolve → Bundle → Verify → Migrate →
      Deploy → Probe with no check steps, and that the boot probe goes green.
- [ ] 5.5 Apply the `internal` changelog label; no customer-visible behavior changed.
