## MODIFIED Requirements

### Requirement: Build iOS on every push

The system SHALL run a **GitHub Actions** job (`ios-build` in `.github/workflows/ios.yml`) on every push that builds the iOS **device (`iphoneos`, arm64)** app on a **`macos-26` hosted runner**, linking the `iosArm64` framework with the runner's **GM Xcode** (no Xcode beta), and reports a stable status-check context (`ios-build`) used to gate merges. On **every** ref the job SHALL produce a **signed archive** of the device app (signing — capability `ios-testflight-delivery`); the archive compiles `iosArm64`, so the `ios-build` check reflects whether the device app builds.

The archive's **build configuration SHALL depend on the ref**: on `refs/heads/main` — the delivery source — and on any `workflow_dispatch` without an `upload_host` (the deliberate escape hatch for exercising the full Release path on a branch before merge), the archive SHALL be built in the **Release** configuration; on every **other** push ref it SHALL be built in the **Debug** configuration. (A dispatch **with** an `upload_host` is the dev-IPA path — Debug by its own contract, capability `ios-testflight-delivery`.) The Debug gate archive compiles the identical surface — the same Kotlin frontend and `iosArm64` klib compiles, the same Swift compile, entitlements, and signing — skipping only the LLVM optimization pass of the Release link, which dominates the archive (measured 5–9.5 min of a 9–15 min job) while gating nothing on an artifact that is discarded off `main`. The accepted trade-off: a **Release-only build failure** (e.g. an optimizer crash in the Kotlin/Native link) passes the branch gate and surfaces on the post-merge `main` run — a red but non-gating `ios-build`, with delivery skipped because `ios-deliver` needs both gates (capability `ios-testflight-delivery`).

The job SHALL be a **pure gate**: it SHALL NOT export an IPA and SHALL NOT upload anything to Apple. On **`refs/heads/main` only** it SHALL publish the signed archive as a **workflow artifact** for the `ios-deliver` job (capability `ios-testflight-delivery`); on any **other** ref the archive is produced **solely as the merge gate** and the job publishes no artifact. Because a workflow-artifact round-trip does not preserve executable bits or symlinks — which would corrupt the signed `.app` bundle and break the later export — the archive SHALL be **packed (tar) before upload and unpacked after download**, so it survives the hand-off intact.

Per-branch device installability before merge is served **out of band** by the interactive ssh-mac build loop (dev infrastructure — `.github/workflows/ssh-mac.yml`; see the runbook in `CLAUDE.md`), not by any CI artifact. The job SHALL run no tests and boot no simulator. The device app SHALL be compiled exactly once per push — the delivery job re-signs and packages this archive and SHALL NOT recompile it.

#### Scenario: A push triggers the iOS build check
- **WHEN** a commit is pushed to any branch
- **THEN** the `ios-build` job builds the iOS device (arm64) app on `macos-26` with the runner's GM Xcode and reports an `ios-build` status check on the pushed commit

#### Scenario: Build success reports a passing check
- **WHEN** the device app's signed archive compiles successfully
- **THEN** the `ios-build` status check concludes as success (green)

#### Scenario: Build failure reports a failing check
- **WHEN** the device app fails to compile
- **THEN** the `ios-build` status check concludes as failure (red)

#### Scenario: A branch push gates with a Debug archive
- **WHEN** a commit is pushed to a ref other than `refs/heads/main`
- **THEN** the signed archive is built in the Debug configuration — same klib compiles, Swift compile, entitlements, and signing, no LLVM optimization pass — and the `ios-build` check still reflects whether the device app compiles

#### Scenario: main archives Release
- **WHEN** the `ios-build` job runs on `refs/heads/main`
- **THEN** the signed archive is built in the Release configuration, so the artifact handed to `ios-deliver` is the distribution build

#### Scenario: A plain dispatch exercises the Release path on a branch
- **WHEN** the workflow is manually dispatched on a branch with no `upload_host` input
- **THEN** the archive is built in the Release configuration, providing pre-merge proof of the Release link for that branch

#### Scenario: Every ref archives as the gate; non-main publishes nothing
- **WHEN** the `ios-build` job runs on any ref
- **THEN** it produces a signed archive of the device app (the merge gate), executing no tests and no simulator boot; on a ref other than `refs/heads/main` it publishes no artifact, while the `ios-build` check still reflects whether the device app compiles

#### Scenario: The gate job never talks to Apple
- **WHEN** the `ios-build` job runs on any ref, including `refs/heads/main`
- **THEN** it exports no IPA and uploads nothing to TestFlight; delivery is performed only by the `ios-deliver` job (capability `ios-testflight-delivery`)

#### Scenario: Only main hands the archive to the delivery job
- **WHEN** the `ios-build` job runs on `refs/heads/main`
- **THEN** it packs the signed archive and publishes it as a workflow artifact for `ios-deliver`; on any other ref that step is skipped

#### Scenario: The archive survives the hand-off intact
- **WHEN** the signed archive is passed from `ios-build` to `ios-deliver` as a workflow artifact
- **THEN** it is packed before upload and unpacked after download, preserving executable bits and symlinks, so the signed `.app` bundle is not corrupted and the export succeeds

#### Scenario: No beta Xcode is used
- **WHEN** the iOS workflow runs
- **THEN** it uses the `macos-26` runner's GM Xcode and does not select an Xcode beta SDK
