> **Shipped-design note (Phase 1 internals evolved during the live smoke test).** The ssh-mac loop is
> dev infra with **no spec**, so these internal changes carry no spec delta; they are recorded here for
> the archive. Two proposal assumptions changed once proven on a real `macos-26` runner:
> 1. **Hostname handoff** — GitHub run/job **logs are unreadable until the job completes**, but the
>    session step blocks the whole time, so log-scraping is impossible headless. The host is instead
>    published as the **`ssh-mac-host` artifact** (upload-artifact v4+ is available mid-run) and the
>    sandbox polls `gh run download`.
> 2. **Signing** — the "fetch the profile via the ASC key, then delete it" plan was dropped. Xcode's
>    managed profile isn't API-listable and `fetch-signing-files` needs the cert key, so the runner now
>    installs **baked** dev profiles from the `DEV_PROVISIONING_PROFILE_BASE64` secret (a tar of BOTH the
>    app `app.snapsync` and extension `app.snapsync.BackgroundUpload` profiles) — **no ASC key on the box
>    at all**. This also removed the ~12-min warm archive, giving ~45 s first-connect.
> Shipped across PR #61 (Phase 1), #62 (fast headless + baked profile + `debugging` rename), #63 (both
> profiles). Verified end-to-end on 2026-07-01, incl. on-device install (`app.snapsync 0.1.0 build 1`).

## 1. Phase 1 — ssh-mac build loop workflow (dev infra, no spec) [PR #61/#62/#63]

- [x] 1.1 Add `.github/workflows/ssh-mac.yml`: `workflow_dispatch` only, **not** a required check. Inputs
  `ssh_pubkey` + `stop_after`. `permissions: contents: read`.
- [x] 1.2 Env/toolchain mirroring `ios.yml`: checkout, `setup-java` 25, `setup-gradle`, `~/.konan` cache
  (same key). (Sim boots on demand via the in-session gradle task.)
- [x] 1.3 Signing prep — **SHIPPED as baked profiles, no ASC key** (see note above): import the Development
  cert (`SIGNING_DEV_CERT_*`) and install both dev profiles from `DEV_PROVISIONING_PROFILE_BASE64` to the
  classic + Xcode-16 profile dirs. The Admin ASC key is **never materialized** on the box.
- [x] 1.4 Auth + transport: `ssh_pubkey` → `authorized_keys`; user-space `sshd` (non-root, high port,
  generated host key); `cloudflared --url ssh://localhost:<port>`.
- [x] 1.5 Lifecycle: interactive step **blocks on the `/tmp/ssh-mac-stop` sentinel** with `timeout-minutes`
  (150) backstop + `stop_after`; cleanup step.
- [x] 1.6 Fat header comment on `ssh-mac.yml` (pubkey-auth-not-in-logs, cloudflared-TCP-only, baked-profile
  / no-ASC-key, artifact hostname, non-gating).

## 2. Phase 1 — agent/operator runbook (docs) [PR #61/#62]

- [x] 2.1 "Headless macOS build loop (ssh-mac)" section in `CLAUDE.md` — **SHIPPED with artifact hostname**:
  gen keypair → dispatch → **`gh run download <id> -n ssh-mac-host`** for the host (not a log scrape) →
  connect via `cloudflared access ssh` ProxyCommand → rsync → build/test/export → scp back →
  `pymobiledevice3 apps install` → touch the sentinel. Marked operator runbook, not CI behavior.

## 3. Phase 1 verification gate — proven on the SE2 (2026-07-01) [PR #61/#62/#63]

- [x] 3.1 Dispatched `ssh-mac.yml`; sandbox connects end-to-end via the `cloudflared` ProxyCommand and only
  the dispatched key authenticates. Headless host via the `ssh-mac-host` artifact in ~45 s.
- [x] 3.2 In-session **archive + dev export off the baked profiles with no ASC key** →
  `ARCHIVE/EXPORT SUCCEEDED`, a 19.5 MB dev IPA. (Caught + fixed the missing extension profile here.)
- [x] 3.3 `scp` the IPA back + `uvx pymobiledevice3 apps install` on the SE2 → `Installation succeed.`,
  `app.snapsync 0.1.0 build 1` present; embedded profile carries the SE2 UDID. **Phase-2 gate met.**

## 4. Phase 2 — retire the CI dev-IPA sideload channel [PR #4]

- [x] 4.1 Deleted the **Export development IPA** + **Upload dev IPA artifact** steps from `ios.yml`. Kept
  the signed archive (gate), **both** cert imports, and the `main`-only TestFlight steps.
- [x] 4.2 Updated the `ios.yml` header comment: single channel (TestFlight on `main`); per-branch installs
  served out of band by the ssh-mac loop.
- [x] 4.3 Rewrote the `CLAUDE.md` "Sideload a dev IPA" section: dropped `gh run download … snapsync-dev-ipa`,
  points at the ssh-mac loop; kept the one-time UDID + Developer-Mode prerequisites + a standalone install.

## 5. Phase 2 — spec application (at archive)

- [x] 5.1 `ios-ci` Purpose + "Build iOS on every push": two channels → one (TestFlight on `main`);
  installability out of band via ssh-mac. Also tidied the now-stale sideload mention in the host requirement.
- [x] 5.2 `ios-testflight-delivery` Purpose + the two modified requirements (installability via ssh-mac; the
  Development cert import justified by archive-provisions-a-dev-identity, not a sideload export).
- [x] 5.3 Retired `openspec/specs/ios-sideload-delivery/` (capability removed).

## 6. Archive

- [x] 6.1 Archived after the on-device install proof, applying the deltas (retire `ios-sideload-delivery`;
  amend `ios-ci` + `ios-testflight-delivery`).
