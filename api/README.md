# api/ — SnapSync backend (bunny Edge Scripting)

A **streaming proxy** (Deno/TypeScript + **Hono**) on **bunny Edge Scripting** — the one runtime.
The device-facing origin is the custom domain **`snapsync.stho.net`** (our Bunny DNS zone, Let's
Encrypt cert), `CNAME`'d to the bunny **pull zone** that fronts the Edge Script. It mints events,
streams photo bytes from the iOS background-upload extension straight into a bunny **native**
Storage zone, records what each device shares in a **relational store**, and serves per-device and
event-wide listings. Downloads are **presigned S3 GET URLs** the device fetches directly from
bunny's S3 endpoint — no download proxy, no on-device SigV4, no per-resource mint round-trip.

> A CDN pull zone sits between every device and this script. Anything the device depends on must
> hold **as observed through the pull zone**, not merely at the origin — it may answer `OPTIONS`
> itself, and it caches on the origin's `Cache-Control` (which is why the listing routes send
> `no-cache`, not just `no-store`; bunny documents the former, never the latter). Deno Deploy, the
> retired runtime, had no CDN in front and so could not exhibit this class of behavior at all.

Authoritative contracts: `openspec/specs/api-endpoints` (the route table — bodies, statuses, and
which routes are gated), `openspec/specs/database` (the relational store),
`openspec/specs/device-attestation` (the gate), `openspec/specs/event-limits`,
`openspec/specs/leave-event`, `openspec/specs/scheduled-cleanup`, and
`openspec/specs/backend-deployment` + `openspec/specs/deployment-configuration` (the pipeline and
the configuration contract). Rationale lives in each spec's `## Purpose` and its `Decision record:`
pointer into `openspec/changes/archive/`.

## Where state lives

**The database holds the facts; storage holds the bytes.** Five tables (capability `database`),
reached through the one narrow `Db` port in `db.ts`:

```
events        id, name, created_at, starts_at, ends_at, capacity, lifetime_seconds
memberships   (event_id → events, device_id), state ∈ {active, departed}, joined_at
event_assets  (event_id, device_id → memberships), asset_id, creation_date
resources     (device_id, key), asset_id, role, content_type, filename, uploaded
devices       device_id, created_at, attest_key/env/attested_at/token_expires_at, push_* (nullable)
```

`memberships` and `event_assets` cascade from `events`, so deleting an event removes both in one
statement. **`resources` is deliberately outside that chain**: the byte upload route addresses a row
from the URL path alone (`/api/v1/files/devices/<id>/<filename>`), which carries no event — a row
bearing `event_id` could not be written by the route that knows a byte landed. That is also what
lets one uploaded byte serve two events during a switch without being stored twice.

An event **exists** iff its `events` row does — the whole existence gate, and what makes a `404` a
sealed absence a client's self-leave can act on. A **`devices` row exists iff that device has
attested**; the ordering is forced by the gate below, not chosen.

The storage zone now holds only bytes and the public site:

```
files/devices/<deviceId>/<filename>            a device's raw uploaded photo/resource byte objects
site/index.html · site/join/index.html · site/_astro/*   the Astro build (public; mirror-deployed by site-deploy.yml)
```

> **Legacy objects.** `events/<id>/metadata.json`, `events/<id>/devices/<id>.json[.left]`,
> `devices/<id>.json` and `devices/<id>.attest.json` may still be present. **Nothing reads or writes
> them.** The first three were left in place as the relational migration's rollback path
> (`changes/archive/2026-08-25-record-uploads-in-database`, D13) and reclaiming them is a later
> change's job, and the attestation objects now join them: they are NOT deleted by the cutover,
> because the previously-deployed bundle is still reading them to renew while the new one rolls out.
> The nightly sweep does not touch any of them.

Every event is **bounded** (capability `event-limits`), along two INDEPENDENT axes:

- the capture **window** — the creator's `[startsAt, endsAt]`, at most **30 days** long (`400`
  otherwise; absent `endsAt` falls back to the maximum). It bounds only which photos may be
  **uploaded** and closes nothing: joining is never refused on time, so a guest who scans days late
  still contributes the in-window photos still on their phone.
