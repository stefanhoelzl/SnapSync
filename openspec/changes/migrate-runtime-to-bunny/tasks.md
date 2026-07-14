> **Ordering is load-bearing.** Group 4 (verification) gates group 6 (cutover), and group 7 (teardown)
> MUST trail group 6 — deleting the Deno Deploy app before the `CNAME` moves takes production down.
> Groups 2–5 all run while bunny is **dark** (0 requests, production served by Deno Deploy), so
> nothing in them can break anything.

## 1. Config: source constants, two secrets, delete the dead one

- [x] 1.1 `backend/src/config.ts`: introduce source constants for the seven non-secret values —
      `ZONE=snap-sync-dev`, `HOST=storage.bunnycdn.com`, `S3_REGION=de`,
      `S3_HOST=de-s3.storage.bunnycdn.com`, `APNS_KEY_ID`, `APNS_TEAM_ID=E9Z8BADH58`,
      `APNS_TOPIC=app.snapsync`. **Source wins**: `readConfig` must not consult the environment for any
      of them.
      **The zone is `snap-sync-dev`, NOT `snap-sync`** — the latter does not exist (the account has only
      `snap-sync-dev`, 96 files). The dead Edge Script, the June provisioning notes, and the README all
      said `snap-sync`; only the live Deno Deploy env had the truth. Hardcoding the documented value
      would have repointed production at an empty bucket.
- [x] 1.2 `backend/src/config.ts`: reduce the env read to exactly two secrets —
      `BUNNY_STORAGE_ACCESS_KEY` and `APNS_PRIVATE_KEY` — still validated once at startup, still
      throwing (naming the missing var) on absent/blank. Keep the "do not trim the PEM" note.
- [x] 1.3 `backend/src/config.ts`: delete `PUBLIC_BASE_URL` — the `ENV_PUBLIC_BASE_URL` constant, the
      `baseUrl` field, the required-var check, and the trailing-slash normalization. Nothing reads
      `config.baseUrl` (verify with a grep over `backend/src`). Also drop the now-dead `baseUrl` from the
      `CONFIG` fixtures in `test/app.test.ts` and `test/apns.test.ts`.
- [x] 1.4 `backend/test/config.test.ts`: drop the four `PUBLIC_BASE_URL` tests; drop the per-var
      missing-value tests for the seven now-constant values; keep/add missing-and-blank fail-closed
      tests for the two secrets; add a test that the seven constants are **not** overridable by env
      (pinned with the real stale value, `BUNNY_STORAGE_ZONE=snap-sync`).
- [x] 1.5 `backend/src/app.ts`: listing routes send `Cache-Control: no-store, no-cache, max-age=0` (both
      the per-device list and the event union). Update the corresponding assertions in
      `backend/test/app.test.ts`.
- [x] 1.6 `backend/src/main.ts`: collapse the `if ("Bunny" in globalThis)` branch to an unconditional
      `BunnySDK.net.http.serve(app.fetch)`; rewrite the header comment (local `deno run` now binds
      `127.0.0.1:8080` via the SDK, which is what the README already documents).
- [x] 1.7 `deno fmt` / `deno lint` / `deno check src/*.ts` / `deno test` all green in `backend/`.
      (101 tests pass; `deno bundle` → 104 KB, far inside the 10 MB script limit.)

## 2. Repair the dark Edge Script (script 79725) — operator, via proton-env

- [x] 2.1 Set the `APNS_PRIVATE_KEY` **secret** (the `.p8` PEM for key `W34NF6UMVU`) on Edge Script
      79725 (`POST /compute/script/79725/secrets`, account key). Without it the script will not boot.
- [x] 2.2 **Overwrite** the `BUNNY_STORAGE_ACCESS_KEY` secret with `snap-sync-dev`'s zone password (the
      value in Proton Pass at `bunny/storage-access-key`, which is what Deno Deploy serves production
      with). Do **not** trust the existing secret: the `AccessKey` is the *zone password*, and the
      script was configured for `snap-sync` — a zone that does not exist — so whatever is stored there
      authenticates against nothing.
