# Tasks

## 1. Tests for the restated outcomes

- [x] 1.1 Retarget the three `@Verifies` that name "Switching between full and limited access never re-uploads"
  (`SelectionIsTheWalkIntegrationTest`, `LimitedSelectionIntegrationTest`, `PhotoAccessIntegrationTest`) to the new
  title "Switching between full and limited access re-uploads only what was de-selected"; verify the tests pass
- [x] 1.2 Add a `@Verifies` test for photo-access "A photo de-selected earlier returns on widening" — de-select a shared
  photo, switch to full access, assert it is uploaded again and no still-shared photo is; verify it passes against the
  current app
- [x] 1.3 Claim photo-sharing "A photo re-added to the selection is shared again" on the existing re-selection test,
  and add a test for "A photo moved back into DCIM is shared again" (none existed) asserting the re-upload; verify
  both pass
- [x] 1.4 Add a `@Verifies` test for photo-sharing "A video just under the floor's pixel count is shared" (a 1100 × 900
  video is uploaded), adding that video to the library levers' photo kinds; verify it passes
- [x] 1.5 Add a `@Verifies` test for photo-sharing "Before the start the preview counts the photos that wait for it" —
  the preview's count before the start equals the uploads after it, and nothing uploads before; verify it passes

## 2. Archive

- [x] 2.1 Run `./gradlew :test:architecture:verifiesReport` and verify every restated requirement and new scenario is
  listed as verified
- [x] 2.2 Validate with `npx --yes @fission-ai/openspec@1.13.2 validate align-selection-specs --strict` and verify it
  reports no issues
- [x] 2.3 Archive the change, then run both archiving gates from `openspec/config.yaml` (placeholder Purpose over the
  whole tree; no code identifiers in `photo-sharing` and `photo-access`) and verify neither prints anything
