## ADDED Requirements

### Requirement: A maintenance window answers every device-API route with 503

The application SHALL answer **`503`** to every request under the **`/api/` prefix** while the bundle
serving it carries the maintenance flag (capability `backend-deployment`) — before any other handling, and
making no storage or database request for it.

The match SHALL be the **prefix**, not an enumeration of routes. A closed list can be omitted from — a
route added later lands ungated by nobody's decision — whereas a prefix cannot, and a future
`/api/v2` mount inherits the gate by construction.

`503` is the status HTTP defines for exactly this: a temporary inability to serve due to scheduled
maintenance. The response SHALL carry `Retry-After`, which HTTP pairs with it, and SHALL carry the
listings' no-cache directives.

**The no-cache directives are load-bearing, not decoration.** A pull zone fronts every request and caches
on the origin's directives; a cached `503` would outlive the window and turn a bounded, deliberate outage
into an unbounded accidental one. The deploy workflow cannot configure the pull zone, so the origin's
header is the only lever, and its behaviour SHALL be verified **through the pull zone** rather than at the
origin alone.

The gate SHALL run **before** the device-token gate (capability `device-attestation`), so an unauthenticated
request during the window is answered `503` rather than `401`. That is both cheaper — no token verification
— and truthful: the service is unavailable, and the caller's credentials are not what is wrong. Nothing is
disclosed by answering before authentication that the health route does not already disclose publicly.

Routes served at the **root** — the marketing page, the no-app download page, the site's fingerprinted
assets, the AASA document, and the health route — SHALL NOT be gated. They read only the public storage
`site/` prefix or nothing at all, never the relational store, so a schema migration has no bearing on them,
and the health route is how the deploy learns the window's state.

Downloads are unaffected by construction: presigned S3 URLs are fetched directly from the storage
provider's S3 endpoint and never reach this application.

#### Scenario: A device-API request during the window is refused

- **WHEN** a request under `/api/` arrives while the serving bundle carries the maintenance flag
- **THEN** it is answered `503` with `Retry-After` and no-cache directives, and no storage or database
  request is made

#### Scenario: Maintenance is answered before authentication

- **WHEN** a request under `/api/` arrives during the window carrying no valid device token
- **THEN** it is answered `503`, not `401`

#### Scenario: A future version prefix is gated without being enumerated

- **WHEN** a request under a device-API version prefix other than `/api/v1` arrives during the window
- **THEN** it is answered `503`, because the gate matches the `/api/` prefix rather than a list of routes

#### Scenario: Root routes keep serving during the window

- **WHEN** the marketing page, the download page, a fingerprinted site asset, the AASA document, or the
  health route is requested during the window
- **THEN** it is served normally

#### Scenario: The window's refusal is not cached past the window

- **WHEN** a device-API route is requested through the pull zone after the window closes
- **THEN** the response is served by the application, not from a cached `503`
