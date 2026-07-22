## MODIFIED Requirements

### Requirement: The backend serves a public landing page at the root

The backend SHALL respond to `GET /` (and `HEAD /`) with the SnapSync landing page: HTTP `200`,
`Content-Type: text/html; charset=utf-8`, body the page (for `HEAD`, headers only). The page SHALL be
built by the `web-site` capability and served by **proxying the `site/index.html` object from the storage
`site/` prefix** (per `web-site`), not embedded in the deploy bundle. The route SHALL be reachable
**without a device token** (see `device-attestation`).

#### Scenario: The root returns the page

- **WHEN** a `GET /` request arrives (with or without an `Authorization` header)
- **THEN** the response is `200` with `Content-Type: text/html; charset=utf-8` and the landing-page body

#### Scenario: HEAD on the root is answered

- **WHEN** a `HEAD /` request arrives
- **THEN** the response is `200` with the same headers and no body

#### Scenario: The page is served by proxying storage

- **WHEN** `GET /` is served
- **THEN** the handler fetches the landing page from the storage `site/` prefix and streams it back, and
  makes no request to Apple

### Requirement: The page is the App Store submission surface and is fully self-contained

The served page SHALL carry, in one document, the marketing content, a **Privacy Policy** reachable at
the `#privacy` fragment, and **Terms of Use / EULA** reachable at the `#terms` fragment. It SHALL expose a
**support path** (a link to the project's GitHub issues) and a **contact email**. It SHALL be
**self-contained** per the `web-site` invariant — it SHALL load no off-origin runtime subresource (styles,
scripts, fonts, and images resolve to the same origin) — and it SHALL include **no analytics, tracking, or
cookies**.

#### Scenario: Privacy and Terms are present and anchored

- **WHEN** the page body is inspected
- **THEN** it contains an element with `id="privacy"` (the Privacy Policy) and an element with
  `id="terms"` (the Terms of Use / EULA)

#### Scenario: Support and contact are reachable

- **WHEN** the page body is inspected
- **THEN** it links to `https://github.com/stefanhoelzl/SnapSync/issues` and contains a `mailto:` contact

#### Scenario: No off-origin subresources and no tracking

- **WHEN** the page body is inspected
- **THEN** every style, script, font, and image subresource resolves to the same origin, and the page
  includes no analytics or cookie-setting code

### Requirement: The page shows the app

The landing page SHALL carry screenshots of the real app, produced at build time by `astro:assets` from
the committed raw captures — the same source of truth the App Store listing is built from, so the page and
the listing can never depict different software. The images SHALL be **fingerprinted, same-origin asset
files** (per `web-site`), preserving the page's self-containment (no off-origin subresource).

The screenshots SHALL NOT depict Apple hardware: no device frame, bezel, or other rendering of a phone
around the screen. Apple's third-party guidelines permit a depiction of Apple hardware only when it is an
actual photograph of the genuine product and not an artist's rendering, which a drawn or fetched frame is.

#### Scenario: Screenshots are present as same-origin assets

- **WHEN** the page body is inspected
- **THEN** it contains screenshot images of the app, each referenced by a same-origin, content-hashed asset
  URL and not from any external origin

#### Scenario: The screenshots are the same captures the listing uses

- **WHEN** the committed raw captures are refreshed and deployed
- **THEN** the page serves images derived from those captures

### Requirement: The screenshots match the reader's theme

The page SHALL offer each screenshot in both a light and a dark rendering and SHALL select between them by
the reader's `prefers-color-scheme`, so a dark-mode reader is never shown a light-theme screenshot inside
the page's dark palette (and the inverse). Both renderings SHALL be same-origin assets (per `web-site`).

#### Scenario: A dark-mode reader sees dark screenshots

- **WHEN** the page is rendered by a client reporting `prefers-color-scheme: dark`
- **THEN** the dark rendering of each screenshot is displayed

#### Scenario: A light-mode reader sees light screenshots

- **WHEN** the page is rendered by a client reporting `prefers-color-scheme: light`
- **THEN** the light rendering of each screenshot is displayed

### Requirement: The page carries the app's icon as its favicon

The page SHALL declare a favicon showing the app's icon, as a **same-origin** resource (per `web-site`, an
inlined `data:` URI or a same-origin asset), issuing no off-origin request. It SHALL be sized for a favicon
rather than reusing the full-resolution app icon, which renders at 16–32px and would otherwise dominate the
document.

#### Scenario: A favicon is declared and same-origin

- **WHEN** the page body is inspected
- **THEN** it declares an icon link whose href is same-origin (an inlined `data:` URI or a same-origin
  asset) and not an external origin

## REMOVED Requirements

### Requirement: The landing page is cached at the edge

**Reason**: The landing page's HTML entry point is now an always-fresh `no-cache` shell that references
immutable fingerprinted assets; the `public, max-age` edge-caching of the whole document is superseded by
the split caching model.
**Migration**: Caching is now governed by `web-site` — "HTML is never cached; fingerprinted assets are
immutable" (`no-cache` on `/`; `public, max-age=31536000, immutable` on the assets it references).

### Requirement: The page is revalidatable

**Reason**: With the HTML served `no-cache` and each asset addressed by an immutable content hash, a
content-derived `ETag` on the document is no longer needed for cache correctness; the FNV-over-bundle ETag
machinery is deleted.
**Migration**: See `web-site` — "HTML is never cached; fingerprinted assets are immutable". Freshness of
the HTML is guaranteed by `no-cache`; asset versioning is the content hash in the URL.