- [x] 2.3 Delete the two now-inert variables — `BUNNY_STORAGE_ZONE` (id 20657, value `snap-sync`) and
      `BUNNY_STORAGE_HOST` (id 20658) — so the dashboard stops being a place configuration can live.
      The `snap-sync` value is actively wrong; under source-wins it is inert, but leaving it invites a
      future reader to "restore" it.
- [x] 2.4 From the branch: `deno bundle src/main.ts -o dist/main.js`, then deploy it by hand with the
      script-scoped deploy key (`POST /compute/script/79725/code`, then `POST /compute/script/79725/publish`).
      This is safe: the script is dark (`MonthlyRequestCount: 0`, production is on Deno Deploy).

## 3. Prove the script boots — curl gates, no device needed

- [x] 3.1 `GET https://snap-sync-n8xmz.bunny.run/events/<random-uuid>` returns **404** (the marker gate
      ran), **not** the bodyless 400 that means the config parse threw.
- [x] 3.2 `GET /files/devices/<random-uuid>` returns `200 []`, `cdn-cache: MISS` on **both** of two
      successive requests — **the pull zone does not cache listings**. Note: the edge **collapses** our
      three-directive header and the device sees `cache-control: no-cache` — which is exactly the
      directive bunny documents as cache-suppressing, and exactly the one this change added. Sending only
      `no-store` (the old header) would have rested on a directive bunny never documents.
- [x] 3.3 `OPTIONS /files/devices/<uuid>/x.jpg` — **RESOLVED: the script's own `204` is what the device
      sees.** Response: `HTTP/2 204`, `allow: PUT, OPTIONS`, `cdn-requestpullcode: 204` — the CDN forwarded
      to origin and relayed it. The 2026-06-26 "the pull zone answers/caches OPTIONS itself (generic 200),
      shadowing the script's handler" finding is **stale**; bunny changed that behavior. The "#1 device
      check" is closed by curl, no device needed.
- [x] 3.4 `POST /events` → `201` and `GET /events/<id>` → `200` (read-after-write on the main region);
      the event union → `200 []`. This is the first **write**, so it proves the `AccessKey` overwritten in
      2.2 authenticates against `snap-sync-dev` — the old one was a password for a zone that never existed.
      Stray probe marker to clean up in 4.5: event `0c2b7d1d-4577-41af-af60-3b2572973116`.

## 4. Prove the iOS-facing surface on a real device — the four gates

Dispatch `ios.yml` with `upload_host=https://snap-sync-n8xmz.bunny.run` (HTTPS, so ATS is satisfied and
the pull zone is exercised), sideload the dev IPA. **Use a fresh event id** — otherwise the rejoin
reconcile seeds already-stored photos as `COMPLETED` and nothing uploads.

- [x] 4.1 **Gate A — PASSED.** A 1.6 MB HEIC uploaded through the PhotoKit extension and landed in
      `snap-sync-dev`. The extension log shows `completed key=…-primary.heic attempt=0` — **the OS uploader
      accepted the endpoint's `201` on the first try and never retried**, which closes the frozen
      "accepted success codes" assumption with evidence. The presigned S3 URL fetches the bytes back
      (`206` from `de-s3.storage.bunnycdn.com`), so downloads bypass the runtime as specified.
- [x] 4.2 **Gate B — PASSED.** A **4.5 MB paired video** (`video/quicktime`) streamed through the CDN and
      the Edge Script into storage, `attempt=0`. A Live Photo's video is always ~2–3 s, so this is
      essentially the worst case for the resource type — the 60 s pull-zone timeout is not a threat.
      **Server-side resumable uploads stay deferred.**
