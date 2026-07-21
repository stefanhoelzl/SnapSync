## Why

The device API is served from the root of the device-facing origin, sharing the URL namespace with the
web/link surface (`/`, `/join`, the AASA) and leaving no room to evolve the API contract independently.
Serving the device API under a **versioned prefix** carves out a stable namespace for the API and opens
the door to running multiple API versions side-by-side, without ever colliding with the load-bearing
web/link paths.

## What Changes

- Device-API routes are served under a **versioned prefix**, `/api/v1` (e.g. `POST /api/v1/events`,
  `PUT /api/v1/files/devices/:id/:filename`, `GET /api/v1/attest/challenge`). The server routing is
  structured so that additional versions (`/api/v2`, …) can be mounted later without restructuring.
- The **bare (un-prefixed) paths keep working** as a **deprecated grace alias**, so already-installed
  apps — which bake the host at compile time and cannot be force-updated — are not broken. **Non-breaking.**
- The web/link paths — `/`, `/join`, `/.well-known/apple-app-site-association` — **stay at the root,
  un-prefixed**. Apple's CDN and browsers require fixed paths there; they use a separate host constant
  (`LINK_ORIGIN`) and are untouched.
- The client starts targeting `/api/v1` by appending the prefix to the single compile-time base host
  (`BACKGROUND_UPLOAD_URL_BASE`). Because every device-API client interpolates `$base/…` from that one
  value, this covers uploads (both tiers), event creation, listings, enrollment, leave, directory, device
  config, notify, push registration, and attest at once — no per-client path edits.
- The auth gate's ungated `/attest/*` bootstrap check keeps holding for the prefixed attest routes (else
  token issuance dead-locks), and keeps holding for the bare alias during the grace period.
- Grace-period removal is deferred: the bare alias stays until a later change removes it. No bare-path
  traffic logging is added.

## Capabilities

### New Capabilities
<!-- none: this change adds a cross-cutting routing property, not a new capability. -->

### Modified Capabilities
- `backend-deployment`: adds a cross-cutting requirement that device-API routes are served under a
  versioned `/api/vN` prefix (currently `/api/v1`), structured for multiple versions to coexist, with the
  bare paths served as a deprecated grace alias; the compile-time device-facing base host carries the
  current version prefix. The web/link paths remain at the root, un-prefixed.

## Impact

- **Server** (`backend/src/app.ts`): device-API routes moved under a `/api/v1` mount plus a bare-path
  alias mount; the auth gate (incl. the `/attest/` ungated check) applies to both. Web routes stay at root.
  New coverage in `backend/test/app.test.ts` asserting device routes resolve identically under `/api/v1/*`
  and the bare paths.
- **Client** (`iosApp/Configuration/Config.xcconfig`): `BACKGROUND_UPLOAD_URL_BASE` gains the `/api/v1`
  suffix — one line, covering the main app and the upload extension, all tiers. No Kotlin path-literal
  changes; no new client tests (prefix is pure string concatenation, verified on-device).
- **Rollout ordering**: the backend (serving both `/api/v1` and bare) must deploy **before** any
  TestFlight/App Store build carrying the prefixed host reaches devices. The bunny deploy is main-only and
  decoupled, with no boot probe — verify manually post-deploy.
- **Docs**: `backend/README.md` API map, the `app.ts` route-doc header, and the `Config.xcconfig` base
  comment.
- No change to storage keys, presigned download URLs (served off the S3 endpoint), or the web/link surface.
