# Proposal

## Why

The app speaks English and German, but both store pages are English only. So a German family meets SnapSync in
English at the moment they decide whether to install it. No spec says which languages SnapSync speaks or what is
translated, so nothing says the store page has to keep up with the app. `docs/architecture.md` even states that
the listing "stays English for now".

## What Changes

- The App Store and Google Play pages gain a German localization next to the English one. It translates the listing
  text, the headline over each screenshot, and the words of the use-case graphic (the first screenshot and Play's
  feature graphic). The app's name stays "SnapSync Photos" in every language.
- The store pages speak exactly the app's languages, no more and no fewer. A language is added to the app and to
  both store pages together.
- What stays English, on purpose: the app screens shown in the screenshots (every language reuses the English
  captures), "What's New" (Apple requires the field in every localization, so the German one carries the English
  notes), the web site and the invite page.
- A new capability, `localization`, becomes the one place that says which languages SnapSync speaks and what is and
  isn't translated. That covers the app (already English and German but never specced), the text the phone shows on
  SnapSync's behalf, dates, and now the store pages.
- `web-site`'s promise that the site's captions match the store headlines narrows to the **English** store
  headlines. The site stays English.
- The German copy is drafted from `metadata/messaging.md` and the app's German words, then reviewed by the
  maintainer. `messaging.md` gains a German glossary column.

## Capabilities

### New Capabilities
- `localization`: the languages SnapSync speaks (English as the base, plus German) and what follows the reader's
  language: the app's screens, the text the phone shows for SnapSync, dates, and the App Store and Google Play pages.
  It also lists what deliberately stays English.

### Modified Capabilities
- `web-site`: "The site shows the real app, the same as the App Store". The captions match the **English** store
  listing's headlines.

## Impact

- `metadata/listing/de-DE.json` and `metadata/screenshots/de-DE.json` (new); `metadata/messaging.md` (German
  glossary).
- The language gate: the `nativeStrings` build-logic plugin also checks the store-listing language files against
  the app's language list, through a declared mapping from app language to store locale (`en` → `en-US`,
  `de` → `de-DE`).
- The graphic: `metadata/graphic/render.py` and `check.py` render and check the graphic per locale. The German
  first-screenshot frames and the German Play feature graphic are committed. The site hero stays English only.
- Delivery: `compose_screenshots.sh` and `asc_screenshots_upload.sh` compose and upload every locale.
  `play_release.py` syncs every rendered language's text and images. `asc_release_notes_apply.sh` writes the
  English notes to every localization. `promote.yml` stages images per locale. The resolver already renders every
  `metadata/listing/<locale>.json`, and `ci.yml`'s `metadata` gate validates them.
- Docs: `docs/architecture.md` "Localization" (adding a language now includes the store pages) and
  `docs/deployment.md` (per-locale listing, screenshots and release notes).
- No app code, no backend, no stored format, no shim.
