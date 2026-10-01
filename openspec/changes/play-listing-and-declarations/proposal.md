# Proposal

## Why

Android's production launch needs a closed test that has run for 14 days with 12+ testers, and that clock starts only once
a closed release passes Google Play's review. A release can pass only when the app is listed: it needs a store listing
(text, icon, feature graphic, screenshots), the Console's App-content declarations, and a launcher icon (the Android app
has none). The Privacy Policy the listing links to describes only the iPhone: it says Apple issues the push token, names
only Apple App Attest as the integrity check, and lists Apple but not Google among the providers. That breaks the
existing promise that the policy names every provider that receives data, and Play compares the policy against the
Data safety form.

## What Changes

- **The Android launcher icon.** An adaptive icon (gradient background, the mark inside the safe zone as foreground,
  and a monochrome layer for themed icons), drawn from the same geometry as the iPhone icon, which `scripts/appicon.py`
  already defines in code. The same source also yields the 512×512 Play icon and the 1024×500 feature graphic.
- **One listing source for both stores.** `metadata/listing/<locale>.json` holds the shared name, description and URLs,
  an `apple` section (subtitle, keywords, promotional text) and a `play` section (short description), with platform
  words as placeholders. The renderer writes asc's strict-schema files (now generated, no longer hand-edited) and
  Play's fields, and CI validates both. Copy: the name stays "SnapSync Photos"; the description loses its iOS-only
  wording; en-US only.
- **Play listing delivery.** On `main` only, inside the existing single Play edit of `android-deliver`: the rendered
  text, contact details and images are compared with what Play holds, and only a difference is written. Most merges
  send nothing. A branch dispatch never touches the listing.
- **Android screenshots.** Raws captured from the same `Shots.kt` states on the Android emulator, light and dark,
  committed under `screenshots/android/`, composited with the shared headlines onto a 9:16 canvas.
- **Play declarations recorded in the repo.** `metadata/play/declarations.md` records every App-content answer and
  its justification: target audience 13+, no special app access, the photo & video permissions, user-generated
  content, Data safety (minimal: photos/videos and crash logs), the content rating (location sharing: No), no ads,
  category Photography, phones only, countries the same as the App Store's, pre-launch report on. You enter them by
  hand in the Console.
- **The Privacy Policy covers Android.**
  - It names the push token's issuer on each platform (Apple on iPhone, Google's Firebase Cloud Messaging on Android).
  - It describes the integrity check on each platform.
  - It lists Google among the service providers, for push wake-ups on Android and distribution through Google Play.
- **Play's contact email** is a GitHub secret, injected at apply time, never committed.

Out of scope: promotion to either store and the release-notes form (phase 5e); `playStoreUrl`, testers, the closed
release itself, production (phase 6); any app behaviour change beyond the launcher icon; Android screenshots on the
landing page.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `privacy-security`: "The Privacy Policy states what leaves the device" gains the per-platform rule: where a kind of
  data goes to a different provider on iPhone and on Android, the policy names each platform's provider.

The launcher icon, the store listing and the screenshots are not spec matters: how the app looks is the code's, and
release and listing are `docs/deployment.md`'s. The `web-site` capability is unchanged: the landing page keeps the
iPhone captures the App Store uses, and the policy's exact wording is copy.

## Impact

- `app/android/src/main/res/` (new `mipmap-anydpi`, drawables) and the manifest's `android:icon`/`roundIcon`.
- `scripts/appicon.py` (emits the SVG master, Android layers, Play icon, feature graphic).
- `metadata/` (new `listing/`, `play/`; `app-info/` and `version/current/` deleted as committed files),
  `scripts/resolve-deployment.py` (`render_metadata`) and its test, `ci.yml`'s `metadata` gate.
- `.github/scripts/play_release.py`, `ci.yml`'s `android-deliver`, `.github/scripts/compose_screenshots.sh`,
  `.github/workflows/screenshots.yml`, `test/integration`'s capture (an Android driver beside `CaptureShots`).
- `site/src/pages/index.astro` (the Privacy Policy section).
- `docs/deployment.md` §6 (listing, screenshots, Play delivery); CLAUDE.md's Releasing block.
- New GitHub secret: the Play contact email.
