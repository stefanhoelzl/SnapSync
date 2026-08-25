# api/ — SnapSync backend (bunny Edge Scripting)

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

Disjoint key namespaces in one zone (an `eventId`/`deviceId` is a UUID, never a literal label, so
nothing collides). The private data (below) is joined by one PUBLIC `site/` prefix holding the built
browser-facing site (capability `web-site`), which the api proxies (`serveSiteObject`); the nightly
sweep is prefix-scoped and never touches it:

```
events/<eventId>/metadata.json                 event marker / registry record { eventId, name, createdAt, startsAt, endsAt, capacity }
events/<eventId>/devices/<deviceId>.json       per-event device manifest — ACTIVE member (projected assets)
events/<eventId>/devices/<deviceId>.left.json  per-event device manifest — DEPARTED (left; still in the union)
files/devices/<deviceId>/<filename>            a device's raw uploaded photo/resource byte objects
devices/<deviceId>.json                        a device's config { pushToken: { kind, token, env } } (NOT a file)
site/index.html · site/join/index.html · site/_astro/*   the Astro build (public; mirror-deployed by site-deploy.yml)
```

An event **exists** iff its marker `events/<eventId>/metadata.json` is present. Bunny's Edge Storage
API has no `HEAD`, so existence is a small `GET` of the marker. The **byte store is
device-partitioned and event-independent** — a resource is uploaded once under its device's
namespace and linked into any number of events by reference (the per-event manifest). The device id
is self-asserted (possession of the UUID is the capability); App Attest is the noted hardening path.

Every event is **bounded** (capability `event-limits`), along two INDEPENDENT axes:

- the capture **window** — the creator's `[startsAt, endsAt]`, at most **30 days** long (`400`
  otherwise; absent `endsAt` falls back to the maximum). It bounds only which photos may be
  **uploaded** and closes nothing: joining is never refused on time, so a guest who scans days late
  still contributes the in-window photos still on their phone.
- the **lifetime** — `lifetimeSeconds` (30 days), stamped onto the write-once marker as a DURATION.
  The delete-by is DERIVED per read as `max(createdAt, startsAt) + lifetimeSeconds`: anchoring at
  the later of the two keeps a back-dated event from being born expired and a created-early one from
  dying inside its own window. Stamping the duration rather than the instant keeps the per-event
  value immutable against a config change while leaving the anchor policy correctable without
  rewriting stored markers.

`capacity = 10` (ever-enrolled, active ∪ departed) is the only refusal a route makes, `409`.

The lifecycle is **binary**: the event exists, or the nightly sweep (capability `scheduled-cleanup`)
has deleted it. No route reaps on touch, even past the delete-by — which is what makes a `404` a
REAL deletion, and therefore safe as one of the two witnesses a client's self-leave requires
(capability `leave-event`). The sweep reclaims an event past its delete-by (the guarantee) or one
that is EMPTY — ever joined, no active member left (opportunistic: a leave whose `DELETE` never
landed keeps a manifest active, so an abandoned event may never empty). A marker missing
`startsAt`/`endsAt`/`capacity` is `gone`: `404`, and the sweep deletes it.

## Contract

> **Versioned prefix (capability `backend-deployment`).** Every device-API route below is served
> under the prefix **`/api/v1`** — written that way here, and the one shape it answers at. The gate
> normalizes the `/api/vN` prefix before its closed-list checks, so the ungated `/api/v1/attest/*`
> set holds under it. The **web/link** routes (`/`, `/join`, the AASA) stay at the **root**, never
> under `/api/v1`. The routing is version-parametric: a future `/api/v2` is one more mount in
> `createApp`.

