## 1. Remove the promotion job

- [x] 1.1 In `.github/workflows/ios.yml`, delete the entire `ios-promote:` job (including its header comment block describing promotion).
- [x] 1.2 Reword the file-level top comment in `ios.yml` from "Four jobs … the FOURTH promotes …" to three jobs, dropping the alpha-promotion sentence.
- [x] 1.3 Confirm `ios-build`, `ios-test`, and `ios-deliver` (and `concurrency`, `permissions`, triggers) are untouched, and that `branches: ["**"]` still excludes tag refs.

## 2. Delete dead promotion code

- [x] 2.1 Delete `.github/scripts/testflight_promote.py`.
- [x] 2.2 Grep the tree to confirm nothing else invokes it functionally (`grep -rn testflight_promote .github openspec/specs CLAUDE.md` — only stale doc mentions should remain, fixed below).

## 3. Fix stale cross-references

- [x] 3.1 In `.github/scripts/appstore_release.py`, remove/rephrase the "mirroring `testflight_promote.py`" comment (line ~10).
- [x] 3.2 In `.github/workflows/ios-release.yml`, correct the guard comments that describe the guard in terms of `ios-promote` / the alpha channel (lines ~22, 28, 104, 156–157, 222): the released commit must be "on main / gated", no longer "on the alpha channel"; drop the `ios-promote`-not-required aside where it no longer applies.

## 4. Update the spec (contract of record)

- [x] 4.1 Apply the delta by rewriting `openspec/specs/ios-testflight-delivery/spec.md`: remove the promotion requirements, add *Tag refs fire only the release workflow*, and apply the MODIFIED edits to *Delivery never blocks merges…*, *Monotonic build numbers…*, and *Distribution builds use production APNs*.
- [x] 4.2 Rewrite the spec's `## Purpose` from "`main` is the public alpha channel / two jobs" to "`main` uploads a signed build to internal TestFlight; distribution to real users is App-Store-only (capability `ios-appstore-release`)."
- [x] 4.3 Update the spec's `Decision record:` line to add `changes/archive/<this-change-id>`.
- [x] 4.4 Run `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` and confirm it passes.

## 5. Update CLAUDE.md

- [x] 5.1 Rewrite the "### `main` is the public alpha channel" section: `main` no longer reaches public TestFlight; it uploads to the internal `development` group via `ios-deliver`; the only external distribution path is `gh workflow run ios-release.yml`.
- [x] 5.2 Remove the now-inapplicable notes: the alpha join link, the `autoNotifyEnabled`/order-is-load-bearing note, the "every main build promoted" bullet, and the `MARKETING_VERSION`-bump Beta-App-Review-stall trap (that stall was caused by `ios-promote`'s submit-to-review, which is gone).
- [x] 5.3 Fix the `ios-release.yml` note that tells operators to "check `ios-promote` yourself if alpha delivery matters" — there is no `ios-promote`.

## 6. Clean up phantom-job references in sibling capabilities (surfaced during apply)

- [x] 6.1 `openspec/specs/ios-appstore-release/spec.md` — restate the *A release only builds a merged, fully-green commit* requirement without `ios-promote` (references `ios-deliver` only); add the matching MODIFIED delta.
- [x] 6.2 `openspec/specs/ios-appstore-metadata/spec.md` — drop the phantom `ios-promote` from the Purpose example list (Purpose-only; no delta).
- [x] 6.3 `.github/workflows/appstore.yml` and `.github/workflows/appstore-screenshots.yml` — drop `ios-promote` from the "posts no required check" / Admin-key example comment lists.
- [x] 6.4 Update `proposal.md` Modified Capabilities + Impact to include `ios-appstore-release` and `ios-appstore-metadata`.

## 7. Verify

- [x] 7.1 `grep -rn "ios-promote\|testflight_promote\|public alpha channel\|/join/pvqgV7Uz" .github CLAUDE.md openspec/specs` returns only intended/historical mentions (none live).
- [x] 7.2 Sanity-check `ios.yml` (and `ios-release.yml`) YAML is still valid.
- [x] 7.3 `openspec validate --specs --strict` and `openspec validate <change> --strict` both pass.
- [ ] 7.4 Branch → PR → `/ship`.