- the **lifetime** — `lifetime_seconds` (30 days), stamped onto the write-once row as a DURATION.
  The delete-by is DERIVED per read as `max(createdAt, startsAt) + lifetimeSeconds`: anchoring at
  the later of the two keeps a back-dated event from being born expired and a created-early one from
  dying inside its own window. Stamping the duration rather than the instant keeps the per-event
  value immutable against a config change while leaving the anchor policy correctable without
  rewriting stored rows.

`capacity = 10` (ever-enrolled, active ∪ departed) is the only refusal a route makes, `409`. It is
enforced by **one conditional statement**, so concurrent first enrollments cannot overshoot —
measured: ten devices racing for three slots enrolled ten under read-then-write and exactly three
here.

The lifecycle is **binary**: the event exists, or the nightly sweep (capability `scheduled-cleanup`)
has deleted it. No route reaps on touch, even past the delete-by — which is what makes a `404` a
REAL deletion. The sweep reclaims an event past its delete-by (the guarantee) or one that is EMPTY —
ever joined, no active member left (opportunistic: a leave whose `DELETE` never landed keeps a
membership active, so an abandoned event may never empty).

## The gate

**Every route requires a device token** (capability `device-attestation`) — a backend-minted,
HMAC-signed bearer credential obtainable only by completing App Attest. The exceptions are a
**closed list**: the three `/api/v1/attest/*` issuers, `OPTIONS` on any path, `GET`/`HEAD` on
exactly `/`, `/join` and `/.well-known/apple-app-site-association`, and `GET`/`HEAD` on
`/api/v1/events/<id>` and `/api/v1/events/<id>/files` (the no-app download page holds no
attestation; possession of the `eventId` is the read capability). Every non-`GET` method on those
event paths stays gated.

**Verifying a token touches nothing** — one HMAC comparison, no storage read, no Apple call. That is
load-bearing: verification runs on the streaming byte-upload hot path, where a round-trip per
resource would be paid on every photo. A route that additionally needs the device's _record_ reads
it itself, after the gate has passed.

## Contract

> **Versioned prefix (capability `backend-deployment`).** Every device-API route below is served
> under the prefix **`/api/v1`** — written that way here, and the one shape it answers at. The gate
> normalizes the `/api/vN` prefix before its closed-list checks, so the ungated `/api/v1/attest/*`
> set holds under it. The **web/link** routes (`/`, `/join`, the AASA) stay at the **root**, never
> under `/api/v1`. The routing is version-parametric: a future `/api/v2` is one more mount in
> `createApp`.

