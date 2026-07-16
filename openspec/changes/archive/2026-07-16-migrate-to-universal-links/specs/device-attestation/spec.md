## MODIFIED Requirements

### Requirement: Ungated routes are a closed list

Exactly the routes named below SHALL be reachable without a token, and the list SHALL be closed — a route
not named here SHALL require the token:

1. `GET /attest/challenge` — it issues the input to attestation and touches no storage.
2. `POST /attest/token` — self-authenticating (it carries the attestation).
3. `POST /attest/renew` — self-authenticating (it carries the assertion).
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
7. `GET /join` and `HEAD /join` — the App Store fallback a browser reaches when an event link is opened
   on a device with no app to claim it (capability `event-link`). By definition its audience holds no
   attestation. This exception SHALL be **exact-path and method-scoped** on the same terms as entry 5. The
   route is a constant redirect: it reads no storage, holds no per-event state, and carries no side
   effect — and it cannot read the link's payload even in principle, because that payload is carried in
   the URL fragment, which a browser never transmits.

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

#### Scenario: The App Store fallback is served without a token

- **WHEN** a `GET /join` (or `HEAD /join`) request arrives without a valid token
- **THEN** it is answered with the App Store redirect, not `401`

#### Scenario: The new exceptions do not leak to other paths or methods

- **WHEN** a request without a valid token arrives for a path that merely begins with `/join` or
  `/.well-known/` but is not exactly one of the two named paths, or a non-`GET`/`HEAD` method arrives for
  either named path
- **THEN** it responds `401`
