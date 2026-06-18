## Why

The iOS CI is **build-only by design** (`ios-ci`: "the workflow SHALL NOT run tests, boot a simulator"). That leaves two real gaps:

1. **Shared logic is never validated on Kotlin/Native.** The `commonTest` suites in `:domain:status`, `:domain:engine`, and `:domain:presentation` run only as **JVM bytecode** on the Linux `build` job. They have never been compiled to Kotlin/Native and run — so Native-specific behaviour (memory model, numeric conversions, `expect`/`actual` wiring) is unverified on the platform the app actually ships on.
2. **The device target is never linked.** `xcodebuild` currently builds the **simulator** app (`-sdk iphonesimulator`), which links the `iosSimulatorArm64` framework. The **`iosArm64` (device)** framework — the one that ships to real phones and TestFlight — is a *separate Kotlin/Native target* and is never compiled in CI. A device link can fail while the simulator link passes.

This change closes both gaps and runs them **in parallel** so wall-clock barely moves. macOS runners are free and unlimited on this public repo, so the only cost is a second runner doing genuinely different work.

## What Changes

- **Add an `ios-test` job** to `.github/workflows/ios.yml` (parallel to `ios-build`, same `macos-26`/`concurrency`/caching setup) that runs `./gradlew iosSimulatorArm64Test`, booting an iOS simulator and executing the shared modules' `commonTest` compiled to **Kotlin/Native**. Posts a stable status-check context `ios-test`.
- **Switch the `ios-build` job from simulator to device**: `xcodebuild -sdk iphoneos -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build`. This links the `iosArm64` framework (the shipping target) and exercises the Xcode app integration for device. Still **build-only and unsigned** — the `ios-build` job runs no tests.
- **No rename.** Because tests live in a separate job, `ios-build` stays genuinely build-only and keeps its name. The build/test split maps cleanly to the two Kotlin/Native targets: **device via build, simulator via test** — both targets exercised, no redundant work.
- **Update `.github/rulesets/main.json`**: ADD `{ "context": "ios-test", "integration_id": 15368 }` alongside the existing `build` and `ios-build` entries (no entry renamed or removed). Three required checks: `build`, `ios-build`, `ios-test`.

## Capabilities

### Modified Capabilities
- `ios-ci`: the build job now builds the **device (`iphoneos`, arm64)** app instead of the simulator app (still build-only, unsigned); a new **parallel `ios-test` job** runs the shared `commonTest` compiled to Kotlin/Native on a booted simulator and reports an `ios-test` gating check. Together the build and test jobs cover both Native targets.
- `branch-protection`: the default-branch ruleset now additionally requires the `ios-test` status check (reported by GitHub Actions).

## Impact

- **Modified CI config**: `.github/workflows/ios.yml` — the `ios-build` step switches SDK/destination to device; a new `ios-test` job is added (own `gradlew iosSimulatorArm64Test` step, same checkout/`setup-java`/`setup-gradle`/`~/.konan` cache pattern).
- **Modified ruleset**: `.github/rulesets/main.json` — `ios-test` added to `required_status_checks`; reapplied during `/ship`.
- **Required-check ordering caveat**: the PR that adds `ios-test` must show that check **green on the PR** before the reapplied ruleset requires it — otherwise merges freeze. Same class of risk as the prior `ios-build` introduction, and *lower* than a rename (the existing `build`/`ios-build` checks keep reporting unchanged).
- **Accepted coverage trade**: the *simulator* `.app` bundle (Swift compiled for the sim arch + sim `Info.plist`) is no longer assembled by `xcodebuild`. The sim **framework** link is still covered (by the test job's executable), and the same Swift compiles for arm64 in the device build. Acceptable given the trivial Swift shell.
- **Unchanged**: the Linux `build` job (`ubuntu-latest`, `./gradlew build`, still runs the JVM unit tests — kept as fast/free feedback, no overlap with the Native run); the `:app:ios` module; the `iosApp/` Xcode project sources; the shared modules' iOS targets.
- **Out of scope**: code signing, device archive, TestFlight, App Store (`ios-testflight-delivery`); Swift/XCUITest targets (none exist; the Swift shell is trivial); any new test code (the existing `commonTest` simply runs on a new target).