```
GET  /api/v1/attest/challenge                              (UNGATED — it issues the input to attestation)
    →  200 {challenge}   a stateless, HMAC-signed, time-bounded nonce. Writes NOTHING.

POST /api/v1/attest/token                                  (UNGATED — self-authenticating)
    body: {deviceId, keyId, attestation, challenge}
    →  verifies chain → Apple's root, nonce, app-id hash, counter, aaguid
    →  records the attested key + the minted token's expiry as the device's row  (the ENROLMENT)
    →  201 {token}  | 401 on any failed check or a stale challenge | 502 if the record cannot be written
       PERSISTS BEFORE MINTING: a token issued against a record we failed to write is a credential
       nothing knows about.

POST /api/v1/attest/renew                                  (UNGATED — self-authenticating)
    body: {deviceId, assertion, challenge}                 (no keyId — the key is found by deviceId)
    →  verifies a local Secure-Enclave ASSERTION against the stored key; no Apple round-trip
    →  advances the recorded token expiry, THEN mints
    →  201 {token}  | 401 when no attestation is on file, or the assertion is refused | 502 on a read
       or write failure (absence and "could not ask" are DIFFERENT answers — 401 sends the device
       down a full, throttled re-attestation, so a database blink must read as retry-me)

POST /api/v1/events
    body: {"name": "<name>", "startsAt": "<canonical instant>"}   (name trimmed, non-empty, ≤100 chars)
    body: … optional {"endsAt": "<canonical instant>"}  (strictly after startsAt, ≤30 days after it)
    →  INSERT events  (stamps capacity + lifetime_seconds)
    →  201 {eventId, name, createdAt, startsAt, endsAt, capacity, deletesAt}   | 400 | 502

GET  /api/v1/events/<eventId>                              (UNGATED — GET/HEAD only)
    →  SELECT events
    →  200 {eventId, name, createdAt, startsAt, endsAt, capacity, deletesAt}
       (`deletesAt` DERIVED per response, never stored)
       | 404 when never created OR already swept | 502 on a read failure

PATCH /api/v1/events/<eventId>                             (RENAME — capability `event-rename`)
    body: {"name": "<name>"}
    →  UPDATE events SET name  — the ONLY write to an existing event row; every other column is
       write-once, which is why the statement is spelled out in one place rather than composed
    →  200 | 400 | 404 | 502            No ownership check: the device-token gate is the whole authorization.

PUT  /api/v1/files/devices/<deviceId>/<filename>           (byte upload — GATED by token, reads no event)
    body: raw resource bytes (streamed, never buffered)
    →  bunny native PUT  https://<host>/<zone>/files/devices/<deviceId>/<filename>
       header  AccessKey: <storage-zone password>
    →  then BEST-EFFORT: record the resource row as uploaded (a failure never changes the response)
    →  201 on confirmed store | 502 on any upstream error/abort
    OPTIONS → 204 (no resumable-upload advertised → the iOS uploader falls back to a plain PUT)
       The device id stays self-asserted: the token proves a genuine app instance, NOT ownership of
       the partition (a stated non-goal — the UUID is the capability).

GET  /api/v1/files/devices/<deviceId>                      (per-device listing)
    →  SELECT resources WHERE uploaded = 1
    →  200 [ {filename, url}, … ]  (200 [] for a device with nothing stored) | 502
       `filename` is the stored object KEY (what the rejoin reconciler matches its ledger against),
       not the human capture name;  url = a presigned S3 GET URL (below)
       Cache-Control: no-store, no-cache, max-age=0

PUT  /api/v1/devices/<deviceId>                            (push registration — DEVICE-ID is the capability)
    body: { pushToken: { kind: "apns", token, env } }   or  {} / {"pushToken": null} for an explicit absence
    →  UPDATE devices SET push_*   — an UPDATE, never an insert: a row is created only by attestation
    →  201 | 400 on a malformed pushToken | 502
    →  401 when the write affects NO ROW — the token verified, but the backend holds no attestation
       for this device. The shipped client recovers with no change: it drops the token, attests
       (which creates the row), and re-sends the registration when the new credential arrives.

POST /api/v1/events/<eventId>/notify                       (silent push to members — GATED on existence)
    →  [gate] SELECT events → absent? 404 | read failure? 502
    →  SELECT memberships WHERE state = 'active' → read each device's push token → APNs silent push
    →  202 (bare) | 502 only if the member read fails
       best-effort: members without a token are skipped; a per-token failure never fails the request
       fixed payload (content-available); DEPARTED members are skipped

PUT  /api/v1/events/<eventId>/devices/<deviceId>           (device manifest — GATED on existence + capacity)
    body: full-state JSON device manifest
    →  [gate] enrol via ONE conditional statement: a NEW device is refused 409 once `capacity`
       distinct device ids have ever enrolled (leaving frees no slot; a rejoin reuses its own).
       Capacity is the ONLY refusal — enrollment is never closed by time, however long after endsAt.
       A zero-row outcome is disambiguated by a follow-up read, never collapsed: 409 vs 404.
    →  ONE ATOMIC BATCH: membership → active; the membership's event_assets REPLACED with exactly
       what the body lists (an omitted asset is removed); each named resource upserted, with
       `uploaded` MONOTONE (an out-of-order publish cannot un-say an upload)
    →  201 | 400 | 404 | 409 | 502

DELETE /api/v1/events/<eventId>/devices/<deviceId>         (LEAVE — GATED on existence)
    →  [gate] SELECT events → absent? 404 | read failure? 502
    →  UPDATE memberships SET state = 'departed'
    →  200 | 502
       Idempotent and NON-DESTRUCTIVE: the assets are RETAINED, so the union keeps serving what the
       device shared. No reap here and no leave-time GC — when this was the last active member the
       event becomes EMPTY and the nightly sweep reclaims it.

GET  /api/v1/events/<eventId>/files                        (event-wide UNION — UNGATED, GET/HEAD only)
    →  [gate] SELECT events → absent? 404 | read failure? 502
    →  ONE query joining event_assets to resources, spanning active AND departed memberships
    →  200 [ {deviceId, assetId, creationDate, resources:[{role,contentType,key,filename,url}]} ]
       an asset naming an unrecorded resource is dropped (defense in depth); 200 [] for an empty event
       Cache-Control: no-store, no-cache, max-age=0

GET  /health                                               (ROOT-mounted, UNGATED, GET/HEAD only)
    →  reaches BOTH dependencies: SELECT 1 on the store, and a listing GET on the storage zone root
    →  200 {sha}  — plus `maintenance: true` ONLY while a deploy window is open
    →  503 (bare) when either dependency is unreachable — the cause is logged, not served
       Cache-Control: no-store, no-cache, max-age=0
       `sha` is the bundle's commit; the window field says which of a migrating deploy's TWO publishes
       of that commit is answering, which the sha alone cannot. ABSENT MEANS CLOSED: the only other
       thing that omits it is a bundle predating the flag, which was built before maintenance mode
       existed and is therefore serving — the same answer, so the collapse loses nothing.
```

