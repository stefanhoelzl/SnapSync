## Why

SnapSync has never shipped to the App Store. The App Store version record (`app.snapsync`, ASC app id 6781692480) sits in `PREPARE_FOR_SUBMISSION` as version **"1.0"** with **no build attached** — and no build is *attachable*, because every build ever uploaded carries `MARKETING_VERSION = 0.1.0` (pinned for the alpha channel), and a "1.0" record can only accept a build whose `CFBundleShortVersionString` is exactly `1.0`.

The obvious fixes are both traps. Editing the record down to `0.1.0` makes the public store version read as a pre-release forever and doesn't scale past one release. Committing a `MARKETING_VERSION` bump to `1.0` on `main` triggers a genuine first-of-version Beta App Review (hours to days) that **silently stalls the alpha TestFlight channel** — the "MARKETING_VERSION trap" the `ios-testflight-delivery` spec and CLAUDE.md already warn about — and would recur on *every* future release.

The scalable answer is a **tag-driven App Store release pipeline**: `git push vX.Y` builds, uploads, and attaches an `X.Y`-versioned build to its App Store version record, deriving the version from the tag so committed source never bumps and `main`/alpha never touches the trap. This first submission is just its first invocation.

## What Changes

- **`MARKETING_VERSION` is injected from the tag at build time** — same mechanism as the existing `CURRENT_PROJECT_VERSION=${{ github.run_number }}` override. Committed source stays a fixed fallback; git never carries a per-release bump.
- **The fallback `0.1.0` moves from the 4 target-level pbxproj entries into `Config.xcconfig`** (the project-level base config both app and extension inherit). The target-level entries are deleted so the xcconfig value is not shadowed.
- **New tag-driven release workflow `ios-release.yml`** (`on: push: tags: ['v*']`): guards → build an `X.Y` archive → export → upload to App Store Connect → **find-or-create** the `X.Y` App Store version record → **attach** the build. It **stops before submit** — attaching needs no listing/screenshots/privacy (those are owned elsewhere and required only at Submit).
- **Tag format is `vX.Y`** (two-part, Apple-style); the store version is the tag minus `v`. A malformed tag fails the run. First release: `v1.0` → store version "1.0", reusing the existing record.
- **Release guards**: tag matches `^v\d+\.\d+$`; the tag SHA is an ancestor of `origin/main`; and **every check-run on the tag SHA concluded `success`** (excluding the release workflow's own in-progress runs). A commit whose full pipeline — including promotion to alpha — is not green does not reach the App Store; a cancelled/red `ios-promote` is fixed by re-running that idempotent job and re-tagging.
- **A shared composite action `.github/actions/ios-archive/`** holds the archive+sign steps; both `ios-build` and the release job `uses:` it (`marketing_version`, `aps_environment`, `apns_env`, `build_config`, `upload_host` as inputs). Job names are unchanged, so `ios-build`'s required status-check context is preserved.
- **Tags are excluded from the `push` triggers of both `build.yml` and `ios.yml`**, so a tag fires only `ios-release.yml` — no redundant `build`/`spec-validate`/alpha re-runs and fewer in-progress check-runs to self-exclude.
- **APNs is corrected to production for all TestFlight/App Store distribution builds** (main-alpha *and* release). Today `aps-environment`/`APNS_ENV` resolve from `Config.xcconfig`'s `development`/`sandbox` default with **no CI override anywhere** — so every alpha TestFlight build currently ships sandbox APNs (push broken), contradicting the xcconfig comment. The composite action injects `APS_ENVIRONMENT=production APNS_ENV=production` for **Release** archives; Debug/dev-IPA paths and ssh-mac stay sandbox.
- No new secrets: the release workflow reuses the existing Admin `ASC_KEY_ID` / `ASC_ISSUER_ID` / `ASC_API_PRIVATE_KEY`.

## Capabilities

### New Capabilities

- `ios-appstore-release`: the tag-driven App Store release channel — the `vX.Y` trigger and format contract, the tag→`MARKETING_VERSION` injection, the ancestor-of-main and every-check-run-green guards, the once-per-tag find-or-create-and-attach of the App Store version record (idempotent, stops before submit), production-APNs release builds, and the operational note that a cancelled/red `ios-promote` is unblocked by re-running that job.

### Modified Capabilities

- `ios-testflight-delivery`: the `MARKETING_VERSION` fallback now lives in `Config.xcconfig` (not pbxproj); the `push` triggers of `build.yml` and `ios.yml` exclude tags; and CI **Release/distribution archives set `APS_ENVIRONMENT=production` / `APNS_ENV=production`** (making the xcconfig comment true and fixing alpha push), while Debug/dev-IPA paths stay sandbox. The "MARKETING_VERSION bump forces a review / stalls the channel" narrative is superseded for releases: `main` is permanently pinned, and real versions reach the store via the tag channel instead of a committed bump. The archive steps are now sourced from the shared `ios-archive` composite action.

## Impact

- `.github/workflows/ios-release.yml` — **new**.
- `.github/actions/ios-archive/action.yml` — **new** (extracted shared archive+sign steps).
- `.github/scripts/appstore_release.py` — **new** (ASC JWT + REST: find-or-create version record, attach build; idempotent), mirroring `testflight_promote.py`.
- `.github/workflows/ios.yml` — `ios-build` uses the composite action; `push` trigger excludes tags; Release archives get production APNs.
- `.github/workflows/build.yml` — `push` trigger excludes tags.
- `iosApp/Configuration/Config.xcconfig` — gains `MARKETING_VERSION = 0.1.0`.
- `iosApp/iosApp.xcodeproj/project.pbxproj` — the 4 target-level `MARKETING_VERSION` entries removed.
- `.github/rulesets/main.json` — **unchanged**, deliberately: `ios-release` never runs on a PR branch, so requiring it would freeze merges (the same trap the ios.yml header documents for `ios-deliver`/`ios-promote`).
- App Store Connect — the "1.0" version record becomes CI-attachable; a human still clicks Submit once the listing/screenshots/privacy (other workspaces) are complete.
- `CLAUDE.md` — note the tag channel and the production-APNs correction.
- No application code, no Gradle module, no test changes. The `ios-build` / `ios-test` merge gates are untouched in behavior.
