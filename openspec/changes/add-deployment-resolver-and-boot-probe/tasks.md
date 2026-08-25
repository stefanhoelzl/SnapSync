## 1. The resolver and the authored tree

- [x] 1.1 Create `deployments/components/` with `build.json` (sha, channel), `policy.json` (capacity, window max, lifetime), `apple.json` (team id, bundle id, app name, App Store URL, App Attest root CA as a single `\n`-escaped string), `storage-snap-sync-dev.json` (`kind: bunny`), and `storage-local.json` (`kind: filesystem`)
- [x] 1.2 Create `deployments/prod.json` and `deployments/local.json` — each an `extends` list plus its own keys; `prod` carries the domain and the runtime env references, `local` carries `127.0.0.1:8080` and no secrets
- [x] 1.3 Write `scripts/resolve-deployment.py` (stdlib only): argv is the deployment name, no default, unknown name is an error
- [x] 1.4 Implement the key inventory — per key: renderings, scope, required-if, default, rationale. Move the load-bearing comments out of `api/src/config.ts` into it (main-region host, do-not-trim the APNs PEM, do-not-lengthen the attest TTL, window-max vs lifetime are deliberately distinct)
- [x] 1.5 Implement shallow merge in `extends` order, deployment's own keys last; reject a component that itself declares `extends`
- [x] 1.6 Implement validation: unknown key, missing required key, unresolvable component, storage kind outside the sealed set, runtime-scope key naming a baked rendering. Write no rendering when any check fails
- [x] 1.7 Implement value resolution: literal, or `{env, scope?}` defaulting to `runtime`. Build-scope reads the process environment and emits the value (falling back to the inventory default); runtime-scope is copied through verbatim as a name
- [x] 1.8 Implement the six renderings, all emitted from one invocation at fixed paths: `api/src/deployment.json`, `api/src/deployment.d.ts`, `build/deployment.properties`, `iosApp/Configuration/Deployment.xcconfig`, `build/metadata/**`, `site/src/deployment.json`
- [x] 1.9 In the xcconfig renderer, derive `APS_ENVIRONMENT`, `APNS_ENV` and `SENTRY_ENVIRONMENT` from the single `channel` value, and emit no `SENTRY_DSN` unless `channel` names a distributed build
- [x] 1.10 Emit `deployment.d.ts` from the inventory (not by inference): the real discriminated union over storage kinds, with the inventory's rationale as JSDoc
- [x] 1.11 Add `.gitignore` entries for every rendering
- [x] 1.12 Write `scripts/resolve_deployment_test.py` (stdlib unittest — the resolver is Python, so a Deno test cannot drive it; `config.ts`'s consumption of the rendering is covered by the Deno suite in group 2) against fixture deployment trees: merge order, unknown key, missing required key, missing component, nested `extends`, unknown storage kind, runtime-key-in-baked-rendering, build-scope default, partial-write-on-failure

## 2. The backend consumes the resolution

- [x] 2.1 Rewrite `api/src/config.ts` around the static JSON import; derive `Config` from the generated `.d.ts`; delete every source constant except what `attestAppId` derives
- [x] 2.2 Derive `readConfig`'s required-secret set from the resolved declaration; delete the hand-written `missing[]` literal
- [x] 2.3 Add `buildSha` to `Deps` in `createApp`, beside `now` — not to `Config`, and not as a module-level import
- [x] 2.4 Shrink `api/src/dev/config.ts`: under `kind: filesystem` no secrets are required, so it overrides only the runtime-discovered tunnel host
- [x] 2.5 Move the eight tests off hardcoded `snap-sync-dev` / `snapsync.stho.net` onto explicit fixture `Config`s (`config`, `app`, `attest`, `apns`, `sweep`, `eventlink`, `landing`, `download`)
- [x] 2.6 Chain the resolver into the `deno.json` tasks (`check`, `test`, `bundle`, `dev:local`, `dev:tunnel`), each naming its deployment explicitly, as a separate invocation so the test task's permission set is unchanged
- [x] 2.7 Widen `deno task check` to cover `src/scripts/*.ts`

## 3. Out-of-bundle programs

- [x] 3.1 Create `api/src/scripts/`; `git mv` `sweep.ts` into it, fix its relative imports, move `sweep.test.ts` to `api/test/scripts/`, update `nightly-cleanup.yml`'s path
- [x] 3.2 Write `api/src/scripts/probe.ts`: pure argv (`--sha`, `--origin`, both required), injected `FetchLike`, `runProbe` + a pure `classify(status, body, expectedSha)`
- [x] 3.3 Implement the decision table — retry connection failure, 5xx, 404 and a valid-but-different sha to a 120s deadline at 5s intervals; fail immediately on `"dev"` and on an unparseable body, naming the cause; log every attempt's (code, sha)
- [x] 3.4 Write `api/test/scripts/probe.test.ts` covering every cell and both deadline boundaries, against a stubbed fetch and with no `--allow-net`

## 4. The health route

- [x] 4.1 Add `GET`/`HEAD` `/health` at the root in `api/src/app.ts`, returning `{"sha": …}` with `NO_CACHE`; `HEAD` returns headers with no body
- [x] 4.2 Add `/health` to the auth gate's `publicGet` predicate, extending its comment to cover the third reason a path is ungated
- [x] 4.3 Extend `app.test.ts` (status, body, cache header, HEAD, non-GET/HEAD not served) and `attest.test.ts` (ungated in both directions, and `/api/v1/health` not served)

## 5. The deploy workflow

- [x] 5.1 Add the resolver step to `api-deploy.yml`, before the checks, naming `prod` and passing `GITHUB_SHA`
- [x] 5.2 Add `grep -q "$GITHUB_SHA" dist/main.js` after the bundle step, so a missing stamp fails before anything deploys
- [x] 5.3 Add the probe step after the deploy step, main-only, invoking `src/scripts/probe.ts`
- [x] 5.4 Add `concurrency: { group: api-deploy, cancel-in-progress: false }`
- [x] 5.5 Rewrite the workflow header: the "there is deliberately no post-deploy boot probe" paragraph reverses, and states which half of the failure class the probe restores
- [x] 5.6 Add the resolver step to `nightly-cleanup.yml` and `site-deploy.yml`

## 6. The site

- [x] 6.1 Import the resolved rendering in `site/src/layouts/Layout.astro` and `site/src/pages/join.astro`; replace both `ogUrl` host literals

## 7. Gradle

- [x] 7.1 Replace `snapsync.domain` with `snapsync.deployment=prod` in `gradle.properties`
- [x] 7.2 Run the resolver from `domain/build.gradle.kts` at configuration time (`providers.exec`) and generate `LINK_ORIGIN` from the rendering
- [x] 7.3 ~~Add the resolver step to `build.yml`~~ — NOT NEEDED: `:domain` invokes the resolver at configuration time, so `./gradlew build` is self-sufficient on any runner with python3 (every runner has it)

## 8. Xcode — needs a Mac to verify

- [ ] 8.1 **Measure first:** does xcconfig `#include` hard-error or only warn on a missing file? If it only warns, fall back to a `__UNRESOLVED__` sentinel plus a guard asserting no built `Info.plist` contains it
- [x] 8.2 Strip `BUNDLE_ID`, `TEAM_ID`, `APP_NAME`, `MARKETING_VERSION`, `ASSOCIATED_DOMAIN`, `BACKGROUND_UPLOAD_URL_BASE`, `APS_ENVIRONMENT`, `APNS_ENV`, `SENTRY_DSN` and `SENTRY_ENVIRONMENT` from `Config.xcconfig`; add the hard `#include` of `Deployment.xcconfig`
- [x] 8.3 Add the resolver step to `ios.yml`, before "Archive signed device app", and pass the computed `MARKETING_VERSION` and `SENTRY_DSN` into it as build-scope environment values
- [x] 8.4 Document why the ssh-mac tunnel override STAYS a bare `BACKGROUND_UPLOAD_URL_BASE` string — a quick tunnel's hostname is minted after the resolver runs and is random per session, so no declared file can hold it (the same timing fact that kept `domain` literal-only); runbook and the `ios-ci` delta updated to admit the one forced exception
- [ ] 8.5 **Verify on a Mac:** a full `xcodebuild` archive resolves the fragment, and both `Info.plist`s carry the expected host, bundle id, team id and APNs environment

## 9. Metadata

- [x] 9.1 Turn `metadata/app-info/en-US.json` and `metadata/version/current/en-US.json` into templates carrying a domain placeholder for `privacyPolicyUrl`, `marketingUrl` and the support URL
- [x] 9.2 Point `.github/scripts/asc_metadata_apply.sh` at the rendered output, and add the resolver step to the workflow that runs it

## 10. Guards

- [x] 10.1 Reduce `EventLinkDomainTest` to a staleness check: each generated artifact matches the deployment it derives from, still failing loudly rather than vacuously
- [x] 10.2 Update `RuntimeIdentityTest`'s `TEAM_ID` read to the generated fragment
- [x] 10.3 Check the remaining guards for reads of files this change moves or empties — only `EventLinkDomainTest` and `RuntimeIdentityTest` read `Config.xcconfig`/`gradle.properties`; no other guard is affected (verified by grep over `test/architecture/src/`)

## 11. Close-out

- [x] 11.1 Run `./gradlew build` and `./gradlew architectureDiagrams`, committing any diagram churn
- [x] 11.2 Run `deno fmt --check`, `deno lint`, `deno task check`, `deno task test` from a clean clone to confirm the resolver chain works with nothing committed
- [ ] 11.3 Correct `crash-reporting`'s Purpose sentence ("the DSN exists only as a CI secret baked into Release archives") and `apns-push-sender`'s "source constants" sentence at sync time
- [ ] 11.4 Tell the `record-uploads-in-database` workspace that the probe now lands inside this change, and that `BUNNY_DATABASE_URL` should be a deployment value rather than an unreadable bunny secret
