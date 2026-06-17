## Why

The shared KMP stack has only ever been built and run on the JVM (desktop); nothing has ever been compiled for iOS. We need to prove the whole pipeline — shared Kotlin → Compose Multiplatform on iOS → an Xcode app on a simulator — works end-to-end on cloud CI, establishing the foundation every later iOS feature builds on. This is bring-up, not a feature: the goal is a green, gated iOS build of the real shared UI, not new product behavior.

## What Changes

- Add `iosArm64` + `iosSimulatorArm64` targets (default-hierarchy `iosMain`) to the shared modules, so the domain/UI stack compiles for iOS.
- Introduce a new `:app:ios` Gradle module that exposes a static Compose framework and a `ComposeUIViewController` entry point rendering the existing shared `StatusScreen` with a single static `UiState` (parity with desktop — neither platform wires a live ledger yet).
- Add an `iosApp/` Xcode project (committed from Linux via the KMP template — no Mac needed) that consumes the framework and builds a runnable simulator app.
- Add a Codemagic CI pipeline that builds the iOS simulator app on every push using `xcode: edge` (iOS 27 beta SDK), caches `~/.gradle` + `~/.konan`, and reports a **merge-gating** iOS build check. The pass/fail gate is `xcodebuild` success — no simulator boot, no tests, no code signing.
- Make the iOS build check **required** for merging by adding it to the committed branch ruleset alongside the existing `build` check.
- Preserve the existing GitHub Actions Linux build: `./gradlew build` on `ubuntu-latest` stays green (Kotlin skips Apple-target tasks on non-Mac hosts). Linux-buildable work (JVM/desktop, future Android, all unit tests) remains on GitHub Actions; only the irreducible Apple delta runs on Codemagic.

## Capabilities

### New Capabilities
- `ios-app-shell`: the launchable iOS application target — a Compose `UIViewController` entry point hosting the shared `StatusScreen`, plus the contract that the app and its module dependency closure compile for the iOS simulator target.
- `ios-ci`: continuous integration that builds the iOS simulator app on every push via Codemagic and reports a merge-gating iOS build status check.

### Modified Capabilities
- `branch-protection`: the default-branch ruleset now requires the iOS build check in addition to `build`, so a broken iOS build blocks merges.

## Impact

- **New module**: `:app:ios` (added to `settings.gradle.kts`); new `iosApp/` Xcode project directory.
- **Modified build scripts**: iOS targets added to the shared modules' `build.gradle.kts`; new SQLDelight/native concerns are explicitly out of scope (no native driver).
- **New CI config**: `codemagic.yaml`. Requires a one-time, operator-performed Codemagic↔GitHub connection (OAuth) that cannot be scripted.
- **Modified ruleset**: `.github/rulesets/main.json` gains the iOS check as a required status check; reapplied during `/ship`.
- **External dependency**: Codemagic (cloud CI) and the Apple iOS 27 beta SDK via `xcode: edge`. The required-check-on-a-moving-beta risk is accepted, with a planned follow-up to pin `xcode: 27.x` once it reaches GM.
- **Unchanged**: GitHub Actions `ci-build` (Linux) keeps building on every push.
