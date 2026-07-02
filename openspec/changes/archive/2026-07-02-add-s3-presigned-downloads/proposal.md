## Why

Today every photo **download** is a byte-stream proxied through the backend: the list/union `url`
field points at `<PUBLIC_BASE_URL>/files/device/<deviceId>/<filename>`, the device fetches that, and
the backend re-fetches the object from bunny with its `AccessKey` and streams the bytes back. All
download bandwidth transits Deno Deploy, and the backend sits on the hot path of every collected
photo.

bunny.net granted this account access to its **S3-compatible API** (closed preview). A live spike
against a new S3-enabled zone (`snap-sync-dev`, `de-s3.storage.bunnycdn.com`) proved the pivotal
fact: **an S3-enabled zone speaks both the native HTTP Storage API and the S3 API over one shared
object namespace** — an object written through the native API was read back through an S3 **presigned
GET** with no credential. That makes a **hybrid** possible: keep every native call-site exactly as it
is (uploads, marker/manifest, listings) and add S3 **only** to mint presigned GET URLs, so the device
downloads photo bytes **directly from bunny** and the backend leaves the byte path entirely.

This is deliberately **not** a storage migration. Uploads, listings, and the event registry stay on
the native API untouched; S3 is used for one thing — presigning download URLs.

## What Changes

- **The list/union `url` becomes a presigned S3 GET URL.** `downloadUrl()` stops composing a
  backend-proxy URL and instead mints an AWS SigV4 presigned GET against
  `https://de-s3.storage.bunnycdn.com/<zone>/<key>` (path-style), `X-Amz-Expires=604800` (7 days),
  signed with the storage zone's credentials (Access Key ID = zone name, Secret = the storage-zone
  `AccessKey`). Both list routes (`GET /files/device/<deviceId>` and the union `GET
  /event/<eventId>/files`) return presigned URLs, minted fresh on every response.
- **The backend byte-download proxy route is removed.** `GET /files/device/<deviceId>/<filename>` is
  deleted; the device downloads directly from bunny's S3 endpoint. The `bunny-download-endpoint`
  capability is **retired** — it described an HTTP route that no longer exists. Its two surviving
  contracts move: the **download-URL format** authority moves into `bunny-list-endpoint`; the
  **client short-read = failed download** contract moves into `photo-download` (its consumer).
- **Signing via `aws4fetch`.** A ~4 KB Web-Crypto SigV4 signer (npm, same import model as `hono`),
  chosen over the AWS SDK for edge-isolate safety (runs unchanged on both Deno Deploy and bunny Edge
  Scripting) and because its value would land on only one operation while fighting the streamed
  upload. It is used solely to presign; no backend→S3 request signing is introduced (writes/reads
  stay native).
- **The device refreshes not-yet-staged download URLs on re-plan.** Presigned URLs expire, and today
  `download-store`'s `upsertResource` is `INSERT OR IGNORE` — it freezes a resource's `url` at first
  plan, so an expired link would be stuck (only cleared on leave/switch). It is changed to refresh the
  `url` of resources whose `stagedPath IS NULL`, leaving staged and terminal rows untouched. Combined
  with the server minting a fresh 7-day URL on every union read (which the client already re-reads on
  join and every foreground), an expired link **self-heals** on the next foreground reconcile.
- **Config gains the S3 endpoint.** `backend-config` adds the S3 region (`de`) and S3 endpoint host
  (`de-s3.storage.bunnycdn.com`) for the presigner. `PUBLIC_BASE_URL` stays the backend's public
  origin for upload/event/list traffic but **no longer appears in any download URL**.
- **The deploy points at the new S3-enabled zone.** `BUNNY_STORAGE_ZONE` becomes `snap-sync-dev`
  (S3-compatibility is a create-time-only flag; the existing zone cannot be converted). Existing
  objects are **not** migrated — the new zone starts empty and each device re-uploads its library once
  on the next reconcile (the old zone held only dev/test data).

## Capabilities

### New Capabilities
<!-- none — no new capability is introduced; presigning folds into the existing list capability. -->

### Modified Capabilities

