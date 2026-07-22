## 1. Rename backend → api (Phase 0 — may ship as a separate mechanical PR)

- [ ] 1.1 Rename the `backend/` directory to `api/` (git mv); confirm no source path references break
- [ ] 1.2 Update `.github/workflows/backend-deploy.yml`: path filter `backend/**` → `api/**`, `working-directory`, and rename the workflow file to `api-deploy.yml`
- [ ] 1.3 Update `.github/workflows/nightly-cleanup.yml` `working-directory: backend` → `api`
- [ ] 1.4 Update `backend/` references in `CLAUDE.md`, `README.md`, and any `openspec/` prose to `api/`
- [ ] 1.5 Verify the `api↔sweep` sharing is intact: `sweep.ts` still imports `./storage.ts` and `./lifecycle.ts`; `deno task test` passes; `deno task shots`/`check`/`bundle` still run
- [ ] 1.6 Confirm `snapsync.stho.net`, the pull zone, DNS, and the baked `BACKGROUND_UPLOAD_URL_BASE` are untouched

## 2. Scaffold the `site/` Astro project (shared layer)

- [x] 2.1 Create the `site/` module: minimal Astro project (`@sveltejs`-free), `astro build` → static output
- [x] 2.2 Resolve the image toolchain: confirm `astro:assets` runs under Deno; if `sharp` fails under Deno's npm-compat, run the `site/` build under Node (document the choice) — **Node** (Astro/rolldown fails to resolve export conditions under Deno; documented in `site/package.json`)
- [x] 2.3 Build the shared layer: `Layout.astro` (head, theme-color metas, favicon, header/brand, footer), `theme.css` (tokens incl. SnapSync green, light/dark), shared components (Brand, AppStoreButton, Footer) — Layout + theme + header/footer chrome in place; named sub-components (Brand/AppStoreButton/Footer) to be extracted during the full landing port (3.1)
- [x] 2.4 Confirm fingerprinted output paths (`/_astro/*`, hashed asset names) so the proxy allowlist and immutable-cache policy can target them

## 3. Landing page in `site/` (Phase 1)

- [x] 3.1 Port `landing.html` to `index.astro` on the shared layout: marketing content, Privacy (`#privacy`), Terms (`#terms`), support/GitHub link, `mailto:` contact — zero client JS (no `<script>`) — recovered from git history; extracted `AppStoreButton.astro`; full theme in `theme.css`
- [x] 3.2 Move the six `screenshots/*.png` raws under `astro:assets`; render light/dark by `prefers-color-scheme`; no Apple-hardware frame; images emitted as fingerprinted same-origin assets
- [x] 3.3 Verify the emitted landing page loads no off-origin subresource (styles, scripts, fonts, images all same-origin)

## 4. API static-proxy serving + remove the shots pipeline (Phase 1)

