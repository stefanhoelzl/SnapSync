## 1. iOS targets on the shared render-path modules

- [x] 1.1 Add `iosArm64()` + `iosSimulatorArm64()` (default-hierarchy `iosMain`) to `:domain:permission`, `:domain:engine`, `:domain:status`, `:domain:presentation`, `:domain:ui:components`, and `:domain:ui`
- [x] 1.1a Add root `build.gradle.kts` declaring all plugins `apply false` — REQUIRED once iOS targets exist, else the Apple build service (`SwiftPMLockTaskAggregationBuildService`) fails to cast across per-subproject classloaders (config error). Added `kotlin.native.ignoreDisabledTargets=true` to `gradle.properties` to silence the benign "iOS test target disabled on Linux" warning.
- [x] 1.1b Make shared `commonTest` Kotlin/Native-name-safe: removed commas from two backtick test names (`StatusContainerHostTest`, `S3PresignGoldenTest`) — Kotlin/Native rejects `,` in identifiers that the JVM allows. (No other Native-illegal chars found.)
- [x] 1.2 Ran `./gradlew build` on Linux: green. All iOS `compileKotlinIos{Arm64,SimulatorArm64}` (+ test variants) compile clean (klibs cross-compile on Linux; only framework-linking needs a Mac) and all JVM compiles/tests pass. Verifies risk R4. Caveat: the pre-existing Compose *Desktop* UI test `:domain:ui:jvmTest` hangs under local Xvfb (unrelated to iOS, green on real CI) — excluded locally via `-x :domain:ui:jvmTest`.
- [x] 1.3 Confirmed `:domain:ui`'s `StatusScreen`/`UiState` compile for `iosSimulatorArm64` (the `compileKotlinIosSimulatorArm64` klib compile succeeds on Linux — no Mac needed)

## 2. `:app:ios` module

- [x] 2.1 Created `:app:ios` (KMP, `iosArm64`+`iosSimulatorArm64` only) and registered it in `settings.gradle.kts`
- [x] 2.2 Depends on `:domain:ui`; exposes a static framework (`isStatic = true`, `baseName = "SnapSyncKit"`). Verified `:app:ios:compileKotlinIosSimulatorArm64` green on Linux.
- [x] 2.3 Added `MainViewController.kt` = `ComposeUIViewController { StatusScreen(UiState.InProgress(0.6f, "about 2 min left")) }`

## 3. `iosApp/` Xcode project

> ⚠️ All of group 3 is AUTHORED but UNVERIFIABLE on Linux (xcodebuild needs a Mac). The `project.pbxproj` was hand-authored to the standard CMP template; its first real test is the first Codemagic run (task 5.2). If that build fails on a pbxproj/build-setting issue, fix here.
- [x] 3.1 Hand-authored `iosApp/` (committed from Linux): `iosApp.xcodeproj/project.pbxproj` + shared scheme `xcshareddata/xcschemes/iosApp.xcscheme`, Swift sources (`iOSApp.swift`, `ContentView.swift`), `Info.plist`, `Assets.xcassets`, `Configuration/Config.xcconfig`
- [x] 3.2 Added "Compile Kotlin Framework" Run Script build phase = `./gradlew :app:ios:embedAndSignAppleFrameworkForXcode`; `FRAMEWORK_SEARCH_PATHS` + `OTHER_LDFLAGS -framework SnapSyncKit` link the framework
- [x] 3.3 Deployment target iOS 16.0; bundle id `app.snapsync` (via `Config.xcconfig` `BUNDLE_ID`). SwiftUI App lifecycle (scene-based) chosen to satisfy the iOS 27 SDK's mandatory UIScene adoption.
- [x] 3.4 `ContentView` hosts `MainViewControllerKt.MainViewController()` via `UIViewControllerRepresentable`
- [x] 3.5 CI-fix (1st Codemagic run, exit 65): `:app:ios:syncComposeResourcesForIos` failed with "Unknown iOS simulator arch: 'x86_64'" — `xcodebuild` generic-simulator build requested both arm64 + x86_64, but only `iosSimulatorArm64` is declared (design D6). Added `"EXCLUDED_ARCHS[sdk=iphonesimulator*]" = x86_64` to both target build configs so only arm64 is built (runners are Apple-Silicon M2). Note: GitHub Actions Linux `build` was GREEN on the same push — iOS-targets did not break it (R4 confirmed on real CI). Codemagic check identifiers captured: context `iOS simulator build`, integration_id `34548`.

## 4. Codemagic pipeline

- [x] 4.1 Authored `codemagic.yaml` workflow `ios-build`: `instance_type: mac_mini_m2`, `xcode: edge`, `triggering.events: [push]`
- [x] 4.2 Caches `$HOME/.gradle/caches`, `$HOME/.gradle/wrapper`, `$HOME/.konan`
- [x] 4.3 `triggering.cancel_previous_builds: true`
- [x] 4.4 Build step = `xcodebuild -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build` (build-only; framework links via the project Run Script phase)

## 5. Connect Codemagic and capture the gating check (footguns R1 + R2)

- [x] 5.1 Operator connects Codemagic ↔ GitHub — DONE (Codemagic is already connected to the SnapSync repo; only the build config remains)
- [x] 5.1a CI-fix: auto-triggering didn't fire (first build was started manually; repo had NO webhook). Cause: missing webhook to Codemagic. Operator added the webhook in the Codemagic UI; also added explicit `branch_patterns: ['*']` to `codemagic.yaml`. Confirmed: push of `baa4baa` AUTO-triggered the Codemagic build with no manual action.
- [x] 5.2 Confirmed — Codemagic auto-ran the `codemagic.yaml` workflow on push and the iOS check concluded **green** (commit `baa4baa`).
- [x] 5.3 Captured exact identifiers: context = `iOS simulator build`, app slug `codemagic-ci-cd`, integration_id `34548`.

## 6. Make the iOS check required (branch-protection)

- [x] 6.1 Added `{ "context": "iOS simulator build", "integration_id": 34548 }` to `.github/rulesets/main.json` alongside `build`. Safe now (R2 cleared — the check is already green on the branch).
- [~] 6.2 Ruleset source-of-truth updated and committed; the live enforcement (PR cannot merge while iOS red) is APPLIED during `/ship` (per branch-protection: ruleset applied when the PR is first in the merge queue). Full gate verification happens at ship time.

## 7. Forward-readiness and follow-ups

- [ ] 7.1 DEFERRED — adding iOS targets to `:capability:s3` revealed its SigV4 canonicalization uses JVM-only `sortedMapOf` (`S3SigV4Presigner.kt`), which Kotlin/Native lacks. Making s3 iOS-ready needs a multiplatform refactor of correctness-sensitive signing code; out of scope for first-target bring-up (s3 is off the app's render path). Reverted the target. Vindicates the Fork 2 decision not to treat whole-layer iOS-capability as an invariant. Track as its own future change when s3 is actually needed on iOS.
- [ ] 7.2 Note the post-GM follow-up: pin `xcode: 27.x` once iOS 27 is stable (the check is already required; this only de-risks the moving-beta gate — R3)
