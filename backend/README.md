# backend/ — SnapSync backend (bunny Edge Scripting)

A **streaming proxy** (Deno/TypeScript + **Hono**) on **bunny Edge Scripting** — the one runtime.
The device-facing origin is the custom domain **`snapsync.stho.net`** (our Bunny DNS zone, Let's
Encrypt cert), `CNAME`'d to the bunny **pull zone** that fronts the Edge Script. It mints events,
streams photo bytes from the iOS background-upload extension straight into a bunny **native**
Storage zone, records per-event device manifests, and serves per-device and event-wide listings.
Downloads are **presigned S3 GET URLs** the device fetches directly from bunny's S3 endpoint — no
download proxy, no on-device SigV4, no per-resource mint round-trip.

> A CDN pull zone sits between every device and this script. Anything the device depends on must
> hold **as observed through the pull zone**, not merely at the origin — it may answer `OPTIONS`
> itself, and it caches on the origin's `Cache-Control` (which is why the listing routes send
> `no-cache`, not just `no-store`; bunny documents the former, never the latter). Deno Deploy, the
> retired runtime, had no CDN in front and so could not exhibit this class of behavior at all.

Authoritative contracts: `openspec/specs/event-creation`, `openspec/specs/bunny-upload-endpoint`,
`openspec/specs/bunny-list-endpoint`, `openspec/specs/device-manifest`,
`openspec/specs/event-leave-endpoint`, and `openspec/specs/backend-deployment` (which owns the
deployment pipeline **and** the configuration contract — the former `backend-config` capability
folded into it). Rationale lives in each spec's `## Purpose` and its `Decision record:` pointer into
`openspec/changes/archive/`.

## Storage layout

Three disjoint key namespaces in one zone (an `eventId`/`deviceId` is a UUID, never a literal label,
so nothing collides):

