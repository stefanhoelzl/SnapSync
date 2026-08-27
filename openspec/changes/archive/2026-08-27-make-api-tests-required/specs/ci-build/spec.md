## REMOVED Requirements

### Requirement: Build on every push

**Reason**: `.github/workflows/build.yml` is committed and states this contract exactly, in the file
that implements it and with its own rationale in comments. Under the criterion recorded in
`openspec/config.yaml` — a spec exists where a contract is spread across artifacts and drift between
them is invisible; where a single committed artifact IS the contract and carries its own rationale, the
spec is a second copy — this capability does not earn one. A rescue pass over its requirements found
zero claims stated only here. The second copy had already drifted: it describes one job where the file
declares four (`build`, `spec-validate`, `resolver-test`, and this change's sibling `api-test` in
`api.yml`), and omits the `compileIosMainKotlinMetadata -Psnapsync.rig=true` step guarding the
build-property-gated trees. Nothing caught that, which is what a drift source with no gate behind it
means. `changes/archive/2026-08-26-address-pr-titles-to-users` removed `ship-command` and
`branch-protection` under this criterion and named `ci-build` in its Non-Goals, deferring it until the
capability was touched; this change touches it.

**Migration**: `.github/workflows/build.yml` declares the trigger (`push: branches: ["**"]`, lines 4-8,
with the comment explaining why `vX.Y` tags are excluded and why there is no path filter), the runner
and toolchain (`ubuntu-latest`, `actions/setup-java` with `temurin` JDK 25, lines 19-30), and the
command (`./gradlew build`). A job's name IS its status-check context, so the `build` job posts the
`build` check. Which checks gate a merge is declared by `.github/rulesets/main.json`, the sole
authority for that set. That a status check concludes green on success and red on failure is GitHub
Actions' own semantics and was never a project claim.

### Requirement: Concurrency control

**Reason**: As above — a restatement of four lines of the file it describes.

**Migration**: `.github/workflows/build.yml:13-15` declares `concurrency: group: ${{ github.ref }}`
with `cancel-in-progress: true`. Note that `api-deploy.yml` and `api.yml` deliberately use different
groups and, for `api-deploy.yml`, `cancel-in-progress: false`; each states its own reason in its own
file, which is the arrangement this removal preserves.
