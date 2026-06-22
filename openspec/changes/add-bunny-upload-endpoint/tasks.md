> **Status (2026-06-22):** endpoint, tests, deploy workflow, design.md pivot — implemented and green
> (`deno fmt`/`lint`/`check`/`test`, 22 tests). Bunny **provisioned** (zone `snap-sync`, script
> `79725` at `https://snap-sync-n8xmz.bunny.run`), env + GH secrets set. Only the **live deploy/smoke**
> (§6) remains.

## 1. Bunny provisioning (via `BUNNY_API_KEY` over `proton-env`)

- [x] 1.1 Resolved: the Bunny account key is injected as **`BUNNY_API_KEY`** via `proton-env`
      (`.proton.yaml` maps `BUNNY_API_KEY: bunny/api-key`), same pattern as the Apple creds.
- [x] 1.2 Storage zone **`snap-sync`** (DE, host `storage.bunnycdn.com`) created; zone password held
      in Proton (`bunny/storage-access-key`).
- [x] 1.3 Edge Scripting app created: **script id `79725`**, standalone (not GitHub-integrated),
      endpoint `https://snap-sync-n8xmz.bunny.run`; deploy key in Proton (`bunny/script-deployment-key`).
- [x] 1.4 Script env set via API: `BUNNY_STORAGE_ZONE`/`BUNNY_STORAGE_HOST` as variables
      (`PUT /compute/script/79725/variables`), `BUNNY_STORAGE_ACCESS_KEY` as a secret
      (`POST /compute/script/79725/secrets`).

## 2. `backend/` Deno project (`bunny-upload-endpoint`)

- [x] 2.1 Scaffold a top-level `backend/` Deno project: `deno.json`, `@bunny.net/edgescript-sdk` +
      **Hono** (`src/app.ts` builds the Hono app; `src/main.ts` serves `app.fetch`). `README.md`
      documents local dev (Deno + mocked bunny) and the env-var contract.
- [x] 2.2 Implement `PUT /event/<eventId>/device/<deviceId>/file/<filename>`: derive the key from
      the URL path (literal `event`/`device`/`file` labels); validate `eventId`/`deviceId` as UUIDs
      and `filename` (non-empty, no `..`); unmatched path → `404`, matched-but-invalid → `400`.
      Compose the **bare** storage key `<eventId>/<deviceId>/<filename>` (labels stripped). Validators
      in `src/validators.ts` (`validateUUID`/`validateFilename`); key composed in the handler.
- [x] 2.3 Implement the **streaming pass-through**: pipe `request.body` straight into a single bunny
      native `PUT` (`https://<host>/<zone>/<key>`, `AccessKey` header, forwarded/defaulted
      `Content-Type`). Never `await request.bytes()`; never hash/transform. (`src/app.ts`)
- [x] 2.4 Implement **outcome propagation**: `2xx` only on bunny-confirmed store; upstream
      error/timeout/abort/partial → `5xx`; never a false success.
- [x] 2.5 Implement **method/OPTIONS**: other methods / unmatched path → `404` (Hono default);
      `OPTIONS` answered (`204`, no
      resumable advertised) so the iOS uploader falls back to a plain non-resumable PUT.
- [x] 2.6 Implement **fail-closed config**: `readConfig(env)` (`src/config.ts`) **throws** on a
      missing/blank var; called once at startup in `src/main.ts` → a misconfigured deploy fails to
      boot. The validated `Config` is injected into `createApp`.

## 3. Tests (`Deno.test`, mocked bunny) — 21 tests, green

- [x] 3.1 Validation: `validateUUID`/`validateFilename` units (`test/validators.test.ts`) +
      end-to-end in `test/app.test.ts` — non-UUID / unsafe filename (`..`, `/`, `%2F`) → `400`;
      path-shape 404s are Hono's.
- [x] 3.2 Storage-key composition: a labeled URL path maps to the bare
      `<eventId>/<deviceId>/<filename>` upstream key (labels stripped, filename passed through).
- [x] 3.3 Streaming pass-through: forwarded upstream URL, `AccessKey`, body byte-identical;
      `Content-Type` forwarded and defaulted; percent-encoded filename verbatim. (`test/app.test.ts`)
- [x] 3.4 Last-write-wins: exactly one `PUT`, no `HEAD`/`GET` precedes it.
- [x] 3.5 Outcome mapping: bunny success → `2xx`; bunny error/throw → `5xx` (asserted never `2xx`).
- [x] 3.6 Method/OPTIONS: `GET` → `404` (Hono default); `OPTIONS` → `204`, does not advertise
      resumable. Plus: percent-encoded filename forwarded verbatim; empty filename → `404`.
- [x] 3.7 Config: `readConfig` returns a `Config` for complete env and **throws** (naming the var)
      on a missing/blank one. (`test/config.test.ts`)

## 4. Deploy workflow (`backend-deployment`)

- [x] 4.1 Add `.github/workflows/backend-deploy.yml`: trigger path-scoped to `backend/**` + the
      workflow file; isolated from the Gradle/iOS workflows.
- [x] 4.2 Run `deno fmt --check` / `deno lint` / `deno check src/main.ts` / `deno test`; **gate** the
      deploy step on all passing (`deno check` covers `main.ts`/SDK wiring that `deno test` misses).
- [x] 4.3 Deploy via `BunnyWay/actions/deploy-script` (`script_id`, `deploy_key`,
      `file: backend/src/main.ts`); idempotent (same script overwritten); deploy step `main`-only.
- [x] 4.4 GH secrets set on `stefanhoelzl/SnapSync`: `BUNNY_SCRIPT_ID=79725` and `BUNNY_DEPLOY_KEY`
      (piped from Proton `bunny/script-deployment-key` via `proton-env`). Storage `AccessKey` is a
      script env secret, not a workflow secret; the account API key is not used by CI.

## 5. docs/design.md pivot (mint → proxy)

- [x] 5.1 Rewrote the storage/auth narrative end-to-end: §scope (byte-blindness **reversed**),
      §2 overview, §2.1 module graph, §2.2 (provider → local URL builder), §3.1 (key
      `<eventId>/<deviceId>/<filename>`, no `events/` prefix; native API, no custom metadata), §3.3
      (proxy flow; TOP RISK retired; no presign/expiry), §3.4/§3.5, §4 (native Storage API +
      `AccessKey`), §6 testing, §7 libraries (+ a Backend row).
- [x] 5.2 Relocated the proxy's unverified iOS-facing unknowns into §8 (OPTIONS fallback; accepted
      `2xx`; largest paired-video within the 30 s CPU budget + undocumented wall-clock timeout);
      recorded ack-path key recovery as **resolved** (key parsed from `job.destination.URL.path`);
      noted **resumable uploads** as the deferred large-payload fix; retired the `UNSIGNED-PAYLOAD`
      TOP RISK.

## 6. Verify & deploy

- [x] 6.1 `deno test` green locally (21 tests, against mocked bunny) + `deno fmt`/`lint` clean.
- [ ] 6.2 First deploy to bunny Edge Scripting (via the workflow or a manual API deploy); smoke-test
      a real `PUT /event/<uuid>/device/<uuid>/file/<name>` with curl → object lands at
      `<uuid>/<uuid>/<name>` in the new zone (live check or the bunny Storage browser). *(blocked on §1)*
- [ ] 6.3 Confirm a malformed key → `400` against the live endpoint, and that a deploy with missing
      env **fails to boot** (fail-closed). *(blocked on §1)*
