## Why

`api-deploy.yml` applies schema migrations **before** publishing the bundle written against them. That
ordering is correct — it is what keeps a failed migration from putting new code on an old store — but it
leaves a window: between the migration landing and the new bundle actually serving, **the previous bundle
runs against the new schema**. Its statements are written for a shape that no longer exists, so a request
arriving in that window can fail against a renamed table, or write against a column whose constraints have
moved.

Nothing watches that window today, and nothing bounds it. It is also the one deploy hazard the boot probe
cannot see: the probe runs *after* the publish, by which time the window has already closed on whatever
went through it.

## What Changes

- **A maintenance gate on the device API.** One Hono middleware on the `/api/` prefix — version-agnostic,
  so a future `/api/v2` inherits it by construction — answering `503` while the flag is set. It is
  registered **before** the attestation gate, so maintenance wins over `401`: during the window the service
  is down, not the caller unauthorized.
- **The flag ships in the bundle, not the environment.** A `maintenance` build-scope key in the deployment
  resolver, rendered only into the Deno bundle. CI cannot write Edge Script environment variables (it holds
  only the script-scoped deploy key), so the only lever CI has is *what code is published* — and that is
  exactly what this uses.
- **A three-publish deploy, but only when it buys something.** `migrate.ts --pending` reports whether any
  migration is unapplied. None pending — the overwhelming majority of deploys — and the pipeline is
  unchanged: one publish, no window. Pending, and CI publishes the maintenance bundle, probes that it is
  live, migrates, publishes the real bundle, and probes again.
- **A rollback path, which this pipeline has never had.** Each successful deploy archives its bundle as a
  GitHub Actions artifact keyed by commit. On failure, an always-run step republishes the artifact for the
  commit that was live before the window opened. A missing or expired artifact fails **loudly**, naming the
  commit, rather than silently leaving the API down.
- **`/health` answers what the probe now needs to ask** — the bundle's commit *and* whether it is the
  maintenance bundle, since both bundles carry the same commit and a sha match alone can no longer tell
  them apart.
- **`/health` verifies storage reachability**, closing the gap `backend-deployment` currently states
  outright: a present-but-wrong `BUNNY_STORAGE_ZONE` boots and probes green. That was the other half of the
  2026-07 outage and nothing has watched it since.
- **BREAKING (internal): the `/health` foreign-keys assertion is removed.** `/health` becomes
  `{sha}` — plus `maintenance: true` only while a window is open — and signals an unreachable store or
  storage with a bare non-200 instead of a `200`
  carrying a state string. FK enforcement moves from *asserted on every deploy* to *trusted*. This is a
  deliberate reduction in guarantee, recorded as its own decision in `design.md` rather than absorbed into
  the change around it.

## Capabilities

### New Capabilities

None. Maintenance mode is not a capability of its own — it exists solely to make the deploy pipeline safe,
and its contract is inseparable from that pipeline's. A separate spec would restate `backend-deployment`
and become a second place for the same rules to drift.

### Modified Capabilities

- `backend-deployment`: the deploy pipeline gains a conditional maintenance window (publish → probe →
  migrate → publish → probe), a bundle archive, and a failure-path rollback; `/health`'s response shape and
  the probe's cause table change with it; the residual guarantees are stated.
- `api-endpoints`: every route under `/api/` may now answer `503` during a deploy window — a status no
  device-API route could previously return, and one every client must treat as retryable. `/health`'s
  response body and its non-200 behaviour change.
- `deployment-configuration`: a new build-scope `maintenance` key in the resolver's inventory, and a
  restructure of `deployments/prod.json` so a second deployment can share its values without duplicating
  them.
- `database`: the store's foreign-key enforcement is no longer asserted on every deploy. The spec must say
  that plainly, and name what would falsify the assumption now standing in the assertion's place.
- `device-attestation`: **added during implementation.** Its closed list is the authority for which routes
  need a token, and the maintenance gate is answered *before* it — so an ungated `/attest/*` route can now
  answer `503`. No route's gating changes; the spec says so rather than leaving a reader of the closed list
  to discover the ordering from code. The ordering test lives in that capability's suite.

## Impact

**Code**

- `api/src/app.ts` — the maintenance middleware ahead of the attestation gate; `/health` rewritten.
- `api/src/config.ts`, `api/src/deployment.ts` — the resolved `maintenance` value.
- `api/src/scripts/migrate.ts` — a `--pending` mode with distinct exit codes.
- `api/src/scripts/probe.ts` — a maintenance cell in `classify()`, and an expected-maintenance parameter.
- `api/test/app.test.ts`, `api/test/attest.test.ts`, `api/test/scripts/probe.test.ts` — the gate, the gate's
  ordering against attestation, the new probe cells.

**Configuration**

- `scripts/resolve-deployment.py` — the `maintenance` key in the inventory.
- `deployments/prod.json` split so its own keys live in a shared component; new
  `deployments/maintenance.json`.
- `scripts/resolve_deployment_test.py` — the new key's rendering set and default.

**Pipeline**

- `.github/workflows/api-deploy.yml` — the pending check, the conditional window, the second probe, the
  bundle archive, the failure-path rollback.

**Not affected**

- Downloads. Presigned S3 GET URLs are fetched directly from bunny's S3 endpoint and never reach the
  script, so a maintenance window cannot stop one.
- The browser-facing routes (`/`, `/join`, `/_astro/*`, the AASA). They are root-mounted, read only the
  public storage `site/` prefix, and never touch the database.
- Every shipped client. `SyncEngine` retries forever with no attempt budget; `HttpEventCreation` and
  `HttpEventDirectory` already map an unrecognised status to `Transient`/`Failed`. A `503` costs a retry
  and a transient error state, nothing durable.
