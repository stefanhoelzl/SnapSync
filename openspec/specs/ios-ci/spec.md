# ios-ci Specification

## Purpose
Continuous integration that, on every push, builds the iOS device app and runs the shared Kotlin/Native unit tests on a simulator, each reporting a merge-gating status check. Runs on GitHub Actions (`macos-26`, GM Xcode) — the same provider as the Linux build — doing only the irreducible Apple delta. **Two parallel jobs are the merge gates**: `ios-build` produces a **signed archive** of the device (`iosArm64`) app via `xcodebuild` (the archive is the gate, and the app's only compile), and `ios-test` runs `iosSimulatorArm64Test` on a booted simulator. Together they exercise both Kotlin/Native targets. `ios-build` is a **pure gate** — it exports nothing and uploads nothing to Apple. **Delivery is a third job** (`ios-deliver`) that runs on **delivering runs** — a push to `main`, or a deliberate `workflow_dispatch` on any ref — and **depends on both gates**, so a red test suite stops the release; it re-signs and packages `ios-build`'s archive without recompiling (capability `ios-testflight-delivery`, which also details code signing). Per-branch device installability before merge is served by that dispatch (the only route to a test device reachable solely through TestFlight) or out of band by the ssh-mac build loop, which hands a human an IPA.
## Requirements
### Requirement: Build iOS on every push

The system SHALL run a **GitHub Actions** job (`ios-build` in `.github/workflows/ios.yml`) on every push that builds the iOS **device (`iphoneos`, arm64)** app on a **`macos-26` hosted runner**, linking the `iosArm64` framework with the runner's **GM Xcode** (no Xcode beta), and reports a stable status-check context (`ios-build`) used to gate merges. On **every** ref the job SHALL produce a **signed archive** of the device app (signing — capability `ios-testflight-delivery`); the archive compiles `iosArm64`, so the `ios-build` check reflects whether the device app builds.

The workflow SHALL be triggered by **branch pushes** and by a **`workflow_dispatch`** on any ref. A dispatched run SHALL be a **delivering run**: it SHALL archive Release, publish the archive, and deliver to internal TestFlight exactly as a push to `main` does (capability `ios-testflight-delivery`). It SHALL accept an optional operator note for the TestFlight "What to Test" text, defaulting to the ref name and short SHA when none is given.

A dispatched run SHALL be marked **distributed** (`channel = release`, capability `deployment-configuration`). This is load-bearing, not cosmetic: the renderer emits `SENTRY_DSN` only for a distributed build, and a build with no DSN opens **no in-app bug-report dialog at all** (capability `diagnostic-logging`) — so a dispatched build that was not marked distributed could not send the diagnostic dump it exists to produce. Production APNs, which a TestFlight build requires regardless, derives from the same single discriminator, so `deployment-configuration`'s one-discriminator rule is preserved.

The dispatch exists because **a test device reachable only through TestFlight cannot be served any other way**. The ssh-mac loop hands a human an IPA, which needs a cable; a platform question only that device can answer otherwise had to merge to `main` to be asked, which forfeits any ability to change one variable at a time. Delivery reaching only the internal group, `ios-deliver`'s dependency on both merge gates, and the deliberate human act a dispatch requires are together what make the widening safe.

An earlier dispatch trigger carrying an `upload_host` input was removed and SHALL NOT return: `ios-build` published its archive on `refs/heads/main` only, so that dispatched run archived Debug and then **discarded** the very build it was dispatched to produce. Pointing a device build at an alternate backend remains the ssh-mac loop's job, where a human is present to receive the IPA.

The archive's **build configuration SHALL depend on whether the run delivers**: on a **delivering run** — a push to `refs/heads/main`, or a `workflow_dispatch` on any ref — the archive SHALL be built in the **Release** configuration; on every **other** push ref it SHALL be built in the **Debug** configuration. The Debug gate archive compiles the identical surface — the same Kotlin frontend and `iosArm64` klib compiles, the same Swift compile, entitlements, and signing — skipping only the LLVM optimization pass of the Release link, while gating nothing on an artifact that is discarded off `main`. **The saving is real but modest**: measured 2026-08-09, the Debug gate averages **10.6 min** (n=21 branch runs) against Release's **13.0 min** on `main` (n=19) — ~2.4 min, not the 5–9.5 min this spec previously claimed, and small beside the job's own run-to-run spread (6.9–14.6 min for identical work). The optimizer is **not** what dominates the archive: profiled in CI, `:app:ios:linkDebugFrameworkIosArm64` alone accounts for 249 s of a 414 s archive (71% with the extension's link), against ~1 s of Gradle configuration — the cost is CPU-bound Kotlin/Native linking of the Compose closure on a 3-core runner, which no build configuration or cache avoids. The accepted trade-off: a **Release-only build failure** (e.g. an optimizer crash in the Kotlin/Native link) passes the branch gate and surfaces on the post-merge `main` run — a red but non-gating `ios-build`, with delivery skipped because `ios-deliver` needs both gates (capability `ios-testflight-delivery`). The pre-merge escape from that trade-off is a `workflow_dispatch` on the branch, which takes the Release path in full.