- [x] 4.3 **Gate C — PASSED (the silent-failure risk is retired).** `POST /notify` → `202` at 10:26:00,
      and the device's own log shows `[onSilentPush] → onSilentPush(eventId=0c2b7d1d…)` **in the same
      second**, driving a full download reconcile. bunny's edge `fetch` **does** ALPN-negotiate HTTP/2 to
      `api.sandbox.push.apple.com`. The `202` alone would have proved nothing; the device waking proves it.
- [x] 4.4 **Gate D — PASSED.** Relaunched with `SNAPSYNC_FORCE_URLSESSION_UPLOAD=1` (log confirms
      `url-session.onForeground` / `pump.onForeground`, i.e. the app-driven producer). A second asset
      (2.9 MB HEIC + 4.0 MB paired video) uploaded via the background `URLSession` pump:
      `url-session.runCycle = COMPLETED`, notify → `202`. **Both tiers work against bunny** — it was not
      an assumption after all.
- [x] 4.5 Cleaned up — **89 objects deleted, 0 failed**, via a plan-then-execute sweep against the
      storage API (NOT the leave cascade: our device holds active manifests in two other events, so the
      cascade's GC would have spared the bytes anyway). Removed: this session's test event, 29 dead event
      trees (20 of which had **no `metadata.json` at all** — by the contract's own rule, events that do
      not exist), the 4 test-asset objects, and 55 synthetic `SNAPSYNC_SEED_PHOTOS` jpegs. Kept: the 5
      member-bearing events, the 2 older real Live Photos (5.8 MB), and every other device's partition.
      Zone went from ~35 events / 19 MB to **5 events / 5.8 MB**.

## 5. Retire Deno Deploy in the repo (the PR)

- [x] 5.1 `.github/workflows/backend-deploy.yml`: delete the "Configure Deno Deploy env" and "Deploy to
      Deno Deploy" steps and the `DENO_DEPLOY_TOKEN` env; rewrite the header comment (one runtime, one
      deploy).
- [x] 5.2 `backend/deno.json`: remove the `deploy:` block.
- [x] 5.3 `.proton.yaml`: remove `DENO_DEPLOY_TOKEN`.
- [x] 5.4 `backend/README.md`: rewrite the title, the runtime story, the config table (7 constants /
      2 secrets / `PUBLIC_BASE_URL` gone), the local-run command, the Deploy section, and the "On-device
      caveats (unverified)" section — the caveats are resolved by group 4.
