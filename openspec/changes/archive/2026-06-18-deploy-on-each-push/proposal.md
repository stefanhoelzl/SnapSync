## Why

TestFlight delivery is currently gated to `main` only (`ios-testflight-delivery`): a branch push builds the device app **unsigned** as the merge gate and never reaches a phone. But the natural workflow is to **test a change on real hardware before merging it** — which today is impossible without merging first. Since signing is fully cloud-managed and build numbers come from the globally-monotonic `github.run_number`, uploading from any ref is a near-free extension of the existing pipeline: no new secrets, no new job, no build-number collisions.

## What Changes

- **Sign and deliver on every push, any ref** — not just `main`. The `ios-build` job's `main`-conditional archive/export/upload steps lose their `if: github.ref == 'refs/heads/main'` guards and run on every push. Every branch becomes installable via TestFlight internal testing (no Beta App Review) before merge.
- **Remove the unsigned non-`main` build step** — the signed archive (which compiles `iosArm64`) is now the merge gate on every ref, so the separate unsigned `xcodebuild build` step is redundant and is deleted. The device app is still compiled exactly **once** per push.
- **Decouple delivery from the gate** — `Export signed IPA` and `Upload to TestFlight` run with `continue-on-error: true`, so a transient App Store Connect / delivery failure leaves the required `ios-build` check green and does not block merges (the failure stays visible in the run).
- **Build identity** — builds are identified by `CFBundleVersion = github.run_number`; cross-reference it to the branch/commit via the Actions run. No "What to Test" notes, no per-branch version strings.

## Capabilities

### Modified Capabilities
- `ios-testflight-delivery`: delivery is no longer `main`-only — every push on any ref archives, signs, exports, and uploads. The non-`main`-skips behavior is removed, and a new requirement makes export + upload non-blocking for the merge gate.
- `ios-ci`: the `ios-build` job no longer builds unsigned-and-build-only on non-`main`; on **every** ref it produces a signed archive that doubles as the merge gate.

## Impact

- **CI:** `.github/workflows/ios.yml` — the unsigned build step is removed; the prepare-key / archive / export / upload steps lose their `main`-only guards; export + upload gain `continue-on-error`. `ios-test` is unchanged. Status-check contexts (`ios-build`, `ios-test`) are unchanged, so `.github/rulesets/main.json` still matches.
- **No new secrets** — the existing 3 ASC secrets (`ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_API_PRIVATE_KEY`) already cover any-ref signing.
- **Cost:** every WIP push now pays the full signed archive + export + upload on the ~10x-billed `macos-26` runner (previously only `main` did). Branch builds also shift Debug→Release (archives are distribution builds).
- **TestFlight:** the single `app.snapsync` app's build list fills from all branches; internal builds expire after 90 days, so it self-trims.
- **Forked PRs** cannot read secrets, so their pushes can't sign/deliver — only relevant if outside contributions are ever accepted.
- **No application/domain code changes** — purely delivery infrastructure.
