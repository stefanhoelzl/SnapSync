## Context

The SnapSync backend (`backend/src/app.ts`, Hono on Deno / bunny Edge Scripting) serves both the device
API and the web/link surface from the root of one custom-domain origin (`snapsync.stho.net`). Every device
route is a bare literal (`POST /events`, `PUT /files/devices/:id/:filename`, `GET /attest/challenge`, …);
there is no path-prefix constant on either the server or the client.

On the client, no shared path builder exists: each of the 8 Ktor clients, the 2 `:domain` push URL builders,
and `EdgeUploadRequestProvider` compose `"$base/…"` where `base = host.trimEnd('/')`, and every `host`
traces back to a **single** compile-time value — `BACKGROUND_UPLOAD_URL_BASE` in
`iosApp/Configuration/Config.xcconfig` — read by both the main app (`SnapSyncRoot.backendHost`) and the
upload extension (`UploadHost`). The web/link surface uses a **different** constant (`LINK_ORIGIN`, from the
`snapsync.domain` gradle property), so it is decoupled from the device-API base.

Two platform facts frame the change: the device-facing host is **baked at compile time** (the OS-driven
upload extension permits exactly one upload host), so already-installed apps cannot be redirected to a new
path without a rebuild they may never receive; and the bunny deploy is **main-only, decoupled from the iOS
build, with no boot probe** (`backend-deployment`).

## Goals / Non-Goals

**Goals:**
- Serve every device-API route under a versioned prefix, `/api/v1`.
- Keep the bare (un-prefixed) paths working as a deprecated grace alias so no installed app breaks.
- Structure the server routing so additional versions can be mounted later without restructuring.
- Move the client to the prefix with the smallest, safest change (one config value).
- Keep the web/link paths (`/`, `/join`, AASA) at the root, un-prefixed.

**Non-Goals:**
- Building or specifying `/api/v2` now. Only the structure that admits it later.
- Client-side version negotiation. The client bakes one version prefix per build.
- Bare-path traffic logging / observability. (Explicitly declined; grace-end decided later without it.)
- Removing the bare alias. That is a deferred follow-up change.
- Touching storage keys, presigned S3 download URLs, or the web/link surface.

## Decisions

### 1. Server: one device-API router, mounted at `/api/v1` (canonical) and at `/` (bare alias)

Extract the device-API route registrations into a **single reusable router**, including the auth-gate
middleware. Mount it at `/api/v1` (canonical) and — for the grace period — mount the **same** router at the
root. The web routes (`/`, `/join`, AASA) stay registered on the root app directly and coexist with the
bare-alias mount.

- **Why this shape:** the canonical routes genuinely live under `/api/v1` (the end-state), and the bare
  alias is one additional mount. Grace removal is deleting that one mount — no handler rewrites, no
  per-route edits.
- **Version-parametric:** because routes live in a factory/router rather than as scattered root literals,
  a future `/api/v2` is *adding a mount of a v2 router*, not restructuring `createApp`. This satisfies the
  "structured for multiple versions" intent without building v2.
