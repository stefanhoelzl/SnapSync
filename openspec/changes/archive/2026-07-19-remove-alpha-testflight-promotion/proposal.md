## Why

`main` is currently the **public alpha channel**: every merge auto-promotes the uploaded build into the `alpha` external TestFlight group, whose open join link hands the app to any stranger who taps it. The project is going **App-Store-only** — the dispatch-driven `ios-release.yml` is the intended distribution path — so the automatic public promotion is no longer wanted. This removes it.

## What Changes

- **BREAKING (delivery behavior):** Remove the `ios-promote` job from `.github/workflows/ios.yml`. No `main` build is ever added to the `alpha` external group again; **no automated path distributes to any external tester**. The only route to real users becomes `gh workflow run ios-release.yml`.
- `ios-deliver` is **kept**: every `main` merge still uploads a signed build to TestFlight, where it reaches the **internal** `development` group only. (Accepted consequence: these builds accumulate unseen — the pre-promotion state — acceptable under an App-Store-only direction.)
- Delete `.github/scripts/testflight_promote.py` — dead once `ios-promote` is gone (nothing else calls it).
- Notification silence is **not** preserved: the `autoNotifyEnabled=false` step lived in `ios-promote`, so internal-group testers will again receive a TestFlight push per `main` build. Accepted (the internal group is effectively the developer).
- Fix now-stale prose: the `ios-testflight-delivery` spec, the `main`-is-the-public-alpha-channel section of `CLAUDE.md`, the release-guard comments in `ios-release.yml`, and the dangling `testflight_promote.py` reference in `appstore_release.py`.
- The App Store Connect portal is **out of scope**: the public join link stays live and existing alpha testers freeze on their last-promoted build. This change only stops CI from feeding the group.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `ios-testflight-delivery`: Drop the public-alpha-promotion contract. Remove the six promotion-specific requirements (promoted-to-alpha, promoted-build-identified-by-run-number, no-alpha-tester-notified, promotion-idempotent, newest-build-wins-review-pile-up, cancelled-promotion-benign) plus the promotion half of *Every main build is promoted, unfiltered*; relocate the tag-refs-fire-only-the-release-workflow rule (embedded in that requirement — not promotion-specific, still true for `ios-deliver`) into its own requirement; edit the delivery-never-blocks-merges requirement to drop its promotion clauses. Reframe the Purpose from "`main` is the public alpha channel" to "`main` uploads a signed build to internal TestFlight." Delivery, signing, build-numbering, and production-APNs requirements keep their normative content (touched only to drop the `/alpha` descriptor).
- `ios-appstore-release`: The *A release only builds a merged, fully-green commit* requirement named `ios-promote` as its illustrative non-required check and as the job to re-run for alpha delivery. With `ios-promote` deleted, restate it referencing `ios-deliver` only. No behavior change — the release guard still gates on required checks.
- `ios-appstore-metadata`: **Purpose prose only** (no requirement changes, so no delta) — its Purpose listed `ios-deliver`/`ios-promote` as example jobs that post no required check; drop the phantom `ios-promote`.

## Impact

- `.github/workflows/ios.yml` — remove the `ios-promote` job and its header block; reword the file-level comment (four jobs → three).
- `.github/scripts/testflight_promote.py` — deleted.
- `.github/scripts/appstore_release.py` — remove the stale "mirroring `testflight_promote.py`" comment.
- `.github/workflows/ios-release.yml` — fix release-guard comments that describe the guard in terms of `ios-promote` / the alpha channel (non-functional; they would otherwise be false).
- `openspec/specs/ios-testflight-delivery/spec.md` — Purpose rewrite + requirement deletions/edits (delta).
- `openspec/specs/ios-appstore-release/spec.md` — restate the fully-green-commit requirement without `ios-promote` (delta).
- `openspec/specs/ios-appstore-metadata/spec.md` — drop the phantom `ios-promote` from Purpose prose (no delta; Purpose-only).
- `.github/workflows/appstore.yml`, `.github/workflows/appstore-screenshots.yml` — drop `ios-promote` from example-job comment lists.
- `CLAUDE.md` — rewrite the "`main` is the public alpha channel" section and the `ios-release` note that says to check `ios-promote`.
- `.github/rulesets/main.json` — **no change** (`ios-promote` was never a required check; no merge-freeze risk).
- No Kotlin/module code changes; no test changes.
