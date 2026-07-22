## Context

The `backend/` Edge Script (Deno + Hono on bunny Edge Scripting) serves both the device API and two
browser pages: the marketing landing (`marketing-site`) at `/` and the no-app download page
(`web-event-download`) at `/join`. Both are hand-written HTML embedded in the deployed bundle; the landing
page inlines six screenshots as base64 `data:` URIs via a build-time substitution (`deno task shots` +
`@jsquash` WASM → `shots.generated.ts`). The pages share no code.

The device-facing origin is `snapsync.stho.net`, CNAME'd to a bunny **pull zone** whose origin is the Edge
Script. Photos live in a bunny **storage zone** (`snap-sync-dev`) and are fetched by devices via presigned
S3 URLs — no pull zone fronts them. The repo's deployment philosophy is emphatic and scar-earned
(`backend-deployment`): **config lives in source, CI ships code only, CI never holds the bunny account
key** (which owns storage + DNS), and there is deliberately no boot probe. A nightly out-of-edge sweep
(`scheduled-cleanup`) reclaims stale storage; it already holds the storage password in CI and imports the
Edge Script's own `storage.ts`/`lifecycle.ts` so the two cannot drift.

This change moves the two pages into an **Astro** static site served from storage, while keeping the API on
Hono. The design was validated by a throwaway spike (below) and a long design exploration.

## Goals / Non-Goals

**Goals:**
- Get the ~290 KB of screenshots and the two HTML documents **out of the API bundle**.
- Replace the data-URI/`{{SHOT_*}}` authoring pattern with a real component build (shared layout, theme,
  components; `astro:assets` for images).
- Preserve the repo's core invariants: **config in source, no account key in CI, zero out-of-band config,
  DR = redeploy bundle + repoint DNS.**
- Keep `/join`'s privacy contract (same-origin, self-contained) and lay a foundation that scales to a
  future client-side event gallery / web-upload UI.

**Non-Goals:**
- **Web upload.** Contributing photos from a browser stays out of scope; it reverses a documented non-goal
  and needs a new browser-write auth model. The design only keeps it *possible* (client-side islands).
- **Replacing Hono / a unified full-stack framework.** Spiked and rejected (see Decisions).
- **A second hostname or pull zone.** Everything stays under `snapsync.stho.net`.
- **Changing the AASA or any device-API behavior.**

## Decisions

### D1: Keep Hono for the API; do not unify on SvelteKit
A spike ported the streaming byte-upload PUT + attestation gate to SvelteKit and measured it. The
*mechanics passed*: a 400 MB body proxied at flat 3 MB RSS (non-buffered streaming, like Hono's
`c.req.raw.body`), the gate ported cleanly to `hooks.server.ts`, and handlers unit-tested fully offline.
But the **only** bunny SvelteKit deploy adapter (`planza-digital/sveltekit-adapter-bunny`) is a v0.0.1,
not-on-npm, ~11-months-untouched, test-less weekend project with real bugs in its deploy tool (swapped
destructuring in the asset upload; account-key auth). Bunny has **no first-party SvelteKit support**; Hono
runs directly on bunny's own `edgescript-sdk` with bunny's own deploy action. Unifying would mean owning a
forked build adapter under the attestation+billing boundary for no functional gain. *Rejected.*

### D2: Astro for the `site/` module
The `/join` future (event gallery, later web upload) makes the site a real interactive app — and
**fragment privacy permanently rules out SSR** (the server never sees the eventId), so the required shape
is a **static shell + client-side islands**. Astro is purpose-built for that and scales from two pages
without changing tools; it can use Svelte components as islands for Svelte DX. Alternatives: *Lume*
(Deno-native SSG, but a content tool the interactive future would outgrow); *Fresh* (SSR-first, moot under
fragment privacy, Preact-locked); *plain Deno build* (no component sharing). Astro's build-time npm tree is
a build-only cost (no runtime/bundle impact). *Chosen: Astro.*

### D3: The Edge Script owns routing and proxies static from storage (NOT pull-zone edge rules)
Two shapes were considered for serving the site from storage under one host:
- **A — pull-zone edge rules** route `/`, `/join`, `/assets/*` → storage, `/api/*` → script. The script
  never touches static. But bunny's Core API (pull zones, **edge rules**, storage, DNS, API keys) is
  **account-key-gated** — there is no scoped key. So edge rules can only ever be applied out-of-band with
  the account key: routing config that lives in a console, invisible to the repo, un-shippable by CI, lost
  on a DR rebuild. That is exactly the config-drift class `backend-deployment` was built to make impossible.
- **B — the Edge Script owns routing.** The api holds a closed allowlist (`/`, `/join`, `/assets/*`,
  `/_astro/*`) that **proxies** the object from storage `site/` and streams it back with cache headers set;
  everything else is the existing API/gate. Routing is code, shipped by CI's script key, reviewable,
  recoverable by "redeploy + repoint DNS." The cost — the script sits on the static request path — is
  absorbed by the **existing pull-zone cache**: immutable assets are served from the edge after one hit;
  only the tiny `no-cache` HTML shell touches the script per view (a single storage GET, off the hot path).

*Chosen: B.* It preserves config-in-source and keeps the account key out of the pipeline entirely, which
this repo values above the architectural purity of "script serves only API+AASA." (A redirect instead of a
proxy was also rejected: bouncing `/join` to a CDN host breaks its same-origin/self-contained contract.)

