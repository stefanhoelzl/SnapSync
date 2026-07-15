## Context

The device-facing origin `snapsync.stho.net` is a bunny pull zone → Edge Script (Hono, `backend/src/app.ts`).
Today `GET /` is unhandled and every route is behind the `device-attestation` gate, so a browser reaching the
origin gets `401 unattested`. App Store submission needs a reachable Privacy Policy URL (currently `null` in
ASC) and a Support URL. Rather than stand up a second host, we serve one static page from the existing script
at `GET /`. The backend ships as a single `deno bundle` output with **no runtime filesystem** and a
**source-owned** configuration discipline (`backend-deployment`): anything the running system depends on must
be compiled into the bundle, never read at runtime or set in the dashboard.

The page itself is already drafted and reviewed (single self-contained `index.html`: brand-matched hero,
three-step flow, `#privacy` Privacy Policy, `#terms` Terms/EULA, footer → GitHub issues; the app icon inlined
as a data URI; no external requests, no analytics).

## Goals / Non-Goals

**Goals:**
- Serve the marketing/privacy/terms page at `GET /` (and `HEAD /`) over the existing origin.
- Keep it **source-owned**: embedded in the bundle, no runtime file read, no new infra.
- Make it **cacheable at the pull zone** so the Edge Script is barely hit and the upload hot path is never
  affected.
- Open exactly `GET /` and `HEAD /` to the public without weakening the attestation gate anywhere else.

**Non-Goals:**
- No TestFlight/waitlist CTA, no email capture, no analytics/cookies.
- No separate route files — one page, anchored `#privacy` / `#terms` sections.
- No new pull zone, subdomain, or DNS record; no change to the device-facing host.
- No App Store age-rating assertion in the page (handled by Apple's questionnaire).

## Decisions

**1. Embed the HTML via a text import, serve it from a Hono `GET /` handler.**
`import LANDING_HTML from "./landing.html" with { type: "text" };` — verified that `deno bundle` inlines the
file into the single output bundle (Deno 2.8.3). The handler returns it with
`Content-Type: text/html; charset=utf-8`.
- *Alternatives rejected:* runtime file read (no filesystem on the edge bundle); a generated `landing.ts`
  exporting a template string (needs codegen + backtick escaping; the text import is simpler and equivalent);
  a separate static pull zone / subdomain (more infra, and the user wants the apex URL).

**2. Canonical page location: `backend/src/landing.html`.**
Co-located with the code that serves it and reachable by the bundle's relative import. The reviewed draft at
`web/index.html` moves here and becomes the source of truth (the throwaway local-preview server is not part of
the change).

**3. Cache at the edge: `Cache-Control: public, max-age=300`.**
bunny caches on the origin's `Cache-Control` (per `backend-deployment` / `bunny-list-endpoint` notes), so a
`public` page is served from the CDN edge and the script runs ~once per edge-TTL. This is the deliberate
inverse of the listing routes' `no-cache`. Trade-off: a copy edit takes up to the TTL (or a manual pull-zone
purge) to propagate — acceptable for marketing/legal text. 300 s keeps propagation quick while still shielding
the script.

**4. Attestation exception: add exactly `GET /` and `HEAD /` to the single gate middleware.**
The gate is one `app.use("*")` that passes through `OPTIONS` and `/attest/*` and otherwise requires a valid
token. Extend the pass-through predicate with `((method === "GET" || method === "HEAD") && path === "/")` —
an **exact-path, method-scoped** allowance. It cannot bypass the gate for any other path (no `startsWith`) or
any mutating method. The ungated set stays a single readable closed list, as the capability requires.
- *Alternative rejected:* registering the marketing route before the gate middleware — that would rely on
  route-ordering rather than an explicit, auditable entry in the closed list; the capability's whole point is
  that ungated routes are enumerated in one place.

**5. `HEAD /` is allowed alongside `GET /`.** The pull zone / health checks may issue `HEAD`; a `401` there
would be surprising for a public page. Both are safe (no side effects, no token).

## Risks / Trade-offs

- **[Coupling marketing to the critical upload backend]** → The route is a pure static-string response with no
  storage or Apple call, cached at the edge, and deployed through the same path-scoped, CI-gated `backend/**`
  pipeline (deploy only on `main`). A marketing edit is as safe as any other backend deploy; the bundle grows
  by ~120 KB (the inlined HTML), negligible for the edge.
- **[Gate-bypass regression]** → The exception is exact (`path === "/"`) and method-scoped; a test asserts that
  an unauthenticated request to a non-`/` path (and a non-GET/HEAD method on `/`) still returns `401`, so a
  future widening is caught.
- **[Cache staleness after a copy edit]** → `max-age=300` bounds it; a manual pull-zone purge forces immediate
  propagation when needed.
- **[Apple rejects an anchor Privacy URL (`/#privacy`)]** → Accepted: the user chose a single anchored page.
  The section is self-contained and reachable; if a reviewer objects, a follow-up can add a `GET /privacy`
  alias returning the same page. Not built now.

## Migration Plan

Purely additive. Deploy = merge to `main`; the existing `backend/**` workflow bundles (HTML inlined) and ships.
Rollback = revert the route + gate-exception commit; no data migration, no config change, no client impact.

## Open Questions

- Whether to state the EU/Germany storage region in the privacy text (now known: zone region `de`). Minor copy
  decision, resolvable during apply; the current high-level wording ("hosting provider Bunny.net") is already
  accurate.
