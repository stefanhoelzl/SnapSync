# backend/ — SnapSync upload endpoint (bunny Edge Scripting)

A **streaming proxy upload endpoint** (Deno/TypeScript + **Hono**) deployed to bunny.net Edge
Scripting. The iOS background-upload extension PUTs photo bytes here; this endpoint streams them
straight into a bunny **native** Storage zone. It replaces the design's mint/presigned-URL model —
no SigV4, no `UNSIGNED-PAYLOAD`, no per-resource mint round-trip.

Authoritative contract: `openspec/specs/bunny-upload-endpoint` (and `backend-deployment`); rationale
in `docs/design.md` §4.

## Contract

```
PUT /event/<eventId>/device/<deviceId>/file/<filename>
    body: raw resource bytes (streamed, never buffered)
    →  bunny native PUT  https://<host>/<zone>/<eventId>/<deviceId>/<filename>
       header  AccessKey: <storage-zone password>
```

- `eventId`, `deviceId` — **UUIDs** (Hono route params, validated). The event UUID is the only
  capability (no token, no registry).
- `filename` — a single path segment, read **raw** from the URL and forwarded **verbatim** (Hono
  decodes params, so the object key is taken from the raw URL to stay byte-exact). A literal or
  encoded `/` (`%2F`) is rejected so keys stay flat.
- **Stored key is bare** `<eventId>/<deviceId>/<filename>` — the `event`/`device`/`file` labels are
  URL-only.
- **Last-write-wins** — one unconditional PUT, no existence check.
- **Faithful outcome** — `201` only when bunny confirms the store; any upstream error/abort → `502`
  (the iOS ledger then retries). Never a false success.
- **Methods** — `PUT` is the only handled method; `OPTIONS` → `204` (no resumable advertised, so the
  iOS uploader falls back to a plain PUT). Any other method or unmatched path → **`404`** (Hono's
  default — it does not emit `405`). Bad UUID / unsafe filename → `400`.

## Layout

```
src/app.ts        Hono app (createApp({config, fetch}) → routes); the upload handler
src/validators.ts validateUUID(value) / validateFilename(name) → boolean
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
