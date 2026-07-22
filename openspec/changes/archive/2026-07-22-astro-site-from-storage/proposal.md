## Why

The Edge Script bundle has quietly become a website host. The landing page and the no-app `/join`
download page are hand-written HTML embedded in the deployed bundle as text imports, with the landing
page's six screenshots inlined as base64 `data:` URIs via a build-time `{{SHOT_*}}` substitution pass
(`deno task shots` → `shots.generated.ts`). That is ~290 KB of screenshots and two full HTML documents
riding inside the same 10 MB Edge Script that streams every photo upload — and the two pages, though they
are the same brand, **share nothing**: duplicated `<style>`, brand mark, footer, App Store button, and
theme metadata. The authoring pattern (text imports + placeholder substitution + data-URI inlining) is the
friction, and the growing weight is the cost. As the `/join` page is expected to grow into an event gallery
(and, later, a web upload flow), hand-written HTML in the API bundle is the wrong foundation.

## What Changes

- **New `site/` module** — an **Astro** static project builds the two pages from a **shared layout, theme,
  and components**, with `astro:assets` owning the screenshots. Marketing ships zero client JS; `/join`
  opts its download/zip logic in as an island (and may bundle a real zip library instead of the hand-rolled
  writer). Astro runs under Deno's npm-compat (fallback: Node for the `site/` build only).
- **The site is served from storage, proxied by the API.** The build is **mirror-deployed** to a new
  `site/` prefix in the existing storage zone (upload-new-then-delete-stale; no retained generations). The
  Edge Script (`api/`) gains **routing in code** that proxies `/`, `/join`, `/assets/*`, and `/_astro/*`
  from storage `site/`, streaming the object back with cache headers set. The pull zone caches the result,
  so the script serves only cold misses and the always-fresh HTML shell.
- **No edge rules, no account key.** Routing lives in the api bundle (shipped by CI's script-scoped deploy
  key); assets are uploaded with the storage password CI already holds. The account key stays out of the
  pipeline — config-in-source is preserved.
- **Caching:** HTML entry points (`/`, `/join`) are `no-cache`; fingerprinted assets (`/_astro/*`,
  `/assets/*.<hash>.*`) are `public, max-age=31536000, immutable`. The content hash is the version, so no
  content-ETag machinery is needed.
- **Self-containment becomes a whole-site invariant.** The site emits **no off-origin runtime subresource**
  (no CDN scripts/fonts/styles, no external `fetch` beyond the presigned photo URLs `/join` already uses);
  navigational links are unrestricted. Astro makes this the default; a build check over the emitted output
  pins it. This subsumes the `/join`-only "self-contained" rule.
- **BREAKING (internal): the shots pipeline is deleted** — `scripts/shots.ts`, `src/shots.ts`,
  `src/shots.generated.ts`, the three `@jsquash` deps, the `{{SHOT_*}}` substitution, and the two embedded
  HTML files leave `api/`. The `screenshots/*.png` raws stay the source of truth, now consumed by the
  `site/` build via `astro:assets`.
- **Rename `backend/` → `api/`.** A behavior-preserving rename (may ship as a separate mechanical PR
  first). The `api↔sweep` code sharing (`storage.ts`/`lifecycle.ts` imported by both `app.ts` and the
  standalone `sweep.ts`) is preserved unchanged.
- **The nightly sweep stays prefix-scoped** and explicitly ignores `site/`; `site/` hygiene is owned by the
  mirror deploy, not the sweep.

## Capabilities

### New Capabilities
- `web-site`: the `site/` Astro project as the single source of the browser-facing pages — shared
  layout/theme/components, the two pages, `astro:assets` images, the no-off-origin-subresource invariant,
  content-hashed immutable assets + `no-cache` HTML, the mirror deploy to the storage `site/` prefix, and
  the API's static-proxy serving of those paths from storage.

### Modified Capabilities
- `marketing-site`: the landing page is no longer embedded in the api bundle and is no longer
  source-owned/served-from-memory; it is built by `web-site` and served by proxying storage `site/`. The
  build-time screenshot inlining (`shots` pipeline) is removed.
- `web-event-download`: the `/join` page is built by `web-site` (Astro) and served by proxy from storage;
  its "static, self-contained, byte-identical, reads no state" guarantees are preserved, with
  self-containment generalized to the whole-site "no off-origin runtime subresources" invariant.
- `backend-deployment`: `backend/` → `api/`; the storage layout gains the public `site/` prefix; a second
  deploy path (the `site/` build → storage via the storage password) joins the code deploy; the api's
  static-proxy routing is source-owned config (no edge rules, no account key); the sweep is affirmed
  prefix-scoped and ignores `site/`.

## Impact

- **Affected code:** new `site/` Astro module; `api/` (renamed from `backend/`) gains a static-proxy route
  handler and loses `landing.html`, `download.html`, `scripts/shots.ts`, `src/shots.ts`,
  `src/shots.generated.ts`; `deno.json` loses the `@jsquash` deps and the `shots` task.
- **Affected CI:** `backend-deploy.yml` (path filter `backend/**`→`api/**`, working-directory, possible
  rename to `api-deploy.yml`); `nightly-cleanup.yml` (`working-directory`); a new `site/` build+deploy
  workflow (Astro build → mirror upload to storage `site/` with the storage password).
- **Storage:** the existing `snap-sync-dev` zone gains a public `site/` prefix (co-tenant with the private
  `files/`/`events/`/`devices/`; public reach is scoped by the proxy, not the zone).
- **Unaffected:** `snapsync.stho.net` (the pull zone + DNS are unchanged); the AASA (`event-link`, still
  served directly by the api); `appstore-screenshots.yml` and `screenshots.yml` (they consume the raws
  independently); every device-API route and the attestation gate.
- **Credentials:** no new credential; the account key stays out of CI.