- **Auth gate for free:** with Hono, a sub-app mounted at `/api/v1` sees **version-stripped relative
  paths** internally, so the gate's `path.startsWith("/attest/")` ungated check and the Bearer requirement
  hold **identically** for both the `/api/v1` and bare mounts with **no change to the gate logic**. This
  is the key correctness property: the attest bootstrap must stay ungated under the prefix or token
  issuance dead-locks. (The exact Hono path-stripping behavior for middleware `c.req.path` is verified
  during implementation; if it does not strip, the gate's closed-list checks are made prefix-tolerant.)

**Alternatives considered:**
- *Register every route twice* (bare + `/api/v1`): duplicates ~12 registrations and the gate's `/attest/`
  check; removal touches every line. Rejected — noisy, error-prone.
- *Prefix-strip middleware* rewriting `/api/v1/*` to bare, handlers stay bare: fewer edits now, but the
  handlers never actually live under `/api/v1`, so the end-state is inverted and removal is awkward.
  Rejected.

### 2. Client: append `/api/v1` to `BACKGROUND_UPLOAD_URL_BASE`

Change the single compile-time base value to `https://snapsync.stho.net/api/v1`. Because every device-API
client interpolates `"$base/…"` from this one value (both upload tiers, event creation, listings,
enrollment, leave, directory, device config, notify, push registration, byte upload, attest), the prefix
lands on **all** device calls with **no Kotlin path-literal edits**.

- **Why safe:** the value feeds only device-API construction. The web/link surface uses the separate
  `LINK_ORIGIN` constant, so `/join` and the AASA are untouched.
- **Why one place:** both the main app and the extension read the same key, so one line covers both
  processes and all tiers.

**Alternatives considered:**
- *Append in Kotlin where host is read* (`SnapSyncRoot`/`UploadHost`): keeps the config value "clean" but
  splits the change across two code sites. Rejected — the config value *is* the base; the prefix belongs
  there, and one edit beats two.
- *Edit each path literal* (8 clients + 2 push builders + provider): most verbose, most error-prone.
  Rejected.

### 3. Test coverage: server-side backend tests; client relies on config + on-device

Add cases in `backend/test/app.test.ts` asserting representative device routes resolve **identically**
under both `/api/v1/*` and the bare paths (including `/api/v1/attest/*` staying ungated and returning the
challenge/token without a Bearer). This is a pure in-process `app.request(...)` test — the fake upstream
`fetch` only records bunny calls, which the client-facing prefix does not affect.

No new Kotlin/world tests. The `:test:world` mini-edge injects its **own** bare host (`https://world.edge`)
and matches on `segments[0]`, so it is decoupled from the production `Config.xcconfig` value and stays
green untouched. The prefix is pure string concatenation on the client; its correctness is confirmed by
the backend tests (server accepts `/api/v1`) plus an on-device check (real client sends `/api/v1`).

### 4. Grace-period lifecycle: bare alias indefinite, no observability

The bare alias ships and stays until a **later change** removes it. No bare-path hit logging/counter is
added. Grace-end is decided later.

## Risks / Trade-offs

- **Rollout order inverted → outage for new builds.** If a TestFlight/App Store build carrying the
  `/api/v1` host reaches devices *before* the backend serves `/api/v1`, every device call 404s. → Deploy
  the backend (serving both `/api/v1` and bare) first; only then let a prefixed build reach devices. The
  bunny deploy is decoupled and has no boot probe, so **verify manually** post-deploy (a `GET`/`PUT` under
  `/api/v1` through the pull zone).
- **Attest gate under the prefix.** If the ungated `/attest/` check does not hold for `/api/v1/attest/*`,
  token issuance dead-locks (the token routes would require a token). → The gate lives inside the mounted
  router so it sees stripped paths; the backend test explicitly covers ungated `/api/v1/attest/*`. If Hono
  does not strip for middleware, make the closed-list checks prefix-tolerant.
- **No client-side automated coverage of the prefix.** A typo in the xcconfig value would not be caught by
  any test. → The value is one line and reviewed; the on-device check is the backstop. Accepted as
  proportionate (pure string concat).
- **Deciding grace-end blind.** With no bare-path traffic data, removing the alias later is a judgment call
  on lingering installs. → Accepted for a small install base; a future change may add logging if needed
  before removal.

## Migration Plan

1. Merge the change; the backend deploys to bunny on `main`, serving `/api/v1` **and** the bare alias.
2. Manually verify a device-API route resolves under `/api/v1` through the pull zone.
3. Let the next iOS build (carrying the prefixed `BACKGROUND_UPLOAD_URL_BASE`) flow to TestFlight/App Store.
4. Installed apps continue on bare paths, served by the alias, until a later change removes it.

**Rollback:** revert the `Config.xcconfig` prefix for future builds; the bare alias means already-shipped
prefixed builds keep working regardless. The server change is additive — reverting it removes `/api/v1`
but restores the original bare-only behavior.
