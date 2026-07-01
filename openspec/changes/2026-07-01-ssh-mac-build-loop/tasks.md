## 1. Phase 1 — ssh-mac build loop workflow (dev infra, no spec) [PR #1]

- [x] 1.1 Add `.github/workflows/ssh-mac.yml`: `on: workflow_dispatch` only (never `push`), **not** a
  required check. Inputs: `ssh_pubkey` (required, the connecting public key) and `stop_after` (optional,
  default the `timeout-minutes` backstop). `permissions: contents: read`.
- [x] 1.2 Env/toolchain steps mirroring `ios.yml`: `actions/checkout` (the dispatched branch),
  `setup-java` 25 temurin, `setup-gradle`, restore `~/.konan` with the **same cache key** as `ios.yml`
  (`konan-${{ runner.os }}-${{ hashFiles('gradle/libs.versions.toml') }}`), and boot an iOS simulator for
  `iosSimulatorArm64Test`.
- [x] 1.3 Signing prep, **ASC key removed before the box is reachable**: import the Development cert
  (`SIGNING_DEV_CERT_*`) into an ephemeral keychain; write the ASC `.p8`, run one
  `xcodebuild archive -allowProvisioningUpdates` (installs the development provisioning profile + warms
  derived data), then **delete the ASC key** (`shred`/`rm`). Credentials come only from Secrets, never
  cached.
- [x] 1.4 Auth + transport: write `ssh_pubkey` to `authorized_keys`; start a **user-space `sshd`** on a
  high port with a generated host key (non-root); start `cloudflared --url ssh://localhost:<port>` and
  **echo the `*.trycloudflare.com` hostname to the run log**.
- [x] 1.5 Lifecycle: an interactive step that **blocks on a stop-sentinel** (`while [ ! -f /tmp/stop ]; do
  sleep 5; done`) with `timeout-minutes` (≈90) as a backstop; a cleanup step (keychain/profile removal is
  moot on the ephemeral runner but delete the sentinel path).
- [x] 1.6 Add a **fat header comment** to `ssh-mac.yml` (house style, like `ios.yml` lines 1–28): why it
  exists, the pubkey-auth-not-in-logs model, cloudflared-relays-encrypted-TCP-only, dev-cert-only /
  ASC-key-removed blast radius, and non-gating/dispatch-only.

## 2. Phase 1 — agent/operator runbook (docs) [PR #1]

- [x] 2.1 Add a "Headless macOS build loop (ssh-mac)" section to `CLAUDE.md` near the on-device iOS
  section: gen an ephemeral ed25519 keypair (scratchpad) → `gh workflow run ssh-mac.yml -f ssh_pubkey=…`
  → scrape the hostname via `gh run view <id> --log` → connect with
  `ssh -i <key> -o ProxyCommand="cloudflared access ssh --hostname %h" <user>@<host>` → per-iteration
  `rsync` (exclude `.git`, `build/`, `.gradle`) → `ssh … ./gradlew iosSimulatorArm64Test` / `xcodebuild`
  → in-session **manual** dev export (no `-allowProvisioningUpdates`) → `scp` the IPA back →
  `uvx pymobiledevice3 apps install …` over usbmuxd → `ssh … touch /tmp/stop` to end. Note it is operator
  runbook, not CI behavior; note `cloudflared` is fetched to scratchpad (not globally installed).

## 3. Phase 1 verification gate — prove the replacement on the SE2 [PR #1]

- [ ] 3.1 Dispatch `ssh-mac.yml`; confirm the sandbox connects end-to-end via the `cloudflared`
  ProxyCommand and only the dispatched key authenticates.
- [ ] 3.2 Over the session: rsync a working-tree change, run `iosSimulatorArm64Test` green, and produce a
  **manually dev-signed** IPA using the profile installed in 1.3 (verify manual export succeeds with the
  ASC key already deleted).
- [ ] 3.3 `scp` the IPA back and `uvx pymobiledevice3 apps install` on the SE2; confirm it installs and the
  embedded profile carries the SE2 UDID. **This on-device install is the gate for Phase 2.**

## 4. Phase 2 — retire the CI dev-IPA sideload channel (only after §3 passes) [PR #2]

- [ ] 4.1 In `.github/workflows/ios.yml` (`ios-build` job), delete the **Export development IPA** step and
  the **Upload dev IPA artifact** step. Keep the signed archive (merge gate), **both** cert imports, and
  the `main`-only TestFlight export/upload steps unchanged.
- [ ] 4.2 Update the `ios.yml` header comment: drop the "two channels" / sideload-artifact description; it
  now delivers a single channel (TestFlight on `main`), and per-branch installability is served out of
  band by the ssh-mac loop.
- [ ] 4.3 Rewrite the `CLAUDE.md` "Sideload a dev IPA (skip TestFlight)" section: remove the
  `gh run download … snapsync-dev-ipa-<n>` flow and point at the ssh-mac runbook (§2.1) as the single way
  to produce a sideloadable dev IPA. Keep the one-time UDID-registration + Developer-Mode prerequisites.

## 5. Phase 2 — spec prose sync (applied at archive)

- [ ] 5.1 Update `openspec/specs/ios-ci/spec.md` **Purpose**: the archive feeds **one** channel (TestFlight
  on `main`), not "two channels (dev-IPA artifact every ref; TestFlight on main)".
- [ ] 5.2 Update `openspec/specs/ios-testflight-delivery/spec.md` **Purpose**: "per-branch installability
  served by the development-IPA artifact" → served out of band by the ssh-mac build loop (dev infra).
- [ ] 5.3 Retire `openspec/specs/ios-sideload-delivery/` — all requirements removed (see the delta); the
  capability spec is dropped at archive.

## 6. Archive

- [ ] 6.1 Archive this change **only** after PR #1 and PR #2 have merged and the §3 on-device install is
  proven, applying the deltas (retire `ios-sideload-delivery`; amend `ios-ci` + `ios-testflight-delivery`).
  Until then, `openspec/specs/` continues to describe the live CI dev-IPA channel.
