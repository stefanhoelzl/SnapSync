# Tasks

## 1. The language gate (design D1, D2)

- [x] 1.1 Add `storeLocales` (app tag → store locale) to `NativeStringsExtension`/`NativeStringsTask`, set
      `mapOf("en" to "en-US", "de" to "de-DE")` in `ui/screens/build.gradle.kts`, and fail when it does not cover
      exactly `locales`. Verify that `./gradlew nativeStrings` passes, and fails after temporarily dropping `de`
      from the map.
- [x] 1.2 Make `nativeStrings` check that `metadata/listing/*.json` and `metadata/screenshots/*.json` each hold
      exactly the mapped store locales, and that every file has the same keys as `en-US`, recursively, with
      `_comment` exempt. Cover the language-set and key-parity logic (the pure `StoreListings`) with a unit test in
      `build-logic/src/test/.../StoreListingsTest.kt` (missing locale, extra locale, missing key, extra key, pass).
      Verify the test passes and `./gradlew nativeStrings` fails until 2.2 lands.
- [x] 1.3 Update `docs/architecture.md` "Localization": the store pages follow the app's languages, "Adding a
      language" gains the two `metadata/*/<store-locale>.json` files and the `storeLocales` entry, and the
      "store listing … stay English for now" sentence goes (the site and invite page stay English, citing capability
      `localization`). Verify by reading the section against design D1–D2.

## 2. The German copy (design D7)

- [x] 2.1 Add a German column to `metadata/messaging.md`'s glossary, taken from
      `ui/screens/.../values-de/strings.xml` (du, Event, Galerie, Fotos, beitreten, erstellen), and state there that
      every language changes in the same PR as English (D6). Verify each German term appears in `values-de`.
- [x] 2.2 Draft `metadata/listing/de-DE.json` (same keys as `en-US`; name "SnapSync Photos"; German subtitle,
      description, promotional text, Play short description, German search keywords ≤100 characters; same `urls`)
      and `metadata/screenshots/de-DE.json` (headlines + tagline). Verify that `./gradlew nativeStrings` passes,
      `python3 scripts/resolve-deployment.py prod` renders `de-DE` for both stores,
      `python3 scripts/validate_play_listing.py build/metadata/play` passes, and `asc metadata validate --dir
      build/metadata` passes.
- [x] 2.3 Extend `scripts/resolve_deployment_test.py` with a two-locale tree and assert that both locales render for
      both stores. Verify `python3 scripts/resolve_deployment_test.py` passes.

## 3. The graphic per locale (design D3)

- [x] 3.1 Make `metadata/graphic/render.py` loop over `metadata/screenshots/*.json`: write the store layouts
      (`feature-graphic`, `frame-appstore`, `frame-play`) to `metadata/play/images/<locale>/featureGraphic.png` and
      `metadata/graphic/<locale>/frame-*.png`, render the site heroes from `en-US` only, and keep `bg-*` single.
      Move the existing en-US renders to their per-locale paths. Verify that `uv run metadata/graphic/render.py`
      produces both locales and that the German renders look right (check them by eye, with umlauts and wrapping).
- [x] 3.2 Make `metadata/graphic/check.py` check each per-locale render against its own JSON's tagline, and update
      `graphic.py`'s, `render.py`'s and `ci.yml`'s comments that name `en-US.json`. Verify that `python3
      metadata/graphic/check.py` passes, and fails after a temporary edit to the German tagline.
- [ ] 3.3 Point `compose_screenshots.sh`'s concept frame at `metadata/graphic/<locale>/frame-<target>.png`. Verify
      that `TARGET=appstore` and `TARGET=play` runs for `de-DE` produce correctly sized sets: German headlines on the
      English raws, with the German graphic first.

## 4. Delivery per locale (design D4, D5)

- [ ] 4.1 Make `asc_screenshots_upload.sh` compose every locale in `metadata/screenshots/` into `$OUT_DIR/<locale>/`
      before its single fan-out upload, replacing the `LOCALE` variable. Verify with a local compose run that
      `$OUT_DIR` holds `en-US/` and `de-DE/`, and with `bash -n` / shellcheck.
- [ ] 4.2 Make `asc_release_notes_apply.sh` write the English notes to every localization the version holds, plus
      every committed store locale it lacks (created first), and replace its "en-US ONLY" comment with D5's
      reasoning. Verify with shellcheck, and on the next promote by the version showing `whatsNew` in en-US and
      de-DE.
- [ ] 4.3 Make `play_release.py` handle many languages: `rendered_listing` returns every rendered language,
      `image_sets` reads `<images>/<language>/…`, and `sync_listing` diffs and writes text and images per language,
      with the contact details once. Release notes stay `en-US`. Verify with
      `uv run .github/scripts/play_release.py listing-diff <package> build/metadata/play <images>` (read-only),
      which reports per-language lines for en-US and de-DE.
- [ ] 4.4 Make `promote.yml`'s "Render the Play store listing and its images" step stage
      `$IMAGES/<locale>/{icon.png, featureGraphic.png, phoneScreenshots/}` for every locale, from the per-locale
      feature graphic and a `compose_screenshots.sh <locale>` run each. Verify with actionlint, then with a
      `promote.yml -f dry_run=true` run whose Play preflight lists de-DE text and image changes.
- [x] 4.5 Update `docs/deployment.md` (listing metadata, screenshots, graphic, Google Play listing delivery, release
      notes) for the per-locale loop, the German What's New decision, and where the per-locale renders live. Verify
      no remaining statement there says the listing is en-US only (`grep -n "en-US" docs/deployment.md`).

## 5. The site stays English

- [x] 5.1 Confirm the site still reads `metadata/listing/en-US.json` / `metadata/screenshots/en-US.json` and the
      `en-US` hero renders, and update any path the move in 3.1 changed. Verify that `cd site && deno task test` (or
      the site's test task) and the site build pass.

## 6. Integration

- [x] 6.1 Run `./gradlew build` and verify it passes and leaves a clean tree (renders and generated files committed).
- [ ] 6.2 After merge, on the next promote: check that the App Store's and Google Play's German pages show the German
      text, headlines and graphic over English screenshots, and that both What's New fields are English (the spec's
      scenarios in `localization`).
