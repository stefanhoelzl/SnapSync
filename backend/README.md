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
`openspec/specs/bunny-list-endpoint`, `openspec/specs/device-manifest`,
`openspec/specs/event-leave-endpoint`, and `openspec/specs/backend-config` (and
`backend-deployment`). Rationale lives in each spec's `## Purpose` and its `Decision record:`
pointer into `openspec/changes/archive/`.

## Storage layout

Three disjoint key namespaces in one zone (an `eventId`/`deviceId` is a UUID, never a literal label,
so nothing collides):

```
events/<eventId>/metadata.json                 event marker / registry record { eventId, name, createdAt }
events/<eventId>/devices/<deviceId>.json       per-event device manifest — ACTIVE member (projected assets)
events/<eventId>/devices/<deviceId>.left.json  per-event device manifest — DEPARTED (left; still in the union)
files/devices/<deviceId>/<filename>            a device's raw uploaded photo/resource byte objects
devices/<deviceId>.json                        a device's config { pushToken: { kind, token, env } } (NOT a file)
```

An event **exists** iff its marker `events/<eventId>/metadata.json` is present. Bunny's Edge Storage
API has no `HEAD`, so existence is a small `GET` of the marker. The **byte store is
device-partitioned and event-independent** — a resource is uploaded once under its device's
namespace and linked into any number of events by reference (the per-event manifest). The device id
is self-asserted (possession of the UUID is the capability); App Attest is the noted hardening path.

## Contract

```
POST /events
    body: {"name": "<event name>"}                        (JSON; trimmed, non-empty, ≤100 chars)
    →  bunny native PUT  events/<minted-uuid>/metadata.json
    →  201 {eventId, name, createdAt}                     (eventId minted server-side) | 502

GET  /events/<eventId>
    →  200 {eventId, name, createdAt}  | 404 when never created | 502 on a non-404 marker read failure

PUT  /files/devices/<deviceId>/<filename>                 (byte upload — UNGATED, no marker read)
    body: raw resource bytes (streamed, never buffered)
    →  bunny native PUT  https://<host>/<zone>/files/devices/<deviceId>/<filename>
       header  AccessKey: <storage-zone password>
    →  201 on confirmed store | 502 on any upstream error/abort
    OPTIONS → 204 (no resumable-upload advertised → the iOS uploader falls back to a plain PUT)

GET  /files/devices/<deviceId>                            (per-device raw listing — UNGATED)
    →  single bunny native LIST of  files/devices/<deviceId>/
    →  200 [ {filename, size, url}, … ]  (200 [] for an empty/unknown partition) | 502 on LIST failure
       url = a presigned S3 GET URL (below);  Cache-Control: no-store

PUT  /devices/<deviceId>                           (device config / push token — UNGATED by event)
    body: { pushToken: { kind: "apns", token, env } }  (streamed)     DEVICE-ID is the capability
    →  bunny native PUT  devices/<deviceId>.json   → 201 | 502   (last-write-wins; not a listed file)

POST /events/<eventId>/notify                              (silent push to members — GATED on event existence)
    →  [gate] GET events/<eventId>/metadata.json  → absent? 404 | non-404 failure? 502
    →  LIST events/<eventId>/devices/  → per ACTIVE member (LWW): read devices/<id>.json → APNs silent push
    →  202 (bare)  |  502 only if the member LIST fails
       best-effort: members without a token are skipped; a per-token failure never fails the request
       fixed payload (content-available), ACTIVE members only (a departed <id>.left.json is skipped)

PUT  /events/<eventId>/devices/<deviceId>                   (device manifest — GATED on event existence)
    body: full-state JSON device manifest (streamed)
    →  [gate] GET events/<eventId>/metadata.json  → absent? 404 (stream nothing) | non-404 failure? 502
    →  bunny native PUT  events/<eventId>/devices/<deviceId>.json   → 201 | 502

DELETE /events/<eventId>/devices/<deviceId>                 (LEAVE — GATED on event existence)
    →  [gate] GET events/<eventId>/metadata.json  → absent? 404 | non-404 failure? 502
    →  (1) rename active manifest → events/<eventId>/devices/<deviceId>.left.json (copy → FRESH ts, then delete active)
       (2) if NO active member remains (last-write-wins over the devices/ listing): delete the events/<eventId>/ tree
       (3) per freed device with NO manifest in any surviving event: delete files/devices/<id>/* + devices/<id>.json
    →  200  |  502 on any transport failure
       idempotent + leak-safe (write .left.json BEFORE deleting .json; every delete of an absent object is a no-op)
       membership is last-write-wins: a departed <id>.left.json stays in the UNION but is skipped by NOTIFY & the reap

GET  /events/<eventId>/files                               (event-wide UNION — GATED on event existence)
    →  [gate] GET events/<eventId>/metadata.json  → absent? 404 | non-404 failure? 502
    →  LIST events/<eventId>/devices/  → per device: read device.json + LIST files/devices/<deviceId>/
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
- **Stored keys are bare** — the URL labels (`files`/`devices`/`events`) are structural, not part of
  the stored key beyond the layout above.
- **Last-write-wins** — every object write is one unconditional PUT with no existence check on the
  object key. A byte key is device-partitioned (same-device overwrite of a byte-identical
  re-upload); a manifest is rewritten in full each cycle.
- **Faithful outcome** — a write returns `2xx` **only** when bunny confirms the store; any upstream
  error/abort → `502` (the iOS ledger retries). A read returns a `2xx` array **only** when every
  required LIST/GET succeeds; otherwise `502`, never a partial/truncated result. Never a false
  success.
- **Presigned download URLs** — each listed object's `url` is an AWS **SigV4 presigned S3 GET URL**
  (path-style `https://<s3-host>/<zone>/files/devices/<deviceId>/<filename>?X-Amz-…`,
  `X-Amz-Expires` **7 days**), signed with the zone name as the S3 Access Key ID and the storage
  `AccessKey` as the secret. The query signature is the sole authorization — the device fetches the
  object directly from bunny's S3 endpoint with no credential. A **fresh** URL is minted on every
  list/union response (never cached), so each read yields one valid for a further 7 days. Both the
  per-device list and the union use the same builder, so their `url`s agree by construction. The
  former download-proxy route is retired.
