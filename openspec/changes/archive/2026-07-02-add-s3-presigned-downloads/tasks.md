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
- [x] 4.1 (operator) Set `BUNNY_STORAGE_ZONE = snap-sync-dev` in the live runtime env and the S3 vars in
  the dashboard (confirmed by the operator). `BUNNY_STORAGE_ACCESS_KEY` reused — no new secret. Verified
  live: the deployed backend returns presigned `de-s3` URLs (§5.2).
- [x] 4.2 `PUBLIC_BASE_URL` unchanged (`https://snapsync.stho.net`) — serves upload/event/list traffic
  and no longer appears in any download URL.

## 5. Verify on device (the straight-cutover proof)

- [x] 5.0 Backend deployed to Deno Deploy on merge to `main` (run 28621425974, success); `snapsync.stho.net`
  now serves presigned downloads against the `snap-sync-dev` S3 zone.
- [x] 5.2 **Verified end-to-end against the live backend + real zone** (curl): create event → native byte
  upload (`201`) → manifest (`201`) → union returns `url =
  https://de-s3.storage.bunnycdn.com/snap-sync-dev/…?X-Amz-Expires=604800&…X-Amz-Signature=…` with
  `Cache-Control: no-store` → **fetching that URL directly from bunny S3 (no auth) returns the exact bytes
  (`200`, 26 B)**. Per-device list likewise presigned + `no-store`. This GET is byte-identical to the
  device's `NSURLSession` download request. SE2 confirmed installed and pointed at `snapsync.stho.net`.
- [x] 5.1 **Literal on-device cycle verified on the SE2.** Seeded a fresh event with a real 64×64 JPEG
  foreign asset (`S3DLTEST`), provisioned the SE2 via `SNAPSYNC_DEEPLINK`, and observed in `debug.log`
  (~3 s): `reconcile: 1 union asset(s), 1 foreign planned` → `imported foreign asset S3DLTEST as
  F28DDACC-…_L0_001` with the exact seeded `creationDate`. `NSURLSession` fetched the presigned
  `de-s3.storage.bunnycdn.com` URL and PhotoKit imported it. The SE2 wrote no manifest of its own (no
  library upload in the window). Seeded zone objects cleaned up; the 64×64 test photo remains in the
  SE2 Photos library (deletable by hand).

## 6. Docs + spec prose sync (applied at archive)

- [x] 6.1 `docs/design.md` §3.5 / §4 updated: downloads are presigned S3 (minted by the list capability,
  7-day expiry, fetched directly from bunny's S3 endpoint); uploads, listings, and the event registry
  stay native; the `snap-sync-dev` S3-enabled zone noted, S3 presign-only.
- [x] 6.2 Spec deltas applied to `openspec/specs/` at archive: **retired** `bunny-download-endpoint`;
  modified `bunny-list-endpoint` (+ presigned-URL authority), `photo-download` (direct-S3 + short-read +
  self-heal), `download-store` (url refresh), `backend-config`, `backend-deployment`.

## 7. Archive

- [x] 7.1 Archived after PR #65 merged and §5 proven on device; deltas applied so `openspec/specs/`
  describes the presigned-download contract and no longer carries `bunny-download-endpoint`.