```
POST /api/v1/events
    body: {"name": "<name>", "startsAt": "<canonical instant>"}   (name trimmed, non-empty, ≤100 chars)
    body: … optional {"endsAt": "<canonical instant>"}  (strictly after startsAt, ≤30 days after it)
    →  bunny native PUT  events/<minted-uuid>/metadata.json   (stamps capacity + lifetimeSeconds)
    →  201 {eventId, name, createdAt, startsAt, endsAt, capacity, deletesAt}   | 400 | 502

GET  /api/v1/events/<eventId>
    →  200 {eventId, name, createdAt, startsAt, endsAt, capacity, deletesAt}
       (`deletesAt` DERIVED per response, never stored; a legacy/incomplete marker is never served)
       | 404 when never created OR already swept | 502 on a non-404 marker read failure

PUT  /api/v1/files/devices/<deviceId>/<filename>          (byte upload — UNGATED, no marker read)
    body: raw resource bytes (streamed, never buffered)
    →  bunny native PUT  https://<host>/<zone>/files/devices/<deviceId>/<filename>
       header  AccessKey: <storage-zone password>
    →  201 on confirmed store | 502 on any upstream error/abort
    OPTIONS → 204 (no resumable-upload advertised → the iOS uploader falls back to a plain PUT)

GET  /api/v1/files/devices/<deviceId>                     (per-device raw listing — UNGATED)
    →  single bunny native LIST of  files/devices/<deviceId>/
    →  200 [ {filename, size, url}, … ]  (200 [] for an empty/unknown partition) | 502 on LIST failure
       url = a presigned S3 GET URL (below);  Cache-Control: no-store, no-cache, max-age=0

PUT  /api/v1/devices/<deviceId>                    (device config / push token — UNGATED by event)
    body: { pushToken: { kind: "apns", token, env } }  (streamed)     DEVICE-ID is the capability
    →  bunny native PUT  devices/<deviceId>.json   → 201 | 502   (last-write-wins; not a listed file)

POST /api/v1/events/<eventId>/notify                       (silent push to members — GATED on event existence)
    →  [gate] GET events/<eventId>/metadata.json  → absent? 404 | non-404 failure? 502
    →  LIST events/<eventId>/devices/  → per ACTIVE member (LWW): read devices/<id>.json → APNs silent push
    →  202 (bare)  |  502 only if the member LIST fails
       best-effort: members without a token are skipped; a per-token failure never fails the request
       fixed payload (content-available), ACTIVE members only (a departed <id>.left.json is skipped)

PUT  /api/v1/events/<eventId>/devices/<deviceId>   (device manifest — GATED on existence + event limits)
    body: full-state JSON device manifest (streamed)
    →  [gate] GET events/<eventId>/metadata.json  → absent? 404 (stream nothing) | non-404 failure? 502
       then LIST events/<eventId>/devices/ (known-vs-new + the capacity count):
       NEW device with ever-enrolled (active ∪ departed) ≥ capacity → 409  — the ONLY refusal; there is
       no time-based rejection, however long after endsAt the enrollment arrives
       (a KNOWN device — active or .left — always passes; leaving frees no slot; rejoin reuses its slot)
    →  bunny native PUT  events/<eventId>/devices/<deviceId>.json   → 201 | 502

DELETE /api/v1/events/<eventId>/devices/<deviceId>          (LEAVE — GATED on event existence)
    →  [gate] GET events/<eventId>/metadata.json  → absent? 404 | non-404 failure? 502
    →  (1) rename active manifest → events/<eventId>/devices/<deviceId>.left.json (copy → FRESH ts, then delete active)
       (2) if NO active member remains (last-write-wins over the devices/ listing): delete the events/<eventId>/ tree
       (3) per freed device with NO manifest in any surviving event: delete files/devices/<id>/* + devices/<id>.json
    →  200  |  502 on any transport failure
       idempotent + leak-safe (write .left.json BEFORE deleting .json; every delete of an absent object is a no-op)
       membership is last-write-wins: a departed <id>.left.json stays in the UNION but is skipped by NOTIFY & the reap

GET  /api/v1/events/<eventId>/files                        (event-wide UNION — GATED on event existence)
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
- **Methods** — `POST /api/v1/events`, `GET /api/v1/events/<id>`, `GET /api/v1/files/devices/<id>`,
  `PUT`/`OPTIONS` on `/api/v1/files/devices/<id>/<name>`, `PUT /api/v1/events/<id>/devices/<id>`,
  `DELETE /api/v1/events/<id>/devices/<id>`, `GET /api/v1/events/<id>/files` (plus the
  `/api/v1/attest/*` issuers). Any other method or unmatched path → **`404`** (Hono's default — no
  `405`). Bad UUID / unsafe filename / invalid name → `400`.

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
                  per-device list + device-manifest write + event union + device config + event notify;
                  PLUS the static-site proxy (serveSiteObject → GET /, /join, /_astro/* from the storage
                  site/ prefix, capability web-site) and the AASA. Key helpers (markerKey,
                  deviceManifestKey/Dir, byteKey, deviceDir, deviceConfigKey), the existence gates,
                  presignDownloadUrl(), and readPushToken() (notify fan-out). The landing + /join pages
                  are NOT here — they are built by the sibling site/ Astro module.
src/apns.ts       createApnsSender(config, fetch) → { sendSilent(tokens) }: ES256 provider-JWT signing
                  (WebCrypto, memoized) + a silent HTTP/2 push per token; per-token best-effort outcomes.
src/validators.ts validateUUID / validateFilename → boolean; validateEventName(raw) → trimmed | null
src/config.ts     the 7 non-secret SOURCE CONSTANTS (zone/host/S3 region+host/APNs kid+iss+topic) +
                  readConfig(env) → Config, which reads only the 2 SECRETS and THROWS if either is
                  missing/blank. Env is never consulted for a constant.
src/main.ts       Edge Scripting entry: reads config at startup, serves createApp(...).fetch via the SDK
src/dev/*.ts      DEV-ONLY, never imported by main.ts (so `deno bundle` cannot reach it): the local rig —
                  fs-storage.ts (a FetchLike answering bunny's native Storage API off a directory),
                  config.ts (source constants + dev secrets, s3Host pointed at the rig), serve.ts (the
                  second entry: presigned-path serving + the fallback bearer), tunnel.ts (cloudflared)
test/*.test.ts    Deno tests (app via app.request(), upstream fetch + config injected)
test/dev/         the fs shim's contract test — pins it to the same bunny assumptions the mocks encode
                  (proves shim ≡ mocks, NOT that either matches bunny; nothing here ever has)
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
deno task test          # full suite; upstream bunny mocked, config injected → offline
deno task lint
deno task check         # type-checks src/ AND src/dev/
deno fmt --check

# The LOCAL RIG — the real app over a FILESYSTEM store, touching no bunny zone:
deno task dev:local     # 127.0.0.1:8080 — the curl loop
deno task dev:tunnel    # + a cloudflared quick tunnel, so a physical device can reach it
```

`deno task test` carries `--allow-read --allow-write` for the dev shim's contract test.
**`--allow-net` is deliberately absent** — that absence is what guarantees no test can reach the
real zone: a network call fails as a permission error rather than becoming a live request.

**The local rig** (`src/dev/`, dev infrastructure — non-gating, no spec) composes the **same**
`createApp({ config, db, fetch })` this file documents, with a filesystem `fetch` in place of bunny
and a `Config` built from the same source constants — so event limits, the attest TTL, and every
route behave exactly as deployed. Keys map 1:1 onto `api/.localstore/objects/<key>`, so `find` is
the verification oracle for the BYTES.

**Everything relational lives in a real SQLite file** at `api/.localstore/api.db` (capability
`database`), opened through Deno's built-in `node:sqlite` — no credential, no network, and the same
statements the deployed store runs, so cascades, the conditional capacity insert and the atomic
publish behave here as they do in production. Inspect it with any `sqlite3`. It lives INSIDE the
store directory on purpose, so `rm -rf api/.localstore` clears both halves at once: clearing the
objects and keeping the rows leaves a rig whose events exist but whose photos do not, which reads as
"downloads are broken" with no error anywhere. Reset is still `rm -rf api/.localstore`.

⚠️ **A `filesystem` deployment declares no database credentials at all.** That is not an oversight —
it is what makes it structurally impossible for a dev run to address the PRODUCTION store, which
holds real events and, unlike the storage zone, offers no per-object blast radius to fall back on.
The attestation gate stays fully on (a bad token still `401`s), but a request arriving with **no**
`authorization` header gets a dev token attached, so bare `curl` works. Presigned download URLs are
minted with the real production shape, pointed at the rig. Full runbook — including pointing a
device build at it, and the mandatory device reset (`POST /device/reset` over the control channel)
when crossing backends — is in the root `CLAUDE.md`.

`src/dev/` **cannot ship**: `deno bundle src/main.ts` roots the deployed bundle at `main.ts`, which
imports nothing under it.

Running `src/main.ts` directly still targets the **real** `snap-sync-dev` zone **and the real
database**, and needs every secret; prefer the rig unless you specifically mean to hit production:

```bash
BUNNY_STORAGE_ACCESS_KEY=k APNS_PRIVATE_KEY="$(cat AuthKey.p8)" \
  ATTEST_TOKEN_KEY=t BUNNY_DATABASE_URL=… BUNNY_DATABASE_AUTH_TOKEN=… \
  deno run --allow-net --allow-env src/main.ts
```

Provision the APNs **Auth Key** (`.p8`) for team `E9Z8BADH58` once (App Store Connect API / portal)
and set `APNS_PRIVATE_KEY` (the PEM) as an Edge Script **secret**. `APNS_KEY_ID` / `APNS_TEAM_ID` /
`APNS_TOPIC` are source constants — update `config.ts` if the key is ever rotated.

## Deploy

CI deploys via `.github/workflows/api-deploy.yml` (path-scoped to `api/**`, **gated on green
`deno fmt`/`lint`/`check`/`test`**) using `BunnyWay/actions/deploy-script`. It ships **code only** —
it configures nothing (see above). Provision once:

> **The browser-facing site is a SEPARATE deploy.** `.github/workflows/site-deploy.yml` builds the
> Astro `site/` module (under Node) and **mirror-deploys** it to the storage `site/` prefix
> (`site/scripts/deploy.mjs` — upload new, delete stale, never clear-first), authenticating with
> **only the storage-zone password** (`BUNNY_STORAGE_ACCESS_KEY`), never the account key. The api
> Edge Script proxies that prefix, so the routing lives in the bundle as source-owned code — **no
> pull-zone edge rules**. Capability `web-site`.

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
> post-deploy probe with it (`GET /api/v1/events/<uuid>` must answer `404`, not a bodyless `400`).

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
