## Why

The App Store release channel currently **builds a brand-new archive at release time** — bits no tester ever ran. Now that every `main` merge already uploads a signed, gated build to internal TestFlight (`ios-deliver`), we can instead **promote one of those already-tested builds** to the App Store: ship the exact binary that was validated, reuse a build that's already in App Store Connect, and turn the release into a lighter "pick a build and submit" button. This is unlocked by the just-removed alpha promotion — with `main` builds reaching only the internal group, bumping the marketing version no longer triggers a Beta App Review, so builds can carry real, incrementing versions.

## What Changes

- **BREAKING (release mechanism):** `ios-release.yml` is replaced by **`ios-appstore-promote.yml`** — a **promote-only** workflow. It **no longer builds/exports/uploads** anything; it promotes an existing App Store Connect build (chosen by a **required `build_number` input**), attaches it, applies review details, optionally submits, and tags. Collapses to a **single `ubuntu` job** (no macOS, no Xcode, no signing certs).
- **Version is derived from the promoted build, not chosen.** The store version = the build's own frozen marketing version (`preReleaseVersion.versionString`), read from the same ASC fetch that attaches it. No `version` input, no mismatch risk.
- **`ios.yml` now computes the marketing version** every main build carries: **`MARKETING_VERSION = max(Config.xcconfig floor, latest vX.Y tag with minor+1)`**, seeded at floor **`0.1`** (two-part). Minor auto-increments off the last release tag (`0.1 → 0.2 → … → 0.9 → 0.10`); a **manual major** (`→ 1.0`) is a floor bump via PR. The pin therefore moves from "never bumped" to "a floor, bumped only for a major."
- **`Config.xcconfig` `MARKETING_VERSION` `0.1.0` → `0.1`** (three-part fallback → two-part floor).
- **Release-time green/ancestor verification is retired.** Provenance is guaranteed at upload time (`ios-deliver` runs only on `main`, only when both merge gates pass), so the promoted build is inherently from a merged, gated commit. The elaborate check-suite-self-exclusion apparatus is deleted; only two guards survive: derived `version` matches `^\d+\.\d+$`, and the `vX.Y` tag is absent.
- **The release tag points at the build's origin commit**, resolved `build_number → ios.yml run(head_branch=main) → head_sha`. On an unresolvable run the workflow **fails loud** (never a silent/wrong tag). The tag message records `build N` for auditability.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `ios-appstore-release`: Release becomes **promote-only + version-derived**. **REMOVE** *A release only builds a merged, fully-green commit* (its release-time verification is retired — provenance is now an upload-time guarantee) and **ADD** *A release promotes an already-gated build* (provenance guarantee + the two surviving guards, build selected by required `build_number`, version derived from the build, tag at the build's origin commit, fail-loud on unresolvable SHA). Edit the build/attach/upload requirements to drop the fresh-build path (single `ubuntu` job, no archive/export/upload). The workflow file renames to `ios-appstore-promote.yml`; the **capability name stays** (contract = outcome; the workflow name = mechanism).
- `ios-testflight-delivery`: The *Monotonic build numbers from the CI run* requirement changes — `ios.yml` no longer bakes a fixed empty fallback; it **computes** `MARKETING_VERSION = max(floor, latest vX.Y tag + minor 1)` (floor in `Config.xcconfig`, seeded `0.1`, two-part), so main/internal builds carry real, incrementing versions. Build numbering itself (`CFBundleVersion = run_number`) is unchanged. The *production APNs* requirement drops its now-nonexistent "tag release build" clause.
- `architecture-guards`: The *migration beacon* requirement referenced the release workflow by filename (`ios-release.yml`); the rename to `ios-appstore-promote.yml` updates that mention (filename only — no behavior change).

## Impact

- `.github/workflows/ios-release.yml` → **renamed** `ios-appstore-promote.yml`; rewritten promote-only (single ubuntu job; drop build/export/upload; drop green/ancestor guards; `build_number` input replaces `version`; derive version + SHA from the build/run).
- `.github/workflows/ios.yml` — add a step computing `MARKETING_VERSION = max(floor, lastTag+1)` and pass it to `ios-archive` (today it passes `""`); add `fetch-tags`.
- `.github/actions/ios-archive/action.yml` — fix the stale `marketing-version` comment (`0.1.0, alpha` → the computed value); no behavior change (it already accepts a non-empty value).
- `.github/scripts/appstore_release.py` — resolve the store version from `build.preReleaseVersion.versionString` instead of a `version` argument; add the `build_number → run → head_sha` resolution (or a companion step) + fail-loud.
- `iosApp/Configuration/Config.xcconfig` — `MARKETING_VERSION 0.1.0 → 0.1`; rewrite the "never bumped / trap" comment to "floor, bumped only for a major".
- `openspec/specs/ios-appstore-release/spec.md` and `openspec/specs/ios-testflight-delivery/spec.md` — deltas.
- `CLAUDE.md` — the "App Store releases are dispatch-driven" section (promote-only, version-derived, `build_number`), and the internal-TestFlight section (main builds now carry computed `0.X` versions; the version model).
- **Stacks on** `remove-alpha-testflight-promotion` (same branch, one PR): the penalty-free-bump property this relies on is that change's result.
- No Kotlin/module code; no test changes.