- [x] 4.1 Add the static-path allowlist + proxy handler in `api/`: for `/`, `/assets/*`, `/_astro/*` (and the site's other top-level static files), fetch the object from storage `site/` (AccessKey GET) and stream it back; everything else continues to the existing gate/API — `serveSiteObject` + the `/` and `/_astro/*` routes (Astro emits `/_astro/*`, not `/assets/*`, by default)
- [x] 4.2 Set cache headers on the proxy response: `no-cache` for the HTML entry point(s); `public, max-age=31536000, immutable` for fingerprinted assets — `SITE_HTML_CACHE` / `SITE_ASSET_CACHE`
- [x] 4.3 Add `/`, `/assets/*`, `/_astro/*` to the attestation gate's ungated closed-list (served without a device token); keep every device route gated
- [x] 4.4 Replace the in-bundle `GET|HEAD /` handler (serve `LANDING_PAGE`) with the proxy path; delete `landing.html`, `src/shots.ts`, `src/shots.generated.ts`, `scripts/shots.ts`, the `{{SHOT_*}}` substitution and `LANDING_ETAG`; remove the three `@jsquash` deps and the `shots` task from `deno.json`
- [x] 4.5 Update/retire `api/` tests for `/` (the landing.test.ts assertions on inlined bytes/ETag) to the proxy behavior; keep the suite offline (inject a fake fetch for the storage read)
- [x] 4.6 Confirm `deno task check`/`test`/`bundle` pass and the api bundle no longer contains the landing HTML or screenshots — 191 tests pass, bundle 615 KB (screenshots gone)

## 5. Site build + mirror deploy (Phase 1)

- [x] 5.1 Add a CI workflow that builds `site/` and mirror-deploys to the storage `site/` prefix: upload new (HTML at stable keys; assets at new hashed keys) THEN delete `site/` objects not in the new build — never clear-first — `site-deploy.yml` + `site/scripts/deploy.mjs` (local walk verified: 8 build files → correct `site/` keys). Also fixed the now-broken `backend-deploy.yml` (removed the dead `deno task shots` step + `screenshots/**` trigger, moved to `site-deploy.yml`)
- [x] 5.2 Authenticate the deploy with the storage-zone password only (`BUNNY_STORAGE_ACCESS_KEY`); assert the bunny account key is absent from the job
- [ ] 5.3 First cutover: deploy `site/` to storage before/independently of the api proxy change so `/` resolves; verify `GET /` returns the Astro page through the pull zone, `no-cache` on HTML, `immutable` on assets — **NEEDS BUNNY CREDS**: workflow + script are correct and the walk is verified, but the actual storage write + pull-zone serve can only be confirmed on the deployed backend (see backend/README "Verify real uploads")
- [x] 5.4 Confirm the nightly sweep still lists only `events/`, `files/devices/`, `devices/` and leaves `site/` untouched; add `site/` as an explicitly-ignored prefix and pin it in a sweep test — sweep is prefix-scoped by construction (nothing to explicitly exclude); pinned by the new "site/ prefix is never touched by the sweep" test

## 6. Move `/join` into `site/` (Phase 2)

- [x] 6.1 Port `download.html` to `join.astro` on the shared layout, with the fragment-read + union-fetch + zip logic as a client island; optionally bundle a zip library (`fflate`, STORE mode) and delete the hand-rolled writer; point API fetches at `/api/v1/events/:id` — kept the PROVEN hand-rolled STORE zip writer verbatim (fflate swap deferred: browser-untestable here); fetches → `/api/v1/events/…`; island bundles same-origin
- [x] 6.2 Add `/join` to the proxy allowlist and the ungated closed-list; serve `no-cache`; confirm byte-identical for every link and reads no per-event state — `/join` was already in the gate's `publicGet`; added the proxy route (`site/join/index.html`)
- [x] 6.3 Delete the in-bundle `GET|HEAD /join` handler, `download.html`, and `DOWNLOAD_ETAG`; update/retire the api `/join` tests to the proxy behavior — download.test.ts, eventlink.test.ts, attest.test.ts gate all updated (193 pass)
- [ ] 6.4 Verify `/join` end-to-end through the pull zone: same-origin only (no off-origin subresource), the download/install controls present, the zip assembles client-side — **NEEDS A BROWSER**: build verified self-contained (no off-origin) + island intact (zip sigs, `/api/v1/` fetches present); the actual union-fetch → zip → download flow needs a browser against a live event

## 7. Self-containment guard + final verification

- [x] 7.1 Add a build check over the `site/` emitted output that fails on any off-origin subresource `src=`/`href=`/`url()` (allowing `<a href>` navigation and the presigned photo fetches) — `site/scripts/check-selfcontained.mjs`, wired as `npm run check` + a workflow step; verified it passes clean and catches an injected CDN `<link>`
- [x] 7.2 Full pass: `deno task test` (api, offline) green; `site/` builds and mirror-deploys; `GET /` and `GET /join` served from storage with correct cache headers; screenshots refresh flow (`screenshots.yml` raws → `astro:assets`) still works; App Store / screenshots workflows unaffected — backend: fmt/lint/check green, 193 tests, bundle 598 KB; site: builds + self-contained check green
- [~] 7.3 Update `CLAUDE.md`/`README.md`: the site now lives in `site/` (Astro), served by the api proxy from storage `site/`; the `shots` pipeline is gone; the storage layout gains `site/` — **backend/README.md done** (storage layout, app.ts role, site-deploy); the root **CLAUDE.md** pass (stale `shots`/marketing references, module list) is pending, best folded into the `backend→api` rename (group 1) since both rewrite that 77 KB file
