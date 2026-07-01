# ios-ci Specification

## Purpose
Continuous integration that, on every push, builds the iOS device app and runs the shared Kotlin/Native unit tests on a simulator, each reporting a merge-gating status check. Runs on GitHub Actions (`macos-26`, GM Xcode) — the same provider as the Linux build — via two parallel jobs doing only the irreducible Apple delta: `ios-build` produces a **signed archive** of the device (`iosArm64`) app via `xcodebuild` — the archive is the merge gate and feeds a single delivery channel from that single compile (a TestFlight upload on `main`, capability `ios-testflight-delivery`; per-branch device installability before merge is served out of band by the ssh-mac build loop, not a CI artifact) — and `ios-test` runs `iosSimulatorArm64Test` on a booted simulator. Together they exercise both Kotlin/Native targets. Code signing and delivery are detailed in a separate capability (`ios-testflight-delivery`).
## Requirements

### Requirement: Build iOS on every push

The system SHALL run a **GitHub Actions** job (`ios-build` in `.github/workflows/ios.yml`) on every push that builds the iOS **device (`iphoneos`, arm64)** app on a **`macos-26` hosted runner**, linking the `iosArm64` framework with the runner's **GM Xcode** (no Xcode beta), and reports a stable status-check context (`ios-build`) used to gate merges. On **every** ref the job SHALL produce a **signed archive** of the device app (signing — capability `ios-testflight-delivery`); the archive compiles `iosArm64`, so the `ios-build` check reflects whether the device app builds. From that single archive the job SHALL deliver across **one channel**: an **App Store build uploaded to TestFlight on `refs/heads/main` only** (capability `ios-testflight-delivery`). On any **other** ref the archive is produced **solely as the merge gate** and the job delivers no build artifact. Per-branch device installability before merge is served **out of band** by the interactive ssh-mac build loop (dev infrastructure — `.github/workflows/ssh-mac.yml`; see the runbook in `CLAUDE.md`), not by any CI artifact. The job SHALL run no tests and boot no simulator. The device app SHALL be compiled exactly once per push.

#### Scenario: A push triggers the iOS build check
- **WHEN** a commit is pushed to any branch
- **THEN** the `ios-build` job builds the iOS device (arm64) app on `macos-26` with the runner's GM Xcode and reports an `ios-build` status check on the pushed commit

#### Scenario: Build success reports a passing check
- **WHEN** the device app's signed archive compiles successfully
- **THEN** the `ios-build` status check concludes as success (green)

#### Scenario: Build failure reports a failing check
- **WHEN** the device app fails to compile
- **THEN** the `ios-build` status check concludes as failure (red)

#### Scenario: Every ref archives as the gate; non-main delivers nothing
- **WHEN** the `ios-build` job runs on any ref
- **THEN** it produces a signed archive of the device app (the merge gate), executing no tests and no simulator boot; on a ref other than `refs/heads/main` it uploads no build artifact, while the `ios-build` check still reflects whether the device app compiles

#### Scenario: Only main delivers to TestFlight
- **WHEN** the `ios-build` job runs on `refs/heads/main`
- **THEN** it additionally exports an App Store IPA and uploads it to TestFlight (capability `ios-testflight-delivery`); on any other ref those steps are skipped

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

### Requirement: Compile-time edge host default and override

The extension's `BackgroundUploadURLBase` (build setting `BACKGROUND_UPLOAD_URL_BASE`) SHALL default
to the **deployed HTTPS backend URL** baked from `Config.xcconfig` — the single source of the host
literal — so **every ref**, including the `main`/TestFlight build, targets it (safe because the
device carries no storage credential and the endpoint is the production backend). The iOS workflow
SHALL **not** restate the host: on a plain push or a dispatch with an empty `upload_host`, the
workflow SHALL omit any `BACKGROUND_UPLOAD_URL_BASE` override and let the `Config.xcconfig` default
flow through. The workflow SHALL retain a `workflow_dispatch` `upload_host` input that, when
non-empty, overrides the baked host for that run (for pointing a development IPA at an alternate
**HTTPS** host, e.g. a staging backend). The `upload_host` input SHALL be **HTTPS-only**: a value
that does not begin with `https://` SHALL fail the run before archiving (default ATS forbids
plaintext, so a baked `http://` host would silently fail on device). The inert `https://dummy.invalid`
default is removed. This requirement is the **single owner** of the compile-time upload-host contract;
the TestFlight build inherits whatever host this shared archive step bakes.

#### Scenario: Default build bakes the deployed host from xcconfig
- **WHEN** the iOS workflow runs on any ref with no `upload_host` dispatch input
- **THEN** the workflow sets no `BACKGROUND_UPLOAD_URL_BASE` override and the archive bakes the
  `Config.xcconfig` default (the deployed HTTPS backend URL, not `dummy.invalid`)

#### Scenario: TestFlight build targets the live endpoint
- **WHEN** the `ios-build` job runs on `refs/heads/main`
- **THEN** the uploaded TestFlight build's `BackgroundUploadURLBase` is the deployed HTTPS backend URL

#### Scenario: Dispatch override bakes a supplied HTTPS host
- **WHEN** the workflow is dispatched with a non-empty `upload_host` beginning with `https://`
- **THEN** that host is baked into `BackgroundUploadURLBase` for that run, overriding the default

#### Scenario: A non-HTTPS dispatch override fails the run
- **WHEN** the workflow is dispatched with an `upload_host` that does not begin with `https://`
- **THEN** the run fails before archiving and bakes no plaintext host

#### Scenario: A dispatch override does not pollute subsequent builds
- **WHEN** a manual dispatch supplies `upload_host` for one run
- **THEN** only that run's archive uses it; subsequent ordinary pushes (including `main`) set no
  override and bake the `Config.xcconfig` default
