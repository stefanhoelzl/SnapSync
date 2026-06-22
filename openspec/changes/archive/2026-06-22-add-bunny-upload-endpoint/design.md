## Context

design.md commits to a **mint** storage model whose central unknown (§3.3 TOP RISK) is whether a
bunny S3-compatible presigned `PUT` accepts `UNSIGNED-PAYLOAD` — which the background extension is
forced to use because it never sees the bytes. Rather than spike that, we pivot to a **proxy**: the
extension PUTs to our endpoint and the endpoint writes to bunny via the **native** Storage API,
where authentication is a single `AccessKey` header and there is no payload-hash requirement at all.

Constraints that shape the implementation:

1. **bunny Edge Scripting limits** ([docs](https://docs.bunny.net/docs/edge-scripting-limits)):
   **30 s CPU time** per request (not wall-clock), **128 MB** active memory, 50 subrequests, 10 MB
   script. The budget is **CPU**, so a pure I/O-bound pass-through stream is cheap; the killers are
   (a) buffering the body (`request.bytes()` → 128 MB blow) and (b) per-byte CPU (hashing/transform
   → 30 s exhaustion). Neither is required by a proxy.
2. **bunny native Storage** ([docs](https://docs.bunny.net/api-reference/storage)): `PUT
   https://{region-host}/{zone}/{path}` with header `AccessKey: <storage-zone password>` (the zone
   password, **not** the account API key). DE/Falkenstein default host `storage.bunnycdn.com`.
3. **The engine is retry-forever** (design §2.2, no attempt budget). A persistently-failing upload
   churns a job slot indefinitely; a **false success** is worse — it strands a truncated/garbage
   object in the bucket with no retry. The endpoint's outcome contract must respect both.
4. **The iOS uploader is not exercisable in this task.** The contract faces
   `PHAssetResourceUploadJobChangeRequest` (verified in-repo: the destination is a full `URLRequest`
   — `HTTPMethod=PUT`), but OPTIONS behavior and accepted success codes are unverified against a
   custom origin. The contract is frozen *provisionally* on that side. (Key recovery is **not** an
   unknown: the key rides the URL path, the field the shipped code already proves survives a
   re-fetch.)

## Goals / Non-Goals

**Goals:**
- A deployed, test-covered streaming proxy endpoint:
  `PUT /event/<eventId>/device/<deviceId>/file/<filename>` → bunny native Storage at the bare key
  `<eventId>/<deviceId>/<filename>`, authorized by the event UUID alone.
- A frozen HTTP contract the iOS follow-up can build against, with the unverified iOS-facing surface
  explicitly bracketed (not asserted).
- Test-gated, path-scoped CI deployment to bunny Edge Scripting.
- docs/design.md pivoted mint→proxy, coherently, with on-device unknowns in §8.

**Non-Goals (this change):**
- Any iOS-side rewiring (`BackgroundUploadURLBase`, provider replacement, key-recovery). Separate
  follow-up.
- Server-side **resumable uploads** (the IETF resumable-upload protocol the iOS uploader can
  negotiate). Deferred — it is the *principled* future fix for large-payload churn (resume from
  offset instead of restart-forever), but v1 makes the uploader fall back to a plain single-shot
  PUT.
- Any event registry / abuse protection / rate-limiting (design §8 deferred).
- Size guards / scope cuts for large paired-video (rejected: a 413 loops forever just as a timeout
  would; excluding paired video is a product-scope decision, not a backend one).

## Decisions

- **Proxy, not mint.** Kills the `UNSIGNED-PAYLOAD` TOP RISK; native API needs no signing.
- **Streaming pass-through.** Pipe `request.body` (a `ReadableStream`) straight into the bunny
  `fetch` PUT body. Never `await request.bytes()`; never hash/transform per byte. One subrequest.
- **Key in the URL path (labeled).** `PUT /event/<eventId>/device/<deviceId>/file/<filename>`. The
  path carries the key on the channel **proven** to survive a job re-fetch (the shipped iOS code
  reads `destination.URL`), so the iOS ack path recovers the ledger key from
  `job.destination.URL.path`. This needs no header/query hedge and **removes** the "header survival
  on re-fetch" unknown from §8 entirely. The labels are URL sugar for routing/recovery clarity.
- **Strict path validation, bare storage key.** The path MUST match
  `/event/<eventId>/device/<deviceId>/file/<filename>`; `eventId`/`deviceId` MUST be UUIDs; reject a
  `..` or empty `filename`. A path that doesn't match the template → `404`; matched-but-invalid
  (non-UUID, bad filename) → `400`. The **stored** key is the bare `<eventId>/<deviceId>/<filename>`
  (labels are URL-only); design §3.1's `events/` prefix is dropped (the zone is the event
  collection).
- **Last-write-wins.** Single PUT, no existence check (a HEAD/GET pre-check would add latency and
  *break* the engine's legitimate `ReUpload` of the same key). Overwrite abuse is deferred (design
  §8); within an event all holders already share the capability.
- **Faithful outcome, no false success.** `2xx` iff bunny confirms the stored object; any upstream
  error/timeout/partial → `5xx` (legit retry). Never a `2xx` on an unconfirmed write.
- **Method/OPTIONS.** `PUT` is the only handled method; `OPTIONS` is answered non-resumable so the
  iOS uploader falls back to a plain single-shot PUT. Any other method or unmatched path → Hono's
  default `404` (Hono does not emit `405` — verified against the framework).
- **Env-only config, fail-closed at boot.** Zone, host, and `AccessKey` from Edge Script env vars;
  never in source. `readConfig` is called once at startup and **throws** on a missing/blank var, so a
  misconfigured deployment fails to boot rather than mis-targeting per request. The validated
  `Config` is injected into `createApp`, so the handler has no config path.
- **Deploy as its own capability** (`backend-deployment`), symmetric with `ios-ci`/
  `*-delivery`; the runtime spec stays pure. Path-scoped to `backend/**`, gated on green `deno test`.
- **Hono for routing.** The endpoint uses **Hono** (the framework the Edge Scripting SDK bundles),
  served via `BunnySDK.net.http.serve(app.fetch)`. The upload path is declared **once**: a child
  Hono holds `put("/")` + `options("/")` and is mounted with `app.route(<upload path>, child)`.
  Params come from Hono's decoded `c.req.param()`; the filename is re-encoded per-segment when
  building the bunny URL (so the stored object is the real filename and keys stay flat). Wrong method
  / unmatched path fall through to Hono's default **404** (Hono emits no 405). Tests drive the app via
  `app.request()` (no SDK, no network), injecting `config` + `fetch`.

## Risks / Open questions (→ docs/design.md §8)

These are the iOS-facing assumptions the contract is frozen on but cannot verify in a backend-only
task. They become the iOS follow-up's first job and are mirrored in the spec's "Assumptions"
section and design.md §8:

- **OPTIONS preflight.** Does the bg uploader send an OPTIONS resumable-upload preflight to a custom
  origin, and does it fall back to plain PUT when resumable is declined? (Raw S3 verified to need
  none; unverified for our origin.)
- **Accepted success codes.** Which `2xx` does the bg uploader treat as success?
- **Large-payload budget.** Does the largest Live-Photo paired-video upload complete within the
  30 s **CPU** budget (expected: yes, pass-through is I/O-bound) and any **undocumented wall-clock /
  idle timeout** on a long-held streaming request? If it bites → enable resumable uploads.
