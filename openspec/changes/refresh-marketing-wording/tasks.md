# Tasks

The wording to apply is in `wording.md`; the graphic to build is in `reference/`.

## 1. Purpose and messaging reference

- [x] 1.1 Replace MISSION's opening sentences in `openspec/config.yaml` with F2 from `wording.md`, keeping the rest of MISSION unchanged, and verify that `git diff` shows only those sentences changed and that `npx --yes @fission-ai/openspec@1.13.2 validate --specs --strict` passes
- [x] 1.2 Bring the mission summary at the top of `CLAUDE.md` in line with F2 (family and friends, an afternoon to a few weeks, no account, anonymous) and verify that it no longer lists "celebrations, holidays, trips" as the event examples
- [x] 1.3 Write `metadata/messaging.md` (positioning F1, pillars, writing principles, glossary, citing MISSION) per D2 and verify that each term in it matches `wording.md`

## 2. Shared strings and store listings

- [x] 2.1 Rewrite `metadata/listing/en-US.json` (subtitle, promotional text, keywords, Play short description, description; name unchanged) from `wording.md`, drop the `words` block and the `{{gallery}}` placeholders (D6), and verify that the resolver's suite and `ci.yml`'s `metadata` gate pass locally, including the Play length limits
- [x] 2.2 Update `metadata/screenshots/en-US.json`: set the new headlines for `create`, `joining` and `in_sync`, and add the `tagline` block (F3, F4) per D1. Verify that `.github/scripts/compose_screenshots.sh` still renders the three app frames for both targets
- [x] 2.3 Update `docs/deployment.md` "Listing metadata" (no `words`, the `tagline` block, the concept frame) and verify that its examples match the files

## 3. The graphic

- [x] 3.1 Build the graphic source in `metadata/graphic/` from `reference/mockup/` per D3 and D4. The headline and support line must come from `metadata/screenshots/en-US.json`, and the phones must be generic (punch-hole, no Dynamic Island). Verify that a render matches `reference/` apart from the D4 change
- [x] 3.2 Add the render script (headless Chromium via Playwright) that writes the Play feature graphic (1024×500), the concept frame for both store canvases (1320×2868 and 1080×1920) and the site hero, and stamps the tagline into each PNG. Verify the output sizes with `identify`, then commit the renders
- [x] 3.3 Point the Play feature graphic at the new render and remove `feature_graphic()` from `scripts/appicon.py`. Verify that `python3 scripts/appicon.py --check` passes and that `metadata/play/images/featureGraphic.png` is the new render
- [x] 3.4 Add the build check that compares the stamped tagline with `metadata/screenshots/en-US.json` and fails with "re-render the graphic". Verify it fails after editing the tagline without re-rendering and passes after re-rendering

## 4. Store screenshot pipeline

- [x] 4.1 Teach `.github/scripts/compose_screenshots.sh` to emit `01-graphic.png` from the committed concept frame for both targets, with the app frames following as `02-…` to `04-…` (D5). Verify that both targets produce four images at the exact canvas sizes, and that a second run on unchanged inputs produces byte-identical output
- [x] 4.2 Check that `promote.yml` and `ci.yml`'s `android-deliver` pass the new set through unchanged: no hard-coded count of three, and the upload order is kept. Verify with a dry run (`gh workflow run promote.yml -f build_number=<n> -f dry_run=true`) and by reviewing the Play delivery step's file handling

## 5. Landing page and join page

- [x] 5.1 Rebuild `site/src/pages/index.astro` per D7:
  - Hero: F3 plus A2, read from `metadata/`, the store buttons and the hero render.
  - Screenshots captioned with the store headlines from `metadata/screenshots/en-US.json`.
  - The description's three sections and the privacy block.
  - The "How it works" steps and the screenshots heading removed.

  Verify with the site build and in the browser in light and dark mode, with JavaScript off and by keyboard.
- [x] 5.2 Update `site/src/pages/join.astro`: the line under the title and the dead-link body from `wording.md`. Verify by opening a valid link and an invalid one against a local backend
- [x] 5.3 Verify the web-site delta: change one headline in `metadata/screenshots/en-US.json`, rebuild the site, and confirm that the caption follows (scenario "Captions match the store headlines"); then revert

## 6. App strings

- [x] 6.1 Apply the create-screen, calendar and share-range strings from `wording.md` (S002–S025, S038–S041, S098, S101), including removing the "HOST AN EVENT" eyebrow. Update the affected UI tests and verify that `./gradlew :ui:screens:jvmTest` passes
- [x] 6.2 Apply the join-screen and participation strings (S046–S117, S082–S089). Update `JoinScreenTest` and the others, and verify that `./gradlew :ui:screens:jvmTest` passes
- [x] 6.3 Apply the joined-screen, status-line, settings and dialog strings (S159–S201), including removing the settings screen's "YOU'RE INVITED" header. Update `StatusScreenTest` and the others, and verify that `./gradlew :ui:screens:jvmTest` passes
- [x] 6.4 Change the iOS photo-access prompt (S220) in `iosApp/iosApp/Info.plist` and the extension's display name (S221) in `iosApp/BackgroundUploadExtension/Info.plist`. Verify with `plutil -lint`, or by reading both files back
- [x] 6.5 Search the journeys, the harness and the integration tests for any old string (for example "Start an event" or "Invalid invite") and update each one. Verify that `./gradlew build` passes, and run `compileIosMainKotlinMetadata` as the iOS proxy

## 7. Re-capture and integration

- [ ] 7.1 Run `screenshots.yml` against the branch, download both raw sets, check each one by eye per the runbook, and commit `screenshots/` and `screenshots/android/`. Verify that the create and joining captures show the new wording
- [ ] 7.2 Run the full set locally: `./gradlew build`, the site build, the compositor for both targets, and `openspec validate --strict` for the change. Then review the rendered App Store and Play sets and the landing page against `reference/` and `wording.md`
