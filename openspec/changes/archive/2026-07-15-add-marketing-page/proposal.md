## Why

SnapSync is preparing for App Store submission, which **requires** a publicly reachable Privacy
Policy URL (currently `null` in App Store Connect) and a Support URL, and benefits from a Marketing
URL. We own `snapsync.stho.net` and already front it with a bunny pull zone → Edge Script, so the
cheapest way to stand these up is to serve one static page from the existing backend at `GET /` —
today that path is unhandled, and a browser reaching the device origin gets nothing.

## What Changes

- Add a single self-contained static landing page (marketing hero + how-it-works + a `#privacy`
  Privacy Policy section + a `#terms` Terms/EULA section) served by the Hono app at `GET /` (and
  `HEAD /`), with `Content-Type: text/html; charset=utf-8` and a **cacheable** `Cache-Control:
  public` so the pull zone serves it from edge and the Edge Script is barely hit.
- The page is **source-owned** — embedded in the bundle at build time, no runtime file read —
  consistent with `backend-deployment`'s source-owned rule. No analytics, no cookies, no external
  requests (the app icon is inlined as a data URI).
- **BREAKING (gate surface):** `GET /` and `HEAD /` become **public, unauthenticated** routes. The
  `device-attestation` gate today rejects every route without a valid device token except a closed
  list (`/attest/*`, `OPTIONS`); this adds exactly `GET /` and `HEAD /` to that list, scoped so it
  cannot bypass the gate for any other path.
- Produces the App Store Connect URLs: Marketing `https://snapsync.stho.net/`, Privacy
  `https://snapsync.stho.net/#privacy`, Support `https://github.com/stefanhoelzl/SnapSync/issues`.
  The in-page App Store badge links to `https://apps.apple.com/app/id6781692480` (dead until the app
  is published, then self-activating).

## Capabilities

### New Capabilities

- `marketing-site`: the backend serves a single, source-owned, cacheable static HTML page at `GET /`
  (and `HEAD /`) carrying the marketing content, the Privacy Policy (`#privacy`), and the Terms/EULA
  (`#terms`) — the App Store submission surface for `snapsync.stho.net`.

### Modified Capabilities

- `device-attestation`: the closed list of ungated routes gains exactly `GET /` and `HEAD /` — a
  public, unauthenticated marketing route — without widening the gate for any other path or method.

## Impact

- **Code**: `backend/src/app.ts` (new `GET /` + `HEAD /` route; adjust the attestation gate's
  ungated allowance); a new source module carrying the embedded HTML (source of truth
  `web/index.html`); the `deno bundle` output must inline the HTML.
- **Specs**: new `marketing-site`; delta on `device-attestation`.
- **Config / infra**: none new — reuses the existing pull zone, custom domain, and deploy pipeline.
  Marketing-copy edits now redeploy the device-facing Edge Script (acceptable: the route is a static
  string response, path-scoped and CI-gated like the rest of `backend/**`).
- **App Store Connect**: privacy/support/marketing URLs become settable; no code dependency.
- **Non-goals**: no TestFlight/waitlist CTA, no analytics, no separate route files (single page,
  anchored sections), no App Store age assertion.
