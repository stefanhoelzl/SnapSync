## Context

iOS work is macOS-only and there is no Mac in the local environment (Linux sandbox). The Linux proxy
(`compileIosMainKotlinMetadata`) catches iOS *compile* breakage, but the real device archive
(`xcodebuild archive`, `iosArm64`), the simulator tests (`iosSimulatorArm64Test`), and a sideloadable dev
IPA all require a Mac. Today that Mac is only ever a fresh `macos-26` CI runner reached by a full push →
build → download round-trip.

The physical SE2 (`00008030-0018703A1A7A402E`, iOS 26.5) is reachable **from the sandbox** over the host
usbmuxd bridge (`USBMUXD_SOCKET_ADDRESS=/run/host/run/usbmuxd`) — **not** from the runner. So a build
produced on the runner must come back to the sandbox to be installed. `ios.yml` already imports two
persistent certs (Distribution + Development) and uses an **Admin** ASC key with
`-allowProvisioningUpdates`; the repo is **public**, so standard `macos-26` runners are free (the cost is
the 6-hour job cap and concurrency, not money).

## Goals / Non-Goals

**Goals:**
- Give the agent a headless, long-lived macOS build box it can drive over SSH from the sandbox, iterating
  `rsync → build → test → sign → scp back → install` many times against one warm runner.
- Expose it with an auth model where **no auth secret is ever in a log** and only the sandbox can connect.
- Minimize what signing material is reachable during the interactive (world-addressable) window.
- Once the loop works, retire the redundant every-push CI dev-IPA channel so there is a **single** way to
  produce a sideloadable build — while keeping `openspec/specs/` truthful.

**Non-Goals:**
- A capability spec for the ssh-mac loop. It is dev infrastructure (build/CI), exempt from OpenSpec per
  the repo rule; its rationale lives in the workflow header comment + a `CLAUDE.md` runbook.
- Tapping/UI automation on device (still needs a signed WebDriverAgent) or forcing the OS-scheduled upload
  extension — out of scope, unchanged.
- Changing the merge gate, `ios-test`, build numbering, the imported certs, or the `main`-only TestFlight
  delivery.
- Automating keypair generation, dispatch, or install inside CI — those are agent/operator steps.

## Decisions

