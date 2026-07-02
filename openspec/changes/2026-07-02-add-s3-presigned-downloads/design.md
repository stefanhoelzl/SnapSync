## Context

All bunny access is server-side, in `backend/src/app.ts` + `config.ts`; every device module speaks
only to `snapsync.stho.net` and is storage-agnostic. Today downloads are **proxied**: the list/union
`url` is `<PUBLIC_BASE_URL>/files/device/<deviceId>/<filename>`; the device GETs that; the backend
re-fetches from bunny's native Storage API (`https://storage.bunnycdn.com/<zone>/<key>`, header
`AccessKey: <zone password>`) and streams the bytes back. There is no S3, no SigV4, no pull zone
anywhere in the live code (the v1 on-device presigner was retired; it survives only in
`openspec/changes/archive/`).

bunny granted S3-compatible-API preview access. The goal: return **direct S3 presigned download
links** from the listings so the device pulls bytes straight from bunny, off the backend.

## The pivotal spike (already run, green)

A ~60-line Deno + `aws4fetch` probe ran against a fresh S3-enabled zone (`snap-sync-dev`, region `de`,
`de-s3.storage.bunnycdn.com`), credentials from `proton-env` (`BUNNY_STORAGE_ACCESS_KEY`):

```
[1] native PUT on the S3 zone ................................. 201 {"Message":"File uploaded."}
[2] S3 presigned GET of the NATIVE-written object ............. 200, body == what native wrote   ◀── decisive
[3] native JSON LIST on the S3 zone .......................... 200, normal [{ObjectName,Length,…}]
[4] S3 PutObject with x-amz-content-sha256: UNSIGNED-PAYLOAD .. 200
[5] S3 presigned GET of the S3-written object ................ 200 round-trip
[6] ListObjectsV2 XML ........................................ standard <ListBucketResult>
```

**[2] is the whole basis for this change:** on an S3-enabled zone, native and S3 are two protocols
over **one shared object namespace**. So we keep native for everything we already do and add S3 only
to presign — the **hybrid**. ([4]/[6] additionally prove a full S3 rewrite is a viable fallback, but
there is no reason to take it.)

```
  device ─ upload PUT ─┐                       device ─ upload PUT ─┐
         ─ list  GET ──┼─▶ snapsync.stho.net          ─ list  GET ──┼─▶ snapsync.stho.net
         ─ DOWNLOAD ───┘        │  proxy               (list url = presigned S3)   │ presign only
                    bytes ◀─────┤ (native)                                          ▼
   BEFORE: bytes transit backend                 backend ─ AccessKey ─▶ native API (writes+LIST) ─┐
                                                                                                  │ one
   AFTER: backend leaves the byte path;           device downloads DIRECT ◀── S3 presigned GET ◀──┘ zone
          list mints a 7-day presigned S3 GET               de-s3.storage.bunnycdn.com
```

## Key decisions

### 1. Hybrid, not migration (spike [2])
Keep uploads, marker/manifest, and both listings on the **native** API — zero rewrite of the proven
call-sites. S3 touches exactly one function: `downloadUrl()`. This drops the two riskiest parts a full
migration would have carried — the streamed-`PutObject` `UNSIGNED-PAYLOAD` dependency and the
`ListObjectsV2` XML rewrite — from the production path entirely.

### 2. Fold `bunny-download-endpoint` into `bunny-list-endpoint` (retire the capability)
With the proxy route gone there is **no download endpoint** — only a URL builder. The capability's
"list `url` and download route must agree by construction" seam existed to keep two things in sync;
with one thing (the list mints the URL), the seam is moot. So the capability is retired and its two
durable contracts are re-homed: **download-URL format** → `bunny-list-endpoint` (already the sole
consumer of it); **short-read = failed download** → `photo-download` (always a consumer contract, not
an endpoint one). Everything else (faithful-read, relayed-headers, indistinguishable-404) becomes
bunny's S3 endpoint's concern, reached directly by the device.

### 3. `aws4fetch`, presign-only
~4 KB, zero transitive deps, Web-Crypto only — no Node shims, safest in the bunny Edge Scripting
isolate; same `npm:` import model as `hono`. One `AwsClient` (Access Key ID = zone name, Secret = the
storage-zone `AccessKey`, region `de`, service `s3`) presigns a GET via
`aws.sign(url + "?X-Amz-Expires=604800", { method: "GET", aws: { signQuery: true } })`. It is used
**only** to presign; native writes/reads keep their `AccessKey` header (no backend→S3 request signing
introduced). The AWS SDK was rejected: its value (request build + XML parse) lands only on LIST, which
stays native, while its request machinery fights the streamed upload and risks edge-isolate compat.

### 4. Expiry = 7 days, self-healed by two cooperating halves
```
server half: downloadUrl() runs per list/union response  → every foreground reconcile gets FRESH 7-day urls
             (union already Cache-Control: no-store; per-device list gains it)
device half: download-store upsertResource refreshes url WHERE stagedPath IS NULL
             → the fresh url replaces the frozen one for work still pending
together   : an expired presigned link cannot get stuck — the next foreground re-presigns and re-stores it
```
7 days (the S3 max) gives a queued background `NSURLSession` transfer the widest window before it must
rely on the re-presign. `INSERT OR IGNORE` today would freeze the first URL forever; the `stagedPath
IS NULL` guard scopes the refresh to pending work only, never disturbing staged bytes or terminal
rows.

### 5. Straight cutover, fresh zone
Rewrite → deploy; rollback = `git revert` + redeploy (no fallback flag). S3-compatibility is a
create-time-only flag, so the new zone (`snap-sync-dev`) is unavoidable and starts empty; devices
re-upload once (the old zone was dev/test data). Downloads now depend on a closed-preview API — an
accepted risk for a personal backup in TestFlight.

## Risks / trade-offs

- **Preview dependency**: the download path depends on bunny's closed-preview S3 API (no GA date).
  Mitigation: native writes/listings are unaffected; only presigned reads are exposed; rollback is a
  redeploy.
- **Presigned-URL validity window**: a signed URL is a 7-day bearer capability to the object. The
  trust model is unchanged from today (event id / device-id path already an ungated capability; the
  old proxy route was ungated too), and object keys are UUID-pathed. No revocation exists in this app
  regardless.
- **Short-read integrity now vs. S3 directly**: the backend leaves the byte path, so the
  received-body-shorter-than-`Content-Length` check runs against S3's response instead of a relayed
  one. `IosPhotoDownloadJobs` already performs this check; the contract simply re-homes to
  `photo-download`.
- **One-time re-upload cost**: the fresh zone means each device re-backs-up its library once. Benign
  for a one-way personal backup; bounded to a single reconcile pass.

## What is explicitly NOT changing

Uploads (native streamed proxy `PUT`), the event registry (marker/manifest native `PUT`/`GET`), both
listings' native LIST mechanics and JSON parsing, `bunny-upload-endpoint`, the deploy topology
(both runtimes, custom-domain origin for upload/list/event traffic), and every device module other
than the one `download-store` SQL statement.
