# backend/ — SnapSync upload endpoint (bunny Edge Scripting)

A **streaming proxy** (Deno/TypeScript + **Hono**) deployed to bunny.net Edge Scripting. It mints
events, lists an event's objects, and streams photo bytes from the iOS background-upload extension
straight into a bunny **native** Storage zone. It replaces the design's mint/presigned-URL model —
no SigV4, no `UNSIGNED-PAYLOAD`, no per-resource mint round-trip.

Authoritative contracts: `openspec/specs/event-creation`, `openspec/specs/bunny-upload-endpoint`,
`openspec/specs/bunny-list-endpoint` (and `backend-deployment`); rationale in `docs/design.md` §4.

## Event registry

An event **exists** iff the marker object `events/<eventId>.json` (`{ eventId, name, createdAt }`)
is present in the zone. The `events/` prefix is disjoint from any event's photo dir `<eventId>/` (an
`eventId` is a UUID, never the literal `events`), so the marker never appears in a per-event listing
and never collides with a photo. Bunny's Edge Storage API has no `HEAD`, so the existence check is a
small `GET` of the marker (which also serves the metadata route). **List and upload are both gated**
on it: an unknown event → `404`; a non-`404` marker read failure → `502` (a transient failure is
never mistaken for absence).

## Contract

```
POST /event
    body: {"name": "<event name>"}                     (JSON; trimmed, non-empty, ≤100 chars)
    →  bunny native PUT  events/<minted-uuid>.json
    →  201 {eventId, name, createdAt}                  (eventId minted server-side)

GET  /event/<eventId>
    →  200 {eventId, name, createdAt}  | 404 when the event was never created

PUT  /event/<eventId>/file/<filename>
    body: raw resource bytes (streamed, never buffered)
    →  [gate] GET events/<eventId>.json  → 404? respond 404 (stream nothing)
    →  bunny native PUT  https://<host>/<zone>/<eventId>/<filename>
       header  AccessKey: <storage-zone password>

GET  /event/<eventId>/files
    →  [gate] GET events/<eventId>.json  → 404? respond 404
    →  200 [ {filename, size, lastModified}, … ]       (200 [] for a created-but-empty event)
```

- `eventId` — a **UUID** (Hono route param, validated). The event UUID is the capability (no token);
  the registry is consulted only for **existence**, not authorization.
- `filename` — a single path segment, read **raw** from the URL and forwarded **verbatim** (Hono
  decodes params, so the object key is taken from the raw URL to stay byte-exact). A literal or
  encoded `/` (`%2F`) is rejected so keys stay flat.
- **Stored key is bare** `<eventId>/<filename>` — the `event`/`file` URL labels are not stored.
- **Last-write-wins** — one unconditional object PUT, no existence check **on the object key** (the
  event-marker gate reads `events/<eventId>.json`, never the object key).
- **Faithful outcome** — `201` only when bunny confirms the store; any upstream error/abort → `502`
  (the iOS ledger then retries). Create is faithful too: `201` only after the marker store confirms,
  else `502`. Never a false success.
- **Methods** — per route: `POST /event`, `GET /event/<id>`, `PUT`/`OPTIONS` on the upload path
  (`OPTIONS` → `204`, no resumable advertised), `GET /event/<id>/files`. Any other method or
  unmatched path → **`404`** (Hono's default — it does not emit `405`). Bad UUID / unsafe filename /
  invalid name → `400`.

> **Deployment invariant.** `BUNNY_STORAGE_HOST` MUST be the storage zone's **main** region host
> (where writes land), never a replica endpoint. Bunny replicates to other regions asynchronously;
> reads from the main region are read-after-write consistent, so a just-created marker is visible to
> the immediately-following join/list/upload. A replica host could lag and `404` a fresh event.

> **Known gap (iOS follow-up).** A `404` on upload is a _permanent_ failure, but the engine's
> retry-forever policy would retry it indefinitely. It is **unreachable in v1** — join's
> `GET /files` existence check `404`s first, so uploads never start against a non-existent event. If
> `404`-on-upload ever becomes reachable (e.g. a delete-event endpoint), the client needs a
> terminal/event-level disposition for it.

## Layout

```
src/app.ts        Hono app (createApp({config, fetch}) → routes); create + metadata + upload + list,
                  the events/<id>.json marker helpers (markerKey, readMarker), the existence gates
src/validators.ts validateUUID / validateFilename → boolean; validateEventName(raw) → trimmed | null
src/config.ts     readConfig(env) → Config (THROWS on missing/blank var)
src/main.ts       Edge Scripting entry: reads config at startup, serves createApp(...).fetch
test/*.test.ts    Deno tests (app via app.request(), upstream fetch + config injected)
```

## Configuration (env only — no secrets in source)

| Var                        | Meaning                                                              |
| -------------------------- | -------------------------------------------------------------------- |
| `BUNNY_STORAGE_ZONE`       | storage zone name                                                    |
| `BUNNY_STORAGE_HOST`       | native host, e.g. `storage.bunnycdn.com` (DE/Falkenstein default)    |
| `BUNNY_STORAGE_ACCESS_KEY` | storage-zone **password** (the `AccessKey`; NOT the account API key) |

`main.ts` reads these once at startup via `readConfig(Deno.env.toObject())`, which **throws** on any
missing/blank var → a misconfigured deployment **fails to boot** (fail-closed at deploy, never a
mis-targeted upload). The validated `Config` is injected into the app, so the request handler has no
config path.

## Develop & test

```bash
deno task test          # full suite; upstream bunny mocked, env injected → offline, no perms
deno task lint
deno fmt --check
# run locally (listens on 127.0.0.1:8080 via the SDK):
BUNNY_STORAGE_ZONE=z BUNNY_STORAGE_HOST=storage.bunnycdn.com BUNNY_STORAGE_ACCESS_KEY=k \
  deno run --allow-net --allow-env src/main.ts
```

`createApp(deps)` takes injected `{ env, fetch }` so tests drive the real Hono app via
`app.request()` without the network or `Deno.env`.

## Deploy

CI deploys via `.github/workflows/backend-deploy.yml` (path-scoped to `backend/**`, **gated on green
`deno test`**) using `BunnyWay/actions/deploy-script`. Provision once and set GH secrets:

1. With the Bunny **account API key**, create a Storage zone (DE) → record `BUNNY_STORAGE_*` and set
   them as Edge Script **environment variables**.
2. Create the Edge Scripting app → record its **script id** and a **deploy key**.
3. Add GH secrets `BUNNY_SCRIPT_ID` and `BUNNY_DEPLOY_KEY` (the deploy key is script-scoped — the
   account API key is **not** used by CI).

## On-device caveats (unverified — see design.md §8)

This endpoint's bunny-facing behavior is tested; its **iOS-facing** surface is frozen but unverified
until the iOS rewiring follow-up: OPTIONS fallback on a custom origin, which `2xx` the background
uploader accepts, and whether the largest Live-Photo paired-video stays within the 30 s CPU budget /
any undocumented wall-clock timeout (fix if it bites: server-side resumable uploads).
