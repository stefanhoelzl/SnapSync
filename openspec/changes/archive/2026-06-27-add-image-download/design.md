## Context

The backend (Deno + Hono, `backend/src/app.ts`) already mints events, streams uploads in, and lists
an event's stored objects. The list endpoint returns `{ filename, size, lastModified }` per object
but no address to fetch the bytes. We are adding a read path — `GET /event/<id>/file/<name>` — and
threading the download URL into the list response so a consumer can list-then-download.

Constraints carried from the existing endpoints:
- **Possession is the capability.** No tokens; the event id authorizes. Storage is bunny native
  Storage; the object key is `<eventId>/<encodeURIComponent(filename)>` (flat, single segment).
- **Faithful outcomes.** Upload never reports `2xx` for an unconfirmed write; list never returns a
  partial array. The config is validated once at startup and fails closed (a missing var → no boot).
- **One app, injected config.** `createApp({ fetch, config })`; tests inject a fake fetch + config,
  so env-var validation runs only in `main.ts` startup, never in the suite.

The download route shares the **exact** path of the upload PUT, so it slots onto the same child Hono
as a third verb (`put` + `options` + `get`).

## Goals / Non-Goals

**Goals:**
- A streaming, pass-through object download with a faithful status contract.
- Each list entry carries an absolute `url` that fetches that object.
- Make the now-shared runtime config a first-class, single-source contract.

**Non-Goals:**
- `Range` / partial / resumable downloads (full body only).
- `Content-Disposition` / attachment semantics (the client controls how it saves bytes).
- A new client; the existing Kotlin consumer (`ignoreUnknownKeys`) needs no change to ship.
- Any change to the upload or create behavior beyond relocating the config requirement.

## Decisions

### D1 — `url` is exposed, but it is our public route, not bunny's storage key
The list spec deliberately forbids leaking "the full storage key" (`<zone>/<id>/<enc(name)>`), an
internal bunny address whose shape we reserve the right to change. `url` points at the same object
but is a different *kind* of address: our own public, token-free route, stable because we own it,
leaking nothing about the storage backend. The entry stays a **closed** shape — exactly
`{ filename, size, lastModified, url }` — matching the spec's precise-closed-shape personality
(adding a fifth field is a deliberate future spec change, not silent creep).
- *Alternative considered:* open the set to "at least these fields." Rejected — it would be the one
  soft spot in an otherwise hermetic contract, and `ignoreUnknownKeys` on the client is
  defense-in-depth, not license to leave the shape open.

### D2 — The download spec owns the URL format; list only references it
The format string (`${PUBLIC_BASE_URL}/event/:id/file/:enc(name)`, the literal `event`/`file`
labels, per-segment `encodeURIComponent`, eventId-is-identity) lives in exactly one place:
`bunny-download-endpoint`. The list delta says only that each entry carries `url`, "the absolute
download URL per `bunny-download-endpoint`." This makes `bunny-list-endpoint` a *consumer* of
`bunny-download-endpoint`, so the proposal introduces download as the foundational piece.
- *Alternative considered:* restate the format in the list spec. Rejected — two specs defining one
  string drift (e.g. an encoding change in one, missed in the other → a `%20` double-encodes).
- *Code shape:* a single `downloadUrl(config, eventId, filename)` helper, called by the list
  handler. Download defines a route at the matching path; both agree by construction.

### D3 — Download is **ungated**; a missing object and an unknown event are both `404`
Upload and list each read the event marker (`events/<id>.json`) before acting. Download skips it and
goes straight to the object GET. Rationale: the filename a caller downloads came from a (gated) list,
and the object GET *already gives faithful absence* — bunny `404` → `404`, bunny non-`404`/timeout →
`5xx` — so the marker round-trip is pure waste on the feature's hot path. The cost is that download
cannot distinguish "event never existed" from "event exists, no such file"; both are `404`. For a
*read* that is correct (the caller asked for an object; "not there" is the honest answer) and avoids
leaking event-existence as a distinct signal. The spec states this as an explicit **non-requirement**
so a future reader does not "fix" it back to gated.
- *Alternative considered:* gate like upload/list. Rejected — extra latency, no behavioral benefit,
  and the distinct 404 it would buy is not wanted.

### D4 — Read-faithfulness is narrower than write-faithfulness, and that is stated honestly
Upload can promise "never `2xx` for a partial write" because the status is decided *after* the
upstream resolves. Download is the mirror image: it relays bunny's `200` + headers and *then*
streams the body, so a mid-body upstream abort **cannot** retroactively become `5xx` — the client
gets a truncated `200`. The contract is therefore split:
- **Status fidelity (promised):** `200` only if bunny *began* a `200`; `404` → `404`; any other
  status / connect error / pre-body timeout → `5xx`.
- **Body integrity (not a status):** a mid-body abort yields a truncated response under the
  already-sent `200`. To make that *detectable*, the endpoint relays `Content-Length`, turning an
  un-signalable failure into a client-detectable short-read. The spec names the client obligation:
  *a consumer SHALL treat a `Content-Length` short-read as a failed download (and retry).*
- *This is the read-side analog of upload's "never strand a truncated object": we cannot prevent a
  truncated response, but we make it detectable.*

### D5 — Relayed response headers
`Content-Type` (bunny's stored type, fallback `application/octet-stream`), `Content-Length` (per D4),
and the cache validators `ETag` / `Last-Modified` / `Cache-Control` when bunny returns them. No
`Content-Disposition`, no `Range`/`Accept-Ranges`.

### D6 — `PUBLIC_BASE_URL` is required and fail-closed, and config moves to its own capability
`PUBLIC_BASE_URL` (the backend's public origin, trailing slash stripped) is read by the *list*
endpoint to build each `url`. It is **required**: a backend that boots and emits listings with
blank/broken URLs is exactly the subtle half-working state the fail-closed posture exists to kill,
and required-ness costs nothing in tests (config is injected). Because config is now read by multiple
endpoints, the runtime-config contract is relocated out of `bunny-upload-endpoint` (where it lived by
historical accident) into a new shared `backend-config` capability that every endpoint references.
- *Alternatives considered:* (a) keep it in the upload spec — rejected, upload would own vars it does
  not uniquely use; (b) put it in `backend-deployment` — rejected, that spec is scoped to the
  CI/deploy pipeline and explicitly disclaims runtime config ("…the endpoint's runtime config, not a
  CI credential"), and fail-closed-at-boot is a runtime, not deploy, property.

## Risks / Trade-offs

- **Boot coupling.** `PUBLIC_BASE_URL` becomes a hard boot dependency for the *whole* app, so a
  deploy that forgets it takes down create + upload + list + download, not just download. →
  *Mitigation:* this is the intended fail-closed behavior (loud at startup beats a silent
  half-working contract); `backend-config` makes the required inventory the single contract of
  record so the requirement is discoverable.
- **Truncated download reads as `200`.** A mid-body bunny failure reaches the client as a truncated
  success. → *Mitigation:* relay `Content-Length` (D4) and pin the client short-read-is-failure
  obligation in the spec.
- **Two addresses for one object.** Exposing `url` alongside the hidden storage key risks a reader
  conflating them. → *Mitigation:* D1's explicit distinction in the list delta's rationale.
- **List ↔ download format drift.** → *Mitigation:* D2 — single format authority + one shared
  `downloadUrl` helper; pin the round-trip invariant ("a listed `url` fetches that object") as a
  download scenario.
- **Contract change to the list entry shape.** → *Mitigation:* the live Kotlin consumer uses
  `ignoreUnknownKeys`, so it tolerates the new field; no client change is required to ship.
