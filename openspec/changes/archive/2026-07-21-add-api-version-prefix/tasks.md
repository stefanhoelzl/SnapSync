## 1. Server: version-prefixed router + bare alias

- [x] 1.1 In `backend/src/app.ts`, extract the device-API route registrations (attest, events, files, devices, notify, byte-upload child app) plus the auth-gate middleware into a single reusable device-API router (factory), leaving the web routes (`/`, `/join`, AASA) on the root app.
- [x] 1.2 Mount the device-API router at `/api/v1` (canonical).
- [x] 1.3 Mount the same device-API router at the root (`/`) as the deprecated bare alias, ensuring it coexists with the web routes and does not shadow them.
- [x] 1.4 Verify the auth gate holds for both mounts: authenticated routes require a bearer token under `/api/v1/…` and bare; `attest/*` stays ungated under both `/api/v1/attest/*` and `/attest/*`. If Hono does not strip the mount prefix for the gate middleware's `c.req.path`, make the gate's closed-list checks (`/`, `/join`, AASA, `startsWith("/attest/")`) prefix-tolerant.
- [x] 1.5 Confirm the web/link paths (`/`, `/join`, `/.well-known/apple-app-site-association`) are served only at the root and are NOT reachable under `/api/v1`.

## 2. Server: tests

- [x] 2.1 In `backend/test/app.test.ts`, add cases asserting representative device routes (e.g. `POST /events`, `PUT /files/devices/:id/:filename`, `GET /events/:id`) resolve identically under both `/api/v1/*` and the bare paths — same status and same recorded upstream bunny call(s).
- [x] 2.2 Add a case asserting `/api/v1/attest/challenge` (and/or `/api/v1/attest/token`) is served with no bearer token (ungated), matching the bare `/attest/*` behavior.
- [x] 2.3 Add a case asserting an authenticated device route under `/api/v1/…` without a token is rejected by the gate, matching the bare form.
- [x] 2.4 Add a case asserting a web/link path (`/join` or the AASA) is NOT served under `/api/v1` (404 or no-match), while still served at the root.
- [x] 2.5 Run `deno fmt --check`, `deno lint`, `deno check src/*.ts`, and `deno test` in `backend/`; all green.

## 3. Client: prefix the baked base host

- [x] 3.1 In `iosApp/Configuration/Config.xcconfig`, append `/api/v1` to `BACKGROUND_UPLOAD_URL_BASE` (so it becomes `https://snapsync.stho.net/api/v1`), updating the accompanying comment to note the version prefix.
- [x] 3.2 Sanity-check that no Kotlin path literal needs editing (all device clients interpolate `$base/…`; the web/link surface uses the separate `LINK_ORIGIN`, unaffected).

## 4. Docs

- [x] 4.1 Update `backend/README.md` API map and its consolidated Methods list to show the `/api/v1` paths and note the bare paths as a deprecated grace alias.
- [x] 4.2 Update the route-doc header comment block in `backend/src/app.ts` to reflect the versioned prefix and the bare alias.
- [x] 4.3 Update the `BACKGROUND_UPLOAD_URL_BASE` comment in `Config.xcconfig` (covered by 3.1) to describe the folded-in version prefix.

## 5. Validate & verify

- [x] 5.1 Run `openspec validate --specs --strict` (via the pinned npx form) for the change; resolve any structural issues.
- [x] 5.2 Run `./gradlew build` to confirm nothing in the Kotlin/adapter/architecture guards broke (the world/integration tests use their own bare host and must stay green untouched).
- [ ] 5.3 After merge + bunny deploy, manually verify a device-API route resolves under `/api/v1` through the pull zone before letting a prefixed iOS build reach devices (rollout ordering: server first).
