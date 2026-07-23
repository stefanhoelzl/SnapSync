## Context

`api/` has exactly one deploy target and exactly one storage zone. `snap-sync-dev` is named in
`src/config.ts` as a source constant, the deployed Edge Script reads it, **and so does any local
`deno run`** — which is why `api/README.md` warns that a local run "targets the **real**
`snap-sync-dev` zone", the same zone holding real TestFlight/App-Store users' photos. `CLAUDE.md`
states the consequence bluntly: there is deliberately no whole-zone reset tool, and cleanup must be
targeted only.

So a backend change is validated by `deno task test` — which runs with **zero permission flags**, so a
real network call is impossible; every test injects a fetch map and a `Config` literal — and then
deployed. The mocks encode *our beliefs about bunny*, pinning the outbound URLs we build and returning
responses we wrote. Nothing exercises the actual device→backend path until it is live.

Two properties make a local rig cheap:

1. **The injection seam already exists.** `createApp({ config, fetch })` takes its upstream `fetch` as
   a dependency; every storage call in `src/storage.ts` goes through it. `main.ts` is the only place
   that binds it to the global `fetch`.
2. **`deno bundle src/main.ts` roots the deployed bundle at `main.ts`.** A dev tree `main.ts` never
   imports cannot reach production, structurally — no flag, no guard, no build-time exclusion needed.

The device side is harder in one specific way: `BACKGROUND_UPLOAD_URL_BASE` is **compile-time** (the
PhotoKit tier forces it — the OS reads `BackgroundUploadURLBase` from `Info.plist`), so pointing a
device at another backend means a rebuild. The ssh-mac loop already does incremental Debug archives in
~1 minute, and both targets' `Info.plist` values are fed by the one xcconfig setting, so a single
`xcodebuild` override covers the app and the extension together.

## Goals / Non-Goals

**Goals:**

- Run the **real** `api/` app — same routes, same gates, same source constants — against a filesystem
  store, touching no bunny zone.
- Reach it from a **physical iPhone** over HTTPS under default ATS, with no `NSAllowsLocalNetworking`
  exception ever shipping.
- Make an **upload and a download** provable end-to-end locally, with `ls` on a directory as the
  verification oracle rather than a bunny dashboard.
- Keep `curl` usable against the rig without minting a token by hand.
- Make crossing backends **safe and reversible** on a device that already holds sync state.
- Leave every deployed code path byte-identical.

**Non-Goals:**

- Proving our bunny assumptions are *correct*. The shim is a second implementation of the same beliefs
  the mocks encode; it proves internal consistency, not conformance to bunny. Stated plainly rather
  than papered over.
- A production-facing "staging environment". This is a developer's laptop plus a throwaway tunnel.
- Automatic backend-scoped state on the device (rejected — see Decision 7).
- Silent push locally. `/notify` is best-effort by contract, so an unconfigured APNs key is faithful,
  not a fake.
- Serving the marketing site or AASA locally. `SNAPSYNC_EVENT_LINK` / `SNAPSYNC_CREATE_EVENT` bypass
  AASA entirely, and `LINK_ORIGIN` is independent of the upload base — so event links keep working
  against a local backend with no link-domain work at all.

## Decisions

### 1. A filesystem `fetch` shim, not a storage port

**Chosen:** a dev-only `fsFetch(root): FetchLike` that answers bunny's native Storage API off a local
directory, injected exactly where the tests inject their fetch map.

**Alternatives:** (a) refactor `storage.ts` into a `StorageBackend` interface with bunny and fs
implementations; (b) provision a second real bunny zone.

**Why:** (a) rewrites shipped storage code — plus `sweep.ts` and the `site/` deploy, which share those
primitives — for a dev-only need, and every line it touches is a line that can break production. (b)
requires the full-access **account** API key to provision, which `backend-deployment` refuses to put
anywhere near CI or a dev loop precisely because it also owns every user's photos and our DNS zone;
and it keeps the loop online and slow. The shim adds zero risk to the deployed path because the
deployed path never imports it.

**Guard rail:** the shim **throws** on any URL not under `https://<config.host>/<config.zone>/`. An
unshimmed upstream call then fails loudly instead of silently reaching the internet.

