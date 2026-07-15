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
