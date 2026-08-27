## 1. The deployment key and the shared component (inert on its own)

- [x] 1.1 Add a `maintenance` key to `scripts/resolve-deployment.py`'s inventory: `scope="build"`,
      `[JSON]` rendering only, `default=False`, with the rationale from design D3 (CI cannot write the
      script's environment, so the flag must ship in the artifact).
- [x] 1.2 Move `deployments/prod.json`'s own keys — `domain` and its four environment references — into a
      new `deployments/components/prod-core.json`, leaving `prod.json` as an `extends` list only.
- [x] 1.3 Add `deployments/maintenance.json` extending the same five components as `prod.json`, adding
      `"maintenance": true` and nothing else.
- [x] 1.4 Extend `scripts/resolve_deployment_test.py`: the key's rendering set and default; `prod` and
      `maintenance` resolve identically except the flag; a deployment naming another deployment in
      `extends` fails.
- [x] 1.5 Surface the resolved value in `api/src/config.ts` (`Config.maintenance`, read from `DEPLOYMENT`
      like every other non-secret). Nothing reads it yet — `./gradlew`-free, `deno task check` green.

## 2. `/health` and the probe (one contract, land together)

- [x] 2.1 Rewrite `/health` in `api/src/app.ts`: report `{sha, maintenance?}`; verify the relational store
      and the storage zone are reachable; answer a bare non-success status when either is not. Remove the
      `foreignKeysEnabled()` assertion and the `database` state string.
- [x] 2.2 Remove `foreignKeysEnabled` from the `Db` port (`api/src/db.ts`) and both drivers if it has no
      remaining caller; keep it if the sweep or a test still reads it, and say which.
- [x] 2.3 Update `api/src/scripts/probe.ts`: drop the `foreign-keys-off` and `store-unreachable` cells,
      add an expected-maintenance parameter, and add a retryable `wrong-maintenance-state` cause. Keep
      `server-error` retryable — it now carries unreachability.
- [x] 2.4 Update `api/test/scripts/probe.test.ts` for the new cause table: right bundle + wrong state
      retries; right bundle + right state passes; the removed causes are gone.
- [x] 2.5 Update `api/test/app.test.ts` for the new `/health`: shape with and without the flag, non-success
      on an unreachable store, non-success on unreachable storage, no-cache headers, `HEAD` behaviour,
      and that a mutating method is not served.

## 3. The maintenance gate (flag defaults off, so `main` is unchanged)

- [x] 3.1 Add `MAINTENANCE_RETRY_AFTER_SECONDS = 120` as a documented constant in `api/src/app.ts`, with
      the derivation (probe + migrate + publish + probe) and a note that it is an estimate, not a
      measurement.
- [x] 3.2 Register the maintenance middleware in `createApp` **before** the attestation gate: match the
      `/api/` prefix, answer `503` with `Retry-After` and `NO_CACHE`, short-circuit before any handler.
- [x] 3.3 Add tests in `api/test/app.test.ts`: a `/api/v1` route returns `503` under the flag; a
      hypothetical `/api/v2` path returns `503` too (the prefix, not a list); `/`, `/join`, `/_astro/*`,
      the AASA and `/health` all still serve; no storage or database request is made for a gated request.
- [x] 3.4 Add a test in `api/test/attest.test.ts` pinning the ordering: an `/api/` request with no bearer
      token returns `503` under the flag, and `401` without it.

## 4. The pipeline

- [x] 4.1 Add a `--pending` mode to `api/src/scripts/migrate.ts`: compare `appliedVersions(db)` against
      `MIGRATIONS`, print a greppable line, and exit `0` (none), `10` (pending), or non-zero (failed).
      Do not apply anything in this mode.
- [x] 4.2 Add `api/test/migrations.test.ts` coverage for the plan comparison — none pending on a migrated
      store, pending on a store behind the list.
- [x] 4.3 In `.github/workflows/api-deploy.yml`, run the pending check before anything publishes and
      branch on its exit code, treating any unrecognised code as fatal.
- [x] 4.4 Add the no-migration path: unchanged from today — bundle, verify the stamp, publish, probe.
- [x] 4.5 Add the migrating path: capture the live commit from `/health`; resolve `maintenance` and bundle;
      publish; probe expecting the window open; migrate; resolve `prod` and bundle; publish; probe
      expecting the window closed.
- [x] 4.6 Upload `dist/main.js` as artifact `bundle-<sha>` after the final probe passes.
- [x] 4.7 Add the `if: failure()` rollback step: download `bundle-<captured-sha>` and republish it; when the
      artifact is absent or expired, fail naming that commit and what a human must do.
- [x] 4.8 Update the workflow's header comment — the deploy no longer has "no rollback available", and the
      pending check's exit-code contract must be readable where it is branched on.

## 5. Verify against the deployed system

- [ ] 5.1 Verify through the **pull zone**, not the origin, that a `503` carrying `NO_CACHE` is not served
      after the window closes. This is the one assumption whose failure turns a bounded outage into an
      unbounded one, and nothing in CI covers it.
- [ ] 5.2 Run one real migrating deploy end to end and record the observed window duration. Worth knowing
      in its own right; `Retry-After` no longer depends on it, being a poll interval every 503 re-issues.
- [ ] 5.3 Confirm the rollback path by forcing a migration failure on a branch-scoped dry run, or record
      explicitly that it was not exercised and why.

## 6. Documentation

- [x] 6.1 Update `api/README.md`: the `/health` shape, the maintenance window, the pending check, and the
      bundle archive.
- [x] 6.2 Re-check `openspec/specs/device-attestation`'s closed list against the new gate ordering — the
      maintenance gate runs ahead of it and that list is the authority for what is ungated.
