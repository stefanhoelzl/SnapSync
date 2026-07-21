## Context

Every push runs `ios-build` (capability `ios-ci`), which archives the signed device app in **Release** on all refs. Job-log timing of two recent runs (2026-07-21):

- run 29817160552 (`main`, 14m46s): `:app:ios:linkReleaseFrameworkIosArm64` **9m20s**, `:app:ios:extension:linkReleaseFrameworkIosArm64` 1m53s, everything else (setup, klib compiles, Swift, actool, signing, artifact) ~3.5 min.
- run 29816276746 (branch, 8m24s): the same two links at 5m01s and 1m13s.

The klib compiles hit the Gradle build cache; the Kotlin/Native **link** tasks are not cacheable, so the LLVM optimization pass re-runs on every push. The repo's own ssh-mac measurement (CLAUDE.md) puts the ratio at Release 449s vs Debug 57s (~8×) for a warm link, and the on-demand dev-IPA path already builds Debug for exactly this reason.

On any ref except `main` the archive is a **pure gate** — `ios-ci` requires that no artifact is published off main, and `ios-deliver` consumes only main's archive. So on branches the optimization pass is paid on every push and gates nothing.

## Goals / Non-Goals

**Goals:**

- Cut the branch `ios-build` gate to roughly the non-link cost (~4–6 min) without weakening what it gates: full `iosArm64` compile, Swift compile, entitlements, signing.
- Leave the `main` archive — the one TestFlight and the App Store promote channel consume — byte-for-byte governed by the same rules as today.
- No new secrets, steps, runners, or required-check changes.

**Non-Goals:**

- Speeding up `ios-test` (already ~2 min median) or the runner-queueing situation.
- Caching the SPM checkout or trimming the composite action's pre-compile step (small, independent follow-ups).
- Larger (paid) macOS runners.

## Decisions

**D1 — Branch pushes archive Debug; `main` archives Release.**
The gate's contract (`ios-ci`) is "does the device app compile" — the archive compiles `iosArm64`, and that surface is configuration-independent: Debug runs the identical Kotlin frontend, klib set, Swift compile, and signing, skipping only LLVM optimization. The alternatives: status quo (pay ~7–9 min per push for optimization of a discarded binary), or a paid larger runner (roughly halves the link, trades free CI for cash). Rejected: gating branches on a bare `linkReleaseFrameworkIosArm64` Gradle invocation without Xcode — it would drop the Swift/entitlements/signing half of the gate.

**D2 — A plain `workflow_dispatch` (no `upload_host`) stays Release.**
This is the deliberate escape hatch: when a change is suspected of Release-only breakage (e.g. it touches linker-sensitive code), dispatching `ios.yml` on the branch exercises the full Release archive before merge. It costs nothing to keep — that is the current behavior of a plain dispatch.

**D3 — Downstream conditionals are left keying off `BUILD_CONFIG == Release`, not re-plumbed per ref.**
The marketing-version step, the APNs/DSN overrides, and the sentry-dsn input already branch on the configuration. A branch-gate Debug archive therefore automatically bakes the dev defaults (floor version, sandbox APNs, no DSN). That is correct, not incidental: the archive is discarded, and tying reporting/push posture to the configuration with no separate flag is an existing spec decision (`ios-testflight-delivery`). The specs are updated to *name* the branch-gate archive in the dev-default set so the "only dev/sideload builds" sentence stays true.

**D4 — Signing is unchanged on branches (still a signed archive).**
Keeping the archive signed keeps the gate's coverage of entitlements and automatic-signing regressions, and the Debug + automatic-signing + imported-dev-cert path is already proven in CI by the dev-IPA dispatch route through the same composite action. The cert-cap rationale for importing both persistent certs is untouched.

## Risks / Trade-offs

- [A genuine Release-only *build* failure — e.g. an optimizer crash in the Kotlin/Native link — passes the branch gate and surfaces on the post-merge `main` run] → Accepted: `main` still builds Release on every merge, so the feedback lag is minutes, and the failure mode is a red `ios-build` on `main` with delivery skipped (`ios-deliver` needs both gates) — visible, non-blocking, and revertible. The D2 dispatch is the pre-merge escape hatch. Such failures are rare (none observed in this repo's history) and are compiler bugs, not code errors the author could have read off a red check.
- [Branch and main archives now differ in configuration, so "it passed the gate" no longer means "the delivered bits built"] → The delivered bits are still gated: `ios-deliver` runs only after `main`'s own Release `ios-build` succeeds. What is lost is only *pre-merge* proof of the Release link.
- [Runtime-behavior differences between Debug and Release] → Out of scope by construction: the gate never executes the archive (no tests, no simulator — `ios-ci`), so optimization-level runtime behavior was never gated.
