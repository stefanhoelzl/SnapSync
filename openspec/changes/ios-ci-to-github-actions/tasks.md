## 1. GitHub Actions iOS workflow

- [x] 1.1 Add `.github/workflows/ios.yml`: `on: push`; `permissions: contents: read`; one job `runs-on: macos-26` with display `name: ios-build` (the pinned status-check context — D3). DEVIATION from draft: concurrency group is `ios-${{ github.ref }}`, NOT the bare `${{ github.ref }}` used by `build.yml` — a shared group string would put the Linux and iOS workflows in one group and cancel each other. Namespacing keeps cancel-superseded per-workflow.
- [x] 1.2 Steps: `actions/checkout@v5`; `actions/setup-java@v5` (temurin, Java 25 — D7); `gradle/actions/setup-gradle@v5` (caches `~/.gradle` — D5)
- [x] 1.3 Cache `~/.konan` via `actions/cache@v4`, keyed on `hashFiles('gradle/libs.versions.toml')` so a Kotlin/Native version bump invalidates it (warm build skips the Konan download — D5/R4)
- [x] 1.4 Build step (build-only, unsigned — D4): `xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' -derivedDataPath build/ios CODE_SIGNING_ALLOWED=NO build` — the project's "Compile Kotlin Framework" Run Script phase links the Gradle framework, so no separate gradle step is needed
- [x] 1.5 Did NOT add `xcode: edge` / beta selection and did NOT pin Xcode — floats on the `macos-26` default GM (D8). (If a later default bump breaks the build, pin via `maxim-lobanov/setup-xcode@v1` — R3.)

## 2. Remove Codemagic

- [x] 2.1 Delete `codemagic.yaml` (`git rm`)
- [x] 2.2 (Operator, out-of-repo) Disconnected the Codemagic GitHub app / webhook so it stops posting an inert check (R5)

## 3. Verify on the PR before requiring the check (R1 + R2)

- [ ] 3.1 Push the change's PR; confirm `ios.yml` auto-runs on `macos-26` and the iOS build concludes **green**
- [ ] 3.2 Confirm the Linux `build` check stays **green** on the same PR (iOS workflow is independent — D2)
- [ ] 3.3 Capture the EXACT posted status-check context string and the GitHub Actions `integration_id` (expected `ios-build` / `15368`); reconcile with task 4.1 before the ruleset requires it (R1)

## 4. Repoint the branch-protection required check

- [x] 4.1 In `.github/rulesets/main.json`, replaced `{ "context": "iOS simulator build", "integration_id": 34548 }` (Codemagic) with `{ "context": "ios-build", "integration_id": 15368 }` (GitHub Actions). NOTE: `ios-build` is the *expected* context — reconcile against the empirically captured string in 3.3 before relying on the gate.
- [x] 4.2 Left the existing `{ "context": "build", "integration_id": 15368 }` entry untouched
- [ ] 4.3 Live enforcement is APPLIED during `/ship` (per `branch-protection`: ruleset reapplied when the PR is first in the merge queue). Full gate verification (PR cannot merge while `ios-build` is red) happens at ship time.

## 5. Spec sync

- [ ] 5.1 Confirm the `ios-ci` and `branch-protection` spec deltas in this change match the shipped workflow + ruleset; archive on `/ship`
