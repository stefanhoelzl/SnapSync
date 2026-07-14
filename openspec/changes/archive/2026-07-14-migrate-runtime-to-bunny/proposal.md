## Why

The bunny Edge Script — the "insurance" runtime that `backend-deployment` claims we deploy the same
bundle to on every `main` push — **has been dead since 2026-07-02**. Every path returns a bodyless
`400`; the Hono app never runs. Its config holds three of the ten variables `readConfig` requires, so
the parse throws and the script fails closed at boot. `MonthlyRequestCount: 0` — it has never served a
request. CI has reported green throughout, because `POST /code` + `POST /publish` succeed whether or
not the script can boot.

The cause is a config-drift channel we cannot close by discipline: bunny has **no scoped API keys**
(setting an Edge Script's variables requires the full-access account key, which also owns the storage
zone and our DNS), so `backend-deployment` rightly forbids CI from holding one — which means CI ships
code but cannot ship config, so config lives in a dashboard and rots. On 2026-07-02
`add-s3-presigned-downloads` added two required vars, set them on Deno Deploy only, and simultaneously
orphaned a third (`PUBLIC_BASE_URL`) by deleting its only consumer. Nothing detected any of it.

bunny has since fixed the zero-window upload SYN drop that benched it. So we repair the runtime, close
the drift channel at its root, promote bunny to the sole device-facing runtime, and retire Deno Deploy.

## What Changes

- **Non-secret configuration moves into source.** The seven non-secret values (`BUNNY_STORAGE_ZONE`,
  `BUNNY_STORAGE_HOST`, `BUNNY_S3_REGION`, `BUNNY_S3_HOST`, `APNS_KEY_ID`, `APNS_TEAM_ID`,
  `APNS_TOPIC`) become source constants — they are public facts, several already committed. **Source
  wins**: the environment is not consulted for them, so a stale platform variable cannot override git.
- **`PUBLIC_BASE_URL` is deleted.** `app.ts` never reads `config.baseUrl`; its only consumer, the
  download proxy, was retired when presigned S3 downloads landed. A required variable with no
  consumers.
- **Exactly two secrets remain in the environment** — `BUNNY_STORAGE_ACCESS_KEY` and
  `APNS_PRIVATE_KEY` — still validated once at startup, still fail-closed. Ten required vars become
  two, and a new non-secret config value can no longer ship without its value.
- **bunny Edge Scripting becomes the sole device-facing runtime.** `snapsync.stho.net` is repointed
  from Deno Deploy to bunny pull zone `snap-sync-n8xmz` (id `6048703`), with the TLS certificate
  pre-provisioned via bunny's DNS-01 flow so the cutover has no window without a valid cert.
- **BREAKING (operationally): Deno Deploy is retired entirely** — the deploy steps, the env-config
  step, `DENO_DEPLOY_TOKEN` (GitHub secret and `.proton.yaml`), the `deploy:` block in
  `backend/deno.json`, the runtime branch in `main.ts`, and **the Deno Deploy app itself**. After this
  change bunny is load-bearing with no fallback runtime.
- **Listing responses harden their cache header** to `Cache-Control: no-store, no-cache, max-age=0`.
  bunny documents `no-cache` as the directive that suppresses pull-zone caching and never mentions
  `no-store`, which is all the listing routes currently send. A CDN now sits in front of the origin;
  Deno Deploy had none.
- **No iOS build is required.** The baked host is `snapsync.stho.net`, a domain we own — the whole
  point of `add-custom-domain`. Installed devices follow DNS.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `backend-deployment`: near-total rewrite. Drops "deploys to both runtimes" and "Deno Deploy is the
  active device-facing runtime"; bunny Edge Scripting becomes *the* runtime. Keeps the custom-domain
  and no-rebuild requirements as standing properties. Keeps — and now explains — the
  script-scoped-credentials requirement, which is causally why config must be git-owned. **Absorbs
  `backend-config`.**
- `backend-config`: **removed**, folded into `backend-deployment`. Its `PUBLIC_BASE_URL` requirement is
  deleted outright (dead value); its fail-closed requirements survive, rewritten around the
  source-constants / two-secrets model. Config is now a property of how the backend is deployed, not a
  capability of its own.
- `bunny-upload-endpoint`: retires the non-normative `## Assumptions (unverified on device)` section.
  Those assumptions exist only because the iOS-facing surface could never be exercised against bunny
  while the SYN drop stood; this change exercises them on a real device.
- `bunny-list-endpoint`: the two `Cache-Control: no-store` requirements become
  `no-store, no-cache, max-age=0`.

## Impact

**Code** — `backend/src/config.ts` (source constants; two env secrets; `PUBLIC_BASE_URL` and
`baseUrl` deleted), `backend/src/main.ts` (runtime branch collapses to `BunnySDK.net.http.serve`),
`backend/src/app.ts` (listing cache header), `backend/test/config.test.ts`,
`backend/deno.json` (`deploy:` block).

**CI / secrets** — `.github/workflows/backend-deploy.yml` (Deno Deploy steps and env-config step
removed), `.proton.yaml` (`DENO_DEPLOY_TOKEN`), the `DENO_DEPLOY_TOKEN` GitHub secret.

**Docs** — `backend/README.md` (title, runtime story, config table, local-run command, deploy
section, on-device caveats), `iosApp/Configuration/Config.xcconfig` (comment only — the literal is
unchanged).

**Infrastructure (operator, out-of-repo)** — Edge Script `79725`: add the `APNS_PRIVATE_KEY` secret,
delete the two now-inert variables. Pull zone `6048703`: add the `snapsync.stho.net` hostname and
pre-provision its certificate. Bunny DNS `stho.net`: repoint the `snapsync` CNAME. Deno Deploy: tear
down the `snapsync` app.

**Risk accepted** — bunny becomes load-bearing with no fallback runtime and no deploy-time boot probe.
A bunny outage is a SnapSync outage. The mitigating fact is that the ledger retries forever, so uploads
are delayed, never lost. Prevention (config that cannot drift) replaces detection.