**Layout:** storage keys map **1:1** onto directories and files under `api/.localstore/`. Filenames are
already `encodeURIComponent`'d into single path segments by the upload route, so they are path-safe by
construction and round-trip byte-exact. `LIST` synthesizes `ObjectName` / `Length` / `IsDirectory` /
`LastChanged` from directory entries and mtime — `LastChanged` matters, since it is the last-write-wins
tiebreak between a device's active and departed manifests and the sweep's upload-time floor.

### 2. The dev entry reuses the real source constants

`storageConfig()` already returns every non-secret `Config` field. The dev `Config` is that, with
`s3Host` pointed at the live host and dev literals for `attestTokenKey` / `adminKey` (and a blank APNs
key). So `eventCapacity`, `eventDurationSeconds`, `eventGraceSeconds`, and `attestTokenTtlSeconds`
behave locally **exactly** as deployed — a limits change is exercised by the rig for free, and the rig
cannot silently diverge from the constants it is meant to test.

### 3. The attestation gate stays on; the dev entry pre-authenticates

The gate is one `app.use("*")` middleware, and `verifyToken` deliberately **does not bind the token to
the route's `deviceId`** ("this proves the token is OURS and UNEXPIRED… ownership stays capability-based
on the unguessable UUID"). So a single fixed dev token authorizes any `curl`.

The dev entry attaches that token **only when the request carries no `authorization` header** — the
same trick `test/app.test.ts` already uses to avoid threading a header through ~100 call sites.
Consequences, all wanted:

- `app.ts` is untouched; there is no bypass branch that could ever be reachable in production.
- `/attest/*` is ungated anyway, so the **device's real attestation flow runs for real** against the
  rig — a change to the attest verifier is testable locally like anything else.
- A device sending its **own** token is handled faithfully, including an expired or foreign one:
  `401`, exactly as deployed. `DeviceAttestation.rejected()` then drops it and re-attests, so crossing
  backends self-heals the credential with no operator action.

**Rejected:** printing a token for the operator to paste. It grows every ad-hoc `curl` and every agent
command with a header, for no gain in fidelity.

### 4. cloudflared quick tunnel for HTTPS reachability

Default ATS is HTTPS-only and `Config.xcconfig` documents that no `NSAllowsLocalNetworking` exception
ships. A quick tunnel yields a real HTTPS `*.trycloudflare.com` host with no account, no certificate
work, and no device-side trust profile — and `cloudflared` is already the transport in the ssh-mac
runbook, so it is a known quantity here.

**Alternatives:** a stable named tunnel with a `dev.snapsync.stho.net` CNAME in our Bunny DNS zone
(would let the dev IPA be built **once** and reused, at the cost of a Cloudflare account and a DNS
record); Tailscale Funnel; a self-signed cert plus a device trust profile (needs taps — hostile to the
headless loop).

**Accepted cost:** the quick-tunnel hostname is **random per session**, so the baked host changes every
session and the IPA is rebuilt per session. Mitigated by the ssh-mac incremental Debug archive (~1 min)
and by writing the hostname to a file. The stable-host upgrade is available later without redesign —
only the value of `BACKGROUND_UPLOAD_URL_BASE` changes.

### 5. Presigned downloads: point `s3Host` home, serve `/<zone>/<key>` from disk

In production the union and per-device listing embed **SigV4 presigned S3 GET URLs** at `s3Host`, and
the device fetches bytes **directly from bunny**, never through the api. That second host has to exist
locally or downloads are untestable — and the download half is exactly the part most likely to be
subtly wrong, since nothing else exercises the URL builder against a real client.

Setting `s3Host` to the live host makes `presignDownloadUrl` mint a **real** signature in the
**identical URL shape**, just pointed home. The dev handler intercepts paths under `/<zone>/` and
serves from disk, **ignoring** the signature.

**Rejected:** validating the signature locally (bunny's exact acceptance semantics are not reproducible,
so a local validator would pin our guess, not their behavior); reintroducing a download-proxy route
(the proxy was deliberately retired — it would test something the real backend does not do).

