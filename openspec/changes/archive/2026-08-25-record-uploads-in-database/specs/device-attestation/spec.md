## MODIFIED Requirements

### Requirement: Ungated routes are a closed list

Exactly the routes named below SHALL be reachable without a token, and the list SHALL be closed — a route
not named here SHALL require the token:

1. `GET /api/v1/attest/challenge` — it issues the input to attestation and touches no storage.
2. `POST /api/v1/attest/token` — self-authenticating (it carries the attestation).
3. `POST /api/v1/attest/renew` — self-authenticating (it carries the assertion).
4. `OPTIONS` on any path — the pull zone is free to answer the preflight itself, so the script cannot
   gate it; and a `401` there would break the plain-`PUT` fallback the iOS uploader depends on.
5. `GET /` and `HEAD /` — the public marketing/landing page (capability `marketing-site`). This exception
   SHALL be **exact-path and method-scoped**: it applies only when the method is `GET` or `HEAD` **and**
   the path is exactly `/`. It SHALL NOT be a path prefix and SHALL NOT admit any other method, so no
   gated route can be reached through it. The page reads no storage and carries no side effect, so serving
   it unauthenticated grows neither the bill nor the storage this gate protects.
6. `GET /.well-known/apple-app-site-association` and `HEAD` on the same path — the Apple App Site
   Association document that makes the event link a Universal Link (capability `event-link`). Apple's CDN
   and the device fetch it with no `Authorization` header and cannot be made to send one, so a gated route
   here would silently defeat every event link. This exception SHALL be **exact-path and method-scoped**
   on the same terms as entry 5. The document is a static, source-owned constant: it reads no storage and
   carries no side effect.
7. `GET /join` and `HEAD /join` — the no-app download page a browser reaches when an event link is opened
   on a device with no app to claim it (capabilities `event-link`, `web-event-download`). By definition
   its audience holds no attestation. This exception SHALL be **exact-path and method-scoped** on the same
   terms as entry 5. The page is a static, source-owned constant: it reads no storage, holds no per-event
   state, and carries no side effect — and it cannot read the link's payload even in principle, because
   that payload is carried in the URL fragment, which a browser never transmits.
8. `GET /api/v1/events/<eventId>/files` and `HEAD` on the same path — the event photo **union read** (capability
   `api-endpoints`), which the no-app download page fetches from a browser that holds no attestation
   (capability `web-event-download`). This exception SHALL be **method-scoped**: it admits only `GET` and
   `HEAD` on the union path; every non-`GET`/`HEAD` method on any `/api/v1/events/<eventId>/…` path (device
   manifest write, leave, notify) SHALL remain gated.
9. `GET /api/v1/events/<eventId>` and `HEAD` on the same path — the event **marker/metadata read** (capability
   `api-endpoints`), which the download page fetches to show the event name. This exception SHALL be
   **method-scoped**: it admits only `GET` and `HEAD`; `POST /api/v1/events` (creation) SHALL remain gated.

Entries 8 and 9 restate the gate's posture rather than carve a hole in it: attestation was never a
read-authorization mechanism (it "says nothing about which device may read whose photos", and the
presigned bytes it fronts were always ungated). It gates **writes** (byte `PUT`, event creation, manifest,
leave, notify) and — until this change — **existence-probing** on reads. Opening the two read routes
authorizes **event reads by `eventId`-possession alone**: the `eventId` is the read capability. This is an
accepted, eyes-open cost — it lets any HTTP client that learns an `eventId` read the whole event union
(billed as storage egress), and because the union mints a fresh presigned URL on every call, a leaked
`eventId` becomes a **perpetual** read grant rather than the inert value it is today. It is accepted with
no per-event opt-in and no rate limit. Decision record: `changes/archive/2026-07-21-web-event-download`.

This requirement absorbs five per-endpoint duplicates deleted by this change — `bunny-upload-endpoint`'s
*Writes require a device token*, `event-creation`'s *Event routes require a device token*,
`event-leave-endpoint`'s *Leave requires a device token*, `event-notify-endpoint`'s *Notify requires a
device token*, and `device-config-endpoint`'s *The device-config write requires a device token*. They
existed only because there were five endpoint specs; the rule and its closed list of exceptions were always
stated here. **No route's gating changes.** `api-endpoints`' route table carries a `gated` column as a
reader's summary and defers to this list as the authority.

#### Scenario: A new route defaults to gated

- **WHEN** a route not named in the list above receives a request without a valid token
- **THEN** it responds `401`

#### Scenario: OPTIONS is answered without a token

- **WHEN** an `OPTIONS` preflight arrives without an `Authorization` header
- **THEN** it is answered normally, advertising no resumable upload, and the uploader proceeds with a
  plain `PUT`

#### Scenario: The marketing page is served without a token

- **WHEN** a `GET /` (or `HEAD /`) request arrives without a valid token
- **THEN** it is answered with the landing page (`200`), not `401`

#### Scenario: The root exception does not leak to other paths

- **WHEN** a `GET` request without a valid token arrives for any path other than exactly `/`, or a
  non-`GET`/`HEAD` method arrives for `/`
- **THEN** it responds `401`

#### Scenario: The AASA is served without a token

- **WHEN** a `GET /.well-known/apple-app-site-association` (or `HEAD`) request arrives without a valid
  token
- **THEN** it is answered with the AASA document (`200`, `application/json`), not `401`

#### Scenario: The join download page is served without a token

- **WHEN** a `GET /join` (or `HEAD /join`) request arrives without a valid token
- **THEN** it is answered with the static download page (`200`), not `401`

#### Scenario: The event union read is served without a token

- **WHEN** a `GET /api/v1/events/<uuid>/files` (or `HEAD`) request arrives without a valid token
- **THEN** it is answered by the union route (subject to that route's own existence gate), not `401`

#### Scenario: The event metadata read is served without a token

- **WHEN** a `GET /api/v1/events/<uuid>` (or `HEAD`) request arrives without a valid token
- **THEN** it is answered by the metadata route (its fields or `404`), not `401`

#### Scenario: A write method on an ungated read path stays gated

- **WHEN** a request without a valid token uses a non-`GET`/`HEAD` method on an `/api/v1/events/<uuid>/…`
  path
  (e.g. `PUT`/`DELETE` a device manifest, `POST …/notify`) or `POST /api/v1/events`
- **THEN** it responds `401` — only the `GET`/`HEAD` reads are ungated

#### Scenario: The static exceptions do not leak to other paths or methods

- **WHEN** a request without a valid token arrives for a path that merely begins with `/join` or
  `/.well-known/` but is not exactly one of the two named paths, or a non-`GET`/`HEAD` method arrives for
  either named path
- **THEN** it responds `401`
