## 1. The deploy workflow

- [x] 1.1 Create `.github/workflows/deploy.yml`: `on: push: branches: [main]`, no `paths:`, workflow `permissions: {}`, `concurrency: {group: deploy, cancel-in-progress: false}`, and a header stating that it is main-only, that no job in it may ever be a required check, and why TestFlight is not here (design Decisions 1 and 7)
- [x] 1.2 Add the `changes` job (`contents: read`): full-history checkout, resolve the deployment, read `sha` from `https://<domain>/health`, fail open on unreachable / empty / `dev` / non-ancestor, and otherwise `git diff --name-only <live> HEAD` over `api/**`, `deployments/**`, `scripts/resolve-deployment.py`, `.github/workflows/deploy.yml`. Emit `api=true|false` and log the live sha and the decision
- [x] 1.3 Add the `api` job (`needs: changes`, `if: needs.changes.outputs.api == 'true'`, permissions `contents: read` + `actions: read`): move every step from `api-deploy.yml`'s `test-and-deploy` verbatim, delete each `github.ref == 'refs/heads/main' &&` guard (keeping the rest of each condition), and carry over the header's rationale comments
- [x] 1.4 Add the `site` job (`contents: read`, unconditional): move `site-deploy.yml`'s steps and comments verbatim
- [x] 1.5 Add the `appstore-metadata-apply` job (`contents: read`, unconditional, no `needs:`, no `if:`): move the job from `appstore.yml` verbatim, with its `env:` block

## 2. Retire the old deployers

- [x] 2.1 Delete `.github/workflows/api-deploy.yml` and `.github/workflows/site-deploy.yml`
- [x] 2.2 Reduce `appstore.yml` to `appstore-metadata-validate` and rewrite its header (the gate/deliver split now spans two files)
- [x] 2.3 Rewrite the header comments in `api.yml`, `site.yml` and `build.yml` that explain the split by naming `api-deploy.yml` / `site-deploy.yml`, including `api.yml`'s "artifacts do not cross workflows" note on the Bundle step

## 3. References

- [x] 3.1 Update every remaining mention of `api-deploy.yml` / `site-deploy.yml` / `test-and-deploy` outside `openspec/changes/archive/`: `CLAUDE.md`, `api/README.md`, `api/deno.json`, `api/src/config.ts`, `api/src/app.ts`, `api/src/scripts/{assert-schema,probe,migration-plan}.ts`, `api/test/{min-app-version-floor,scripts/migration-plan}.test.ts`, `iosApp/Configuration/Config.xcconfig`, `scripts/resolve-deployment.py`, `scripts/resolve_deployment_test.py`. Re-grep until zero hits. (Found in passing and corrected rather than renamed: `config.ts`/`Config.xcconfig` claimed the deploy "asserts the relation" with `MIN_APP_VERSION`, and `CLAUDE.md`/`deno.json` that it runs `deno lint`. Both have been `api.yml`'s job since the gate split)
- [x] 3.2 At sync, also correct `backend-deployment`'s Purpose line "A path-scoped GitHub Actions workflow runs the Deno checks on every branch", which is already stale and doubly so after this change. A delta cannot carry a Purpose edit

## 4. Verify

- [x] 4.1 `actionlint` (v1.7.12 with shellcheck, via podman) on `deploy.yml` and `appstore.yml`; `deno fmt --check` / `deno task check` in `api/` for the comment-only source edits
- [x] 4.2 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` and `validate unify-main-deploy-workflow --strict`
- [x] 4.3 Confirm the live ruleset's required contexts are unchanged and contain no `deploy.yml` job (`gh api repos/stefanhoelzl/SnapSync/rulesets`)
- [ ] 4.4 After merge: the first `deploy.yml` run on `main` shows `changes` deciding `api=true` (the path set contains the new file), `api` publishing and probing green, `site` and `appstore-metadata-apply` green. Neither deleted workflow runs