### 6. `SNAPSYNC_RESET_STATE` — an explicit trigger, because crossing backends fails silently

The trap, and why nothing weaker fixes it:

- The upload ledger's key is the **bare filename**, event-independent, and `LeaveEvent` **deliberately
  keeps** the ledger because "a `COMPLETED` row stays *true* across a leave". True with one backend.
  Cross to another and the bytes are on the *old* one while the ledger still says `COMPLETED`, so
  **nothing uploads** — no error, no log, indistinguishable from a broken rig.
- Clearing the ledger alone is **not enough**. Discovery's cursor is a `PHPersistentChangeToken` in the
  shared `NSUserDefaults` suite; with it still set, the next cycle sees no changes and enumerates
  nothing. Its own contract — "degrades to full re-enumeration" on absence — is precisely the behavior
  the reset needs.

So the trigger clears **four** things: the ledger (all rows), the discovery token, the membership
config (**locally, with no backend `DELETE`** — the old backend is unreachable and the new one never
knew this device), and **non-terminal** download rows.

`IMPORTED` download rows are **kept**. Their `createdLocalId` is the suppression handle the upload
extension reads to avoid re-uploading a downloaded asset; dropping them would make the device
re-upload photos it downloaded — the echo the download store exists to prevent. `deleteNonTerminalAssets`
is already exactly this shape, used on leave/switch.

The attest token is **untouched**: a `401` already drops and re-mints it.

**Naming:** `SNAPSYNC_RESET_STATE`, not `…_LEDGER`. It clears four stores; a narrow name is how a tool
gets misused. It joins the existing family (`SNAPSYNC_LEAVE`, `SNAPSYNC_SEED_*`, `SNAPSYNC_FORGE_STATE`)
— presence-triggered, read once per process, inert in production because a launch environment variable
is only injectable via a developer launch.

**Ordering:** `reset → leave → create → event-link`, sequentially awaited. Reset runs **first** so one
launch can void a foreign backend's state and immediately join a fresh event; after a reset the device
is unjoined, so a paired `leave` is a no-op rather than a `DELETE` aimed at the wrong backend. A forge
launch ignores it, like the other three.

### 7. No automatic backend-scoped state (considered, rejected)

Two mechanisms were designed and declined:

- **Container subdirectory keyed by the baked host** (prod host → container root, so existing installs
  are unaffected). Symmetric, needs no migration, and makes the return trip to production restore the
  prod world intact.
- **Persisted epoch + reset on mismatch.** Needs a schema migration and an owner rule, because the app
  and the extension both touch the ledger.

**Why rejected:** both put permanent machinery into shipped iOS state layout to serve a scenario the
deployment doctrine says production will never see — `backend-deployment` keeps the baked host a domain
**we** own precisely so swapping runtimes is a DNS repoint and never a new build. The explicit trigger
is smaller, has no durable state of its own, and works in both directions.

**Accepted residual risk:** a forgotten reset fails silently. Mitigated by Decision 8, not by
machinery.

### 8. The boot banner names the baked upload base

`diagnostic-logging` already requires a boot banner naming the process and build version. It gains the
**baked upload base** and the ledger's completed/pending counts. A run that uploads nothing then shows,
in `debug.log`, that the host is a tunnel while the ledger is full — which names the cause immediately.
Pure logging: no behavior, no durable state, and it is the detect-half of Decision 7 **without** the
persistence that made that option unattractive.

### 9. Remove the `ios.yml` `workflow_dispatch` trigger outright

The `upload_host` input never worked: `ios-build` uploads its archive only `if: github.ref ==
'refs/heads/main'`, so a dispatch with `upload_host` builds a Debug archive and **discards it**. The
capability it claimed to serve — per-branch device installability — is already served out of band by
ssh-mac, which `ios-ci` itself says is where it belongs. With the local rig, the host override lives on
the ssh-mac `xcodebuild` line where a human is present to consume the IPA.

Removing the whole trigger (rather than just the input) also removes the **plain-dispatch Release
escape hatch**. Accepted deliberately: it widens the trade-off `ios-ci` already documents for the Debug
branch gate — a Release-only link failure surfaces on the post-merge `main` run, red but non-gating,
with delivery skipped because `ios-deliver` needs both gates.

