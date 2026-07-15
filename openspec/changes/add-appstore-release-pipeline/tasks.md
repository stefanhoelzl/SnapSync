## 1. Version fallback → Config.xcconfig

- [x] 1.1 Add `MARKETING_VERSION = 0.1.0` to `iosApp/Configuration/Config.xcconfig` (with a comment: this is the dev/alpha fallback; the tag release channel overrides it; do not edit the Version field in Xcode's General tab or it re-adds a pbxproj entry).
- [x] 1.2 Delete all 4 target-level `MARKETING_VERSION = 0.1.0;` entries from `iosApp/iosApp.xcodeproj/project.pbxproj` (app + extension × Debug + Release).
- [x] 1.3 Verify both targets resolve `MARKETING_VERSION` from the xcconfig. Verified via the pbxproj object-graph analysis (project-level configs carry the xcconfig base; both targets have no target-level `MARKETING_VERSION` and inherit it; braces balanced). Full `xcodebuild -showBuildSettings` confirmation is a Mac-only follow-up (see 6.3).

## 2. Shared composite action

- [x] 2.1 Create `.github/actions/ios-archive/action.yml` (composite) with inputs `marketing_version`, `aps_environment`, `apns_env`, `build_config`, `upload_host`, `current_project_version`, `archive_path`, and the ASC/cert creds; it holds Java/Gradle/Konan setup, the extension-framework compile, the ASC-key prep, the ephemeral keychain, both cert imports, and the archive. Empty `marketing_version` → no override (xcconfig fallback); non-empty → injected. Same for the APNs/host inputs.
- [x] 2.2 Refactor `ios.yml`'s `ios-build` job to `uses:` the composite action, passing `marketing-version: ''`, `build-config` per the existing Release/Debug selection, and — for the Release path — `aps-environment: production` / `apns-env: production`; the Debug/dev-IPA path passes sandbox (empty).
- [x] 2.3 `ios-build`'s job name (and thus its required status-check context) is unchanged after the refactor, so `.github/rulesets/main.json` still matches.

## 3. Exclude tags from the alpha/build triggers

- [x] 3.1 Narrow `build.yml`'s `on: push` to `branches: ["**"]` so tag refs do not trigger it, keeping all branch pushes intact.
- [x] 3.2 Narrow `ios.yml`'s `on: push` to `branches: ["**"]` the same way, preserving `workflow_dispatch` and every branch push.

## 4. App Store version-record script

- [x] 4.1 Create `.github/scripts/appstore_release.py` (ASC JWT + REST, mirroring `testflight_promote.py`): resolve the uploaded build by build number; `GET …/appStoreVersions?filter[versionString]=X.Y&filter[platform]=IOS`; create the record if absent (`POST /v1/appStoreVersions`); attach the build (`PATCH …/appStoreVersions/<id>/relationships/build`). Idempotent: a build already attached is a green no-op. Surfaces the ASC "one editable version at a time" 409 with a clear message.
- [x] 4.2 Handle the freshly-uploaded-build discovery delay with bounded retries, and additionally wait for `processingState == VALID` (attaching a still-processing build is rejected), failing red on timeout.

## 5. Release workflow

- [x] 5.1 Create `.github/workflows/ios-release.yml`, `on: push: tags: ['v*']`, reusing the existing Admin ASC secrets; per-tag `concurrency` group; `permissions: checks: read` for the green guard.
- [x] 5.2 Guard: fail unless the tag matches `^v[0-9]+\.[0-9]+$`; derive `STORE_VERSION` = tag minus `v`.
- [x] 5.3 Guard: checkout `fetch-depth: 0`; fail unless the tag SHA is an ancestor of `origin/main` (`git merge-base --is-ancestor … FETCH_HEAD`).
- [x] 5.4 Guard: query check-runs for the tag SHA; fail unless every completed check-run concluded `success`; in-progress runs (this workflow's own — tags fire only `ios-release`) are ignored; a commit with no completed check-runs is refused.
- [x] 5.5 Build via the composite action: `marketing-version: <STORE_VERSION>`, `aps-environment: production`, `apns-env: production`, `build-config: Release`, `current-project-version: ${{ github.run_number }}`.
- [x] 5.6 Export the signed IPA (`ExportOptions.plist`, `-allowProvisioningUpdates`, reusing the composite's keychain + `$ASC_KEY_PATH`) and upload via `apple-actions/upload-testflight-build`.
- [x] 5.7 Run `appstore_release.py release` to find-or-create the `STORE_VERSION` record and attach the build; stop before submit. Any failure concludes the job red and blocks nothing.

## 6. Specs, docs, and verification

- [x] 6.1 Change validates: `npx --yes @fission-ai/openspec@1.5.0 validate add-appstore-release-pipeline --strict` (and `validate --specs --strict` = 47/47).
- [x] 6.2 Updated `CLAUDE.md`: the tag release channel (`git push vX.Y`), the production-APNs correction, the MARKETING_VERSION-trap-now-avoided note, and the "re-run idempotent `ios-promote` to unblock a release" escape hatch.
- [ ] 6.3 **(Mac-only follow-up)** On the ssh-mac loop (or a CI dry run), confirm a Release archive now bakes `aps-environment=production` and, with `MARKETING_VERSION=1.0` injected, produces a `1.0` app + extension bundle; and that `ios-build`'s composite refactor still archives green on `main`.
- [ ] 6.4 **(ASC sign-off follow-up)** Apply-time verify `appstore_release.py` against the real ASC record (find-or-create + attach) via `proton-env` with the Admin key; confirm the "1.0" record accepts the attach. (Requires user sign-off per run — an outward ASC action.)
- [ ] 6.5 **(Ship step)** Branch → PR → `/ship`. Do NOT push `v1.0` as part of this change — the operator pushes it later to attach the first build.
