## ADDED Requirements

### Requirement: The browser-facing pages are one Astro project with a shared layer

The two browser-facing pages SHALL be authored in a single **Astro** project (the `site/` module): the
marketing landing (`marketing-site`) and the no-app download page (`web-event-download`). The project SHALL
build them from a **shared layout, theme, and components** (the page shell, brand mark, footer, App Store
button, theme-color metadata, and favicon are defined once and used by both), SHALL be the single source of
both pages, and neither page SHALL be embedded in the `api/` deploy bundle.

#### Scenario: Both pages build from the shared project

- **WHEN** the `site/` project is built
- **THEN** it emits the landing page and the `/join` download page, each composed from the shared
  layout/theme/components

#### Scenario: The pages are not in the API bundle

- **WHEN** the `api/` deploy bundle is inspected
- **THEN** it contains neither the landing-page HTML nor the download-page HTML

### Requirement: The site emits no off-origin runtime subresource

The built site SHALL load **no off-origin runtime subresource**: no script, stylesheet, font, image, or
`iframe` from any host other than `snapsync.stho.net`, and no `@font-face` remote `url()`, and no external
`fetch` other than the presigned photo URLs the `/join` page already uses. **Bundled dependencies are
permitted** — a third-party library compiled into the site's own same-origin output (e.g. a zip library on
`/join`) is not an off-origin subresource. Navigational links (`<a href>` to the App Store, GitHub, or
`mailto:`) are unrestricted. This invariant applies to the **whole site** (it generalizes and subsumes the
`/join`-only self-contained rule) because the two pages share a layout, and it exists so `/join` can never
leak the `eventId` in its fragment to a third party.

#### Scenario: No off-origin subresource in the emitted output

- **WHEN** the built site's emitted HTML/CSS/JS is inspected
- **THEN** every subresource `src`/`href`/`url()` resolves to the same origin, and there is no external
  analytics, font, script, or style reference

#### Scenario: A bundled dependency is same-origin

- **WHEN** the `/join` page bundles a zip library
- **THEN** the library ships inside the page's own `_astro/*` output served from `snapsync.stho.net`, not
  fetched from a CDN

### Requirement: Images are build-time optimized, fingerprinted, same-origin assets

The landing page's screenshots SHALL be produced by Astro's build-time image pipeline (`astro:assets`)
from the committed raw captures in `screenshots/` — the same source of truth the App Store listing is
composited from — and emitted as **fingerprinted, same-origin** asset files (not base64 `data:` URIs and
not runtime-fetched). Refreshing the committed raws SHALL change the images the built page references.

#### Scenario: Screenshots are fingerprinted same-origin assets

- **WHEN** the built landing page is inspected
- **THEN** each screenshot is referenced by a same-origin, content-hashed asset URL, not an inlined `data:`
  URI

#### Scenario: The screenshots track the committed raws

- **WHEN** the committed raw captures are refreshed and the site is rebuilt
- **THEN** the page references images derived from those captures

### Requirement: The site build is mirror-deployed to the storage `site/` prefix

The built site SHALL be deployed to a `site/` prefix in the backend storage zone by **mirroring**: the
deploy SHALL upload the new build (HTML at stable keys via last-write-wins; fingerprinted assets at new
keys) and THEN delete any `site/` object not present in the new build, so the prefix reflects exactly the
current build with no retained generations. The deploy SHALL upload before deleting (never clear the prefix
first). The deploy SHALL authenticate with the **storage-zone password** already available to CI; it SHALL
NOT require the bunny account key.

#### Scenario: A deploy mirrors the prefix

- **WHEN** the site is deployed
- **THEN** `site/` contains exactly the new build's files, and objects from the previous build that are not
  in the new build are removed

#### Scenario: The prefix is never emptied mid-deploy

- **WHEN** a deploy runs
- **THEN** new objects are uploaded before stale objects are deleted, so no request observes an empty
  `site/` prefix

#### Scenario: The deploy uses no account key

- **WHEN** the site deploy runs in CI
- **THEN** it authenticates with the storage-zone password only, and the bunny account key is not present

### Requirement: The API serves the site by proxying the storage `site/` prefix

The `api/` Edge Script SHALL serve the site by **routing in code** (source-owned, no pull-zone edge rules,
no account key): for the closed set of static paths `/`, `/join`, `/assets/*`, and `/_astro/*` (and the
site's other emitted top-level static files such as the favicon), it SHALL fetch the corresponding object
from the storage `site/` prefix and stream it back; every other path SHALL continue to the existing device
API and gate. The proxy SHALL set the response's cache headers itself (see the caching requirement) rather
than passing through the storage API's. These static paths SHALL be reachable **without a device token**.

#### Scenario: A static path is served from storage

- **WHEN** `GET /` (or `/join`, or a `/_astro/*` asset) is requested
- **THEN** the api fetches the corresponding `site/` object from storage and returns it as the response
  body, without a device token

#### Scenario: Non-static paths are unaffected

- **WHEN** a device-API path (e.g. `POST /api/v1/events`) is requested
- **THEN** it is handled by the existing API and gate, not proxied to storage

#### Scenario: The routing is in the deployed code

- **WHEN** the `api/` bundle is inspected
- **THEN** the static-path allowlist and its proxy behavior are present as source-owned code, and no
  pull-zone edge rule is required for the site to be reachable

### Requirement: HTML is never cached; fingerprinted assets are immutable

The proxy SHALL set `Cache-Control: no-cache` on the HTML entry points (`/` and `/join`) and
`Cache-Control: public, max-age=31536000, immutable` on fingerprinted assets (`/_astro/*`,
`/assets/*.<hash>.*`). Because the content hash is the asset's version, the response SHALL NOT require a
content-derived `ETag` for cache correctness.

#### Scenario: HTML entry points are no-cache

- **WHEN** `GET /` or `GET /join` is served
- **THEN** the response carries `Cache-Control: no-cache`

#### Scenario: Fingerprinted assets are immutable

- **WHEN** a `/_astro/*` or `/assets/*.<hash>.*` asset is served
- **THEN** the response carries `Cache-Control: public, max-age=31536000, immutable`
