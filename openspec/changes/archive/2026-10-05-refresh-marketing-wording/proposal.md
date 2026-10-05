# Proposal

## Why

SnapSync's marketing says different things in different places — "SnapSync Photos" here and "SnapSync"
there, "library" on one surface and "gallery" on the next, a trip in the listing and "the moment" on the
site, weddings and festivals in the examples — and none of it shows what the app does. The app itself
adds a third voice ("Start an event", "one shared place"). To win new users, every surface needs to tell
the same story: family and friends, an afternoon to a holiday, just join, and every photo arrives in your
gallery. That story was settled item by item and is recorded in `wording.md`.

## What Changes

- **One purpose statement.** MISSION's opening in `openspec/config.yaml` (and its summary in CLAUDE.md)
  names the audience — family and friends, small groups who know each other — the range from a
  spontaneous afternoon to a few weeks, and that joining needs no account and asks for no name.
- **App Store and Google Play listings** get new wording from `wording.md`. The name stays
  "SnapSync Photos". The subtitle, promotional text, keywords, Play short description and description
  all change, and "gallery" replaces the per-store "Photos library" / "gallery" switch.
- **A use-case graphic.** Two drawn phones on the brand green: one takes a photo, and it arrives in the
  other's gallery. It becomes the Play feature graphic, the first store screenshot on both stores
  (portrait) and the landing page's hero (without text). See `reference/`.
- **Store screenshots** gain that graphic as frame 1. The three existing app states keep their place
  under new headlines: "Create an event" · "Your family and friends join" · "Photos arrive on their own".
- **The landing page** is rebuilt from the same strings: the tagline, the promotional line, the
  screenshots captioned with the store headlines, the description's three sections and a privacy block.
  The separate "How it works" steps and the screenshots heading go.
- **The join page** (an event link opened without the app) gets a new line under the event's name and a
  dead-link message that says what to do.
- **The app's own wording**: 44 of its 226 user-visible strings change. Examples: "Create an event"
  instead of "Start an event", the tagline instead of "one shared place", "gallery" instead of
  "library", "Event not found", and the iOS photo-access prompt. Event settings no longer show the
  invitation's "YOU'RE INVITED" header. The upload extension is renamed "SnapSync PhotoKit Extension".
- The three marketing screenshots are re-captured, because the create and join screens they show change.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `web-site`: the landing page's screenshot captions become the same headlines the store listings carry
  over the same screenshots, so the site and the listings say the same thing, not only show the same
  software.

All other changes are wording, layout, store metadata or a picture — "exact copy" and "how a screen
looks", which the project keeps out of specs. The spec prose that describes changed screens
(`create-event`: the screen states that only photos taken during the window are shared;
`manage-membership`: sharing less only stops new members getting those photos, and the album collects
photos already received; `event-site`: an invalid link says so) stays true under the new wording, so
those capabilities need no delta.

## Impact

- **Copy and metadata:**
  - `metadata/listing/en-US.json` (the `words` block goes)
  - `metadata/screenshots/en-US.json` (headlines, plus a key for the graphic frame)
  - `metadata/play/images/featureGraphic.png`
- **Graphic:**
  - New source and renders for the graphic, which needs a home and a renderer in the repo.
  - `scripts/appicon.py` stops drawing the feature graphic.
- **Store pipelines:**
  - `.github/scripts/compose_screenshots.sh`, used by `promote.yml` and `ci.yml`'s `android-deliver`, learns a leading concept frame for both targets.
  - `scripts/resolve-deployment.py` keeps working without `words`.
- **Site:**
  - `site/src/pages/index.astro` and `join.astro` read the shared strings from `metadata/`.
  - The site keeps its own copy only for the privacy block and the join page.
- **App:**
  - Strings in `ui/screens` and `ui/components`, with their UI tests (`JoinScreenTest`, `StatusScreenTest` and others).
  - `iosApp/iosApp/Info.plist` (photo-access prompt).
  - `iosApp/BackgroundUploadExtension/Info.plist` (display name).
  - Any journey or harness code that looks for an old string.
- **Screenshots:** `screenshots/` and `screenshots/android/` are re-captured via `screenshots.yml` and checked by eye.
- **Docs:**
  - `openspec/config.yaml` MISSION and the CLAUDE.md mission summary.
  - `docs/deployment.md` "Listing metadata" (concept frame, no `words`).
- **No behaviour change** to sharing, joining or receiving.
