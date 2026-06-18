## 1. Switch the build job to the device target

- [x] 1.1 In `.github/workflows/ios.yml`, change the `ios-build` build step from simulator to device: `-sdk iphoneos -destination 'generic/platform=iOS'` (was `-sdk iphonesimulator -destination 'generic/platform=iOS Simulator'`), keeping `-project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -derivedDataPath build/ios CODE_SIGNING_ALLOWED=NO build`
- [x] 1.2 If the device build still demands signing, add `CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY=""` (R3). Confirm `Config.xcconfig`'s empty `TEAM_ID` is sufficient otherwise — added all three CODE_SIGN* flags pre-emptively (belt-and-suspenders for unsigned generic-device builds; safe with empty TEAM_ID, avoids a known first-run failure mode)
- [x] 1.3 Update the step comment: it now links the `iosArm64` (device/shipping) framework, not the simulator framework; the job stays build-only and unsigned

## 2. Add the parallel `ios-test` job

- [x] 2.1 Add a second job `ios-test` to `ios.yml`, `runs-on: macos-26`, display `name: ios-test` (the pinned status-check context — D5), with NO `needs:` so it runs in parallel with `ios-build`
- [x] 2.2 Reuse the `ios-build` scaffolding (D6): `actions/checkout@v5`; `actions/setup-java@v5` (temurin 25); `gradle/actions/setup-gradle@v5`; `actions/cache@v4` for `~/.konan` keyed on `hashFiles('gradle/libs.versions.toml')` (restore-keys `konan-${{ runner.os }}-`)
- [x] 2.3 Test step: `./gradlew iosSimulatorArm64Test` — runs the `commonTest` of `:domain:status`, `:domain:engine`, `:domain:presentation` compiled to Kotlin/Native on a booted simulator (D4). Verified the task exists locally (`Executes Kotlin/Native unit tests for target iosSimulatorArm64`)
- [x] 2.4 Confirm the workflow-level `concurrency: { group: ios-${{ github.ref }}, cancel-in-progress: true }` covers both jobs (no per-job concurrency needed)

## 3. Verify on the PR before requiring the check (R1 + R2)

- [x] 3.1 Push the branch; confirm `ios.yml` runs both jobs and they conclude **green**. If `ios-test` errors with "no matching device", pin a simulator (R2) and re-push — both jobs green on first run (commit `2c9a24c`, run `27758465404`, ~5.5 min cold). No "no matching device" — R2 did NOT materialize; Kotlin's auto-selected simulator worked on `macos-26`, no device pin needed
- [x] 3.2 Confirm the device `ios-build` build links `iosArm64` and stays green; confirm the Linux `build` check is unaffected — `ios-build` (device, `-sdk iphoneos`) succeeded; Linux `build` check stayed green on the same commit
- [x] 3.3 Capture the exact posted `ios-test` context (expected `ios-test`) and `integration_id` (expected `15368`) — must match the ruleset entry from 4.1 before relying on the gate — CAPTURED via `check-runs` API: context = `ios-test`, app = github-actions, `integration_id` = `15368`. EXACTLY matches the 4.1 ruleset entry. R1 cleared

## 4. Add the branch-protection required check

- [x] 4.1 In `.github/rulesets/main.json`, ADD `{ "context": "ios-test", "integration_id": 15368 }` to `required_status_checks.required_status_checks`. Leave the existing `build` and `ios-build` entries untouched — done; JSON re-validated. NOTE: `15368`/`ios-test` are the *expected* values; reconcile against the empirically captured context in 3.3 before the gate is relied upon
- [ ] 4.2 Live enforcement is APPLIED during `/ship` (ruleset reapplied when the PR is first in the merge queue). Full gate verification (PR cannot merge while `ios-test` is red) happens at ship time

## 5. Spec sync

- [x] 5.1 At archive time, sync the `ios-ci` and `branch-protection` deltas into `openspec/specs/`, including updating the `ios-ci` **Purpose** (it currently says "builds the iOS simulator app … Build-only and unsigned" — now: builds the device app **and** runs Kotlin/Native unit tests on the simulator). Verify the synced specs match the shipped `ios.yml` + `main.json` — done via `openspec archive` (1 added + 2 modified requirements); Purpose hand-updated; both `ios-ci` and `branch-protection` strict-valid and match the shipped `ios.yml` + `main.json`