### 10. The shim gets a contract test, and we say what it does not prove

`test/dev/fs-storage.test.ts` pins the shim to the **same assumptions the existing mocks encode**: the
LIST JSON shape, `404`-on-missing-object, `404`-on-empty-directory (`listDir` → `null`),
percent-encoded filename round-trip, and `DELETE`-of-absent as success. `deno task check` is widened to
reach `src/dev` (it globs only `src/*.ts` today).

This proves **shim ≡ mocks**, so a failure in the local loop means *your change* broke, not the rig. It
does **not** prove either matches bunny; nothing in this repo ever has. Recorded here rather than
implied.

## Risks / Trade-offs

- **A forgotten `SNAPSYNC_RESET_STATE` uploads nothing, silently, in both directions.** → The boot
  banner (Decision 8) makes the diagnosis one `grep` of `debug.log`; `CLAUDE.md` carries the trap
  inline, not behind a pointer.
- **The tunnel hostname changes every session, so the IPA is rebuilt every session.** → ssh-mac
  incremental Debug is ~1 min; the host is written to `api/.localdev/host` and the ready-to-paste
  `xcodebuild` override is printed at startup, so nothing is assembled by hand. Upgrade path to a
  stable host needs no redesign.
- **The shim can drift from bunny's real behavior and we would not know.** → Explicit non-goal
  (Decision 10). The rig's purpose is catching *our* regressions early, not replacing the deployed
  runtime as the source of truth; deploy still decides.
- **The rig writes to the developer's disk; a stale `.localstore/` looks like real state.** → Both
  `api/.localstore/` and `api/.localdev/` are gitignored, and reset is `rm -rf`. This is the deliberate
  **inverse** of the production rule — a whole-store wipe is forbidden against the shared zone because
  it holds real users' photos, and trivially safe locally because the local store holds nothing.
- **Losing the plain-dispatch Release escape hatch.** → Accepted in Decision 9; it widens an existing,
  already-documented `ios-ci` trade-off rather than creating a new class of failure.
- **`SNAPSYNC_RESET_STATE` is destructive if fired on a device pointed at production.** → It is a
  launch environment variable, injectable only through a developer launch, so it is unreachable on a
  TestFlight or App Store install — the same inertness argument every trigger in the family rests on.
  On a dev device the damage is bounded: reconcile re-seeds `COMPLETED` from storage, so the cost is
  one reconcile, not a re-upload of the library.
- **APNs is unconfigured locally, so a receiving device does not get the silent push.** → Accepted:
  `/notify` is best-effort by contract (members without a token are skipped and the request still
  `202`s), so this is faithful rather than a fake. Downloads are triggered by relaunch/foreground,
  which the headless loop does anyway.

## Migration Plan

Nothing to migrate: no deployed code path, storage layout, or on-device durable format changes. The
new trigger is additive and inert without a developer launch; the boot banner gains fields.

**Definition of done — both directions proven by hand on the SE2**, since the return trip is the one
with no automatic protection:

1. **prod → local:** `SNAPSYNC_RESET_STATE` + `SNAPSYNC_CREATE_EVENT` against a tunnel-baked build; a
   photo's bytes land under `api/.localstore/files/devices/<id>/`, the device manifest is written, the
   union serves it, and a second membership's photo **downloads** to the device through the rewritten
   presigned URL.
2. **local → prod:** reinstall a production-baked build with `SNAPSYNC_RESET_STATE`, rejoin a real
   event, and confirm `event-rejoin-reconciliation` seeds already-stored photos as `COMPLETED` so
   nothing mass re-uploads.

Rollback is deletion: the `api/src/dev/` tree and the tasks can be removed with no effect on the
deployed bundle. The `ios.yml` trigger removal is revertible by restoring the trigger block.

## Open Questions

None. The design tree was resolved before drafting; the two items settled without an explicit ruling —
reusing the real source constants for the dev `Config` (Decision 2) and the shim's out-of-zone throw
(Decision 1) — are recorded here so they can be revisited on their merits.
