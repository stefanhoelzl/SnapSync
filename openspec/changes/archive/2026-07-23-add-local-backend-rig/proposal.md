## Why

A backend change today can only be exercised against the **deployed** `snapsync.stho.net` — there is
no way to run `api/` and point a device at it. Worse, the one storage zone the deployed backend uses
(`snap-sync-dev`) is **shared with real TestFlight/App-Store users' photos**, so the local-run recipe
in `api/README.md` ("`deno run … src/main.ts`") writes into live user data. The result is that every
backend change is validated only by unit tests whose bunny responses we wrote ourselves, then shipped
straight to the one runtime everybody depends on.

This adds a **local backend rig**: run `api/` against a filesystem store, reach it from a physical
iPhone over HTTPS, and prove an upload and a download end-to-end before deploying anything.

## What Changes

**A local backend rig (dev infrastructure — non-gating, no spec, same posture as `ssh-mac.yml` and
`:test:harness-driver`).**

- A dev-only `api/src/dev/` tree that composes the **same** `createApp({ config, fetch })` the Edge
  Script serves, with a **filesystem `fetch`** implementing bunny's native Storage subset against
  `api/.localstore/`. `main.ts` never imports it, so `deno bundle src/main.ts` excludes it
  structurally.
- Two tasks: `dev:local` (127.0.0.1, no tunnel — the curl loop) and `dev:tunnel` (a **cloudflared
  quick tunnel** for a real HTTPS host the device can reach under default ATS).
- Presigned download URLs are minted with `s3Host` pointed at the live host, so the device receives a
  URL of **identical shape** to production; the dev entry serves those bytes from disk.
- The attestation gate stays **fully intact** — the dev entry only fills in a Bearer when the request
  carries none, so a bare `curl` works while the device's real attest flow is exercised unchanged.

**Device-side support for pointing a build at an alternate backend.**

- A new dev/test launch trigger **`SNAPSYNC_RESET_STATE`** that voids this device's durable sync
  state. Without it, crossing backends fails **silently in both directions**: the upload ledger's key
  is event-independent and `LeaveEvent` deliberately keeps it, so `COMPLETED` rows suppress every
  upload against a backend that does not hold those bytes — and the `PHPersistentChangeToken`
  suppresses re-enumeration even after a ledger wipe.
- The boot banner gains the **baked upload base**, so a forgotten reset is one grep instead of a
  debugging session (read beside the cycle's existing `enumeration: … already-uploaded` summary).

**Removal of the dead `ios.yml` manual-dispatch path.**

- `ios.yml`'s `workflow_dispatch` trigger (and its `upload_host` input) is **removed**. It never
  worked for its stated purpose: the archive artifact upload is `main`-only, so a dispatch with an
  `upload_host` builds a Debug archive and **discards it**. Per-branch device installability is served
  by the ssh-mac loop, which is where the host override belongs.
- **BREAKING (workflow surface, not product):** the plain-dispatch escape hatch for exercising the
  **Release** path on a branch before merge goes away. A Release-only link failure now surfaces only on
  the post-merge `main` run — a widening of the trade-off `ios-ci` already documents for the Debug
  branch gate.

**Deliberately not included** — considered and rejected during design: scoping durable state to the
backend automatically (a per-backend container subdirectory, or a persisted epoch that resets on
mismatch). The reset stays an explicit operator trigger.

## Capabilities

### New Capabilities

None. The rig follows the established precedent for dev infrastructure (`ssh-mac.yml`,
`:test:harness-driver`): non-gating, no spec, documented in `CLAUDE.md` with rationale in the module
header.

### Modified Capabilities

- `ios-app-shell`: adds the `SNAPSYNC_RESET_STATE` launch trigger and extends the fixed
  membership-trigger order to `reset → leave → create → event-link`; forge inertness extends to it.
- `ios-ci`: removes the `workflow_dispatch` trigger and the `upload_host` input, collapsing the
  build-configuration rule to "`main` is Release, every other ref is Debug".
- `ios-testflight-delivery`: drops the `ios.yml` `workflow_dispatch` dev-IPA path from the APNs
  environment and crash-reporting DSN requirements (ssh-mac and the branch-gate archives remain).
- `diagnostic-logging`: the process boot banner additionally names the **baked upload base**. (Narrowed
  during implementation: reading ledger counts at boot would force the app's deferred graph assembly and
  add a launch-time DB read on a possibly-locked device, for information the cycle's existing
  `enumeration: … seen, … new, … already-uploaded` line already carries.)
- `backend-deployment`: the api CI check set is invoked **through the `deno.json` tasks**, so the
  type-checked directory set (now including `src/dev/`) and the suite's permissions are defined once;
  `--allow-net` stays absent, which is what makes it impossible for a test to reach the real zone.
- `ios-photokit-upload`: the compile-time host read moves from `:app:ios:extension` to
  `:adapter:ios:ext-safe` (as `bakedUploadBase`), because both processes read it and its absent-key
  defaulting is a decision the zero-decision shell gate forbids a wiring-only root to hold.

## Impact

- **`api/`** — new dev-only `src/dev/` (filesystem storage shim + dev entry), new `deno` tasks, a new
  `test/dev/` contract test, `deno task check` widened to reach `src/dev`, `.gitignore` for
  `api/.localstore/` and `api/.localdev/`. **No change to any deployed code path.**
- **`:domain` `model/`** — one field on `LaunchDirectives`.
- **`:domain` `feature/`** — the reset use case (ledger `clear()`, discovery `clearToken()`, download
  non-terminal drop, local config clear). `IMPORTED` download rows are **kept**: their
  `createdLocalId` is the upload-suppression handle, and dropping them would make the device re-upload
  photos it downloaded.
- **`:app:ios`** — wiring the trigger into the existing launch sequence; the boot-banner line.
- **`.github/workflows/ios.yml`** — trigger removal.
- **`CLAUDE.md`** — the rig runbook, the ssh-mac `BACKGROUND_UPLOAD_URL_BASE` override, and the
  cross-backend reset step.
- **No backend contract changes.** `api/src/app.ts`, `storage.ts`, `config.ts`, and every route are
  untouched; the rig reuses the real source constants so event limits and the attest TTL behave
  locally exactly as deployed.
