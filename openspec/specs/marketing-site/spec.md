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
**cacheable at the pull zone** so it is served from the edge and never sits on the upload hot path.

It also **shows the app**, from the very same committed raw captures the App Store listing is composited
from — so the storefront and the site can never depict different software, and refreshing one refreshes
both. Source-owned extends to them: the images are *derived at build time* and inlined, never fetched, which
is what keeps "no external request" true of a page that is now mostly pictures. That weight is why the
response is revalidatable: `max-age` already bounds staleness, and an entity tag makes the re-check that
follows cost nothing rather than re-sending the page. It is
**one of a small closed set** of public, unauthenticated routes in an otherwise fully attested API (the
event link's AASA and its `GET /join` App Store fallback are the others, and belong to capability
`event-link`) — an exact-path exception
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

### Requirement: The page shows the app

The landing page SHALL carry screenshots of the real app, derived at build time from the committed raw
captures — the same source of truth the App Store listing is built from, so the page and the listing can
never depict different software. The images SHALL be **inlined** like every other asset, preserving the
page's self-containment (no external network request), and SHALL be built into the bundle at build time,
with no runtime filesystem read and no upstream call.

The screenshots SHALL NOT depict Apple hardware: no device frame, bezel, or other rendering of a phone
around the screen. Apple's third-party guidelines permit a depiction of Apple hardware only when it is an
actual photograph of the genuine product and not an artist's rendering, which a drawn or fetched frame is.

#### Scenario: Screenshots are present and inlined

- **WHEN** the page body is inspected
- **THEN** it contains screenshot images of the app, each referenced as an inlined `data:` URI and not from
  any external origin

#### Scenario: Serving the page with screenshots performs no upstream request

- **WHEN** `GET /` is served
- **THEN** the handler makes no request to the storage zone or to Apple, and reads no file at runtime

#### Scenario: The screenshots are the same captures the listing uses

- **WHEN** the committed raw captures are refreshed and deployed
- **THEN** the page serves images derived from those captures

### Requirement: The screenshots match the reader's theme

The page SHALL offer each screenshot in both a light and a dark rendering and SHALL select between them by
the reader's `prefers-color-scheme`, so a dark-mode reader is never shown a light-theme screenshot inside
the page's dark palette (and the inverse). Both renderings SHALL be inlined.

#### Scenario: A dark-mode reader sees dark screenshots

- **WHEN** the page is rendered by a client reporting `prefers-color-scheme: dark`
- **THEN** the dark rendering of each screenshot is displayed

#### Scenario: A light-mode reader sees light screenshots

- **WHEN** the page is rendered by a client reporting `prefers-color-scheme: light`
- **THEN** the light rendering of each screenshot is displayed

### Requirement: The page is revalidatable

The `GET /` response SHALL carry an `ETag` derived from the served page, so that it changes exactly when the
page changes and cannot be forgotten on a deploy. When a request presents a matching `If-None-Match`, the
response SHALL be `304` with no body and SHALL still carry the caching headers, so a cache learns from it;
a non-matching `If-None-Match` SHALL be served the page as normal.

This does **not** invalidate anything — a cache serves its stored copy for `max-age` without revalidating,
so staleness is already bounded by "The landing page is cached at the edge". It bounds the *cost* of the
revalidation that follows: inlining the screenshots takes the page to roughly 290KB, so a returning client
re-checking it exchanges a full re-send for an empty 304.

#### Scenario: The response carries an ETag

- **WHEN** `GET /` is served
- **THEN** the response carries an `ETag` header

#### Scenario: A matching conditional request is answered 304

- **WHEN** `GET /` is requested with an `If-None-Match` equal to the page's current `ETag`
- **THEN** the response is `304` with no body, and still carries the `Cache-Control` and `ETag` headers

#### Scenario: A stale conditional request is served the page

- **WHEN** `GET /` is requested with an `If-None-Match` that does not match
- **THEN** the response is `200` with the landing-page body

#### Scenario: The ETag tracks the page

- **WHEN** the served page's content is unchanged
- **THEN** its `ETag` is unchanged

### Requirement: The page carries the app's icon as its favicon

The page SHALL declare a favicon showing the app's icon, **inlined** like every other asset so the page
issues no external network request. It SHALL be sized for a favicon rather than reusing the full-resolution
app icon, which renders at 16–32px and would otherwise dominate the document.

#### Scenario: A favicon is declared and inlined

- **WHEN** the page body is inspected
- **THEN** it declares an icon link whose href is an inlined `data:` URI and not an external origin

### Requirement: The screenshots are browsable without scripting

The screenshots SHALL be browsable on a narrow viewport without any script, preserving the page's
no-JavaScript property: the page SHALL ship no `<script>` tag. The screenshot region SHALL be reachable and
scrollable by keyboard and SHALL carry an accessible name. Any headline accompanying a screenshot SHALL be
real document text, not pixels baked into the image, so it is selectable and available to assistive
technology.

#### Scenario: No script is introduced

- **WHEN** the page body is inspected
- **THEN** it contains no `<script>` tag

#### Scenario: Headlines are text, not image content

- **WHEN** the page body is inspected
- **THEN** each screenshot's accompanying headline is present as document text

#### Scenario: The screenshot region is keyboard reachable

- **WHEN** the page body is inspected
- **THEN** the screenshot region is focusable and carries an accessible name

