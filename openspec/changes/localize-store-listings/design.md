# Design

## Context

Motivation is in proposal.md ("Why"); the outcomes are in `specs/localization` and `specs/web-site`.

What exists today:

- **The app's languages** are declared once: `nativeStrings { locales.set(listOf("en", "de")) }` in
  `ui/screens/build.gradle.kts`. `NativeStringsPlugin` (build-logic) generates the OS-shown strings and fails while
  a UI module's `values-*` directories disagree with the list (`docs/architecture.md`, "Localization").
- **Listing text**: one committed source per locale, `metadata/listing/<locale>.json`, today only `en-US`.
  `scripts/resolve-deployment.py` already loops over every file and renders App Store Connect's
  `app-info/<locale>.json` and `version/current/<locale>.json` plus Play's `play/<locale>.json`. `ci.yml`'s
  `metadata` gate validates the renders: `asc metadata validate` for Apple, `scripts/validate_play_listing.py` for
  Play's limits.
- **Headlines and tagline**: `metadata/screenshots/en-US.json`. `compose_screenshots.sh` already takes a locale and
  writes `$OUT_DIR/<locale>/…`. `asc screenshots upload --path` already fans out over locale directories.
- **The graphic**: `metadata/graphic/render.py` reads the en-US tagline and renders the layouts into committed
  PNGs: `featureGraphic.png` (Play), `frame-appstore.png` / `frame-play.png` (the first store screenshot), the site
  heroes, and the word-free `bg-*` backgrounds. `check.py` fails a render whose stamped tagline differs from the
  JSON.
- **Single-language assumptions**: `play_release.py` refuses more than one rendered listing and syncs images for that
  one language. `promote.yml` composes en-US only. `asc_screenshots_upload.sh` composes one `LOCALE`.
  `asc_release_notes_apply.sh` writes en-US only, and its comment argues against writing English into a future
  de-DE localization; this change reverses that decision (D5).
- **The site** imports `metadata/listing/en-US.json` and `metadata/screenshots/en-US.json` by name, so it stays
  English with no change.

## Goals / Non-Goals

**Goals:**
- Adding a language stays a translated file, never a code change. The delivery scripts loop over the locale files
  that exist, and one gate keeps that set equal to the app's languages.
- No new kind of gate: the language-set check joins the existing `nativeStrings` check, and limits stay where they
  are.

