## 1. Device — refresh not-yet-staged download URLs (lands first; safe no-op with today's URLs)

- [x] 1.1 `domain/download-store/src/commonMain/sqldelight/app/snapsync/downloadstore/db/DownloadStore.sq`:
  change `upsertResource` from `INSERT OR IGNORE` to an upsert that, on primary-key conflict, updates
  `url` **only when the existing `stagedPath IS NULL`** (leave `role`/`contentType`/`originalFilename`
  as-is — they are immutable per `resourceKey` — and never touch a staged row). Keep the insert path
  identical for new rows. (Also updated the in-memory fake `InMemoryDownloadStore.plan` to match.)
- [x] 1.2 Extend the `download-store` contract test
  (`domain/download-store/src/commonTest/.../DownloadStoreContract.kt`, runs JVM + `iosSimulatorArm64`):
  plan a resource with url A; re-plan the same resource with url B while unstaged → `pendingDownloads()`
  reflects url B; a staged resource keeps its staging untouched by re-plan. (Terminal-untouched is
  covered by the existing `plan_never_downgrades_an_imported_asset` test.)
- [x] 1.3 `./gradlew build` green (JVM tests) + `compileIosMainKotlinMetadata` green (iOS proxy).

## 2. Backend — presign the download URL (the one real change)

- [x] 2.1 `backend/deno.json`: add `"aws4fetch": "npm:aws4fetch@^1"` to `imports`.
- [x] 2.2 `backend/src/config.ts`: add `s3Region` (env `BUNNY_S3_REGION`, e.g. `de`) and `s3Host` (env
  `BUNNY_S3_HOST`, e.g. `de-s3.storage.bunnycdn.com`) to `Config` and `readConfig`, fail-closed like the
  rest (throw on missing/blank). Keep `zone`, `host`, `accessKey`, `baseUrl`.
- [x] 2.3 `backend/src/app.ts`: construct one `AwsClient` from config at startup
  (`accessKeyId: config.zone`, `secretAccessKey: config.accessKey`, `region: config.s3Region`,
  `service: "s3"`). Replaced `downloadUrl` with async `presignDownloadUrl(aws, config, deviceId, filename)`
  presigning a GET (`aws.sign(<s3Host>/<zone>/<byteKey>?X-Amz-Expires=604800, {method:"GET",
  aws:{signQuery:true}})` → `.url`). Both list routes now `await` it.
- [x] 2.4 `backend/src/app.ts`: **deleted** the byte-download proxy route `byteFile.get("/")`. Upload
  `PUT` on that path left intact; OPTIONS `Allow` updated to `PUT, OPTIONS`.
- [x] 2.5 `backend/src/app.ts`: added `Cache-Control: no-store` to the per-device list response; the
  union already sets it.
- [x] 2.6 Native paths untouched: byte-upload `PUT`, marker/manifest `GET`/`PUT`, and both `listDir`
  LISTs still hit `https://<config.host>/<config.zone>/…` with the `AccessKey` header (verified by the
  unchanged, passing upload/manifest/list/union tests).

## 3. Backend tests

- [x] 3.1 `backend/test/app.test.ts`: replaced the download-URL assertions with an `assertPresigned`
  helper (right S3 origin+path, `X-Amz-Algorithm=AWS4-HMAC-SHA256`, `X-Amz-Expires=604800`, credential
  scope `/de/s3/aws4_request`, non-empty `X-Amz-Signature`) used by the device-list and union tests;
  asserted the per-device list carries `Cache-Control: no-store`. `config.test.ts` gains the S3 vars +
  a missing-S3-var fail-closed test.
- [x] 3.2 Removed the proxy-GET-route tests and the `getFake` helper (route gone). Kept every upload,
  marker, manifest, and LIST test as-is (native behavior unchanged); updated the OPTIONS `Allow`
  assertion to `PUT, OPTIONS`.
- [x] 3.3 `deno fmt`, `deno lint`, `deno check src/*.ts` clean; `deno test` → **65 passed, 0 failed**.
  `deno bundle` → 92 KB (well under the 1 MB Edge Scripting limit).

## 4. Deploy config

- [x] 4.1 (CI) `.github/workflows/backend-deploy.yml`: the "Configure Deno Deploy env" step now also sets
  the non-secret `BUNNY_S3_REGION = de` and `BUNNY_S3_HOST = de-s3.storage.bunnycdn.com` (idempotent
  add/update), reproducibly, for the Deno Deploy runtime.
- [ ] 4.1 (operator) Set `BUNNY_STORAGE_ZONE = snap-sync-dev` in **both** live runtimes' env (bunny Edge
  Script dashboard + Deno Deploy), and add `BUNNY_S3_REGION`/`BUNNY_S3_HOST` to the **Edge Script**
  dashboard env too (CI only covers Deno Deploy). `BUNNY_STORAGE_ACCESS_KEY` is reused (already the zone
  password = S3 secret) — no new secret. Dashboard values can't be set from the repo.
- [x] 4.2 `PUBLIC_BASE_URL` unchanged (`https://snapsync.stho.net`) — serves upload/event/list traffic
  and no longer appears in any download URL.

## 5. Verify on device (the straight-cutover proof)

- [ ] 5.1 Deploy backend; join a **fresh** event id on the SE2 (per the CLAUDE.md dev loop). Confirm an
  upload lands in the `snap-sync-dev` zone (native path unchanged) by checking the storage zone.
- [ ] 5.2 From a second contributor (or a seeded object), confirm the union `url` is a presigned
  `de-s3.storage.bunnycdn.com` URL and the device downloads + imports the foreign asset directly from S3
  (no backend byte traffic). A presigned link left to expire before its transfer runs re-presigns on the
  next foreground reconcile (self-heal).

## 6. Docs + spec prose sync (applied at archive)

- [ ] 6.1 `docs/design.md` §3.5 / §4: download URLs are presigned S3 (minted by the list capability,
  7-day expiry, fetched directly from bunny's S3 endpoint); uploads, listings, and the event registry
  stay on the native API; note the `snap-sync-dev` S3-enabled zone and that S3 is presign-only.
- [ ] 6.2 Apply the spec deltas at archive: **retire** `bunny-download-endpoint`; modify
  `bunny-list-endpoint`, `photo-download`, `download-store`, `backend-config`, `backend-deployment`.

## 7. Archive

- [ ] 7.1 Archive this change after the PR merges and §5 is proven on device, applying the deltas so
  `openspec/specs/` describes the presigned-download contract and no longer carries the retired
  `bunny-download-endpoint`.
