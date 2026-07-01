## Why

The iOS build loop is slow: any Mac-only change (the `iosArm64` archive, `iosSimulatorArm64Test`, or a
sideloadable dev IPA) means push → wait in the `macos-26` queue → cold/warm CI build → `gh run download`
→ sideload. There is no Mac in the local environment (Linux sandbox), so today the only way to exercise
Apple-only code is a full CI round-trip. The `ios-sideload-delivery` capability exists purely to shorten
the *install* half of that loop (a dev-IPA artifact on every push), but it does nothing for the
*iterate* half — every compile still costs a fresh CI run.

A **headless, agent-driveable macOS build session** collapses the whole loop: dispatch one long-lived
`macos-26` job that opens an SSH server, connect to it from the sandbox, and iterate `rsync → build →
test → sign → scp the IPA back → install over usbmuxd` many times against one warm runner. macOS runners
are **free on this public repo**, so the only cost is the 6-hour job cap and concurrency hygiene.

Once that loop exists, the every-push **dev-IPA artifact channel in `ios.yml` is a redundant second way
to produce a sideloadable build**. This change adds the loop (Phase 1, dev infra) and then retires the
redundant CI channel (Phase 2, the spec-bearing part) so there is a **single way** to make a dev IPA.

## What Changes

- **Add `.github/workflows/ssh-mac.yml`** — a `workflow_dispatch`-only, **non-gating** job that gives
  the agent a headless macOS build box (Phase 1). It is **dev infrastructure (build/CI)** and per the
  repo workflow rule carries **no capability spec**; its guarantees live in a fat workflow header comment
  and a `CLAUDE.md` runbook (mirroring the existing "Sideload a dev IPA" section). Mechanics:
  - **Auth:** a `ssh_pubkey` dispatch input authorized on a user-space `sshd`. Only the holder of the
    matching private key connects; a public key is public by design, so it is safe in a world-readable
    public-repo log. No auth secret ever touches a log.
  - **Transport:** a `cloudflared` quick tunnel fronts the `sshd`; the runner publishes the
    `*.trycloudflare.com` hostname to the run log. Cloudflare relays encrypted TCP only — it cannot read
    the SSH session.
  - **Signing (dev cert only):** a pre-`sshd` step fetches the development provisioning profile via the
    Admin ASC key, then **deletes the ASC key** before the box is reachable. In-session archives sign
    **manually** (dev cert + installed profile), no `-allowProvisioningUpdates`, no ASC key. Only the dev
    cert's private key is ever reachable over SSH.
  - **Lifecycle:** the interactive step blocks on a stop-sentinel with a `timeout-minutes` backstop.
- **Retire the CI dev-IPA sideload channel** (Phase 2, gated on Phase 1 proven on the device).
  **BREAKING** (CI delivery behavior): delete the **Export development IPA** and **Upload dev IPA
  artifact** steps from `ios.yml`. The signed archive (merge gate) and both imported certs stay; TestFlight
  export/upload is already `main`-only and is unchanged.
- **Preserve the device-installability guarantee, move its vehicle.** "Any branch is installable on a
  registered device before merge" survives — now served by the ssh-mac session's manually-signed dev IPA
  (installed over usbmuxd), not by a CI artifact. This is an operator/agent runbook step, not a `SHALL`.
- **Keep the specs honest.** Removing the artifact falsifies three published specs; this change amends
  them: retire `ios-sideload-delivery`, and repoint the stale cross-references in `ios-ci` and
  `ios-testflight-delivery`.

## Capabilities

### New Capabilities
<!-- none — the ssh-mac build loop is dev infrastructure (build/CI), exempt from OpenSpec per the repo
     workflow rule. Its behavior lives in the ssh-mac.yml header comment + a CLAUDE.md runbook, not a spec. -->

### Modified Capabilities
- `ios-ci`: The "Build iOS on every push" requirement drops the **two delivery channels** framing — the
  single gate archive now feeds **one** channel (TestFlight on `main`); on any other ref the archive is
  produced solely as the merge gate and the job delivers no build artifact. The per-branch-installability
  rationale moves out of band to the ssh-mac loop.
- `ios-testflight-delivery`: Repoint the two `ios-sideload-delivery` cross-references — "installable
  before merge served by the development-IPA artifact" → served out of band by the ssh-mac loop; and the
  signing requirement no longer manages "a development profile for the sideload export" (CI exports no dev
  IPA), while the **Development cert is still imported** because `xcodebuild archive` provisions a
  development identity in addition to the distribution one.

### Removed Capabilities
- `ios-sideload-delivery`: **Retired.** All four requirements (dev-IPA artifact every push; dev signing
  reuses the imported Development cert; reuses the single gate archive; non-gating delivery) are removed.
  The sideloadable dev IPA now comes from the ssh-mac build loop; its device-installability guarantee is
  preserved as a runbook step, not CI behavior.

## Impact

- **New file**: `.github/workflows/ssh-mac.yml` (dispatch-only, non-gating) + its fat header comment.
- **Workflow**: `.github/workflows/ios.yml` (`ios-build` job) — delete the dev-IPA export + artifact-upload
  steps; update the header comment (two channels → one). No change to the archive gate, the imported certs,
  the ASC secrets, `ios-test`, build numbering, or the `main`-only TestFlight steps.
- **Docs**: `CLAUDE.md` — add a "Headless macOS build loop (ssh-mac)" runbook (gen keypair → dispatch →
  scrape hostname → `cloudflared` ProxyCommand → rsync → build/test/sign over SSH → scp back →
  `pymobiledevice3 apps install`); rewrite the existing "Sideload a dev IPA" section to point at it instead
  of `gh run download … snapsync-dev-ipa-…`.
- **Specs**: retire `ios-sideload-delivery`; deltas to `ios-ci` and `ios-testflight-delivery`.
- **Sequencing**: Phase 1 ships first (a normal branch → PR → `/ship`, no OpenSpec) and is **proven on the
  SE2** before Phase 2 removes the CI channel. Both phases are tracked in this one change; the change is
  **archived only after both are done**, so `openspec/specs/` keeps describing the live CI channel until it
  is actually removed.
- **Secrets**: reuses the existing `ASC_*` and `SIGNING_DEV_CERT_*` secrets. No new secrets; the Admin ASC
  key is used once (profile fetch) then deleted before the session opens.
