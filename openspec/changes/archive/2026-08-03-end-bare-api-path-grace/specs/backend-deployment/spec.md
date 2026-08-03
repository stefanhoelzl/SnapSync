## RENAMED Requirements

- FROM: `### Requirement: Device-API routes are served under a versioned prefix, with bare paths a deprecated grace alias`
- TO: `### Requirement: Device-API routes are served under a versioned prefix`

## MODIFIED Requirements

### Requirement: Device-API routes are served under a versioned prefix

The device-API routes SHALL be served under a **versioned path prefix** of the form `/api/vN`, where the
current version is **`/api/v1`** (e.g. `POST /api/v1/events`, `PUT /api/v1/files/devices/:id/:filename`,
`GET /api/v1/attest/challenge`). The routing SHALL be structured so that additional versions can be mounted
alongside `/api/v1` without restructuring the existing version's routes.

The **web/link paths** — `/`, `/join`, and `/.well-known/apple-app-site-association` — SHALL remain at the
**root, un-prefixed**, and SHALL NOT be served under `/api/v1`. They are not device-API routes; Apple's CDN
and browsers require fixed paths for the AASA and the `/join` universal link.

The **auth gate** SHALL apply to the `/api/v1` routes: authenticated routes SHALL require a valid bearer
token, and the ungated attest bootstrap routes (`attest/*`) SHALL remain ungated under
`/api/v1/attest/*` — so that token issuance is never gated on possessing a token. The gate SHALL resolve
the prefix version-agnostically, so a future `/api/vN` mount is gated identically with no change to it.

The compile-time device-facing base host baked into the app
(`BACKGROUND_UPLOAD_URL_BASE` / `BackgroundUploadURLBase`) SHALL carry the current version prefix, so that
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