```
events/<eventId>/metadata.json                 event marker / registry record { eventId, name, createdAt, startsAt, endsAt, capacity }
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

Every event is **bounded** (capability `event-limits`): `POST /events` stamps
`endsAt = startsAt +
30 days` and `capacity = 10` (source constants in `config.ts`) onto the
write-once marker, and every event-scoped route classifies the lifecycle from the marker + clock
before serving — **live** (`now <= endsAt`: joins under the cap, full sync) → **grace** (1 day:
joins closed with `410`, members keep full sync so late uploads still land) → **expired** (first
touch runs the lazy reap: silent-push the active members, delete manifests + GC'd bytes/configs,
delete the marker LAST — then `404`, indistinguishable from never-created; no tombstone, no
scheduler). A legacy marker missing the limit fields is expired by definition and reaped on touch.

## Contract

> **Versioned prefix (capability `backend-deployment`).** Every device-API route below is served
> under the canonical prefix **`/api/v1`** (e.g. `POST /api/v1/events`,
> `GET /api/v1/attest/challenge`) **and** — for a grace period — at the **bare** path shown here as
> a **deprecated alias**, so already-installed apps (device-facing host baked at compile time, not
> force-updatable) keep working. The paths are written bare below; read each as also available under
> `/api/v1`. The gate normalizes the `/api/vN` prefix, so the ungated `/attest/*` set holds under
> both forms. The **web/link** routes (`/`, `/join`, the AASA) stay at the **root**, never under
> `/api/v1`. The routing is version-parametric: a future `/api/v2` is one more mount. Ending the
> grace period is deleting the single bare-alias mount in `createApp`.

```
POST /events
    body: {"name": "<name>", "startsAt": "<canonical instant>"}   (name trimmed, non-empty, ≤100 chars)
    →  bunny native PUT  events/<minted-uuid>/metadata.json
    →  201 {eventId, name, createdAt, startsAt, endsAt, capacity}   (id + limits minted server-side) | 502

GET  /events/<eventId>
    →  200 the marker (all fields — an expired/legacy marker is never served)
       | 404 when never created OR expired-and-reaped | 502 on a non-404 marker read failure

PUT  /files/devices/<deviceId>/<filename>                 (byte upload — UNGATED, no marker read)
    body: raw resource bytes (streamed, never buffered)
    →  bunny native PUT  https://<host>/<zone>/files/devices/<deviceId>/<filename>
       header  AccessKey: <storage-zone password>
    →  201 on confirmed store | 502 on any upstream error/abort
    OPTIONS → 204 (no resumable-upload advertised → the iOS uploader falls back to a plain PUT)

GET  /files/devices/<deviceId>                            (per-device raw listing — UNGATED)
    →  single bunny native LIST of  files/devices/<deviceId>/
    →  200 [ {filename, size, url}, … ]  (200 [] for an empty/unknown partition) | 502 on LIST failure
       url = a presigned S3 GET URL (below);  Cache-Control: no-store, no-cache, max-age=0

PUT  /devices/<deviceId>                           (device config / push token — UNGATED by event)
    body: { pushToken: { kind: "apns", token, env } }  (streamed)     DEVICE-ID is the capability
    →  bunny native PUT  devices/<deviceId>.json   → 201 | 502   (last-write-wins; not a listed file)

POST /events/<eventId>/notify                              (silent push to members — GATED on event existence)
    →  [gate] GET events/<eventId>/metadata.json  → absent? 404 | non-404 failure? 502
    →  LIST events/<eventId>/devices/  → per ACTIVE member (LWW): read devices/<id>.json → APNs silent push
    →  202 (bare)  |  502 only if the member LIST fails
       best-effort: members without a token are skipped; a per-token failure never fails the request
       fixed payload (content-available), ACTIVE members only (a departed <id>.left.json is skipped)

PUT  /events/<eventId>/devices/<deviceId>          (device manifest — GATED on existence + event limits)
    body: full-state JSON device manifest (streamed)
    →  [gate] GET events/<eventId>/metadata.json  → absent? 404 (stream nothing) | non-404 failure? 502
       expired? reap → 404;  then LIST events/<eventId>/devices/ (known-vs-new + the capacity count):
       NEW device in grace → 410 | NEW device with ever-enrolled (active ∪ departed) ≥ capacity → 409
       (a KNOWN device — active or .left — passes both; leaving frees no slot; rejoin reuses its slot)
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
       Cache-Control: no-store, no-cache, max-age=0
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
  `GET /events/<id>/files` (plus the `/attest/*` issuers) — each served under both `/api/v1/…` and
  the bare path (deprecated alias). Any other method or unmatched path → **`404`** (Hono's default —
  no `405`). Bad UUID / unsafe filename / invalid name → `400`.

> **Deployment invariant.** The storage `HOST` constant MUST be the storage zone's **main** region
> host (where writes land), never a replica endpoint. Bunny replicates asynchronously; reads from
> the main region are read-after-write consistent, so a just-created marker is visible to the
> immediately following join/list/upload. A replica host could lag and `404` a fresh event.
>
> The **leave** cascade depends on this too, and more sharply: its reap decision (is any active
> member left?) and its GC reference-check (does a freed device appear in another event?) LIST the
> devices directories, and a stale replica read could miss a concurrent rejoin's fresh `<id>.json`
> and reap an event out from under an active device. Every other failure mode of leave is a harmless
> orphan; this is the one that would delete in-use data — so the reap MUST read the main region.
> Never point the storage `HOST` constant at a replica.

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
src/config.ts     the 7 non-secret SOURCE CONSTANTS (zone/host/S3 region+host/APNs kid+iss+topic) +
                  readConfig(env) → Config, which reads only the 2 SECRETS and THROWS if either is
                  missing/blank. Env is never consulted for a constant.
src/main.ts       Edge Scripting entry: reads config at startup, serves createApp(...).fetch via the SDK
test/*.test.ts    Deno tests (app via app.request(), upstream fetch + config injected)
```

## Configuration — 7 source constants, 2 env secrets

**Non-secret config lives in `src/config.ts`, not in the environment. Source wins: the environment
is not consulted for any of it**, so a stale platform variable cannot override git.

| Source constant | Value                        | Meaning                                                                 |
| --------------- | ---------------------------- | ----------------------------------------------------------------------- |
| `ZONE`          | `snap-sync-dev`              | storage zone name (also the S3 Access Key ID + bucket)                  |
| `HOST`          | `storage.bunnycdn.com`       | native Storage host (DE/Falkenstein) — **main region, never a replica** |
| `S3_REGION`     | `de`                         | S3 region — used only to presign download URLs                          |
| `S3_HOST`       | `de-s3.storage.bunnycdn.com` | bunny S3 endpoint — the presigned-URL origin                            |
| `APNS_KEY_ID`   | the `.p8` Key ID             | the provider-JWT `kid`                                                  |
| `APNS_TEAM_ID`  | `E9Z8BADH58`                 | the provider-JWT `iss`                                                  |
| `APNS_TOPIC`    | `app.snapsync`               | the `apns-topic` header (the app bundle id)                             |

Every one of these is a **public fact** — the team id and bundle id ship inside every IPA; the key
id rides in the JWT header Apple receives. Committing them exposes nothing.

| Env secret                 | Meaning                                                                                      |
| -------------------------- | -------------------------------------------------------------------------------------------- |
| `BUNNY_STORAGE_ACCESS_KEY` | storage-zone **password** (the `AccessKey`; also the S3 secret; **not** the account API key) |
| `APNS_PRIVATE_KEY`         | the APNs Auth Key `.p8` **PEM contents** (not a path) — ES256-signs the provider JWT         |

`main.ts` reads the two secrets once at startup via `readConfig(Deno.env.toObject())`, which
**throws** on either being missing/blank → a misconfigured deployment **fails to boot** (fail-closed
at deploy, never a mis-targeted upload and never a blank/unsignable download URL). The validated
`Config` is injected into the app, so the request handlers have no configuration path. **No secret
is in source.**

> **Why config is in source.** Bunny issues **no scoped API key**: writing an Edge Script's
> environment variables requires the full-access **account** key, which also owns the storage zone
> holding every user's photos and the `stho.net` DNS zone. CI therefore holds only the
> _script-scoped deploy key_ — and so **CI can ship code but cannot ship config**. That gap is not
> theoretical: it is exactly how this backend died. On 2026-07-02 a change added two required env
> vars, set them on the (then-active) Deno Deploy runtime only, and left the bunny script
> fail-closed at boot for two weeks — with CI green throughout, because `POST /code` + `/publish`
> succeed whether or not the script boots.
>
> Source-owned config closes it structurally: **a new non-secret value ships in the same bundle as
> the code that reads it**, so it cannot be forgotten. The two secrets are exempt because they are
> genuine credentials and change ~never. Do not "simplify" this by giving CI the account key — that
> trades a config-drift bug for a blast radius over every user's photos.

## Develop & test

```bash
deno task test          # full suite; upstream bunny mocked, config injected → offline, no perms
deno task lint
deno fmt --check
# run locally (the SDK binds 127.0.0.1:8080 when no Edge Scripting runtime is present):
BUNNY_STORAGE_ACCESS_KEY=k APNS_PRIVATE_KEY="$(cat AuthKey.p8)" \
  deno run --allow-net --allow-env src/main.ts
```

Two env vars, because everything else is a source constant. Note this targets the **real**
`snap-sync-dev` zone. To point a local run somewhere else, construct a `Config` literal and pass it
to `createApp({ config, fetch })` directly — that is the injection seam the tests use — rather than
reintroducing an env override.

Provision the APNs **Auth Key** (`.p8`) for team `E9Z8BADH58` once (App Store Connect API / portal)
and set `APNS_PRIVATE_KEY` (the PEM) as an Edge Script **secret**. `APNS_KEY_ID` / `APNS_TEAM_ID` /
`APNS_TOPIC` are source constants — update `config.ts` if the key is ever rotated.

## Deploy

CI deploys via `.github/workflows/backend-deploy.yml` (path-scoped to `backend/**`, **gated on green
`deno fmt`/`lint`/`check`/`test`**) using `BunnyWay/actions/deploy-script`. It ships **code only** —
it configures nothing (see above). Provision once:

1. With the Bunny **account API key**: an **S3-enabled** Storage zone (DE), and the Edge Scripting
   app (record its **script id** and a **deploy key**). Set the two **secrets** on the Edge Script.
2. Add GH secrets `BUNNY_SCRIPT_ID` and `BUNNY_DEPLOY_KEY`. The deploy key is script-scoped (it can
   push code to that one script and nothing else); the **account API key is never in CI**.

The device-facing origin is the custom domain **`snapsync.stho.net`** — a `CNAME` in our `stho.net`
Bunny DNS zone pointing at the pull zone that fronts the Edge Script. Because we own the name,
swapping the runtime that answers it stays a **DNS repoint, never a new iOS build** (the
compile-time `BACKGROUND_UPLOAD_URL_BASE` names this domain, not a provider hostname). That property
is what let Deno Deploy be retired without a TestFlight round — keep it.

> **No boot probe.** CI cannot tell a booting script from a dead one. Prevention (config in source)
> replaces detection here; if you ever reintroduce platform-side _required_ config, reintroduce a
> post-deploy probe with it (`GET /events/<uuid>` must answer `404`, not a bodyless `400`).

> **bunny is load-bearing.** There is no second runtime and no warm standby. A bunny outage is a
> SnapSync outage; recovery means standing a runtime back up from this bundle and repointing DNS.
> The engine retries forever, so uploads are **delayed, never lost**.

## Edge Scripting limits worth knowing

- **30 s CPU** per request — CPU, _not_ wall clock, so a pure I/O pass-through stream is cheap. The
  killers would be buffering the body (`request.bytes()` → blows the **128 MB** isolate) or per-byte
  CPU (hashing/transform). The proxy does neither.
- **No documented wall-clock timeout for the script** — but the **pull zone in front has a 60 s
  request timeout** (`http_timeout`). That, not the CPU budget, is the real ceiling on a large
  Live-Photo paired video over a slow link. If it ever bites, the fix is **server-side resumable
  uploads**.
- 10 MB script size, 500 ms startup, 50 subrequests, 128 env vars.