> **The maintenance window (capability `backend-deployment`).** While the serving bundle carries the
> flag, **every route under `/api/` answers `503`** with `Retry-After` and the no-cache directives,
> touching neither storage nor the database — and it is answered **before** the token gate, so an
> unauthenticated request in the window gets `503`, not `401`. The match is the **`/api/` prefix**,
> never a list of routes: a list can be omitted from, so a future `/api/v2` would land ungated by
> nobody's decision. The **root** routes (`/`, `/join`, `/_astro/*`, the AASA, `/health`) keep
> serving — they read only the public `site/` prefix or nothing, and `/health` is how the deploy
> learns the window's state. Downloads are untouched by construction: presigned S3 URLs never reach
> this script.

- `eventId` / `deviceId` — **UUIDs** (Hono route params, validated). The UUID is the capability; the
  event row is consulted for **existence**, never authorization.
- `filename` — a single path segment; a literal or encoded `/` (`%2F`) or `..` is rejected (`400`)
  so keys stay flat. It is percent-encoded into the storage key and decoded back on listing, so the
  round-trip is byte-exact.
- **Faithful outcome** — a write returns `2xx` **only** when the store confirms it; any upstream
  error/abort → `502` (the iOS ledger retries). A read returns a `2xx` array **only** when every
  required query/LIST succeeds; otherwise `502`, never a partial result. Never a false success.
- **Presigned download URLs** — each listed object's `url` is an AWS **SigV4 presigned S3 GET URL**
  (path-style `https://<s3-host>/<zone>/files/devices/<deviceId>/<filename>?X-Amz-…`,
  `X-Amz-Expires` **7 days**), signed with the zone name as the S3 Access Key ID and the storage
  `AccessKey` as the secret. The query signature is the sole authorization — the device fetches the
  object directly from bunny's S3 endpoint with no credential. A **fresh** URL is minted on every
  list/union response (never cached), so each read yields one valid for a further 7 days. Both the
  per-device list and the union use the same builder, so their `url`s agree by construction.
