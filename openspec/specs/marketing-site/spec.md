# marketing-site Specification

## Purpose

The public face of SnapSync and its App Store submission surface. App Store review requires a reachable
Privacy Policy URL (which was absent) and a Support URL; the product also needs a page a person reaches by
opening `snapsync.stho.net` in a browser — today the device-facing origin, which otherwise answers nothing
but the attestation-gated API. Rather than stand up a second host, the backend Edge Script — already
fronting the owned domain through a bunny pull zone — serves **one self-contained static page** at `GET /`
(and `HEAD /`): the marketing content, the Privacy Policy (`#privacy`), and the Terms/EULA (`#terms`), with
support routed to the project's GitHub issues. The page is **source-owned** (embedded in the deployed
bundle, no runtime file read or upstream call) so it cannot drift from the code that serves it, and
**cacheable at the pull zone** so it is served from the edge and never sits on the upload hot path. It is
the **sole public, unauthenticated route** in an otherwise fully attested API — an exact-path exception
that widens the gate for nothing else (see `device-attestation`).

Decision record: `changes/archive/2026-07-15-add-marketing-page`.

## Requirements
### Requirement: The backend serves a public landing page at the root

The backend SHALL respond to `GET /` (and `HEAD /`) with the SnapSync landing page: HTTP `200`,
`Content-Type: text/html; charset=utf-8`, body the embedded page (for `HEAD`, headers only). The page
SHALL be **source-owned** — compiled into the deployed bundle at build time and served from memory, with
**no runtime filesystem read and no upstream storage or Apple call** — consistent with the
source-owned-configuration rule of `backend-deployment`. The route SHALL be reachable **without a device
token** (see `device-attestation`).

#### Scenario: The root returns the page

- **WHEN** a `GET /` request arrives (with or without an `Authorization` header)
- **THEN** the response is `200` with `Content-Type: text/html; charset=utf-8` and the landing-page body

#### Scenario: HEAD on the root is answered

- **WHEN** a `HEAD /` request arrives
- **THEN** the response is `200` with the same headers and no body

#### Scenario: Serving the page performs no upstream request

- **WHEN** `GET /` is served
- **THEN** the handler makes no request to the storage zone or to Apple, and reads no file at runtime

### Requirement: The page is the App Store submission surface and is fully self-contained

The served page SHALL carry, in one document, the marketing content, a **Privacy Policy** reachable at
the `#privacy` fragment, and **Terms of Use / EULA** reachable at the `#terms` fragment. It SHALL expose a
**support path** (a link to the project's GitHub issues) and a **contact email**. It SHALL be
**self-contained**: every style, script, and image is inlined so the page issues **no external network
request**, and it SHALL include **no analytics, tracking, or cookies**.

#### Scenario: Privacy and Terms are present and anchored

- **WHEN** the page body is inspected
- **THEN** it contains an element with `id="privacy"` (the Privacy Policy) and an element with
  `id="terms"` (the Terms of Use / EULA)

#### Scenario: Support and contact are reachable

- **WHEN** the page body is inspected
- **THEN** it links to `https://github.com/stefanhoelzl/SnapSync/issues` and contains a `mailto:` contact

#### Scenario: No external dependencies and no tracking

- **WHEN** the page body is inspected
- **THEN** it references no external origin for styles, scripts, fonts, or images, and includes no
  analytics or cookie-setting code

### Requirement: The landing page is cached at the edge

The `GET /` response SHALL send a **cacheable** `Cache-Control` (a `public` directive with a positive
`max-age`) so the fronting bunny pull zone serves it from the edge and the Edge Script is not on the
request hot path — the deliberate inverse of the listing routes, which send `no-cache`.

#### Scenario: The root response is cacheable

- **WHEN** `GET /` is served
- **THEN** the response carries a `Cache-Control` header with `public` and a positive `max-age`

