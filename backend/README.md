# backend/ — SnapSync backend (bunny Edge Scripting / Deno Deploy)

A **streaming proxy** (Deno/TypeScript + **Hono**). One bundle deploys to **both** bunny Edge
Scripting and Deno Deploy; the device-facing origin is the custom domain **`snapsync.stho.net`**
(our Bunny DNS zone, Let's Encrypt cert), `CNAME`'d to the **active** runtime — **Deno Deploy
today**, while bunny investigates dropping iOS's zero-window upload SYNs. It mints events, streams
photo bytes from the iOS background-upload extension straight into a bunny **native** Storage zone,
records per-event device manifests, and serves per-device and event-wide listings. Downloads are
**presigned S3 GET URLs** the device fetches directly from bunny's S3 endpoint — no download proxy,
no on-device SigV4, no per-resource mint round-trip.

Authoritative contracts: `openspec/specs/event-creation`, `openspec/specs/bunny-upload-endpoint`,
`openspec/specs/bunny-list-endpoint`, `openspec/specs/device-manifest`, and
`openspec/specs/backend-config` (and `backend-deployment`); rationale in `docs/design.md` §3–§4.

## Storage layout

Three disjoint key namespaces in one zone (an `eventId`/`deviceId` is a UUID, never a literal label,
so nothing collides):

```
events/<eventId>/metadata.json          event marker / registry record { eventId, name, createdAt }
events/<eventId>/device/<deviceId>.json per-event device manifest (membership + projected assets)
devices/<deviceId>/files/<filename>     a device's raw uploaded photo/resource byte objects
```

An event **exists** iff its marker `events/<eventId>/metadata.json` is present. Bunny's Edge Storage
API has no `HEAD`, so existence is a small `GET` of the marker. The **byte store is
device-partitioned and event-independent** — a resource is uploaded once under its device's
namespace and linked into any number of events by reference (the per-event manifest). The device id
is self-asserted (possession of the UUID is the capability); App Attest is the noted hardening path.

## Contract

```
POST /event
    body: {"name": "<event name>"}                        (JSON; trimmed, non-empty, ≤100 chars)
    →  bunny native PUT  events/<minted-uuid>/metadata.json
    →  201 {eventId, name, createdAt}                     (eventId minted server-side) | 502

GET  /event/<eventId>
    →  200 {eventId, name, createdAt}  | 404 when never created | 502 on a non-404 marker read failure

PUT  /devices/<deviceId>/files/<filename>                 (byte upload — UNGATED, no marker read)
    body: raw resource bytes (streamed, never buffered)
    →  bunny native PUT  https://<host>/<zone>/devices/<deviceId>/files/<filename>
       header  AccessKey: <storage-zone password>
    →  201 on confirmed store | 502 on any upstream error/abort
    OPTIONS → 204 (no resumable-upload advertised → the iOS uploader falls back to a plain PUT)

GET  /devices/<deviceId>/files                            (per-device raw listing — UNGATED)
    →  single bunny native LIST of  devices/<deviceId>/files/
    →  200 [ {filename, size, url}, … ]  (200 [] for an empty/unknown partition) | 502 on LIST failure
       url = a presigned S3 GET URL (below);  Cache-Control: no-store

PUT  /event/<eventId>/device/<deviceId>                   (device manifest — GATED on event existence)
    body: full-state JSON device manifest (streamed)
    →  [gate] GET events/<eventId>/metadata.json  → absent? 404 (stream nothing) | non-404 failure? 502
    →  bunny native PUT  events/<eventId>/device/<deviceId>.json   → 201 | 502

GET  /event/<eventId>/files                               (event-wide UNION — GATED on event existence)
    →  [gate] GET events/<eventId>/metadata.json  → absent? 404 | non-404 failure? 502
    →  LIST events/<eventId>/device/  → per device: read device.json + LIST devices/<deviceId>/files/
    →  200 [ {deviceId, assetId, creationDate, resources:[{role,contentType,key,filename,size,url}]} ]
       complete assets only (every named resource present in the device's byte partition), flattened
       across devices, each tagged with its owning deviceId;  200 [] for an empty event
       any non-404 read failure anywhere (incl. a manifest JSON parse) → 502 (never a partial union)
       Cache-Control: no-store
```

- `eventId` / `deviceId` — **UUIDs** (Hono route params, validated). The UUID is the capability (no
  token); the event marker is consulted for **existence**, never authorization.
- `filename` — a single path segment; a literal or encoded `/` (`%2F`) or `..` is rejected (`400`)
  so keys stay flat. It is percent-encoded into the storage key and decoded back on listing, so the
  round-trip is byte-exact.
- **Stored keys are bare** — the URL labels (`devices`/`files`/`event`/`device`) are structural, not
  part of the stored key beyond the layout above.
- **Last-write-wins** — every object write is one unconditional PUT with no existence check on the
  object key. A byte key is device-partitioned (same-device overwrite of a byte-identical
  re-upload); a manifest is rewritten in full each cycle.
- **Faithful outcome** — a write returns `2xx` **only** when bunny confirms the store; any upstream
  error/abort → `502` (the iOS ledger retries). A read returns a `2xx` array **only** when every
  required LIST/GET succeeds; otherwise `502`, never a partial/truncated result. Never a false
  success.
- **Presigned download URLs** — each listed object's `url` is an AWS **SigV4 presigned S3 GET URL**
  (path-style `https://<s3-host>/<zone>/devices/<deviceId>/files/<filename>?X-Amz-…`,
  `X-Amz-Expires` **7 days**), signed with the zone name as the S3 Access Key ID and the storage
  `AccessKey` as the secret. The query signature is the sole authorization — the device fetches the
  object directly from bunny's S3 endpoint with no credential. A **fresh** URL is minted on every
  list/union response (never cached), so each read yields one valid for a further 7 days. Both the
  per-device list and the union use the same builder, so their `url`s agree by construction. The
  former download-proxy route is retired.
- **Methods** — `POST /event`, `GET /event/<id>`, `GET /devices/<id>/files`, `PUT`/`OPTIONS` on
  `/devices/<id>/files/<name>`, `PUT /event/<id>/device/<id>`, `GET /event/<id>/files`. Any other
  method or unmatched path → **`404`** (Hono's default — no `405`). Bad UUID / unsafe filename /
  invalid name → `400`.

> **Deployment invariant.** `BUNNY_STORAGE_HOST` MUST be the storage zone's **main** region host
> (where writes land), never a replica endpoint. Bunny replicates asynchronously; reads from the
> main region are read-after-write consistent, so a just-created marker is visible to the
> immediately following join/list/upload. A replica host could lag and `404` a fresh event.

> **Note.** The byte-upload route is **ungated** (it reads no marker), so it never `404`s for an
> "unknown event" — bytes are event-independent. Only the device-manifest write and the event union
> are gated on event existence.

## Layout

```
src/app.ts        Hono app (createApp({config, fetch}) → routes): create + metadata + byte upload +
                  per-device list + device-manifest write + event union. Key helpers (markerKey,
                  deviceManifestKey/Dir, byteKey, deviceDir), the existence gates, and
                  presignDownloadUrl() (the sole builder of each entry's presigned S3 download url).
src/validators.ts validateUUID / validateFilename → boolean; validateEventName(raw) → trimmed | null
src/config.ts     readConfig(env) → Config (zone/host/accessKey/PUBLIC_BASE_URL/S3 region+host;
                  THROWS on any missing/blank var)
src/main.ts       Edge Scripting / Deno entry: reads config at startup, serves createApp(...).fetch
test/*.test.ts    Deno tests (app via app.request(), upstream fetch + config injected)
```

## Configuration (env only — no secrets in source)

| Var                        | Meaning                                                                                                                           |
| -------------------------- | --------------------------------------------------------------------------------------------------------------------------------- |
| `BUNNY_STORAGE_ZONE`       | storage zone name (also the S3 Access Key ID + bucket)                                                                            |
| `BUNNY_STORAGE_HOST`       | native host, e.g. `storage.bunnycdn.com` (DE/Falkenstein default)                                                                 |
| `BUNNY_STORAGE_ACCESS_KEY` | storage-zone **password** (the `AccessKey`; also the S3 secret; NOT the account API key)                                          |
| `PUBLIC_BASE_URL`          | the backend's public origin (no trailing slash) — the host clients reach for upload/event/list. **Not** part of any download URL. |
| `BUNNY_S3_REGION`          | S3 region of the (S3-enabled) storage zone, e.g. `de` — used only to presign download URLs                                        |
| `BUNNY_S3_HOST`            | bunny S3-compatible endpoint host, e.g. `de-s3.storage.bunnycdn.com` — the presigned-URL origin                                   |

`main.ts` reads these once at startup via `readConfig(Deno.env.toObject())`, which **throws** on any
missing/blank var → a misconfigured deployment **fails to boot** (fail-closed at deploy, never a
mis-targeted upload and never a blank/unsignable download URL). The validated `Config` is injected
into the app, so the request handlers have no configuration path.

## Develop & test

```bash
deno task test          # full suite; upstream bunny mocked, config injected → offline, no perms
deno task lint
deno fmt --check
# run locally (listens on 127.0.0.1:8080 via the SDK):
BUNNY_STORAGE_ZONE=z BUNNY_STORAGE_HOST=storage.bunnycdn.com BUNNY_STORAGE_ACCESS_KEY=k \
  BUNNY_S3_REGION=de BUNNY_S3_HOST=de-s3.storage.bunnycdn.com \
  PUBLIC_BASE_URL=http://127.0.0.1:8080 deno run --allow-net --allow-env src/main.ts
```

`createApp({ config, fetch })` takes an injected validated `config` and upstream `fetch`, so tests
drive the real Hono app via `app.request()` without the network or `Deno.env`.

## Deploy

CI deploys via `.github/workflows/backend-deploy.yml` (path-scoped to `backend/**`, **gated on green
`deno fmt`/`lint`/`check`/`test`**) using `BunnyWay/actions/deploy-script`. Provision once and set
GH secrets:

1. With the Bunny **account API key**, create an **S3-enabled** Storage zone (DE) → record the
   `BUNNY_STORAGE_*` and `BUNNY_S3_*` values and set them as Edge Script **environment variables**.
2. Create the Edge Scripting app → record its **script id** and a **deploy key**.
3. Add GH secrets `BUNNY_SCRIPT_ID` and `BUNNY_DEPLOY_KEY` (the deploy key is script-scoped — the
   account API key is **not** used by CI).

The same workflow also deploys to **Deno Deploy** (`--org stefanhoelzl --app snapsync`, secret
`DENO_DEPLOY_TOKEN`) and sets `PUBLIC_BASE_URL` to the device-facing origin. **Deno Deploy is the
active device-facing runtime** while bunny drops iOS's zero-window upload SYNs. The origin is the
custom domain **`snapsync.stho.net`** — a `CNAME` in our `stho.net` Bunny DNS zone pointing at
Deno's `alias.deno.net` (auto-TLS via Let's Encrypt). Because we own the name, the revert to bunny
(once the SYN-drop is fixed) is a **DNS repoint of `snapsync.stho.net` + a `PUBLIC_BASE_URL` flip —
not a new iOS build**.

## On-device caveats (unverified — see design.md §8)

This endpoint's bunny-facing behavior is tested; its **iOS-facing** surface is frozen but unverified
until the iOS rewiring follow-up: OPTIONS fallback on a custom origin, which `2xx` the background
uploader accepts, and whether the largest Live-Photo paired-video stays within the 30 s CPU budget /
any undocumented wall-clock timeout (fix if it bites: server-side resumable uploads).
