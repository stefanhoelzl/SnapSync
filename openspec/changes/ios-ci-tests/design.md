## Context

After `ios-ci-to-github-actions`, iOS CI is a single GitHub Actions job (`ios-build`, `macos-26`, GM Xcode) that builds the **simulator** app, build-only and unsigned, gating merges via the `ios-build` required check. The Linux `build` job (`ubuntu-latest`, `./gradlew build`) compiles everything Linux-buildable and runs all `commonTest`/`jvmTest` suites **as JVM bytecode**.

Two coverage gaps motivate this change:
- The shared `commonTest` (in `:domain:status`, `:domain:engine`, `:domain:presentation` — all of which declare `iosArm64()` + `iosSimulatorArm64()`) is never run as Kotlin/Native.
- The `iosArm64` device framework — a distinct Konan target from `iosSimulatorArm64` — is never linked in CI; only the simulator framework is.

macOS runners are **free and unlimited on public repos** (this repo is public), so the design optimises for **coverage and wall-clock**, not minutes.

## Goals / Non-Goals

**Goals:**
- Run the shared `commonTest` compiled to **Kotlin/Native** on a booted iOS simulator, as a merge-gating check.
- Link the **device (`iosArm64`)** framework + Xcode device app integration in CI.
- Do both **in parallel** with no meaningful wall-clock regression.
- Cover **both** Kotlin/Native targets (device + simulator) with no redundant compilation.

**Non-Goals:**
- Swift / XCUITest targets (none exist; the Swift shell is `ContentView.swift` + `iOSApp.swift`). No new test code of any kind — the existing `commonTest` simply runs on a new target.
- Code signing, device archive, TestFlight (`ios-testflight-delivery`).
- Touching the Linux `build` job or its `build` check.
- Re-running the JVM tests on macOS (they stay on the free/fast Linux job).

## Decisions

### D1 — Two parallel jobs, not one sequential job
GitHub Actions **steps within a job run sequentially** — you cannot parallelise two steps. Backgrounding them in one job (`gradlew test & xcodebuild & wait`) does **not** truly parallelise: `xcodebuild` invokes `./gradlew embedAndSign…`, so two Gradle builds contend on the **same project lock** and serialise (only xcodebuild's Swift compile would overlap the test run — marginal and fragile). Real parallelism therefore means **two jobs**: `ios-build` and `ios-test`, in the same `ios.yml`, on two `macos-26` runners. Wall-clock ≈ `max(build, test)` instead of `build + test`.

The duplication is small: the two jobs compile **different Konan targets** (`iosArm64` vs `iosSimulatorArm64`), so the expensive per-target Native compile is *not* duplicated — only checkout + `setup-*` + the `commonMain` klib compile is re-paid per job. On free runners this is negligible.

### D2 — No rename; `ios-build` stays build-only
Putting the tests in their own job is what lets `ios-build` keep its name honestly: that job still runs **no tests, no signing** — it is genuinely build-only. So we **add** `ios-test` rather than renaming `ios-build → ios`. This also keeps the `ios-ci` spec's existing "build-only" requirement true (scoped to the build job) instead of reversing it, and avoids a required-check **rename** (a freeze risk) in favour of an **add** (the existing `build`/`ios-build` checks keep reporting unchanged).

### D3 — Build the device target; test on the simulator
`iosArm64` (device) and `iosSimulatorArm64` (simulator) are separate Konan targets; a device link can fail while the simulator passes (and vice versa). Unit tests can only run on the simulator in CI (no physical device). So the split is natural and complementary:

```
                     sim FRAMEWORK   sim APP BUNDLE   device FRAMEWORK   device APP BUNDLE   shared logic (Native)
   ios-build (device)      —               —          ✓ (xcodebuild)     ✓ (xcodebuild)            —
   ios-test  (sim)    ✓ (test exe)         —                —                  —              ✓ (gradlew test)
```

The build job switches to `xcodebuild -sdk iphoneos -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build`. The accepted loss is the *simulator* `.app` bundle assembly (Swift-for-sim + sim `Info.plist`) — see R4.

### D4 — Test invocation: `./gradlew iosSimulatorArm64Test`
The aggregate task runs the `iosSimulatorArm64Test` of every module that has one, so new iOS-tested modules are picked up automatically. Kotlin's Native test infrastructure boots a standalone simulator from the runner's installed runtimes (iOS 26 on `macos-26`). We do **not** pin a simulator device unless CI proves we must (R2). `:capability:s3` has `commonTest` but no iOS target, so its tests stay JVM-only — correct and intentional.

### D5 — Required checks: keep `build` + `ios-build`, ADD `ios-test`
Match is `(context, integration_id)`. The `ios-test` job's display `name:` is pinned to `ios-test`; the ruleset gains `{ "context": "ios-test", "integration_id": 15368 }` (15368 = the GitHub Actions app, same as the other two). Three required checks total.

### D6 — Shared workflow scaffolding for the new job
`ios-test` reuses the `ios-build` recipe: `actions/checkout@v5`; `actions/setup-java@v5` (temurin 25); `gradle/actions/setup-gradle@v5` (`~/.gradle`); `actions/cache@v4` for `~/.konan` keyed on `gradle/libs.versions.toml`. The workflow-level `concurrency: { group: ios-${{ github.ref }}, cancel-in-progress: true }` already covers both jobs — a newer push to a ref cancels its in-progress build *and* test.

## Risks / Trade-offs

- **R1 — Adding `ios-test` as a required context can freeze merges** → Once the ruleset requires `ios-test`, any PR lacking a green `ios-test` blocks. Mitigation: the check runs on the PR's own pushes; confirm it goes **green** and **capture the exact posted context** (expected `ios-test`, `integration_id 15368`) before the reapplied ruleset enforces it. Lower risk than a rename — `build`/`ios-build` keep reporting unchanged.
- **R2 — Simulator device unavailable / wrong default** → If Kotlin's Native test runner can't find a standalone simulator on `macos-26` ("no matching device"), the `ios-test` job fails to boot. Mitigation: pin a device in the module Gradle config (`iosSimulatorArm64 { testRuns["test"].deviceId.set("…") }`) or select one in the workflow. Treat as fix-if-CI-fails, not a blocker.
- **R3 — Device build demands signing** → `Config.xcconfig` has an empty `TEAM_ID` ("simulator builds need no signing"). The `iphoneos` build relies on `CODE_SIGNING_ALLOWED=NO`; if xcodebuild still requires signing, add `CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY=""`. Build-only/unsigned — no provisioning profile.
- **R4 — Lost simulator app-bundle coverage** → Switching `xcodebuild` to device means the simulator `.app` (Swift-for-sim + sim `Info.plist`) is no longer assembled. Accepted: the Swift shell is trivial, the device build compiles the same Swift for arm64, and the sim **framework** link is covered by the test executable.
- **R5 — Kotlin/Native link OOM on the test job** → The prior change hit a Kotlin/Native IR-compiler `OutOfMemoryError` until `org.gradle.jvmargs=-Xmx4g` was pinned in `gradle.properties`. That pin already exists and covers the test job's link too; no new action, but the cold `ios-test` build is the same class of memory-sensitive Native link.
- **R6 — Duplicated `commonMain` compile across two jobs** → Minor wasted CPU per run. Accepted: free runners, and the heavy per-target compile is *not* duplicated (different targets).

## Open Questions

- Exact `ios-test` posted context string — proposed `ios-test`; confirm empirically on the PR before requiring it (blocks R1).
- Whether a simulator device id must be pinned on `macos-26` — proposed: rely on Kotlin's auto-selection; pin only if R2 materialises.
