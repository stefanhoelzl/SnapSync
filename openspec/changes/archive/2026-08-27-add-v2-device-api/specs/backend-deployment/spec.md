## MODIFIED Requirements

### Requirement: Device-API routes are served under a versioned prefix

The device-API routes SHALL be served under a **versioned path prefix** of the form `/api/vN`. **More than
one version MAY be served simultaneously**, each as its own mount, and a version already released SHALL
NOT be restructured when a later one is added.

The **web/link paths** — `/`, `/join`, and `/.well-known/apple-app-site-association` — and the **operational
health route** SHALL remain at the **root, un-prefixed**, and SHALL NOT be served under any `/api/vN`. The
web/link paths are not device-API routes; Apple's CDN and browsers require fixed paths for the AASA and the
`/join` universal link. The health route is not a device-API route either — no device calls it — and keeping
it out of the versioned mount means an additional `/api/vN` neither duplicates nor strands it.

The **auth gate** SHALL apply to every `/api/vN` mount: authenticated routes SHALL require a valid bearer
token, and the ungated attest bootstrap routes (`attest/*`) SHALL remain ungated under every version's
`attest/*` — so that token issuance is never gated on possessing a token. The gate SHALL resolve the
prefix **version-agnostically**, so an additional `/api/vN` mount is gated identically with no change to
it. Where more than one middleware resolves the prefix, they SHALL share **one** implementation of that
resolution rather than each carrying its own copy.

The compile-time device-facing base host baked into the app
(`uploadBase` in the generated `Deployment.plist`) SHALL carry **exactly one** version prefix, so that
every device-API request the app and upload extension compose from that base targets that single version.
A build's API version is therefore a property of the build, never of the request path it composes — there
is no per-route version selection, and moving a build to a later version moves every one of its requests
at once. The separate web/link origin constant SHALL NOT carry the prefix.

#### Scenario: A device-API route resolves under the version prefix

- **WHEN** the app issues a device-API request under its baked version prefix through the pull zone
- **THEN** it is routed to that version's device-API route and served with its documented status and
  upstream effect

#### Scenario: Two versions are served side by side

- **WHEN** the deployment serves more than one `/api/vN` mount and a request arrives for each
- **THEN** each is routed to its own version's routes, and the earlier version's routes are unchanged by
  the presence of the later one

#### Scenario: Web/link paths stay at the root, never under a prefix

- **WHEN** Apple's CDN fetches `/.well-known/apple-app-site-association`, a browser opens `/join`, or the
  marketing page is requested at `/`
- **THEN** each is served at its bare root path
- **AND** the same web/link paths are NOT served under any `/api/vN`

#### Scenario: The health route stays at the root, never under a prefix

- **WHEN** the health route is requested at its bare root path
- **THEN** it is served
- **AND** it is NOT served under any `/api/vN`

#### Scenario: Attest bootstrap stays ungated under every prefix

- **WHEN** the app requests `attest/challenge` or `attest/token` under any served version with no bearer
  token
- **THEN** the request is served (the attest routes are ungated)

#### Scenario: An authenticated route requires a token under every prefix

- **WHEN** a non-attest device-API request is made under any served version without a valid bearer token
- **THEN** it is rejected by the auth gate

#### Scenario: The baked base host carries exactly one version prefix

- **WHEN** the compile-time device-facing base is inspected
- **THEN** it carries exactly one version prefix, so every device-API URL the app and upload extension
  compose targets that same version
- **AND** the separate web/link origin constant does not carry the prefix

#### Scenario: The routing admits a further version without restructuring

- **WHEN** a further API version is introduced
- **THEN** it is added as an additional versioned mount alongside the existing ones, without changing any
  existing version's routes
