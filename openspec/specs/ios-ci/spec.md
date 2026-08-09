# ios-ci Specification

## Purpose
Continuous integration that, on every push, builds the iOS device app and runs the shared Kotlin/Native unit tests on a simulator, each reporting a merge-gating status check. Runs on GitHub Actions (`macos-26`, GM Xcode) — the same provider as the Linux build — doing only the irreducible Apple delta. **Two parallel jobs are the merge gates**: `ios-build` produces a **signed archive** of the device (`iosArm64`) app via `xcodebuild` (the archive is the gate, and the app's only compile), and `ios-test` runs `iosSimulatorArm64Test` on a booted simulator. Together they exercise both Kotlin/Native targets. `ios-build` is a **pure gate** — it exports nothing and uploads nothing to Apple. **Delivery is a third job** (`ios-deliver`) that runs on `main` only and **depends on both gates**, so a red test suite stops the release; it re-signs and packages `ios-build`'s archive without recompiling (capability `ios-testflight-delivery`, which also details code signing). Per-branch device installability before merge is served out of band by the ssh-mac build loop, not a CI artifact.
## Requirements
### Requirement: Build iOS on every push

The system SHALL run a **GitHub Actions** job (`ios-build` in `.github/workflows/ios.yml`) on every push that builds the iOS **device (`iphoneos`, arm64)** app on a **`macos-26` hosted runner**, linking the `iosArm64` framework with the runner's **GM Xcode** (no Xcode beta), and reports a stable status-check context (`ios-build`) used to gate merges. On **every** ref the job SHALL produce a **signed archive** of the device app (signing — capability `ios-testflight-delivery`); the archive compiles `iosArm64`, so the `ios-build` check reflects whether the device app builds.

The workflow SHALL be triggered by **branch pushes only**. It SHALL carry **no `workflow_dispatch` trigger**: the manual-dispatch path existed to build a dev IPA against an alternate host, but `ios-build` publishes its archive on `refs/heads/main` **only**, so a dispatched run archived and then **discarded** the very build it was dispatched to produce. Pointing a device build at an alternate backend is served by the ssh-mac loop, which is where per-branch device installability already lives and where a human is present to receive the IPA.

The archive's **build configuration SHALL depend on the ref**: on `refs/heads/main` — the delivery source — the archive SHALL be built in the **Release** configuration; on every **other** push ref it SHALL be built in the **Debug** configuration. The Debug gate archive compiles the identical surface — the same Kotlin frontend and `iosArm64` klib compiles, the same Swift compile, entitlements, and signing — skipping only the LLVM optimization pass of the Release link, while gating nothing on an artifact that is discarded off `main`. **The saving is real but modest**: measured 2026-08-09, the Debug gate averages **10.6 min** (n=21 branch runs) against Release's **13.0 min** on `main` (n=19) — ~2.4 min, not the 5–9.5 min this spec previously claimed, and small beside the job's own run-to-run spread (6.9–14.6 min for identical work). The optimizer is **not** what dominates the archive: profiled in CI, `:app:ios:linkDebugFrameworkIosArm64` alone accounts for 249 s of a 414 s archive (71% with the extension's link), against ~1 s of Gradle configuration — the cost is CPU-bound Kotlin/Native linking of the Compose closure on a 3-core runner, which no build configuration or cache avoids. The accepted trade-off: a **Release-only build failure** (e.g. an optimizer crash in the Kotlin/Native link) passes the branch gate and surfaces on the post-merge `main` run — a red but non-gating `ios-build`, with delivery skipped because `ios-deliver` needs both gates (capability `ios-testflight-delivery`). Removing the dispatch trigger **widens** this accepted trade-off: there is no longer any pre-merge way to force the Release path on a branch, so a Release-only failure is discovered only after merge.

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

#### Scenario: The workflow offers no manual dispatch
- **WHEN** an operator inspects the iOS workflow's triggers
- **THEN** only the branch-push trigger is present and the workflow cannot be dispatched manually

#### Scenario: A branch push gates with a Debug archive
- **WHEN** a commit is pushed to a ref other than `refs/heads/main`
- **THEN** the signed archive is built in the Debug configuration — same klib compiles, Swift compile, entitlements, and signing, no LLVM optimization pass — and the `ios-build` check still reflects whether the device app compiles

#### Scenario: main archives Release
- **WHEN** the `ios-build` job runs on `refs/heads/main`
- **THEN** the signed archive is built in the Release configuration, so the artifact handed to `ios-deliver` is the distribution build

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

### Requirement: Toolchain caching

The workflow SHALL cache the Gradle (`~/.gradle`) and Kotlin/Native (`~/.konan`) directories across runs, so a warm build avoids re-downloading the toolchain and recompiling unchanged Kotlin/Native artifacts.

#### Scenario: A warm build reuses the caches
- **WHEN** a workflow runs after a previous run populated the caches
- **THEN** `~/.gradle` and `~/.konan` are restored and the Kotlin/Native toolchain is not re-downloaded

### Requirement: Cancel superseded builds