**1. Own `sshd` + pubkey-via-dispatch (vs `action-tmate`).**
`action-tmate` routes SSH through the **public `tmate.io` relay**, which terminates SSH and sits in the
session path — undesirable on a box holding signing material, and the connection string in a public-repo
log is the gate. Instead the runner runs its **own user-space `sshd`** and authorizes an `ssh_pubkey`
passed as a `workflow_dispatch` input. A public key is public by design, so it is safe in the dispatch
input and in a world-readable log; the **private key never leaves the sandbox**. This cleanly separates
*authentication* (only the sandbox's key works) from *connectivity* (below), and puts **no auth secret in
any log**. *Trade-off:* we manage a small `sshd` bring-up (generated host key, high port, non-root) instead
of an off-the-shelf action.

**2. `cloudflared` quick tunnel for connectivity (vs Tailscale / ngrok / own bastion).**
The runner has no public inbound IP, so something must carry bytes to the `sshd`. `cloudflared --url
ssh://localhost:<port>` needs no account (quick tunnel) and relays **encrypted TCP only** — it cannot read
the SSH session (unlike the tmate relay). The client side (`cloudflared access ssh --hostname %h` as an
SSH `ProxyCommand`) was verified to download and run in this sandbox. Tailscale would give a stabler
hostname but needs `tailscaled`/auth on the sandbox (may be blocked); an own-bastion reverse tunnel needs
a VPS we do not have. *Trade-off:* a dynamic `*.trycloudflare.com` hostname per run, surfaced via the run
log and scraped with `gh run view --log`.

**3. Dev cert only during the reachable window; ASC key fetched-then-deleted (vs certs upfront).**
Repeatable in-session signing forces certs onto the box for the whole session. To shrink the blast radius
on a public-repo box: a pre-`sshd` step uses the Admin ASC key **once** to install the development
provisioning profile (and warm derived data), then **deletes the ASC key** before the tunnel opens.
In-session archives then sign **manually** (dev cert + installed profile), with **no**
`-allowProvisioningUpdates` and **no ASC key**. During the only window anyone could connect, the sole
reachable secret is the **development cert's private key** — enough to sign dev builds for registered
devices, not the Admin key that manages the whole Apple account. Credentials still come only from Secrets
and are never cached (mirrors `ios-testflight-delivery`).

**4. Sentinel + timeout lifecycle (vs `gh run cancel` only, or pure timeout).**
The interactive step blocks on a wait-for-sentinel loop; the agent `touch`es `/tmp/stop` when done so
cleanup runs and the job ends gracefully, with `timeout-minutes` (≈90) as the backstop against a forgotten
session. A hard `gh run cancel` skips cleanup; a pure timeout can guillotine a build mid-flight. Public
repo ⇒ no dollar cost; the guardrail is the 6-hour cap and not hogging a concurrency slot.

**5. Minimal cut of `ios.yml` (vs unsigned non-main gate).**
Phase 2 deletes **only** the dev-IPA export + artifact-upload steps. The signed archive stays the gate and
**both certs stay imported** — `xcodebuild archive` provisions a Development identity in addition to the
Distribution one, so dropping the Development import would re-introduce the per-run cert-cap churn the
header comment documents. Making non-main a fully unsigned compile gate (removing all signing secrets from
branch pushes) is a larger, separable refactor; not in scope here.

**6. One change, two phases, prove-first, archive-last (vs two changes / all-at-once).**
The ssh-mac loop carries no spec, so Phase 1 is a plain CI PR that stands alone and is exercised on the
SE2 before anything is removed. Because a change's spec deltas are only applied to `openspec/specs/` at
**archive** time, keeping both phases in one change causes **no divergence** during the prove-first window:
the specs keep describing the live CI channel until Phase 2 actually deletes it and the change is archived.
Two separate PRs still ship the phases independently; the single change just tracks the whole effort.

**7. Device-installability guarantee migrates spec → runbook (vs a new spec requirement).**
The "any branch installs on a registered device before merge" guarantee is preserved, but its vehicle is
now the ssh-mac session + a local `pymobiledevice3 apps install` over usbmuxd — an operator/agent
procedure, exactly like the existing sideload runbook. It is documented in `CLAUDE.md`, not asserted as a
`SHALL`.

## Risks / Trade-offs

- **User-space `sshd` on the `macos-26` runner without root** → Verify during Phase 1 that a non-root
  `sshd` on a high port with a generated host key accepts the authorized key. Fallback: a packaged action
  or `cloudflared`-brokered access if bring-up is fragile.
- **End-to-end `cloudflared access ssh` from the sandbox** → client binary verified to run here; the live
  tunnel handshake is verified in Phase 1. Fallback transport: ngrok TCP (agent side stays plain `ssh`).
- **Manual signing reuses the profile across the ASC-key-deletion boundary** → confirm an in-session
  `xcodebuild -exportArchive` (manual, no `-allowProvisioningUpdates`) succeeds using the profile the
  pre-step installed. If not, keep the ASC key for the session (falls back to Decision-3's rejected
  "certs upfront" with a documented larger blast radius).
- **Public-repo box holds the dev cert's private key while reachable** → bounded by pubkey-auth (only the
  sandbox connects) and by removing the Admin ASC key before the tunnel opens; the dev cert is revocable
  independently of CI's Distribution cert.
- **Removing the CI dev-IPA artifact before ssh-mac is proven** → mitigated by sequencing: Phase 2 is gated
  on a real on-device install through the ssh-mac loop; the change is not archived until both phases land.

## Migration Plan

1. **Phase 1 (PR #1, no OpenSpec):** add `ssh-mac.yml` + header comment; add the `CLAUDE.md` runbook.
   Dispatch it, connect from the sandbox, and run a full loop: rsync → `iosSimulatorArm64Test` → archive →
   manual dev export → scp back → `pymobiledevice3 apps install` on the SE2. This proves the replacement.
2. **Phase 2 (PR #2):** delete the dev-IPA export + artifact-upload steps from `ios.yml`; update its header
   comment; rewrite the "Sideload a dev IPA" runbook to point at ssh-mac.
3. **Archive** this change only after both PRs merge and the on-device loop is proven — applying the spec
   deltas (retire `ios-sideload-delivery`; amend `ios-ci` + `ios-testflight-delivery`).
4. **Rollback:** Phase 2 is a pure `ios.yml` revert (the dev-IPA artifact channel returns); Phase 1 is an
   isolated dispatch-only workflow that can be deleted with no effect on the merge gate or any secret.

## Open Questions

None blocking. The three verify-during-Phase-1 items (non-root `sshd`, live `cloudflared` SSH, profile
reuse across the ASC-key-deletion boundary) each have a stated fallback and none gate merges (the workflow
is non-gating).
