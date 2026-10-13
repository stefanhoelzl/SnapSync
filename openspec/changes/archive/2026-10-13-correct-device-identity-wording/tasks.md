# Tasks

## 1. The published statements

- [x] 1.1 Rewrite the Privacy Policy's identifier paragraph in `site/src/pages/index.astro` ("A random device ID")
  per platform — random on iPhone, derived from the identifier Android gives SnapSync alone on Android; linked to no
  person and no other app; what it is for; that it can stay the same after a reinstall and ends with a factory reset —
  and say "device ID" (not "random device ID") in the read-record paragraph; verify with the site build
  (`site-build` gate) and by reading the rendered policy
- [x] 1.2 Update `metadata/play/declarations.md`: add "Device or other IDs" (collected, not shared, App
  functionality) to the data-safety table as declared in the Play Console, and drop it from the "part of the photos"
  notes; verify the `metadata` gate passes

- [x] 1.3 Drop "random" from what an automatic crash report may carry — the Privacy Policy's crash-report paragraph
  (`site/src/pages/index.astro`: "the identifiers of your device, the event and the photos") and the
  `metadata/play/declarations.md` bullet ("the **device, event and photo IDs**"), keeping "one random per-install
  identifier, created by the reporting itself"; verify with the site build and Prettier

## 2. Integrity checks

- [x] 2.1 Grep the specs, the site and `metadata/` for "random install", "random device" and "random identifier" and
  verify every remaining hit is the failure-reporting SDK's own per-install identifier
- [x] 2.2 Validate the change (`npx --yes @fission-ai/openspec@1.13.2 validate correct-device-identity-wording
  --strict`) and verify it passes