### D4: One storage zone with a public `site/` prefix (NOT a separate zone)
Two "visibilities" behave differently in bunny. **Public reach** is controlled *per-prefix* by what fronts
the zone: only `site/` is proxied, so `files/`/`events/`/`devices/` are never publicly reachable and keep
flowing through presigned URLs — co-tenancy leaks nothing. **Credential blast radius** is *not* separable:
a zone has one all-powerful password and bunny has no prefix-scoped keys, so a separate zone is the only
way to give the site-upload credential a key that cannot touch photos. We accept the shared key (CI already
holds it via the sweep), so *one zone with a `site/` prefix* is chosen. Consequence: the sweep's
prefix-scoping becomes **load-bearing for `site/`'s survival** and must be pinned (D8).

### D5: Caching — `no-cache` HTML, immutable fingerprinted assets
The proxy sets `Cache-Control: no-cache` on the HTML entry points (`/`, `/join`) and
`public, max-age=31536000, immutable` on fingerprinted assets (`/_astro/*`, `/assets/*.<hash>.*`). The hash
is the version, so a changed asset gets a new URL and stale-cache bugs are impossible — and **no
content-ETag is needed** (the current `FNV-1a(bundle)` ETag machinery is deleted). Astro fingerprints by
default, so this is native.

### D6: Mirror deploy, no grace
Each `site/` deploy makes the prefix reflect exactly the current build: **upload new (HTML in place;
new hashed assets), then delete stale** — never clear-first (that would leave `site/` momentarily empty).
No retained generations, no grace window. This is safe because `no-cache` HTML means no persistent stale
reference can exist (fresh loads always reference current hashes; already-loaded pages hold their immutable
assets in the browser cache). The residual is a narrow in-flight sliver (a page that grabbed old HTML in
the ~1–2 s of a deploy, fetching an eager asset as the stale-delete runs) → one 404, self-healing on
reload — acceptable at marketing traffic.

### D7: Self-containment is a whole-site invariant
The site emits **no off-origin runtime subresource** — no CDN scripts/fonts/styles, no `@font-face` remote
`url()`, no external `fetch` beyond the presigned photo URLs `/join` already uses. **Bundled npm
dependencies are fine** (they compile to same-origin `_astro/*`); the rule is about the *runtime origin of
subresources*, not whether code came from a third party — so `/join` may bundle a real zip library
(`fflate`, STORE mode) and delete the hand-rolled writer. Carve-outs: navigational links (`<a href>`) and
the presigned photo fetches. Astro makes same-origin the default; a build check over the emitted output
pins it. This generalizes and subsumes the `/join`-only "self-contained" rule.

### D8: Rename `backend/` → `api/`; preserve `api↔sweep` sharing; pin sweep scoping
Behavior-preserving rename (may ship as a standalone mechanical PR first). `sweep.ts` stays inside `api/`
importing `storage.ts`/`lifecycle.ts` — the anti-drift invariant is untouched. The sweep is affirmed
**prefix-scoped** (`events/`, `files/devices/`, `devices/`) and MUST explicitly ignore `site/`; it must
never become a whole-zone walk (D4).

## Risks / Trade-offs

- **`astro:assets` uses `sharp` (a native binding); it may not run under Deno's npm-compat.** → Run the
  `site/` build under **Node** (it is a separate module with its own toolchain; only `api/` must stay
  Deno-pure). Resolve empirically during Phase 1.
- **The script is on the static request path (D3).** → Mitigated by the pull-zone cache: immutable assets
  bypass the script after one hit; only the tiny `no-cache` HTML shell hits it per view. Marketing traffic
  makes this negligible.
- **The sweep's prefix-scoping is now load-bearing for the site (D4/D8).** → Pin it in the
  `scheduled-cleanup`/`backend-deployment` specs and name `site/` as an ignored prefix; a "simplify to a
  whole-zone walk" refactor would delete the site.
- **Mirror-deploy in-flight sliver (D6).** → Self-heals on reload; no persistent stale state; acceptable.
- **Self-containment erosion (D7)** (a future "nice Google Font" in the shared layout silently breaks
  `/join`). → A build check greps the emitted output for off-origin subresource `src=`/`href=`/`url()`.
- **Two deploy targets / first-cutover ordering.** → Both go through CI with keys it already holds (script
  key + storage password). Populate storage `site/` before the api proxy route serves it; content-hashed
  assets + the cache make ordering forgiving.

## Migration Plan

- **Phase 0 (optional, separate PR):** rename `backend/` → `api/` (mechanical, behavior-preserving);
  update `backend-deploy.yml` / `nightly-cleanup.yml` paths.
- **Phase 1:** stand up `site/` (Astro) with the **landing page + shared layer + `astro:assets`**; add the
  api static-proxy route; cut `/` over to storage `site/`; delete the shots pipeline and `landing.html`;
  add the `site/` build+deploy workflow (mirror upload with the storage password).
- **Phase 2:** move **`/join`** into `site/` as an interactive island (optionally bundling `fflate`); proxy
  it from storage; delete `download.html`. The script now serves API + AASA + the static proxy.
- **Rollback:** each phase is independently shippable; rollback is redeploying the prior api bundle (which
  still embeds the page) and, if needed, leaving `site/` in storage (harmless, unreferenced).

## Open Questions

- `astro:assets` image service under Deno vs Node — resolve in Phase 1 (fallback: Node for `site/`).
- Whether the self-containment build check ships in this change or as a fast-follow (lightweight either
  way).