The workflow SHALL cancel an in-progress build for a ref when a newer push to that ref arrives, so rapid pushes do not pile up and exhaust build minutes.

#### Scenario: A newer push cancels the in-progress iOS build
- **WHEN** a new commit is pushed to a ref that already has a running iOS build
- **THEN** the in-progress build is cancelled and the new one proceeds

### Requirement: Test iOS on every push

The system SHALL run a **GitHub Actions** job (`ios-test` in `.github/workflows/ios.yml`) on every push, **in parallel** with the `ios-build` job, on a **`macos-26` hosted runner**. The job SHALL run `./gradlew iosSimulatorArm64Test`, which boots an iOS simulator and executes the shared modules' `commonTest` suites compiled to **Kotlin/Native** for the `iosSimulatorArm64` target. The job SHALL post a stable status-check context (`ios-test`) used to gate merges. The `ios-test` and `ios-build` jobs together exercise both Kotlin/Native targets — `iosSimulatorArm64` via the test, `iosArm64` via the build.

#### Scenario: A push triggers the iOS test check
- **WHEN** a commit is pushed to any branch
- **THEN** the `ios-test` job runs `iosSimulatorArm64Test` on `macos-26`, booting an iOS simulator and running the shared `commonTest` compiled to Kotlin/Native, and reports an `ios-test` status check on the pushed commit

#### Scenario: Test success reports a passing check
- **WHEN** the Kotlin/Native simulator unit tests all pass
- **THEN** the `ios-test` status check concludes as success (green)

#### Scenario: Test failure reports a failing check
- **WHEN** a Kotlin/Native simulator unit test fails
- **THEN** the `ios-test` status check concludes as failure (red)

#### Scenario: Test runs in parallel with the build
- **WHEN** the iOS workflow runs
- **THEN** the `ios-test` and `ios-build` jobs run as independent parallel jobs (neither waits on the other)

### Requirement: The merge gates are exactly the two parallel jobs

The iOS workflow's **merge gates** SHALL be exactly the two parallel jobs `ios-build` and `ios-test`, and these are the only two iOS status-check contexts required by the branch ruleset (capability `branch-protection`). Adding a `needs:` dependency between them is forbidden: a failing `ios-test` would then *skip* `ios-build`, whose required check would never be posted, freezing merges.

The delivery job `ios-deliver` SHALL NOT be a required status check. It runs only on `refs/heads/main`, so it is never posted on a pull-request branch; requiring it would block every merge on a check that can never appear. Its purpose is to gate **delivery**, not merges — it depends on both gates, so it simply does not run when either is red.

#### Scenario: The two gates stay independent
- **WHEN** the iOS workflow runs on any ref
- **THEN** `ios-build` and `ios-test` each run and report regardless of the other's outcome, so a red test still tells you whether the device app compiles

#### Scenario: The delivery job is not a merge gate
- **WHEN** the branch ruleset's required status checks are applied
- **THEN** they include `ios-build` and `ios-test` but NOT `ios-deliver`, which never runs on a pull-request branch and would freeze merges if required

### Requirement: Compile-time edge host default

The extension's `BackgroundUploadURLBase` (build setting `BACKGROUND_UPLOAD_URL_BASE`) SHALL default
to the **deployed HTTPS backend URL** baked from `Config.xcconfig` — the single source of the host
literal — so **every ref**, including the `main`/TestFlight build, targets it (safe because the
device carries no storage credential and the endpoint is the production backend). The iOS workflow
SHALL **not** restate the host and SHALL provide **no** mechanism to override it: it SHALL omit any
`BACKGROUND_UPLOAD_URL_BASE` override on every ref and let the `Config.xcconfig` default flow
through. This requirement is the **single owner** of the compile-time upload-host contract; the
TestFlight build inherits whatever host this shared archive step bakes.

Overriding the host for a **development** build is an out-of-band operator action performed on the
ssh-mac `xcodebuild` invocation (dev infrastructure; see the runbook in `CLAUDE.md`), never a CI
input. The one xcconfig setting feeds **both** targets' `Info.plist`, so a single override covers the
app and the background-upload extension together. It SHALL remain **HTTPS**: default ATS forbids
plaintext and no `NSAllowsLocalNetworking` exception ships, so a baked `http://` host would fail
silently on device.

#### Scenario: Every build bakes the deployed host from xcconfig
- **WHEN** the iOS workflow runs on any ref
- **THEN** the workflow sets no `BACKGROUND_UPLOAD_URL_BASE` override and the archive bakes the
  `Config.xcconfig` default (the deployed HTTPS backend URL)

#### Scenario: TestFlight build targets the live endpoint
- **WHEN** the `ios-build` job runs on `refs/heads/main`
- **THEN** the uploaded TestFlight build's `BackgroundUploadURLBase` is the deployed HTTPS backend URL

#### Scenario: CI exposes no host override
- **WHEN** an operator wants a device build pointed at an alternate backend
- **THEN** no CI input provides one, and the override is applied to the ssh-mac `xcodebuild`
  invocation instead, where the resulting IPA is actually delivered to a device

