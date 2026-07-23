## ADDED Requirements

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

## MODIFIED Requirements

### Requirement: Build iOS on every push

The system SHALL run a **GitHub Actions** job (`ios-build` in `.github/workflows/ios.yml`) on every push that builds the iOS **device (`iphoneos`, arm64)** app on a **`macos-26` hosted runner**, linking the `iosArm64` framework with the runner's **GM Xcode** (no Xcode beta), and reports a stable status-check context (`ios-build`) used to gate merges. On **every** ref the job SHALL produce a **signed archive** of the device app (signing — capability `ios-testflight-delivery`); the archive compiles `iosArm64`, so the `ios-build` check reflects whether the device app builds.

The workflow SHALL be triggered by **branch pushes only**. It SHALL carry **no `workflow_dispatch` trigger**: the manual-dispatch path existed to build a dev IPA against an alternate host, but `ios-build` publishes its archive on `refs/heads/main` **only**, so a dispatched run archived and then **discarded** the very build it was dispatched to produce. Pointing a device build at an alternate backend is served by the ssh-mac loop, which is where per-branch device installability already lives and where a human is present to receive the IPA.

The archive's **build configuration SHALL depend on the ref**: on `refs/heads/main` — the delivery source — the archive SHALL be built in the **Release** configuration; on every **other** push ref it SHALL be built in the **Debug** configuration. The Debug gate archive compiles the identical surface — the same Kotlin frontend and `iosArm64` klib compiles, the same Swift compile, entitlements, and signing — skipping only the LLVM optimization pass of the Release link, which dominates the archive (measured 5–9.5 min of a 9–15 min job) while gating nothing on an artifact that is discarded off `main`. The accepted trade-off: a **Release-only build failure** (e.g. an optimizer crash in the Kotlin/Native link) passes the branch gate and surfaces on the post-merge `main` run — a red but non-gating `ios-build`, with delivery skipped because `ios-deliver` needs both gates (capability `ios-testflight-delivery`). Removing the dispatch trigger **widens** this accepted trade-off: there is no longer any pre-merge way to force the Release path on a branch, so a Release-only failure is discovered only after merge.

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

## REMOVED Requirements

### Requirement: Compile-time edge host default and override

**Reason**: Split. The **default** half is retained verbatim as "Compile-time edge host default"
above; the **override** half — the `workflow_dispatch` `upload_host` input, its HTTPS validation, and
the "dispatch override" scenarios — is removed with the dispatch trigger itself. The input could never
deliver what it promised: `ios-build` publishes its archive on `refs/heads/main` only, so a dispatched
dev-IPA run built a Debug archive and discarded it.

**Migration**: Point a development build at an alternate backend by passing
`BACKGROUND_UPLOAD_URL_BASE=<https-host>` to the ssh-mac `xcodebuild archive` invocation, which
produces an IPA that is actually installed on a device. See the runbook in `CLAUDE.md`.
