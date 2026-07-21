# Archive Debug on branch refs — the gate compiles, only main's archive is consumed

## Why

`ios-build` is the slowest merge gate: 8.5–15 minutes on every push, and the job log shows one dominant cost — the Kotlin/Native **Release** link of the app framework (`:app:ios:linkReleaseFrameworkIosArm64`), measured at **9m20s of a 14m46s run** (5m01s in an 8m24s run; the spread is runner variance). The extension framework's Release link adds 1–2 more minutes. That time buys LLVM optimization — nothing else: the Release and Debug configurations run the identical Kotlin frontend, the same `iosArm64` klib compiles, the same Swift compile, entitlements, and signing. On every ref except `main` the archive is a **pure gate that gets discarded** (spec `ios-ci`: no artifact off main), so on branches the optimization pass gates nothing and costs ~7–9 minutes per push. The repo already measured the same ratio on the ssh-mac loop (Release link 449s vs Debug 57s, CLAUDE.md) and already routes the on-demand dev IPA through a Debug archive for exactly this reason.

## What Changes

- **Branch pushes archive Debug.** `ios.yml`'s configuration-select step gains a third shape: a push to any ref other than `refs/heads/main` sets `BUILD_CONFIG=Debug` with no host/APNs/version overrides. The gate still produces a signed archive that compiles the full `iosArm64` surface; it only skips the LLVM optimization pass.
- **`main` and every `workflow_dispatch` without `upload_host` stay Release** — `main` because its archive is the delivery source (production APNs + DSN + computed marketing version, all unchanged), a plain dispatch as the manual escape hatch to exercise the full Release path on a branch before merge.
- **The dev-IPA dispatch path is untouched** (Debug + `upload_host` override, as today).
- No step, secret, or conditional downstream changes: the marketing-version, APNs, and DSN logic already key off `BUILD_CONFIG == Release`, so branch-gate archives automatically bake the dev defaults (sandbox APNs, no DSN, floor version) — all immaterial to a discarded archive.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `ios-ci`: the "Build iOS on every push" requirement pins the archive configuration per ref — Debug on non-main push refs (the gate), Release on `main` and on any manual dispatch — and names the accepted trade-off (Release-only build breakage surfaces on the post-merge `main` run, which is red but non-gating).
- `ios-testflight-delivery`: the APNs and crash-reporting-DSN requirements each name the branch-gate Debug archive alongside the dev/sideload builds as the cases that keep the `Config.xcconfig` dev defaults — the "only dev/sideload builds stay sandbox" sentence would otherwise be false.

## Impact

- `.github/workflows/ios.yml` — the "Select build configuration" step (one added branch in the conditional) and the surrounding comments. No change to the composite action, the required-checks surface, secrets, or `ios-deliver`.
- `CLAUDE.md` — the APNs sentence in the TestFlight section gains the branch-gate case.
- Expected effect: the `ios-build` check on PR branches drops from ~9–15 min to ~4–6 min; `main` runs are unchanged.
