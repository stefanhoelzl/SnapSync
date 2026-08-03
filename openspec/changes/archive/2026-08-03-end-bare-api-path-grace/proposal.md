## Why

The `/api/v1` prefix landed with the bare (un-prefixed) device paths kept as a **deprecated grace
alias**, because the device-facing host is baked at compile time and already-installed apps could not be
force-updated. That grace period is over: the prefix commit (`62b236a1`) is **not** in `v0.1` but **is**
in `v0.2`, which has been the released App Store version since 2026-07-31 — so every supported install
targets `/api/v1`, and the alias now serves nothing but a second URL shape that every route map, spec,
and test has to hedge about.

## What Changes

- **BREAKING for pre-`v0.2` installs (accepted).** The bare-path mount is deleted: device-API routes are
  served **only** under `/api/v1`. An install still running the App Store `v0.1` build cannot recover —
  it cannot even reach `/attest/challenge` to mint a token — and must update. This is the eyes-open
  cost the original change deferred.
- The `deviceApi` sub-app and its single `app.route("/api/v1", deviceApi)` mount **stay**, so a future
  `/api/v2` remains one more mount line. The auth gate's version-agnostic `/api/vN` normalization stays
  unchanged.
- No transition machinery is added: no bare catch-all, no `410 Gone` responder, no bare-path traffic
  logging. A bare device path is simply not routed.
- The alias is **erased rather than memorialized**: no spec sentence says "bare paths are not served"
  and no test pins their absence. The specs and route maps read as though `/api/v1` is simply the API;
  the rationale for the removal lives here and in `design.md`, and archives with this change.
- Every route literal that names a path **on the deployed origin** is rewritten to carry the prefix —
  `api/README.md`'s contract map, the `app.ts` route-doc header and gate/mount comments, and the specs
  that describe the backend's HTTP surface. Literals that name a path **relative to an injected base**
  (the `:test:world` mini-edge, client-side prose that composes `$base/…`) stay bare, because the
  prefix is a property of the deployed origin, not of any client or fake.
- The api tests target `/api/v1` explicitly at every device-route call site; the mirror tests the prefix
  change added (`/api/v1` resolves identically to the bare path) are folded out as redundant.

## Capabilities

### New Capabilities
<!-- none: this change removes a deferred transition property; it introduces no capability. -->

### Modified Capabilities
- `backend-deployment`: the versioned-prefix requirement drops the **deprecated bare-path grace alias**
  — the bare paths are no longer served as an alias of the current version, and the auth gate no longer
  has a bare form to hold for. The prefix itself, the web/link-paths-stay-at-root rule, the ungated
  attest bootstrap under the prefix, the baked base host carrying the prefix, and the
  additional-versions-mount property are unchanged.

## Impact

- **Server** (`api/src/app.ts`): one deleted line — the bare-alias mount. The `deviceApi` sub-app, the
  `/api/v1` mount, and the gate's `^/api/v\d+` normalization are untouched. Route-doc header, gate
  comments, and mount comments lose the alias prose and gain prefixed literals.
- **Tests** (`api/test/`): ~140 device-route call sites in `app.test.ts`, `attest.test.ts`,
  `download.test.ts`, and `eventlink.test.ts` gain the prefix (constants and inline literals). Web/link
  tests (`landing.test.ts`, and the `/`, `/join`, AASA cases) stay bare. The prefix-mirror test in
  `app.test.ts` and the three duplicated `/api/v1` gate tests in `attest.test.ts` are removed; the
  `web/link paths are NOT served under /api/v1` test stays.
- **Docs**: `api/README.md` (contract banner, route map, the Methods line). Deployed-surface specs gain
  prefixed literals as an editorial correction — they have been describing an alias shape since
  2026-07-21 — rather than as requirement deltas (see `design.md` for why).
- **Not touched**: `:test:world` (`World.host` stays `https://world.edge`; MiniEdge keeps matching from
  segment 0), every Kotlin client (no path literal anywhere composes the prefix), `Config.xcconfig`
  (already `…/api/v1`), and the web/link surface (`/`, `/join`, the AASA, `/_astro/*`) which was never
  under the prefix.
- **Rollout**: merging to `main` deploys via `api-deploy.yml`, which has no boot probe — the deploy is
  verified by curl against `snapsync.stho.net`. No client build, no version bump, no ordering
  constraint: the client change shipped in `v0.2` a week ago.
- **Other bare-path callers: none.** `site/src/pages/join.astro` already fetches `/api/v1/events/…`;
  `nightly-cleanup.yml` runs `src/sweep.ts` against bunny storage directly, not the device origin.
