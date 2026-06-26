## Why

The v1 iOS sync embeds AWS credentials on-device and signs each upload with a hand-rolled SigV4
presigner — the model design.md §4 explicitly retires. The replacement edge proxy
(`bunny-upload-endpoint`) is already built and deployed live at `https://snap-sync-n8xmz.bunny.run`.
This change moves the device onto it: the phone holds **no storage credential**, does **no signing**,
and PUTs each resource to a locally-built, stable edge URL. It is the v1→v2 pivot, scoped to the
**minimum** that gets uploads flowing through the proxy.

## What Changes

- **BREAKING** The QR/deeplink payload changes from the S3 `v=2` `{bucket, region, accessKeyId,
  secretAccessKey}` to `v=3` `{eventId}`. `v=1` and `v=2` are rejected. A device holding an old S3
  config in its Keychain decodes to nothing → setup gate shows "not joined" → user rescans the new
  event QR. The version reject *is* the migration (no Keychain migration code).
- `eventId` is validated as a canonical UUID **at scan time** (a bad QR fails to provision, not at
  upload).
- The on-device `UploadRequestProvider` becomes a **local URL builder** (no network, no crypto): it
  emits `PUT https://<host>/event/<eventId>/device/<deviceId>/file/<filename>` with `Content-Type`
  only — no auth header, no `x-amz-meta-*` (bunny's native API has no custom metadata).
- `deviceId` is introduced: a UUID **lazily minted and persisted in the App Group** the first time
  the extension needs it (Foundation `NSUUID`, no UIKit), then reused. Stable for the install;
  rotates on reinstall (App Group wiped) — accepted.
- **BREAKING** CI bakes the **real deployed edge URL** into `BackgroundUploadURLBase` for *all*
  builds (TestFlight included), replacing the inert `https://dummy.invalid` default. Credential-free
  device + production edge endpoint make this safe. The `workflow_dispatch` `upload_host` override is
  kept (now for pointing at a local Deno backend).
- `:capability:s3` is **deleted** — SigV4 presigner, golden/known-answer tests, `S3Config`,
  `S3ConfigPayload`.
- A new `:capability:upload-url` module holds the edge URL builder and the URL-segment encoder
  (moved out of s3).
- **Out of scope / deferred** (consciously diverging from design.md, doc to be updated): the QR's
  `name`/`startDate` fields and **date-filtered discovery**. Discovery stays whole-library for now.

## Capabilities

### New Capabilities
- `edge-upload-provider`: The on-device, network-free `UploadRequestProvider` that builds the bunny
  edge URL (`/event/<eventId>/device/<deviceId>/file/<filename>`), sets `Content-Type` only, and
  carries the deterministic+injective filename→destination mapping that anchors idempotency. Lives in
  `:capability:upload-url`.

### Modified Capabilities
- `deeplink-config`: payload becomes `v=3` `{eventId}` (UUID-validated); `v=1`/`v=2` rejected;
  `S3Config`/`S3ConfigPayload` re-export removed.
- `ios-background-upload`: upload destination is the locally-built edge URL (not a presigned S3 URL);
  introduces the App-Group-persisted `deviceId`; key placement is `<eventId>/<deviceId>/<filename>`;
  no custom metadata headers; cycle skipped+logged if `deviceId` can't be obtained.
- `s3-request-provider`: **REMOVED** — the on-device SigV4 presigner and S3 request provider are
  deleted, superseded by `edge-upload-provider`.
- `ios-ci`: `BackgroundUploadURLBase` defaults to the deployed edge URL for all builds; the
  `upload_host` dispatch override is retained for local-backend testing.

## Impact

- **Modules**: new `:capability:upload-url`; `:capability:config` (payload + decoder, drops s3
  re-export); deleted `:capability:s3`; `:app:ios:photokit-extension` (composition root + config
  assembly); `:app:desktop` (canned config type); `:test:integration` if it references s3 types.
- **CI**: `.github/workflows/ios.yml` (`BACKGROUND_UPLOAD_URL_BASE` default + input description).
- **Docs**: `docs/design.md` §3.2/§4 edited to record the eventId-only / no-date-filter scoping and
  the lazy-mint deviceId; design.md is the source of truth and must not be left contradicting code.
- **External backend**: unchanged — already deployed and live-verified (PUT validates UUIDs/filename,
  rejects wrong methods).
- **On-device verification (no code, but gating ship confidence)**: a BunnyCDN pull zone fronts the
  Edge Script and **owns `OPTIONS`** (answers a generic `200`, shadowing the script's non-resumable
  handler) — so the #1 device check is that iOS's upload preflight falls back to a plain single-shot
  PUT against the CDN's response.
