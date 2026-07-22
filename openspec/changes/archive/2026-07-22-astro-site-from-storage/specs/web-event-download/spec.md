## MODIFIED Requirements

### Requirement: The join path serves a static no-app download page

`GET|HEAD /join` SHALL return a single **static HTML+JS page** (`200`), not a redirect. The page SHALL be
built by the `web-site` capability and served by **proxying the constant `site/join/index.html` object from
the storage `site/` prefix** (per `web-site`). It SHALL be **identical bytes for every event link**: the
backend SHALL read no per-event state, hold no per-event state, and carry no side effect when serving it,
and SHALL NOT read the link payload (it rides in the URL fragment, which a browser never transmits). The
HTML entry point SHALL be served `no-cache` (per `web-site`, an always-fresh shell referencing immutable
fingerprinted assets). It SHALL be reachable without a `device-attestation` token.

The page SHALL present, **unconditionally and with no User-Agent detection**, both a control to **download
all of the event's photos as a zip** and a link to **install SnapSync from the App Store**. No
User-Agent branching is needed because iOS-with-app never reaches `/join` — the operating system claims
the Universal Link before the request is made — so every visitor who lands on the page is a no-app
visitor.

#### Scenario: The join path returns the download page, not a redirect

- **WHEN** `GET /join` is requested (by any no-app visitor, with or without a token)
- **THEN** the backend responds `200` with the static download page — not a `302` redirect and not `401`

#### Scenario: The page is identical for every event link

- **WHEN** `GET /join` is requested for two different event links
- **THEN** the backend returns byte-identical page content (the same constant `site/` object) and reads no
  per-event state, because the payload rides in the fragment and never reaches the backend

#### Scenario: The page offers both download and install

- **WHEN** the download page is rendered
- **THEN** it presents both a "download all photos (zip)" control and a "Get SnapSync" App Store link,
  with no User-Agent-conditional variation of the served bytes

### Requirement: The download page is fully self-contained

The download page SHALL load **no off-origin runtime subresource** — all CSS, all JavaScript, and the zip
library SHALL be inlined into the page or bundled into its same-origin output (per the `web-site`
whole-site invariant). A zip library MAY be a bundled third-party dependency, since a library compiled into
the page's own same-origin output is not an off-origin subresource. This is load-bearing for the
`event-link` fragment-privacy property — a subresource loaded from a third-party host would run in the
page's context (or carry the page URL off-origin), risking the `eventId` in the fragment.

#### Scenario: No off-origin subresource

- **WHEN** the download page and its assembly logic run
- **THEN** every subresource it loads is inlined or same-origin, and no request is made to a third-party
  host (the presigned photo URLs it fetches for the zip are data, not subresources, and are excepted)

#### Scenario: A bundled zip library is same-origin

- **WHEN** the download page assembles a zip using a third-party library
- **THEN** that library ships inside the page's own same-origin output, not fetched from a CDN