- **Methods** — `POST /events`, `GET /events/<id>`, `GET /files/devices/<id>`, `PUT`/`OPTIONS` on
  `/files/devices/<id>/<name>`, `PUT /events/<id>/devices/<id>`, `DELETE /events/<id>/devices/<id>`,
  `GET /events/<id>/files`. Any other method or unmatched path → **`404`** (Hono's default — no
  `405`). Bad UUID / unsafe filename / invalid name → `400`.

> **Deployment invariant.** `BUNNY_STORAGE_HOST` MUST be the storage zone's **main** region host
> (where writes land), never a replica endpoint. Bunny replicates asynchronously; reads from the
> main region are read-after-write consistent, so a just-created marker is visible to the
> immediately following join/list/upload. A replica host could lag and `404` a fresh event.
>
> The **leave** cascade depends on this too, and more sharply: its reap decision (is any active
> member left?) and its GC reference-check (does a freed device appear in another event?) LIST the
> devices directories, and a stale replica read could miss a concurrent rejoin's fresh `<id>.json`
> and reap an event out from under an active device. Every other failure mode of leave is a harmless
> orphan; this is the one that would delete in-use data — so the reap MUST read the main region.
> Never point `BUNNY_STORAGE_HOST` at a replica.

> **Note.** The byte-upload route is **ungated** (it reads no marker), so it never `404`s for an
> "unknown event" — bytes are event-independent. Only the device-manifest write and the event union
> are gated on event existence.

## Layout

```
src/app.ts        Hono app (createApp({config, fetch}) → routes): create + metadata + byte upload +
                  per-device list + device-manifest write + event union + device config + event notify.
                  Key helpers (markerKey, deviceManifestKey/Dir, byteKey, deviceDir, deviceConfigKey),
                  the existence gates, presignDownloadUrl(), and readPushToken() (notify fan-out).
src/apns.ts       createApnsSender(config, fetch) → { sendSilent(tokens) }: ES256 provider-JWT signing
                  (WebCrypto, memoized) + a silent HTTP/2 push per token; per-token best-effort outcomes.
src/validators.ts validateUUID / validateFilename → boolean; validateEventName(raw) → trimmed | null
src/config.ts     readConfig(env) → Config (zone/host/accessKey/PUBLIC_BASE_URL/S3 region+host/APNS_*;
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
| `APNS_KEY_ID`              | APNs Auth Key id (the `.p8` Key ID) — the provider-JWT `kid`                                                                      |
| `APNS_TEAM_ID`             | Apple team id (`E9Z8BADH58`) — the provider-JWT `iss`                                                                             |
| `APNS_PRIVATE_KEY`         | the APNs Auth Key `.p8` **PEM contents** (not a path) — ES256-signs the provider JWT; runtime env, never a CI secret              |
| `APNS_TOPIC`               | the push topic — the app bundle id `app.snapsync` (the `apns-topic` header)                                                       |

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
  APNS_KEY_ID=k APNS_TEAM_ID=E9Z8BADH58 APNS_TOPIC=app.snapsync APNS_PRIVATE_KEY="$(cat AuthKey.p8)" \
  PUBLIC_BASE_URL=http://127.0.0.1:8080 deno run --allow-net --allow-env src/main.ts
```

The APNs credentials are **runtime** env (the `AccessKey` category), set on the platform, **not**
deploy-workflow secrets. Provision an APNs **Auth Key** (`.p8`) for team `E9Z8BADH58` once (App
Store Connect API / portal) and set `APNS_KEY_ID` / `APNS_TEAM_ID` / `APNS_PRIVATE_KEY` (the `.p8`
PEM) / `APNS_TOPIC=app.snapsync` as Edge Script / Deno Deploy environment variables.

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

## On-device caveats (unverified — see `bunny-upload-endpoint` § Assumptions)

This endpoint's bunny-facing behavior is tested; its **iOS-facing** surface is frozen but unverified
until the iOS rewiring follow-up: OPTIONS fallback on a custom origin, which `2xx` the background
uploader accepts, and whether the largest Live-Photo paired-video stays within the 30 s CPU budget /
any undocumented wall-clock timeout (fix if it bites: server-side resumable uploads).
