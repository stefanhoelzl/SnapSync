## Why

The device-facing host is **baked into the IPA at compile time** (`BackgroundUploadURLBase`, the
single source for the upload origin, the `HttpEventCreationClient` origin, and the invite/deeplink
URL) and is currently a **provider-owned vanity subdomain**: `snapsync.stefanhoelzl.deno.net`. The
backend already deploys to **both** bunny Edge Scripting and Deno Deploy, with Deno Deploy as the
**active device-facing runtime** while bunny investigates dropping iOS's zero-window upload SYNs —
but the `backend-deployment` spec still describes a **bunny-only** deploy, so the spec has drifted
from reality.

Because the baked host is a name **Deno owns**, every change of runtime forces a new compile, a new
TestFlight/sideload build, and a reinstall. This change repoints the device-facing origin at
`snapsync.stho.net` — a subdomain of a zone **we control via Bunny DNS** — as a Deno Deploy **custom
domain** with auto-provisioned, publicly-trusted TLS. The payoff is **provider independence**: the
baked host literal never has to change again, and a future runtime swap (e.g. back to bunny once the
SYN-drop is fixed) becomes a **DNS repoint plus a server-side `PUBLIC_BASE_URL` flip — not an app
rebuild**. This is the *last* host change that forces a build.

The switch is a **hard cutover** (a personal, single-user TestFlight app): the two host strings flip
to the custom domain and the device gets the new build; old installs that bake the deno.net host are
not kept working.

## What Changes

- **Device-facing origin → `snapsync.stho.net`.** A Deno Deploy custom domain on the existing
  `stefanhoelzl/snapsync` app, served with a Let's Encrypt cert (auto-provisioned, auto-renewed; no
  ATS exception — default HTTPS-only). DNS lives in the `stho.net` Bunny DNS zone: a `CNAME` for
  `snapsync` to the Deno Deploy target plus the `_acme-challenge` verification `CNAME`.
- **Two literals flip** (the only strings that move):
  - `iosApp/Configuration/Config.xcconfig` — `BACKGROUND_UPLOAD_URL_BASE` → `https://snapsync.stho.net`
    (drives upload, event-creation, and invite/deeplink origin via `BackgroundUploadURLBase`;
    **requires a new iOS build**).
  - `.github/workflows/backend-deploy.yml` — `PUBLIC_BASE_URL` → `https://snapsync.stho.net` (the
    origin the list endpoint stamps into each file's download `url`; set server-side on Deno Deploy).
- **Spec drift reconciled.** `backend-deployment` records the **dual-runtime** deploy (bunny Edge
  Scripting + Deno Deploy), that **Deno Deploy is the active device-facing runtime**, and the new
  **custom-domain / provider-independence** model (origin is a domain we control; baked host and
  `PUBLIC_BASE_URL` are the same custom domain; runtime swap = DNS repoint, not a rebuild).
- **Untouched:** the storage-zone config (`BUNNY_STORAGE_*`, `storage.bunnycdn.com`, the `snap-sync`
  zone) is server-side and internal — it does not change. No engine/ledger/UI change. ATS stays
  exception-free (the custom domain serves a publicly-trusted cert).

## Capabilities

### Modified Capabilities
- `backend-deployment`: reconcile the bunny-only drift to the **dual-runtime** reality (bunny Edge
  Scripting **and** Deno Deploy, deployed from one bundle on `main`, with Deno Deploy the active
  device-facing runtime), and fold in the **device-facing custom domain**: the origin SHALL be a
  domain we control (Bunny DNS), CNAME'd to the active runtime and served with a publicly-trusted
  cert; the baked `BackgroundUploadURLBase` **and** `PUBLIC_BASE_URL` SHALL both be this domain; and
  swapping the active runtime SHALL be a DNS repoint, **not** an app rebuild.

## Impact

- **Config / CI:** `iosApp/Configuration/Config.xcconfig` (the baked host literal);
  `.github/workflows/backend-deploy.yml` (the `PUBLIC_BASE_URL` literal and the revert-path comment).
- **DNS (operator, via Bunny DNS API):** add the `snapsync` `CNAME` + `_acme-challenge` `CNAME` to
  the `stho.net` zone (driven with the Bunny **account API key**, `BUNNY_API_KEY` via `proton-env`).
- **Deno Deploy (operator, dashboard):** add the custom domain, verify, provision the cert, assign it
  to the `snapsync` app. (Deno's API for this isn't used; the dashboard is the source of the record
  targets.)
- **Docs:** `docs/design.md` (§4/§7 — record the custom-domain front door + provider-independence
  invariant and reframe the bunny-revert path as a DNS repoint) and `backend/README.md` (deploy
  section: dual runtime, the custom domain, the revert path).
- **Spec:** `openspec/specs/backend-deployment/spec.md` (the only spec touched).
- **Accepted, eyes open:** hard cutover means any device still running an old build silently fails to
  upload until reinstalled (acceptable — single user). Deno's `.deno.net` vanity URL cannot be
  deleted (auto-assigned); "hard cutover" means we stop *referencing* it, it harmlessly keeps
  resolving to the same app.
- **To verify on device:** Deno's custom-domain edge must handle the iOS background-upload streaming
  PUTs identically to the `.deno.net` URL (same app, different SNI — expected fine; the SYN-drop was
  bunny-specific). Confirmed by a real upload landing in the bunny storage zone post-cutover.
