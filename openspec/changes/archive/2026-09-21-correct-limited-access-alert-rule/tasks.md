## 1. CLAUDE.md

- [x] 1.1 Rewrite rule ① in `CLAUDE.md` to match the corrected requirement:
  - the prompt is iOS's automatic "Select More Photos… / Keep Current Selection" nudge, not a read guard;
  - the app suppresses it with `PHPhotoLibraryPreventAutomaticLimitedAccessAlert` and owns the picker;
  - settled: reads of an unchanged library and the app's own creations (imports, album creation and adds) raise none;
  - not settled: the key leaked on iOS 26.5 and 26.5.2 and held on 26.6.2, so design on neither;
  - the read discipline stays for scope and round-trip reasons.
  Drop "every photo the member takes costs one system prompt" and "only the full-access upgrade avoids it".
- [x] 1.2 Keep ② and ③ and the surrounding paragraph unchanged; check that nothing else in `CLAUDE.md` restates the removed claim (grep "one system prompt", "costs one", "armed by")

## 2. Other copies of the claim

- [x] 2.1 Grep `openspec/specs/`, `.claude/skills/` (the in-repo runbooks) and the Kotlin KDoc under `adapter/ios/`, `domain/` and `test/rig/` for the retired claims ("every photo … prompt", "one prompt per photo", "measured storm", "armed by the library changing"); fix each live copy, or record why it stays (archived decision records are history and stay untouched)
  — 8 live copies fixed, all comments: `PhotoSelectionSnapshotSource.kt` (×2), `PhotoKitCandidateSource.kt`, `PermissionAwareCandidateSource.kt`, `PermissionAwareCandidateSourceTest.kt` (×2), `World.kt`, `UrlSessionUploadController.kt`; no copy in other specs or in `.claude/skills/`

## 3. Validate and sync

- [x] 3.1 `npx --yes @fission-ai/openspec@1.5.0 validate correct-limited-access-alert-rule --strict` and `validate --specs --strict` green
- [x] 3.2 At sync: rewrite the `limited-photo-access` `## Purpose` paragraph on the first measured fact to match, keeping it a single short statement of the corrected rule, and cite this change's decision record; replace the `changes/archive/…-correct-limited-access-alert-rule` placeholder in `CLAUDE.md` ① with the dated archive path

## Archive gates (2026-09-21)

- **Placeholder Purpose:** none in the tree.
- **Dead types:** none removed.
- **Delta completeness:** every module this change touched (`:adapter:ios:app-only`, `:adapter:ios:ext-safe`, `:app:ios`, `:domain:compose`, `:test:world`) was touched for **comments only**, restating the corrected `limited-photo-access` rule. No behaviour changed, so `limited-photo-access` is the only delta. `CLAUDE.md` is not a capability; its rule ① mirrors that delta.