**Non-Goals:**
- Per-language screenshot captures, a localized site, localized release notes (see the spec's "Some surfaces stay
  English").
- Detecting a stale translation (D6).
- Localizing the App Review notes (`metadata/review/`). They are for Apple's reviewer and stay English.

## Decisions

### D1. Files are named by store locale; the app-to-store mapping is declared next to the app's languages

The listing and headline files are named after the store locale (`de-DE.json`), because that is the name both
stores' APIs and `asc`'s file layout use, and the resolver, `compose_screenshots.sh` and `asc` already treat the
file stem as the locale. The mapping from app language to store locale is declared once, next to the list, in the
`nativeStrings` extension (`storeLocales = mapOf("en" to "en-US", "de" to "de-DE")`), and must cover every app
language.

*Alternatives:* derive the store locale from the app tag. That doesn't work in general: Apple has `de-DE` but no
`de`, `fr-FR` and `fr-CA` are both valid, and Play's codes differ again. Name files by app language and map in each
delivery script: that spreads the mapping over five scripts.

### D2. The language-set gate lives in the `nativeStrings` task

`NativeStringsPlugin` already fails when a UI module's languages disagree with the list. It gains the same check for
`metadata/listing/*.json` and `metadata/screenshots/*.json` against the mapped store locales, and a key-parity check
for both file families: the same keys as `en-US`, recursively, with `_comment` exempt. Parity is a check of its own so a
missing key fails on the PR that dropped it, rather than in the resolver or (for a headline) only at promote time in
`compose_screenshots.sh`. The task runs in `./gradlew build`, so the gate is on every
PR.

*Alternatives:* a Python check in `ci.yml`'s `metadata` job. That puts a second copy of the language list outside
Gradle, or has Python parse `build.gradle.kts`. Reject it: one declaration, one checker.

### D3. The graphic renders once per locale; the site keeps English

`render.py` loops over `metadata/screenshots/*.json`. The layouts that carry words and feed a store
(`feature-graphic`, `frame-appstore`, `frame-play`) are written per locale:
`metadata/graphic/<locale>/frame-*.png` and `metadata/play/images/<locale>/featureGraphic.png`. The site heroes are
rendered from en-US only. The word-free backgrounds (`bg-*`) stay single files. `check.py` checks each per-locale
render against its own JSON. `compose_screenshots.sh` takes the concept frame from `metadata/graphic/<locale>/`.

*Alternative:* keep English graphics on the German page. Rejected: the spec makes the opening graphic follow the
store's language.

### D4. Delivery loops over the rendered locales

- `asc_metadata_apply.sh`: no change. It pushes every rendered `<locale>.json`, and `asc metadata push` creates a
  localization a version does not have yet. A `--dry-run` confirms this in the first apply.
- `asc_screenshots_upload.sh`: composes every locale in `metadata/screenshots/` into `$OUT_DIR/<locale>/`, then makes
  the one existing `--path` upload, which fans out per locale.
- `promote.yml` (Play): stages `$IMAGES/<locale>/{icon.png, featureGraphic.png, phoneScreenshots/}` per locale. The
  icon is the same file for every locale, because Play keeps images per language.
- `play_release.py`: `rendered_listing` returns every language. `sync_listing` diffs and writes text and image sets
  per language, and the contact details once. `listing-diff` reports per language. Release notes stay `en-US` only:
  Play shows the default language's notes to every language that has none of its own.

### D5. Apple's What's New: the English notes, written to every localization

`asc_release_notes_apply.sh` lists the version's localizations and writes the same English notes to each, creating
any committed locale the version lacks. The old comment's argument (an English text in a German localization is
worse than a missing-notes error) loses, because the spec now makes What's New English by decision. Without this,
the first submission with `de-DE` would be refused for a missing `whatsNew`. Its comment changes with it.

*Alternative:* a fixed German sentence. Rejected by the maintainer: it says nothing specific.

### D6. No staleness detection

Parity and limits are gated; whether the German copy still says what the English says is not, the same as the
app's `strings.xml`. `metadata/messaging.md` states that every language changes in the same PR as English.

*Alternative:* a PR-diff check that fails an `en-US` value change without the same key changing in every locale. A
new kind of gate (diff, not tree), and it gets in the way of an English-only typo fix. Rejected.

### D7. The German copy follows the app's German

`metadata/messaging.md` gains a German column in its glossary, taken from the app's `values-de`: "du" (informal),
"Event", "Galerie", "Fotos", "beitreten", "erstellen". Keywords (`apple.keywords`, ≤100 characters) are chosen as
German search terms, not a word-for-word translation. The agent drafts the copy and the maintainer reviews it in the
PR.

## Risks / Trade-offs

- [German copy is longer and breaks a store limit (Play title ≤30, short ≤80; Apple subtitle ≤30, keywords ≤100)]
  → The existing per-locale limit gates catch it on the PR. The name stays "SnapSync Photos".
- [A longer German headline or tagline overflows the graphic] → `compose_screenshots.sh` word-wraps headlines in a
  fixed box. The graphic's layout is checked by eye at render time (render.py's "LOOK at the results"), and the PR
  carries the renders.
- [The first apply touches the live App Store / Play listing in a new language] → Apple writes only to an editable
  version, and Play only inside a promote's edit, so the German page goes live with the next release. A promote
  `dry_run` shows the per-language diff first.
- [Play's per-language images multiply uploads] → the existing hash comparison uploads only a set that changed, per
  language.
- [Graphic renders depend on the machine's fonts] → as today: render on the same kind of machine. German umlauts are
  in every candidate font.

## Migration Plan

Merging changes no store until the next promote. That promote writes the `de-DE` localization on both stores. To
roll back, delete `de-DE.json` from both metadata families and its mapping entry, which makes the gate fail until
`de` also leaves the app or a listing returns. The store localization can be removed by hand in each console. Apple's
metadata apply never deletes on its own (no `--allow-deletes`).
