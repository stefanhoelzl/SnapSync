## 1. Version floor + main-build version computation

- [x] 1.1 `iosApp/Configuration/Config.xcconfig`: change `MARKETING_VERSION 0.1.0 → 0.1`; rewrite the "never bumped / MARKETING_VERSION trap" comment to "floor (seed 0.1), bumped only for a manual major".
- [x] 1.2 `.github/workflows/ios.yml`: add `fetch-tags: true` (and depth as needed) to the `ios-build` checkout so tags are visible.
- [x] 1.3 `.github/workflows/ios.yml`: add a step computing `MARKETING_VERSION = max(floor, latest vX.Y tag with minor+1)` — integer minor bump (`0.9→0.10`), `(major,minor)` tuple compare, floor read from `Config.xcconfig` — and pass it to the `ios-archive` action's `marketing-version` input (today it passes `""`).
- [x] 1.4 `.github/actions/ios-archive/action.yml`: fix the stale `marketing-version` comment (`0.1.0, alpha` → the computed value). No behavior change (it already accepts a non-empty value).

## 2. Promote workflow (rename + rewrite)

- [x] 2.1 Rename `.github/workflows/ios-release.yml → .github/workflows/ios-appstore-promote.yml`; set `name: ios-appstore-promote`.
- [x] 2.2 Collapse to a **single `ubuntu-latest` job**; delete the macOS `build` job (archive, export, upload, signing, keychain) and the `finish`/`build` split.
- [x] 2.3 Inputs: replace `version` with a **required `build_number`**; keep `submit` (default false). `concurrency` keys on `build_number`.
- [x] 2.4 Remove the green/ancestor guards (the check-suite self-exclusion apparatus). Keep: derived-version `^\d+\.\d+$` check, `vX.Y`-tag-absent check (before any mutation).
- [x] 2.5 Steps: fetch `asc`; resolve+attach via `appstore_release.py` (derives version from the build); apply review details; optional gated submit (`asc review doctor`); **last** create `vX.Y` tag at the build's origin commit.
- [x] 2.6 Resolve the origin commit: `build_number → GET /actions/workflows/ios.yml/runs` (paginate newest-first, `head_branch=main`) → `run_number == build_number` → `head_sha`. **Fail loud** if unresolved. Tag message records `build N`.

## 3. Attach script (derive version from the build)

- [x] 3.1 `.github/scripts/appstore_release.py`: change the `release` command to take `build_number` (not `version_string`); after resolving the build, read its marketing version from `preReleaseVersion.versionString` (include on the builds fetch) and use it as the store version for find-or-create + attach. Keep VALID-wait, find-or-create-with-copyright, and idempotency.
- [x] 3.2 Update the module docstring/usage accordingly.

## 4. Specs (deltas, built from the branch's post-change-1 text)

- [x] 4.1 Apply the `ios-appstore-release` delta: **REMOVE** *A release only builds a merged, fully-green commit*, *Release builds use the production APNs environment*, *The release version is injected per release, never committed*; **ADD** *A release promotes an already-gated build*; **MODIFY** *The build is attached…*, *A dispatch drives an App Store release and records it as a tag*, *Release identity and credentials*, *The release workflow never gates merges*.
- [x] 4.2 Rewrite the `ios-appstore-release` spec `## Purpose` from build-fresh to promote-only + version-derived (single ubuntu job; workflow `ios-appstore-promote.yml`; capability name unchanged).
- [x] 4.3 Update the `ios-appstore-release` `Decision record:` line to add this change.
- [x] 4.4 Apply the `ios-testflight-delivery` delta: **MODIFY** *Monotonic build numbers from the CI run* (computed version) and *Distribution builds use the production APNs environment* (drop the now-nonexistent "tag release build" clause).
- [x] 4.5 Sweep both specs for stale `ios-release.yml` filename mentions and `version`-input assumptions in requirements NOT covered by a delta; fix or account for each.
- [x] 4.6 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` passes.

## 5. CLAUDE.md

- [x] 5.1 Rewrite the "App Store releases are dispatch-driven" section: promote-only, `build_number` input, version derived from the build, single ubuntu job, `ios-appstore-promote.yml`; update the `gh workflow run` examples.
- [x] 5.2 Update the internal-TestFlight section: main builds now carry computed `0.X` versions (`max(floor, lastTag+1)`, integer minor bump, manual major); the floor lives in `Config.xcconfig`.
- [x] 5.3 Update any other `ios-release.yml` mentions (tag-is-receipt doctrine, guard notes) for the rename + promote-only shape.

## 6. Verify

- [x] 6.1 `grep -rn "ios-release.yml\|version=\|build-fresh" .github CLAUDE.md openspec/specs` returns only intended mentions (historical/archive OK).
- [x] 6.2 YAML validity of `ios.yml` and `ios-appstore-promote.yml`.
- [x] 6.3 Confirm the version-compute is a pure, unit-checkable function (integer minor bump + tuple compare); sanity-check `0.1 → 0.2 → 0.9 → 0.10`, floor `1.0 → 1.0` beats `0.10`.
- [ ] 6.4 Ship in the combined PR with `remove-alpha-testflight-promotion` (`/ship`).