- `bunny-list-endpoint`: the `url` in both the per-device entry shape and the union resource shape
  becomes a **presigned S3 GET URL**; the spec **absorbs the download-URL-format authority** (a new
  requirement) formerly owned by `bunny-download-endpoint`; the per-device list response gains
  `Cache-Control: no-store` (its `url`s are time-limited signed URLs).
- `photo-download`: background downloads target the presigned S3 URL **directly** (not the backend);
  the **short-read = failed-and-retry** integrity contract moves here from the retired download
  endpoint; and a new requirement records that an expired presigned URL **self-heals** on the next
  foreground reconcile.
- `download-store`: a new requirement — re-planning an asset refreshes the stored `url` of its
  **not-yet-staged** resources (so a fresh presigned URL supersedes an expiring one), leaving staged
  resources and terminal rows untouched.
- `backend-config`: the required env inventory adds the **S3 region** and **S3 endpoint host**;
  `PUBLIC_BASE_URL` is reworded — it is the origin for upload/event/list requests and no longer
  composes any download URL.
- `backend-deployment`: the "active runtime serves … downloads" and "device→backend traffic and the
  list endpoint's download URLs share one origin we own" statements are corrected — **downloads no
  longer transit the runtime or the custom-domain origin**; they go directly to bunny's S3 endpoint.

### Removed Capabilities

- `bunny-download-endpoint`: **retired.** All of its requirements (the `GET
  /files/device/<deviceId>/<filename>` route, single-ungated-streaming-GET, indistinguishable-404,
  faithful-read, relayed-headers, short-read, public-URL-format, and event-id-auth) are removed with
  the proxy route. The two contracts worth keeping are re-homed (URL format → `bunny-list-endpoint`;
  short-read → `photo-download`); the rest are now bunny's S3 endpoint's concern, reached directly by
  the device.

## Impact

- **Backend** (`backend/src/`): `app.ts` — `downloadUrl()` rewritten to presign (build one
  `aws4fetch` `AwsClient` from config); delete the `byteFile.get("/")` proxy route; add
  `Cache-Control: no-store` to the per-device list. `config.ts` — add `s3Region` + `s3Host`. Add
  `npm:aws4fetch` to `deno.json`. `backend/test/app.test.ts` — replace the download-URL assertions
  (now presigned S3: host, `X-Amz-Signature` present, 7-day expiry) and drop the proxy-GET-route
  tests. `scripts/reset-storage.ts` is unchanged (native API, works on the new zone).
- **Device**: `domain/download-store/…/DownloadStore.sq` — `upsertResource` refreshes `url` where
  `stagedPath IS NULL`; update the `download-store` contract test (`commonTest`, runs JVM +
  `iosSimulatorArm64`). No other device change — `DownloadController.reconcile` already re-plans every
  foreground and `IosPhotoDownloadJobs` already re-enqueues pending resources; the download job already
  fetches `resource.url` (now an S3 URL) and already treats a short read as a failure.
- **Deploy** (`.github/workflows/backend-deploy.yml` / Edge Script env): set `BUNNY_STORAGE_ZONE =
  snap-sync-dev`; add `BUNNY_S3_REGION` + `BUNNY_S3_HOST`. Secret reused: `BUNNY_STORAGE_ACCESS_KEY` is
  already the zone password = the S3 secret. No new secret.
- **ATS**: none. The new download host (`de-s3.storage.bunnycdn.com`) is a public HTTPS endpoint with
  a publicly-trusted cert — default ATS covers it, no `Info.plist` exception. Background `NSURLSession`
  needs no per-task auth header, which a presigned URL is designed for.
- **One-time**: the new zone starts empty, so every device re-uploads its library once on the next
  reconcile. The old native zone's objects are orphaned (delete out of band).
- **Docs**: `docs/design.md` §3.5 (downstream reconstruction) and §4 (storage/auth) note that download
  URLs are now presigned S3, minted by the list capability, while uploads/listings stay native.
- **Preview risk (accepted)**: S3 compatibility is bunny closed-preview with no GA date; the download
  path now depends on it. Rollback is a `git revert` + redeploy (straight cutover, no fallback flag).