The job SHALL be a **pure gate**: it SHALL NOT export an IPA and SHALL NOT upload anything to Apple. On a **delivering run only** it SHALL publish the signed archive as a **workflow artifact** for the `ios-deliver` job (capability `ios-testflight-delivery`); on any **other** ref's push the archive is produced **solely as the merge gate** and the job publishes no artifact. Because a workflow-artifact round-trip does not preserve executable bits or symlinks — which would corrupt the signed `.app` bundle and break the later export — the archive SHALL be **packed (tar) before upload and unpacked after download**, so it survives the hand-off intact.

Per-branch device installability before merge is served either by the `workflow_dispatch` above — the only route to a device reachable solely through TestFlight — or **out of band** by the interactive ssh-mac build loop (dev infrastructure — `.github/workflows/ssh-mac.yml`; see the runbook in `CLAUDE.md`), which hands a human an IPA. The job SHALL run no tests and boot no simulator. The device app SHALL be compiled exactly once per push — the delivery job re-signs and packages this archive and SHALL NOT recompile it.

#### Scenario: A push triggers the iOS build check
- **WHEN** a commit is pushed to any branch
- **THEN** the `ios-build` job builds the iOS device (arm64) app on `macos-26` with the runner's GM Xcode and reports an `ios-build` status check on the pushed commit

#### Scenario: Build success reports a passing check
- **WHEN** the device app's signed archive compiles successfully
- **THEN** the `ios-build` status check concludes as success (green)

#### Scenario: Build failure reports a failing check
- **WHEN** the device app fails to compile
- **THEN** the `ios-build` status check concludes as failure (red)

#### Scenario: A branch dispatch delivers to TestFlight
- **WHEN** an operator dispatches the iOS workflow on a ref other than `refs/heads/main`
- **THEN** the archive is built Release, published as an artifact, and delivered to the internal TestFlight group — and the build is marked distributed, so it carries the DSN its diagnostic dump needs

#### Scenario: A dispatched build names itself in TestFlight
- **WHEN** a dispatched run reaches the "What to Test" note and the operator supplied no note
- **THEN** the note is the ref name and the short SHA, so two probe builds are distinguishable in the internal group

#### Scenario: A branch push gates with a Debug archive
- **WHEN** a commit is pushed to a ref other than `refs/heads/main` (and the run is not a dispatch)
- **THEN** the signed archive is built in the Debug configuration — same klib compiles, Swift compile, entitlements, and signing, no LLVM optimization pass — and the `ios-build` check still reflects whether the device app compiles

#### Scenario: A delivering run archives Release
- **WHEN** the `ios-build` job runs on `refs/heads/main`, or as a `workflow_dispatch` on any ref
- **THEN** the signed archive is built in the Release configuration, so the artifact handed to `ios-deliver` is the distribution build

#### Scenario: Every ref archives as the gate; a non-delivering run publishes nothing
- **WHEN** the `ios-build` job runs on any ref
- **THEN** it produces a signed archive of the device app (the merge gate), executing no tests and no simulator boot; on a non-delivering run it publishes no artifact, while the `ios-build` check still reflects whether the device app compiles

#### Scenario: The gate job never talks to Apple
- **WHEN** the `ios-build` job runs on any ref, including `refs/heads/main`
- **THEN** it exports no IPA and uploads nothing to TestFlight; delivery is performed only by the `ios-deliver` job (capability `ios-testflight-delivery`)

