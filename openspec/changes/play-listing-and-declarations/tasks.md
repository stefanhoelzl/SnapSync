# Tasks

## 1. The icon, from the one geometry

- [x] 1.1 Extend `scripts/appicon.py` with a vector emitter over its existing constants: the mark as one even-odd path (no standalone SVG file, see design D1). Verify: `--check` passes (the flattened path, rasterised, matches the raster mark), and `Icon-1024.png` is byte-unchanged.
- [x] 1.2 Emit the Android adaptive icon: `res/drawable/ic_launcher_background.xml` (gradient), `ic_launcher_foreground.xml` (the mark inside the 66/108 safe zone), `ic_launcher_monochrome.xml`, and `res/mipmap-anydpi/ic_launcher.xml` + `ic_launcher_round.xml`. Set `android:icon`/`android:roundIcon` in the manifest. Verify: `./gradlew :app:android:assembleRelease` passes lint, and the icon is eyeballed on the emulator's launcher and with themed icons on (`snapsync-android`).
- [x] 1.3 Render the 512×512 Play icon and the 1024×500 feature graphic (mark, "SnapSync Photos", tagline; a bundled font file) to `metadata/play/images/`, committed. Verify: the dimensions are checked by the script; the images are eyeballed and shown in the PR.
- [x] 1.4 Document the icon outputs in the `appicon.py` docstring and `docs/deployment.md` §6. Verify: `./gradlew build` is green (architecture diagrams unaffected).

## 2. One listing source, rendered per store

- [x] 2.1 Write `metadata/listing/en-US.json` from today's two asc files: name, description (iOS wording → `{{gallery}}`), URLs, `words`, `apple`, `play`. Draft the Play short description (≤80) and the description's platform-neutral wording for review in the PR.
- [x] 2.2 Rework `render_metadata` in `scripts/resolve-deployment.py` to render the asc layout and `build/metadata/play/en-US.json` from the listing (per-store words; an unknown token or a missing word is an error), and stop copying `listing/`, `play/` and `screenshots/` verbatim. Delete `metadata/app-info/` and `metadata/version/`. Verify: new cases in `scripts/resolve_deployment_test.py` pass; the rendered asc files equal today's apart from the intended copy change (diff shown in the PR).
- [x] 2.3 Add `scripts/validate_play_listing.py` (title ≤30, short ≤80, full ≤4000, no `{{`) and run it in `ci.yml`'s `metadata` gate after the resolver. Verify: `asc metadata validate --dir build/metadata` and the new script both pass locally; a deliberately over-long short description fails.
- [x] 2.4 Update `docs/deployment.md` "Listing metadata", the `metadata/screenshots/en-US.json` `_comment`, and CLAUDE.md's Releasing block for the unified source. Verify: no doc still says the asc files are hand-edited (`grep -rn "app-info/en-US" docs CLAUDE.md .github`).

## 3. Privacy Policy covers Android

- [x] 3.1 Update the policy in `site/src/pages/index.astro` per design D5: the push token per platform, the integrity check per platform, Google among the providers (four). Verify: `site-build` passes; the rendered page is opened with `ch ws browser` and read.
- [x] 3.2 Check the policy against the `privacy-security` delta's scenarios and against `declarations.md`'s Data-safety section. Verify: every Data-safety category mentioned in `declarations.md` appears in the policy.

## 4. Android screenshots

- [x] 4.1 Split `CaptureShots.kt` into the shot loop over a `Screen` interface with `Simulator` and `AndroidEmulator` implementations (adb launch/force-stop, `cmd uimode night`, `screencap`, `run-as` state reset, demo-mode status bar). Verify: `./gradlew :test:integration:screenshotsClasses` compiles, and `ShotsTest` still passes in `./gradlew build`.
- [x] 4.2 Run the Android capture locally on the emulator (`snapsync-android`) and confirm all six raws. Verify: the six PNGs are produced, light and dark differ, and they are eyeballed.
- [x] 4.3 Add the `android` job to `screenshots.yml` (ubuntu + KVM, the emulator install ci.yml uses, artifact `screenshots-android-raw`), with the header comment updated. Verify: a dispatch on the branch produces six raws; eyeball them, then commit to `screenshots/android/`.
- [x] 4.4 Add the `play` target to `compose_screenshots.sh` (1080×1920, the same headlines, `-strip`, no PNG time chunks). Verify: running it twice on the committed raws gives byte-identical outputs and passes Play's ratio check; the composites are eyeballed.
- [x] 4.5 Update `docs/deployment.md` "Screenshots" and CLAUDE.md's screenshot runbook for the Android raws. Verify: the documented dispatch/download commands match the workflow's artifact names.

- [x] 4.6 Fix the light-theme status bar exposed by the captures (user's call): a day/night window theme with light bars on the light theme. Verify: re-captured on the emulator, dark icons on light and white on dark, status and navigation bars.

## 5. Play listing delivery

- [x] 5.1 Measure whether Play re-encodes images: under `secrets-env`, upload one composite into an edit, list its sha256, and DELETE the edit, never committing it. Record the result in design.md D3, and pick a byte or pixel comparison accordingly.
- [x] 5.2 Extend `play_release.py`: listing/details/images comparison and conditional PATCH/upload inside `deliver` (flags `--listing`, `--images`, `PLAY_CONTACT_EMAIL`), plus the read-only `listing-diff` verb. Verify: `listing-diff` run locally under `secrets-env` reports the expected first-time differences and commits nothing (`status` shows the internal track unchanged).
- [x] 5.3 Wire `android-deliver`: on `refs/heads/main` only, resolve the deployment, composite the Play screenshots, stage the icon and feature graphic, and pass `--listing`/`--images`; a dispatch passes neither. Verify: `actionlint` passes, and the `if:` conditions are read and checked against a branch-dispatch run of the job.
- [x] 5.4 The contact email: reuse the existing `ASC_REVIEW_CONTACT_EMAIL` secret (the user's choice), passed to the script as `PLAY_CONTACT_EMAIL`. Verify: `gh secret list` shows it.
- [x] 5.5 Document Play listing delivery in `docs/deployment.md` (§6, and the delivery section describing `android-deliver`). Verify: the doc names the main-only gate and the diff rule.

## 6. Declarations

- [x] 6.1 Read the App Store's territories via `asc-portal`.
- [x] 6.2 Write `metadata/play/declarations.md`: every form's answer and justification, per design D6, with the user's two calls marked as theirs and the deferred-widening triggers. Verify: the user reviews it in the PR.

## 7. Ship and integrate

- [ ] 7.1 `./gradlew build` and the `metadata` gate's steps pass locally, and the PR is green on every required check. Ship with `/ship --keep-workspace`, labelled `enhancement`.
- [ ] 7.2 After the merge, confirm on the main run that `appstore-metadata-apply` succeeded and `android-deliver` logged the listing and images as `updated`. Confirm on the next main merge that it logs `unchanged`.
- [ ] 7.3 Guide the user through the Console steps in design.md's order, one at a time, recording every answer back into `declarations.md` (a follow-up PR if any answer differs).
- [ ] 7.4 Report "shipped" to the `release` workspace per the handoff, then delete this workspace.