- [x] 5.5 `iosApp/Configuration/Config.xcconfig`: update the comment only (`CNAME`'d to bunny, not "Deno
      Deploy today"). **The literal does not change** — no iOS build is required.
- [x] 5.6 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` and `./gradlew build` green.

## 6. Cutover

> **Zero downtime was dropped as a goal** (see design Decision 4). Bunny's free cert is HTTP-01, so it
> cannot be issued until DNS already points at bunny — there is a short gap where HTTPS fails. Accepted:
> uploads retry forever (nothing is lost, only delayed); only a **join** attempted inside the gap is a
> visible failure. So the order is flip → cert, back-to-back.

- [x] 6.1 **Certificate pre-provisioned via DNS-01 — the flip WAS zero-downtime after all.** Attached the
      hostname (`POST /pullzone/6048703/addHostname` → `204`), then issued the cert *before* moving DNS:
      `POST /pullzone/requestExternalDnsCertificate` → `POST /pullzone/completeExternalDnsCertificate`.
      **These routes are account-level, NOT under `/pullzone/{id}/`** — bunny's own seamless-migration
      guide prints a buggy curl example with `{id}` in the path, which 404s. The undocumented `200` body
      is `{TxtRecordName, TxtRecordValue, ExpiresAt}`.
      Verified before flipping by forcing the connection to bunny's IP with the real SNI:
      `CN=snapsync.stho.net`, Let's Encrypt, valid to 2026-10-12, accepted by curl against the public
      trust store (so ATS accepts it), and the Edge Script answered `404` through the CDN.
      **Note:** publishing the `_acme-challenge` TXT required deleting Deno's ACME delegation CNAME
      (`_acme-challenge.snapsync → 48101e8fc7ec49d216fe2aeb6329fb5d._acme.deno.net`, ttl 300) — DNS
      forbids a CNAME coexisting with another type at the same name. Deno's **existing** cert stays valid
      (~90 days), so DNS rollback still serves HTTPS; it just can no longer auto-renew. Restore that
      record if Deno is ever reinstated long-term.
- [ ] 6.2 Merge the PR. CI deploys the bundle to bunny (the same bundle already hand-deployed in 2.4, so
      this is a no-op in effect). The Deno Deploy **app** keeps serving its last bundle — still a working
      DNS-repoint rollback.
- [x] 6.3 **FLIPPED.** `snapsync.stho.net` CNAME: `alias.deno.net` → `snap-sync-n8xmz.b-cdn.net`.
      Propagated in ~4 min (300 s TTL). **No TLS gap** — the cert was already in place. Live hostname now
      answers `server: BunnyCDN-DE1-1330` (was `deployd`) with a valid LE cert.
- [x] 6.4a **Contract verified on the production hostname** (`curl`, no residue): `GET /events/<uuid>` →
      `404`; `OPTIONS` → **`204`** (the script's own handler, not a CDN generic `200`);
      `GET /files/devices/<uuid>` → `200 []` with `cache-control: no-cache`, `cdn-cache: MISS`. The 5
      surviving real events are **intact and readable through the new runtime** (event `'test'` still
      returns its 1 asset) — data continuity across the cutover, not just a live endpoint.
- [ ] 6.4b **Device check on the production hostname — PENDING.** The sideloaded dev IPA bakes
      `snap-sync-n8xmz.bunny.run`, i.e. the *same pull zone and the same Edge Script*, just a different
      hostname; all four gates passed against it. What is still unproven on hardware is that exact
      literal `https://snapsync.stho.net`. That comes from the TestFlight build produced by merging this
      PR. **Do not run group 7 (teardown) until this is green** — it is the last thing standing between
      us and an unrecoverable bunny.

## 7. Teardown — only after group 6 is verified

- [ ] 7.1 Delete the Deno Deploy `snapsync` app (org `stefanhoelzl`). **Not before 6.4** — the app is
      the rollback target until the flip is confirmed good.
- [ ] 7.2 Delete the `DENO_DEPLOY_TOKEN` GitHub Actions secret.
- [ ] 7.3 Record in the change that bunny is now load-bearing with no fallback and no boot probe: a
      bunny outage is a SnapSync outage, mitigated only by the ledger's retry-forever semantics
      (uploads delayed, never lost).

## 8. At archive time (`/opsx:archive` — NOT in the PR)

`openspec archive` merges the requirement deltas into the main specs. These are the edits it cannot
make, because they are prose or file removals rather than requirement operations. Doing them by hand
inside the PR would collide with the archive merge — and would also make the specs lie during the soak,
since `backend-deployment` correctly says Deno Deploy is the active runtime right up until task 6.3
flips the `CNAME`.

- [ ] 8.1 Rewrite `openspec/specs/backend-deployment/spec.md`'s `## Purpose` prose: one runtime; the
      **no-scoped-keys ⇒ CI-can't-write-config ⇒ config-in-source** causal chain (without it a future
      reader deletes the account-key requirement as paranoia and silently re-opens the drift channel);
      `Decision record:` gains `changes/archive/<id>-migrate-runtime-to-bunny`.
- [ ] 8.2 Delete the non-normative `## Assumptions (unverified on device)` section from
      `openspec/specs/bunny-upload-endpoint/spec.md` — group 4 turned those assumptions into facts.
- [ ] 8.3 Delete `openspec/specs/backend-config/` (folded into `backend-deployment`).