#### Scenario: Only a delivering run hands the archive to the delivery job
- **WHEN** the `ios-build` job runs on `refs/heads/main`, or as a `workflow_dispatch` on any ref
- **THEN** it packs the signed archive and publishes it as a workflow artifact for `ios-deliver`; on any other ref's push that step is skipped

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

The extension's baked upload host (`uploadBase` in the generated `Deployment.plist`) SHALL be
**derived from the resolved deployment** (capability `deployment-configuration`) — the single source of
the host, shared with the app's `LINK_ORIGIN`, the `applinks:` entitlement, the AASA the backend serves,
and the site's canonical URLs — so **every ref**, including the `main`/TestFlight build, targets the
deployment's device-facing host (safe because the device carries no storage credential and the endpoint
is the production backend). The iOS workflow SHALL **not** restate the host and SHALL provide **no**
mechanism to override the value directly, and none is available to anyone: the value reaches a generated
bundle RESOURCE, which no `xcodebuild` build-setting override can substitute into. This requirement is the **single owner** of the
compile-time upload-host contract; the TestFlight build inherits whatever host this shared archive step
bakes.

Previously the literal lived in `Config.xcconfig` and was checked by nothing — it was the one copy of the
domain that no guard inspected. Deriving it removes the literal rather than pinning it.

Targeting a different backend for a **development** build SHALL be an out-of-band operator action on the
ssh-mac build invocation (dev infrastructure; see the runbook in `CLAUDE.md`), never a CI input. Where the
target is a declared deployment it SHALL be expressed as **selecting** that deployment rather than as a
bare host string.

The previously admitted exception — a build-setting override for the local-rig tunnel, whose hostname
cloudflared mints **inside the running rig** and is random per session — is **withdrawn**, because the
mechanism it named no longer exists. An operator points a build at a local rig or a tunnel by writing
that host into the local deployment and **re-running the resolver**, which happens after cloudflared has
minted it. That is selecting a deployment rather than overriding a string, which this requirement already
preferred; the override was only ever the concession to a value arriving late, and re-resolving answers
that just as well. No CI input SHALL exist for it.

The one generated resource is copied into **both** bundles, so a single selection covers the app and the
background-upload extension together. The URL **scheme** SHALL be DERIVED from the host rather than
declared beside it: `http` for a loopback IP literal, `https` for every other host. Default ATS applies
and no `NSAllowsLocalNetworking` exception ships, so a plaintext host reached over the network fails
**silently** on device — while ATS exempts the loopback literal, which is what lets a simulator reach
`deno task dev:local` at all. Deriving it means a deployment cannot name a host and a scheme that
disagree, and a tunnel — not loopback — correctly stays HTTPS.

Decision record: `changes/archive/2026-08-25-add-deployment-resolver-and-boot-probe` (the host literal is removed
rather than pinned).

#### Scenario: Every build bakes the deployment's host
- **WHEN** the iOS workflow runs on any ref
- **THEN** the workflow sets no host override and the archive bakes the value generated from the
  resolved deployment into both bundles

#### Scenario: No host literal survives in the build settings
- **WHEN** the committed build settings are inspected
- **THEN** they contain no device-facing host literal; the value is supplied by the generated artifact

#### Scenario: TestFlight build targets the live endpoint
- **WHEN** the `ios-build` job runs on `refs/heads/main`
- **THEN** the uploaded TestFlight build's `BackgroundUploadURLBase` is the resolved deployment's HTTPS
  backend URL

#### Scenario: A development build retargets by selecting a deployment
- **WHEN** an operator builds against a declared backend on the ssh-mac loop
- **THEN** it is done by naming that deployment, and the host is derived from it rather than supplied as
  a bare string

#### Scenario: The local tunnel keeps a build-setting override, because its host cannot be declared
- **WHEN** an operator builds against the local rig behind a quick tunnel, whose hostname is minted after
  the resolver has already run and differs every session
- **THEN** the host is supplied as a build-setting override on that invocation
- **AND** no CI workflow exposes that override as an input

#### Scenario: A local rig is selected, not overridden

- **WHEN** an operator needs a build pointed at a local backend or a cloudflared tunnel
- **THEN** they set that host in the local deployment and re-run the resolver, because no build-setting
  override can reach a generated bundle resource

#### Scenario: The scheme follows the host

- **WHEN** the resolved host is a loopback IP literal
- **THEN** the baked base is `http`, and for every other host it is `https`

