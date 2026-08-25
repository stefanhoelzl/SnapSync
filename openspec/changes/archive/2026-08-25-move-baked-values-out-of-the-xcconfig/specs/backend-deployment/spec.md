## MODIFIED Requirements

### Requirement: Device-facing origin is a custom domain under our control

The device-facing origin SHALL be a **custom domain we control** through our own DNS (a Bunny DNS
zone) — not a runtime-provider vanity hostname. It SHALL be `CNAME`'d to the bunny pull zone fronting
the Edge Script and served with a **publicly-trusted TLS certificate** (default ATS applies; no
`NSAppTransportSecurity` exception ships, so a non-HTTPS or privately-signed origin is unacceptable).
The compile-time baked host (`uploadBase` in the generated `Deployment.plist`) SHALL be **this
custom domain**, so device→backend traffic for uploads, event creation, and listings shares one origin
we own. Photo **download** bytes do **not** share this origin — they are served by bunny's S3 endpoint
(`<region>-s3.storage.bunnycdn.com`) against a presigned URL, itself a publicly-trusted HTTPS host
covered by default ATS with no exception.

#### Scenario: App reaches the backend over the custom domain via HTTPS

- **WHEN** the app issues an upload, event-creation, or list request
- **THEN** it targets the custom domain over HTTPS, which presents a publicly-trusted certificate

#### Scenario: The baked host names the custom domain, not a bunny hostname

- **WHEN** the baked `BackgroundUploadURLBase` is inspected
- **THEN** it names the custom-domain origin we control, not the pull zone's bunny-provided hostname

#### Scenario: Download bytes come from bunny's S3 endpoint, not the custom domain

- **WHEN** the app downloads a photo's bytes via a presigned `url`
- **THEN** the request targets bunny's S3 endpoint over HTTPS (default ATS, no exception), not the
  custom-domain origin

### Requirement: Device-API routes are served under a versioned prefix

The device-API routes SHALL be served under a **versioned path prefix** of the form `/api/vN`, where the
current version is **`/api/v1`** (e.g. `POST /api/v1/events`, `PUT /api/v1/files/devices/:id/:filename`,
`GET /api/v1/attest/challenge`). The routing SHALL be structured so that additional versions can be mounted
alongside `/api/v1` without restructuring the existing version's routes.

The **web/link paths** — `/`, `/join`, and `/.well-known/apple-app-site-association` — and the **operational
health route** SHALL remain at the **root, un-prefixed**, and SHALL NOT be served under `/api/v1`. The
web/link paths are not device-API routes; Apple's CDN and browsers require fixed paths for the AASA and the
`/join` universal link. The health route is not a device-API route either — no device calls it — and keeping
it out of the versioned mount means a future `/api/vN` neither duplicates nor strands it.

The **auth gate** SHALL apply to the `/api/v1` routes: authenticated routes SHALL require a valid bearer
token, and the ungated attest bootstrap routes (`attest/*`) SHALL remain ungated under
`/api/v1/attest/*` — so that token issuance is never gated on possessing a token. The gate SHALL resolve
the prefix version-agnostically, so a future `/api/vN` mount is gated identically with no change to it.

The compile-time device-facing base host baked into the app
(`uploadBase` in the generated `Deployment.plist`) SHALL carry the current version prefix, so that
every device-API request the app and upload extension compose from that base targets `/api/v1`. The
separate web/link origin constant SHALL NOT carry the prefix.

#### Scenario: A device-API route resolves under the version prefix

- **WHEN** the app issues a device-API request under `/api/v1` (e.g. `POST /api/v1/events`) through the
  pull zone
- **THEN** it is routed to that device-API route and served with its documented status and upstream effect

#### Scenario: Web/link paths stay at the root, never under the prefix

- **WHEN** Apple's CDN fetches `/.well-known/apple-app-site-association`, a browser opens `/join`, or the
  marketing page is requested at `/`
- **THEN** each is served at its bare root path
- **AND** the same web/link paths are NOT served under `/api/v1`

#### Scenario: The health route stays at the root, never under the prefix

- **WHEN** the health route is requested at its bare root path
- **THEN** it is served
- **AND** it is NOT served under `/api/v1`

#### Scenario: Attest bootstrap stays ungated under the prefix

- **WHEN** the app requests `GET /api/v1/attest/challenge` or `POST /api/v1/attest/token` with no bearer
  token
- **THEN** the request is served (the attest routes are ungated)

#### Scenario: An authenticated route requires a token under the prefix

- **WHEN** a non-attest device-API request is made under `/api/v1/…` without a valid bearer token
- **THEN** it is rejected by the auth gate

#### Scenario: The baked base host carries the version prefix

- **WHEN** the compile-time `BackgroundUploadURLBase` is inspected
- **THEN** it carries the current version prefix (`/api/v1`), so every device-API URL the app and upload
  extension compose targets `/api/v1`
- **AND** the separate web/link origin constant does not carry the prefix

#### Scenario: The routing admits a future version without restructuring

- **WHEN** a future API version is introduced
- **THEN** it is added as an additional versioned mount alongside `/api/v1`, without changing `/api/v1`'s
  routes
