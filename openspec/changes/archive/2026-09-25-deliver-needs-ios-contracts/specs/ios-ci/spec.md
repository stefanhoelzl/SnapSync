## MODIFIED Requirements

### Requirement: Build iOS on every push

The system SHALL run a **GitHub Actions** job (`ios-build` in `.github/workflows/ios.yml`) on every push that builds the iOS **device (`iphoneos`, arm64)** app on a **`macos-26` hosted runner**, linking the `iosArm64` framework with the runner's **GM Xcode** (no Xcode beta), and reports a stable status-check context (`ios-build`) used to gate merges. On **every** ref the job SHALL produce a **signed archive** of the device app (signing — capability `ios-testflight-delivery`); the archive compiles `iosArm64`, so the `ios-build` check reflects whether the device app builds.

The workflow SHALL be triggered by **branch pushes** and by a **`workflow_dispatch`** on any ref. A dispatched run SHALL be a **delivering run**: it SHALL archive Release, publish the archive, and deliver to internal TestFlight exactly as a push to `main` does (capability `ios-testflight-delivery`). It SHALL accept an optional operator note for the TestFlight "What to Test" text, defaulting to the ref name and short SHA when none is given.

A dispatched run SHALL be marked **distributed** (`channel = release`, capability `deployment-configuration`). This is load-bearing, not cosmetic: the renderer emits `SENTRY_DSN` only for a distributed build, and a build with no DSN opens **no in-app bug-report dialog at all** (capability `diagnostic-logging`) — so a dispatched build that was not marked distributed could not send the diagnostic dump it exists to produce. Production APNs, which a TestFlight build requires regardless, derives from the same single discriminator, so `deployment-configuration`'s one-discriminator rule is preserved.

The dispatch exists because **a test device reachable only through TestFlight cannot be served any other way**. The dev build loop hands a human an IPA, which needs a cable; a platform question only that device can answer otherwise had to merge to `main` to be asked, which forfeits any ability to change one variable at a time. Delivery reaching only the internal group, `ios-deliver`'s dependency on every merge gate, and the deliberate human act a dispatch requires are together what make the widening safe.

An earlier dispatch trigger carrying an `upload_host` input was removed and SHALL NOT return: `ios-build` published its archive on `refs/heads/main` only, so that dispatched run archived Debug and then **discarded** the very build it was dispatched to produce. Pointing a device build at an alternate backend remains the dev build loop's job, where a human is present to receive the IPA.

The archive's **build configuration SHALL depend on whether the run delivers**: on a **delivering run** — a push to `refs/heads/main`, or a `workflow_dispatch` on any ref — the archive SHALL be built in the **Release** configuration; on every **other** push ref it SHALL be built in the **Debug** configuration. The Debug gate archive compiles the identical surface — the same Kotlin frontend and `iosArm64` klib compiles, the same Swift compile, entitlements, and signing — skipping only the LLVM optimization pass of the Release link, while gating nothing on an artifact that is discarded off `main`. **The saving is real but modest**: measured 2026-08-09, the Debug gate averages **10.6 min** (n=21 branch runs) against Release's **13.0 min** on `main` (n=19) — ~2.4 min, not the 5–9.5 min this spec previously claimed, and small beside the job's own run-to-run spread (6.9–14.6 min for identical work). The optimizer is **not** what dominates the archive: profiled in CI, `:app:ios:linkDebugFrameworkIosArm64` alone accounts for 249 s of a 414 s archive (71% with the extension's link), against ~1 s of Gradle configuration — the cost is CPU-bound Kotlin/Native linking of the Compose closure on a 3-core runner, which no build configuration or cache avoids. The accepted trade-off: a **Release-only build failure** (e.g. an optimizer crash in the Kotlin/Native link) passes the branch gate and surfaces on the post-merge `main` run — a red but non-gating `ios-build`, with delivery skipped because `ios-deliver` needs every merge gate (capability `ios-testflight-delivery`). The pre-merge escape from that trade-off is a `workflow_dispatch` on the branch, which takes the Release path in full.

The job SHALL be a **pure gate**: it SHALL NOT export an IPA and SHALL NOT upload anything to Apple. On a **delivering run only** it SHALL publish the signed archive as a **workflow artifact** for the `ios-deliver` job (capability `ios-testflight-delivery`); on any **other** ref's push the archive is produced **solely as the merge gate** and the job publishes no artifact. Because a workflow-artifact round-trip does not preserve executable bits or symlinks — which would corrupt the signed `.app` bundle and break the later export — the archive SHALL be **packed (tar) before upload and unpacked after download**, so it survives the hand-off intact.

Per-branch device installability before merge is served either by the `workflow_dispatch` above — the only route to a device reachable solely through TestFlight — or **out of band** by the interactive dev build loop (dev infrastructure; see the `ssh-mac-build` skill), which hands a human an IPA. The job SHALL run no tests and boot no simulator. The device app SHALL be compiled exactly once per push — the delivery job re-signs and packages this archive and SHALL NOT recompile it.

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

### Requirement: The merge gates are exactly the three parallel jobs

The iOS workflow's **merge gates** SHALL be exactly the three parallel jobs `ios-build`, `ios-test` and `ios-contracts`, and these are the only three iOS status-check contexts required by the branch ruleset on `main`. Adding a `needs:` dependency between any two of them is forbidden: a failing gate would then *skip* the one depending on it, whose required check would never be posted, freezing merges.

The delivery job `ios-deliver` SHALL NOT be a required status check. It runs only on a delivering run — a push to `refs/heads/main` or a `workflow_dispatch` — so it is never posted on a pull-request branch's push; requiring it would block every merge on a check that can never appear. Its purpose is to gate **delivery**, not merges — it depends on **all three** gates — `needs: [ios-build, ios-test, ios-contracts]` (capability `ios-testflight-delivery`) — so it simply does not run when any of them is red. The `needs:` edges run from the delivery job **to** the gates, never between two gates, so they skip only delivery and no required check.

#### Scenario: The gates stay independent
- **WHEN** the iOS workflow runs on any ref
- **THEN** `ios-build`, `ios-test` and `ios-contracts` each run and report regardless of the others' outcomes, so a red test still tells you whether the device app compiles and whether the in-app contracts hold

#### Scenario: The delivery job is not a merge gate
- **WHEN** the branch ruleset's required status checks are applied
- **THEN** they include `ios-build`, `ios-test` and `ios-contracts` but NOT `ios-deliver`, which never runs on a pull-request branch and would freeze merges if required

#### Scenario: Any red gate stops delivery
- **WHEN** a delivering run has any one of `ios-build`, `ios-test` or `ios-contracts` red
- **THEN** `ios-deliver` does not run, while every gate still posts its own check