- **Methods** — anything not listed above → **`404`** (Hono's default — no `405`). Bad UUID / unsafe
  filename / invalid name → `400`.

> **Deployment invariant.** The storage `host` MUST be the storage zone's **main** region host
> (where writes land), never a replica endpoint. Bunny replicates asynchronously; reads from the
> main region are read-after-write consistent.
>
> The relational store has the matching hazard, and it is handled where it bites: **read-your-writes
> is unmeasured from the edge**, so the nightly sweep's deletion decision runs inside an interactive
> transaction — which executes against the PRIMARY — rather than on whatever replica an ordinary
> read reaches. A stale read that missed a rejoin would otherwise let it delete a live event.
> Ordinary request handling may use ordinary reads. Any future change that lets a **destructive**
> operation act on an ordinary read must first re-confirm read-your-writes from the edge.

## Layout

```
src/app.ts        Hono app (createApp({config, db, fetch}) → routes): the attest issuers, create +
                  metadata + rename, byte upload, per-device list, device-manifest publish, leave,
                  event union, push registration, notify; PLUS the static-site proxy (serveSiteObject
                  → GET /, /join, /_astro/* from the storage site/ prefix, capability web-site) and the
                  AASA. Holds the token gate's closed list and presignDownloadUrl().
src/db.ts         the RELATIONAL STORE: the `Db` port, `SCHEMA` (the created shape), and every
                  statement — the capacity gate, the atomic publish, the union, the sweep's queries.
src/migrations.ts schema EVOLUTION: the ordered `MIGRATIONS` list + a `schema_migrations` record, and
                  `migrate()`. Bound to `SCHEMA` by migrations.test.ts, which asserts a store built
                  from each is identical. Never reached from main.ts — the edge does not migrate.
src/db-libsql.ts  the `Db` over bunny Database (libsql/web) — the deployed driver.
src/storage.ts    storage primitives for the BYTE store + the site prefix (key builders, LIST/GET/
                  PUT/DELETE), shared verbatim with the out-of-edge sweep so the two cannot drift.
src/attest.ts     App Attest verification (chain/nonce/app-id/counter/aaguid), the stateless
                  challenge, and the device token — mint, verify, and the ONE expiry derivation both
                  the token and its stored record come from.
src/lifecycle.ts  the event lifecycle rules (deleteByMs, eventIsStale), shared with the sweep.
src/apns.ts       createApnsSender(config, fetch) → { sendSilent(tokens) }: ES256 provider-JWT signing
                  (WebCrypto, memoized) + a silent HTTP/2 push per token; per-token best-effort outcomes.
src/validators.ts validateUUID / validateFilename / validateEventName / validateStartsAt
src/config.ts     readConfig(env) / readSweepConfig(env) / storageConfig() over the RESOLVED
                  deployment — non-secret values come from the generated deployment.ts, secrets from
                  the environment, and it THROWS on a missing secret.
src/deployment.ts GENERATED by scripts/resolve-deployment.py. Never committed, never hand-edited.
src/main.ts       Edge Scripting entry: reads config at startup, serves createApp(...).fetch via the SDK
src/scripts/      OUT-OF-EDGE programs (never bundled): sweep.ts (the nightly cleanup), migrate.ts
                  (applied by api-deploy before it publishes), probe.ts (the post-deploy boot probe).
                  A ONE-TIME data cutover is not here and must not be: it runs once, from a scratchpad,
                  through proton-env (capability `database`, "The migration mechanism is permanent; a
                  data cutover is throwaway").
src/dev/*.ts      DEV-ONLY, never imported by main.ts (so `deno bundle` cannot reach it): the local rig —
                  fs-storage.ts (a FetchLike answering bunny's native Storage API off a directory),
                  db-sqlite.ts (the `Db` over node:sqlite — also what the tests run), config.ts,
                  serve.ts (the second entry: presigned-path serving + the fallback bearer),
                  tunnel.ts (cloudflared)
test/*.test.ts    Deno tests (app via app.request(), upstream fetch + db + config injected)
test/support/db.ts  a migrated in-memory store + `enrolDevice` (the attestation row every other
                  device-scoped write now requires)
test/dev/         the fs shim's contract test — pins it to the same bunny assumptions the mocks encode
                  (proves shim ≡ mocks, NOT that either matches bunny; nothing here ever has)
```

## Configuration — a resolved deployment, plus secrets from the environment

**Non-secret config is not in the environment and not hand-written in source.** It is _resolved_
from the authored deployments under `deployments/` (capability `deployment-configuration`) by
`scripts/resolve-deployment.py`, which generates `src/deployment.ts`. The generated module is never
committed; every toolchain that needs a value — the bundle, the sweep, the migrations, the iOS build
— resolves the same deployment, so they cannot name different zones.

```
deployments/prod.json                      extends the components below; declares which env var
                                           supplies each secret
deployments/components/policy.json         eventCapacity, eventWindowMaxSeconds, eventLifetimeSeconds,
                                           attestTokenTtlSeconds
deployments/components/apple.json          bundleId, teamId, apnsKeyId, appStoreUrl, appAttestRootCa
deployments/components/storage-*.json      the storage kind/zone/host/s3Region + the access-key env ref
deployments/components/build.json          the build-scope values (sha, channel)
```

The **five secrets** are named by the deployment and read from the environment — never in source:

| Env secret                  | Meaning                                                                                      |
| --------------------------- | -------------------------------------------------------------------------------------------- |
| `BUNNY_STORAGE_ACCESS_KEY`  | storage-zone **password** (the `AccessKey`; also the S3 secret; **not** the account API key) |
| `APNS_PRIVATE_KEY`          | the APNs Auth Key `.p8` **PEM contents** (not a path) — ES256-signs the provider JWT         |
| `ATTEST_TOKEN_KEY`          | the HMAC key the device token and the attest challenge are signed with                       |
| `BUNNY_DATABASE_URL`        | the relational store's endpoint                                                              |
| `BUNNY_DATABASE_AUTH_TOKEN` | its access token                                                                             |

`main.ts` reads them once at startup via `readConfig(Deno.env.toObject())`, which **throws** on any
being missing/blank → a misconfigured deployment **fails to boot** (fail-closed at deploy, never a
mis-targeted upload and never a blank/unsignable download URL). The validated `Config` is injected
into the app, so the request handlers have no configuration path.

> **Why config is resolved rather than platform-set.** Bunny issues **no scoped API key**: writing
> an Edge Script's environment variables requires the full-access **account** key, which also owns
> the storage zone holding every user's photos and the `stho.net` DNS zone. CI therefore holds only
> the _script-scoped deploy key_ — and so **CI can ship code but cannot ship platform config**. That
> gap is not theoretical: it is exactly how this backend died. On 2026-07-02 a change added two
> required env vars, set them on the (then-active) Deno Deploy runtime only, and left the bunny
> script fail-closed at boot for two weeks — with CI green throughout, because `POST /code` +
> `/publish` succeed whether or not the script boots.
>
> Resolving closes it structurally: **a non-secret value ships in the same bundle as the code that
> reads it**, so it cannot be forgotten, and it stays diffable in git rather than pinned into one
> toolchain's source. The secrets are exempt because they are genuine credentials and change ~never.
> Do not "simplify" this by giving CI the account key — that trades a config-drift bug for a blast
> radius over every user's photos.

## Develop & test

```bash
deno task test          # full suite; upstream bunny mocked, db in-memory, config injected → offline
deno task lint
deno task check         # type-checks src/, src/dev/ AND src/scripts/
deno fmt --check

# The LOCAL RIG — the real app over a FILESYSTEM store and a real SQLite, touching no bunny zone:
deno task dev:local     # 127.0.0.1:8080 — the curl loop
deno task dev:tunnel    # + a cloudflared quick tunnel, so a physical device can reach it
```

`deno task test` carries `--allow-read --allow-write` for the dev shim's contract test.
**`--allow-net` is deliberately absent** — that absence is what guarantees no test can reach the
real zone or the real store: a network call fails as a permission error rather than becoming a live
request.

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

**The schema is applied by an ordered migration list**, not by the create statements. `db.ts`'s
`SCHEMA` is the current shape (readable, and what every statement in that file is written against);
`migrations.ts` holds the ordered history plus a `schema_migrations` record, and `migrate()` applies
only what a store has not yet seen. `migrations.test.ts` builds one store from each and asserts the
two agree, so they cannot drift. The deployed store is migrated by `api-deploy.yml` **before** it
publishes the bundle — a failed migration fails the run with the previous bundle still live. The rig
migrates on start, so a `.localstore` from an older rig is carried forward rather than needing a
wipe.

⚠️ **A migration MIGRATES its data; it does not drop it**, and one that narrows a constraint REFUSES
rather than discarding the rows it cannot carry — a refusal that fails the deploy with the previous
bundle still serving. Both are `database`'s contract and are gated by `migrations.test.ts`; read the
spec for the reasoning rather than this paragraph.

⚠️ **A device must ATTEST before any other device-scoped write**, and the rig fills that in the same
place it fills an absent token. A `devices` row is created only by `POST /api/v1/attest/token`, so
`PUT /api/v1/devices/<id>` (the push registration) answers `401` for a device the backend has never
seen attest. A physical device recovers from that by itself — the `401` drops its token, it attests
for real against the rig, and re-registers — so there it is one extra round-trip, not a failure.

**A SIMULATOR cannot recover, which is why the rig enrols it.** App Attest does not exist on the
simulator (`DCAppAttestService.isSupported` is false), so the app never attests, its refresh returns
early without trying, and the registration would `401` forever. The fallback bearer therefore enrols
the device the path names when it supplies the token — supplying a credential without the enrolment
it implies is half a credential. A caller carrying its own token is untouched, exactly as before.

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
and set `APNS_PRIVATE_KEY` as an Edge Script **secret**. `apnsKeyId` / `teamId` / `bundleId` live in
`deployments/components/apple.json` — update that component if the key is ever rotated.

## Deploy

CI deploys via `.github/workflows/api-deploy.yml` (path-scoped to `api/**` + `deployments/**`,
**gated on green `deno fmt`/`lint`/`check`/`test`**) using `BunnyWay/actions/deploy-script`. On
`main` it takes one of **two paths**, decided by `migrate.ts --pending`:

- **Nothing pending** (most deploys) — publish, probe. One publish, no window, exactly as before.
- **A migration is pending** — read the live commit from `/health` and prove its archived bundle is
  retrievable → publish the **maintenance bundle** → probe that the window is **open** → migrate →
  re-bundle and publish → probe that the window is **closed** → archive `bundle-<sha>`.

> **Why a window at all.** Migrate-before-publish is right and stays — it keeps new code off an old
> store — but it leaves an interval in which the **previous** bundle answers against the
> **migrated** store, with statements written for a shape that no longer exists. The window closes
> that interval.

> **Why the flag ships in the bundle.** CI holds only the script-scoped deploy key and **cannot
> write the Edge Script's environment**, so _which code is published_ is the only lever it has. The
> maintenance and real bundles are one commit resolved from two deployments —
> `deployments/prod.json` and `deployments/maintenance.json`, both extending
> `components/prod-core.json`, differing in exactly one key. That is also why `/health` reports the
> window state and both probes assert it.

> **`migrate.ts --pending` has three outcomes, not two**: exit `0` none, `10` pending, anything else
> **fatal**. `deno run` also exits non-zero on a crash, and a crash read as "none pending" would
> publish the new bundle onto an un-migrated store — the exact failure the window prevents.

> ⚠️ **Atomic per migration, not per run.** A run applying several migrations where a later one
> fails leaves the store at a version **no** bundle is written against — including the one the
> rollback restores. Roll-forward only, by hand. Ship one migration per deploy.

> **The migrate step holds the DATABASE credentials only.** `backend-deployment` requires that
> `BUNNY_STORAGE_ACCESS_KEY` be an Edge Script environment value and **not** a deploy-workflow
> secret — bunny issues no scoped keys, so the key that reads the zone also owns every user's
> photos. The database credentials are a third category, admitted deliberately because CI (not the
> endpoint) is what applies migrations. Do not widen it further.

> **There IS a post-publish boot probe, and it is required.** `POST /code` + `/publish` succeed
> whether or not the script can boot — that is how the previous runtime stayed fail-closed for two
> weeks with CI green. The probe polls the DEVICE-FACING origin, matches the bundle's stamped SHA,
> and asserts the window state. It witnesses that the script BOOTED, that THIS bundle is serving,
> which of a migrating deploy's two publishes is answering, and — new — that **both dependencies are
> reachable**, which closes the half of the 2026-07 outage nothing watched: a `BUNNY_STORAGE_ZONE`
> that is present but names a zone that does not exist used to boot and probe green. It still does
> **not** witness that a value is otherwise CORRECT.
>
> ⚠️ **One observation, ~119 points of presence.** The probe polls one hostname, which resolves to
> one PoP, and bunny publishes no propagation contract (its own claims range from seconds to
> minutes). So a green probe before a migration means _very likely_ every PoP serves the maintenance
> bundle — never _certainly_. Stated, not papered over with a settle wait that would read as a
> guarantee.

> **Rollback exists now, and it is narrow.** Bunny's own release re-publish still needs the ACCOUNT
> key (the deploy key returns `401` on the release endpoints), so this workflow brings its own:
> every green deploy archives `bundle-<sha>` as a GitHub Actions artifact, and a deploy that opens a
> window republishes the archived bundle for the commit that was live if anything after the window
> fails. The archive is **not** in the storage zone — that key is not this workflow's to hold, and a
> rollback must work when bunny is the thing misbehaving. There is deliberately **no rebuild
> fallback**: a missing archive fails **loudly** naming the commit, and the capture step refuses to
> open a window it could not lift rather than discovering it mid-outage. A **non**-migrating deploy
> that goes red still leaves whatever is live, live, and a human fixes it forward.

> **The browser-facing site is a SEPARATE deploy.** `.github/workflows/site-deploy.yml` builds the
> Astro `site/` module (under Node) and **mirror-deploys** it to the storage `site/` prefix
> (`site/scripts/deploy.mjs` — upload new, delete stale, never clear-first), authenticating with
> **only the storage-zone password** (`BUNNY_STORAGE_ACCESS_KEY`), never the account key. The api
> Edge Script proxies that prefix, so the routing lives in the bundle as source-owned code — **no
> pull-zone edge rules**. Capability `web-site`.

Provision once:

1. With the Bunny **account API key**: an **S3-enabled** Storage zone (DE), a Database, and the Edge
   Scripting app (record its **script id** and a **deploy key**). Set the secrets on the Edge
   Script.
2. Add GH secrets `BUNNY_SCRIPT_ID`, `BUNNY_DEPLOY_KEY`, `BUNNY_DATABASE_URL`,
   `BUNNY_DATABASE_AUTH_TOKEN` and `BUNNY_STORAGE_ACCESS_KEY`. The deploy holds the DATABASE pair
   only (it migrates before publishing); `BUNNY_STORAGE_ACCESS_KEY` is the nightly sweep's, and
   `backend-deployment` forbids the deploy from holding it. The deploy key is script-scoped; the
   **account API key is never in CI**.

The device-facing origin is the custom domain **`snapsync.stho.net`** — a `CNAME` in our `stho.net`
Bunny DNS zone pointing at the pull zone that fronts the Edge Script. Because we own the name,
swapping the runtime that answers it stays a **DNS repoint, never a new iOS build**. That property
is what let Deno Deploy be retired without a TestFlight round — keep it.

> **bunny is load-bearing.** There is no second runtime and no warm standby. A bunny outage is a
> SnapSync outage; recovery means standing a runtime back up from this bundle and repointing DNS.
> The engine retries forever, so uploads are **delayed, never lost**.

## Out-of-edge workflows

The Edge Script caps a request at 50 subrequests and 30 s CPU, so anything that walks the whole
store runs from GitHub Actions against the same modules. There is exactly one, and that is the test
a candidate has to pass: it runs again on the next deployment. A program that runs ONCE, against one
store, on one day is not a workflow — it is a scratchpad script (capability `database`).

- **`nightly-cleanup.yml`** (03:17 UTC, capability `scheduled-cleanup`) — MARKS FROM THE DATABASE
  and DELETES FROM STORAGE. Two ordered phases: stale events (past their derived delete-by, or
  empty), then unreferenced bytes below each device's retention floor. A device holding no
  membership in a surviving event also loses its `devices` row — but only once **no token minted for
  it can still verify**, because collecting earlier deletes the attestation behind a credential the
  device is still using and drives it into a nightly re-attestation loop. `-f dry_run=true` reports
  and deletes nothing.

## Edge Scripting limits worth knowing

- **30 s CPU** per request — CPU, _not_ wall clock, so a pure I/O pass-through stream is cheap. The
  killers would be buffering the body (`request.bytes()` → blows the **128 MB** isolate) or per-byte
  CPU (hashing/transform). The proxy does neither.
- **No documented wall-clock timeout for the script** — but the **pull zone in front has a 60 s
  request timeout** (`http_timeout`). That, not the CPU budget, is the real ceiling on a large
  Live-Photo paired video over a slow link. If it ever bites, the fix is **server-side resumable
  uploads**.
- 10 MB script size, 500 ms startup, **50 subrequests**, 128 env vars.
- The relational store is **public preview**: a 1 GB per-database ceiling, a **10-second maximum
  data-loss window** on primary failover, and a 32 766 bound-parameter cap per statement. Every row
  is reconstructible by a device round-trip — a manifest republish or a re-attestation — which is
  what makes those limits acceptable.
