## Why

design.md's storage model is a **mint** endpoint: an external edge script issues a presigned
`PUT` URL and the iOS background extension uploads bytes **directly** to bunny.net Storage's
S3-compatible API. That model carries an unresolved **TOP RISK** (§3.3): the background extension
never sees the bytes, so it must sign with `UNSIGNED-PAYLOAD`, and bunny's S3-compatible API is
**not documented to accept it**. If it doesn't, the whole upload path is dead, and the doc says to
"spike this first."

This change sidesteps the risk entirely by **pivoting from mint to proxy**: the iOS extension PUTs
bytes to **our own endpoint**, and the endpoint streams them into a bunny Storage zone via bunny's
**native Storage API** (a plain authenticated `PUT` with an `AccessKey` header — no SigV4, no
presigning, no `UNSIGNED-PAYLOAD`, no per-resource mint round-trip). The endpoint is the missing
**backend** the design has so far treated as "external, out of scope"; this builds the first
deployable piece of it.

**Honest framing — this reverses a deliberate design claim.** design.md §scope states the edge
"sees no bytes — uploads go device→bucket." Under the proxy, the edge (and whoever operates it)
sees **every photo in transit**. That is a real change to the system's privacy posture, recorded
here and in the design.md pivot, not buried.

**Scope is backend-only.** This change builds and deploys the endpoint and freezes the HTTP
contract. It does **not** rewire the iOS side (the shipped `S3UploadRequestProvider` → S3 path is
untouched); the proxy's iOS adaptation has genuine on-device unknowns (below) and is a separate
follow-up.

## What Changes

- **New `backend/` Deno project** — a bunny Edge Scripting app (`@bunny.net/edgescript-sdk` +
  **Hono** for routing, served via `BunnySDK.net.http.serve(app.fetch)`) implementing a **streaming
  proxy upload endpoint**:
  `PUT /event/<eventId>/device/<deviceId>/file/<filename>` streams the request body straight into a
  bunny native Storage `PUT` (one subrequest, never buffered), keyed from the URL path.
- **Key contract** — the upload URL is `PUT /event/<eventId>/device/<deviceId>/file/<filename>`
  (labeled path; `eventId`/`deviceId` are UUIDs), and the **storage key** is the bare
  `<eventId>/<deviceId>/<filename>` (no labels, no `events/` prefix). Carrying the key in the URL
  path puts the iOS ack-path key recovery back on `job.destination.URL` — the channel the shipped
  extension already proves survives a job re-fetch — so no header/query hedge is needed.
- **Authorization = the event id** — no token, no event registry; possession of the high-entropy
  event UUID is the capability (design §4). The bunny storage-zone `AccessKey` lives only in the
  edge env; the device holds no storage credential.
- **Faithful outcome propagation** — the endpoint returns `2xx` only when bunny confirms the stored
  object, and never masks a partial/failed upstream as success (a false success would strand a
  truncated object forever under the engine's retry-forever policy).
- **Full `Deno.test` suite** against a mocked bunny upstream (key validation, header/query parsing,
  method + OPTIONS handling, streaming pass-through, last-write-wins, error → status mapping).
- **New `backend-deployment` GitHub Action** — path-scoped to `backend/**`, isolated from the
  Gradle/iOS workflows, **gated on a green `deno test`**, bundling and deploying to the Edge
  Scripting app via a Bunny account API key held only as a GH secret.
- **docs/design.md pivot** (rides in this change, thread ① "design-in-change"): the storage/auth
  narrative (§scope, §2.2, §3.1, §3.3, §4, §7) is rewritten **mint→proxy** end-to-end, and the
  proxy's still-unverified iOS-facing unknowns are relocated into §8 "Still open — verify on
  device" rather than asserted as settled.

## Capabilities

### New Capabilities
- `bunny-upload-endpoint`: the streaming proxy upload endpoint — its
  `PUT /event/<uuid>/device/<uuid>/file/<filename>` contract, URL-path key derivation with strict
  UUID validation and the bare `<uuid>/<uuid>/<filename>` storage key, the native bunny Storage
  target + `AccessKey` auth, last-write-wins, faithful outcome propagation (no false success),
  method/OPTIONS handling (iOS falls back to plain PUT), env-only config, and an explicit
  **"Assumptions (unverified on device)"** section bracketing the iOS-facing surface.
- `backend-deployment`: the `backend/**`-scoped GitHub Action that deploys the endpoint to bunny Edge
  Scripting — test-gated, bundle + API-deploy, secret handling, idempotent target.

### Modified Capabilities
<!-- none — backend-only; the iOS-side capabilities (s3-request-provider, ios-background-upload,
     deeplink-config) are deliberately left for a separate proxy-rewiring change. -->

## Impact

- **New top-level `backend/` directory** — the first non-Kotlin, non-app deployable in the repo
  (Deno/TypeScript + Hono, outside the Gradle build). `deno.json`, `src/`, and `test/*.test.ts`.
- **New CI workflow** `.github/workflows/backend-deploy.yml` and **two GH secrets**:
  `BUNNY_SCRIPT_ID` + a script-scoped `BUNNY_DEPLOY_KEY` (the account API key is **not** used by CI;
  the storage-zone `AccessKey` is an Edge Script env var, not a workflow secret).
- **bunny.net account** — a new dedicated Storage zone (DE/Falkenstein default) and an Edge
  Scripting app, provisioned via the Bunny API.
- **docs/design.md** — the storage/auth narrative pivots mint→proxy; §8 gains the proxy's on-device
  unknowns.
- **Downstream (NOT in this change)** — the iOS follow-up must: point `BackgroundUploadURLBase` at
  the edge host, replace the `S3UploadRequestProvider` with a local URL builder (compose
  `/event/<uuid>/device/<uuid>/file/<encoded filename>`), and recover a job's ledger key from
  `job.destination.URL.path` (the labeled URL carries the full key on the channel proven to survive
  a re-fetch — note `lastPathComponent` yields only `<filename>`, so it parses the full path).
  These are tracked as design.md §8 assumptions.
