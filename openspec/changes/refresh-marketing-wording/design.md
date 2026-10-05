# Design

## Context

See proposal.md for the why. The wording itself is not a design question any more: every string was
settled in review and lives in `wording.md`, and the graphic was chosen from mockups (`reference/`).
What remains is where each piece lives, so the surfaces can't drift apart again, and how the graphic is
produced and delivered through pipelines built for app captures only.

Current state that shapes the approach:
- Both store listings render from `metadata/listing/en-US.json` through `scripts/resolve-deployment.py`,
  which substitutes per-store `words`. Store screenshot headlines live in `metadata/screenshots/en-US.json`.
  `.github/scripts/compose_screenshots.sh` composites them over the committed raw captures, for the App
  Store at promote time and for Play in `android-deliver`.
- The landing page (`site/`, Astro, zero client JS, no off-origin resources) carries its own copy, with
  captions that differ from the store headlines.
- Play's feature graphic is drawn by `scripts/appicon.py` from the icon geometry.
- The compositor deliberately draws no device frame. Apple's guidelines allow Apple hardware in listing
  images only as a real photograph, never as an artist's rendering.

## Goals / Non-Goals

**Goals:**
- Every marketing string has exactly one home, and every surface that shows it reads it from there.
- The graphic has a source in the repo and committed renders at each size a surface needs.
- The app's 44 changed strings land with their UI tests, and the marketing captures show them.

**Non-Goals:**
- No new app states or Shots.kt scenarios; the three captures stay, only re-captured.
- No localisation (en-US only, as today), no site analytics, no metrics baseline.
- Spec prose is not re-worded to the glossary. Specs say "members" and "library" in the engineering
  register, and the glossary governs user-facing copy only.
- No behaviour change: what is shared, received or shown when stays the same.

## Decisions

**D1 — `wording.md` is the decision record; `metadata/` holds the live strings.** Strings shared by
several surfaces live in `metadata/` JSON:
- The tagline and support line (F3/F4) go in a new `tagline` block in `metadata/screenshots/en-US.json`, next to the headlines.
- Everything else stays where it is today.

The site imports that JSON at build time, as it already imports the screenshots. The graphic's renderer
reads the tagline from the same file. *Alternative:* copy the strings into each surface by hand. That is
how today's drift happened, so it is rejected.

**D2 — The writing principles and glossary are kept in `metadata/messaging.md`.** It holds the
positioning, pillars, principles and glossary from `wording.md`, minus the one-off app-string table, as
the living reference for future copy. It cites MISSION as its source. *Alternative:* leave them only in
the archived change. Rejected because nobody reads an archive before writing a store note.

**D3 — The graphic is HTML/SVG source rendered to committed PNGs.** The look (perspective, depth, glow,
the photo card in flight) needs a browser engine; plain SVG rasterisers drop CSS 3D.
- The source lives in `metadata/graphic/` with a render script that uses headless Chromium (Playwright).
- The script writes the three renders: the Play feature graphic (1024×500), the store concept frame
  (App Store 1320×2868, Play 1080×1920) and the site hero.
- The renders are committed, like the screenshot raws. CI never renders, so the pipelines gain no
  browser dependency.
- A re-render is a manual step, checked by eye like a screenshot refresh.

`reference/mockup/` holds the mockup code the source starts from. *Alternative:* draw it in a design
tool and commit an export. Rejected: it is unreviewable in a PR and drifts from the tagline in `metadata/`.

**D4 — The phones are generic, not iPhones.** Because of the Apple rule in Context, neither phone may
read as an Apple device:
- No Dynamic Island: a small centred punch-hole instead, on both phones.
- No iPhone side-button layout and no Apple UI chrome.
- The camera screen shows only a shutter and a thumbnail; the gallery shows only a photo grid.

The mockup's pill notch is the one change from `reference/`. *Alternative:* keep it, since it is drawn.
Rejected because it is exactly the "artist's rendering" the guideline bars.

**D5 — The concept frame is a pre-rendered image the compositor puts first.** `compose_screenshots.sh`
emits `01-graphic.png` from the committed render for its target and canvas size. The app captures
follow as `02-…` to `04-…` with their headlines, as today. The graphic carries its own text, so the
compositor adds none. Both targets get the frame. Unchanged inputs must still produce unchanged bytes,
because the Play delivery uploads only a set whose hashes changed.

**D6 — `words` leaves the listing.** "Gallery" is now the same on both stores, so the `{{gallery}}`
placeholder and the `words` block go. The resolver already treats `words` as optional. Its suite must
still pass, and a test that relied on `gallery` is replaced by one that uses a placeholder that still exists.

**D7 — The site keeps copy only where no shared string fits.** Two places:
- The privacy block (built on pillar P3).
- The join page's line and dead-link message, which serve the "someone sent me a link" moment.

The "How it works" steps and the screenshots heading are removed, because the captions now tell those
three steps. The hero is the Google Play header: the graphic's composition without words, with the tagline and support line set over it as real text (on phone-width pages the words stack above a narrow render of the phones, on the page's own green). It is the same in light and dark mode. The light/dark requirement covers
screenshots, and the hero is not one.

**D8 — The app strings change in place, with their tests.** Each changed string is edited where it is
defined (paths in `wording.md`).
- Removing "HOST AN EVENT" and the settings screen's invitation header removes the eyebrow, not just its text.
- The iOS photo-access prompt and the extension's display name change in their `Info.plist` files.
- Strings quoted by specs are untouched (see the proposal).

**D9 — Re-capture after the app strings land, in the same PR.** `screenshots.yml` runs against the
branch. Both raw sets are checked by eye as the runbook says, then committed. Only then do the site and
the compositor show the new screens. One PR with the `enhancement` label: customers see it.

## Risks / Trade-offs

- **App Review rejects the drawn phones in frame 1.** Mitigation: D4 keeps them generic. If Apple still
  objects, the frame is dropped from the App Store target only (one switch in the compositor). The Play
  banner, the Play frame and the site keep it.
- **"Every photo" overclaims**, since only photos taken during the event's dates are shared and
  screenshots are excluded. Mitigation: the description's "Every photo, in your gallery" section and
  the app's create and join screens say so. The tagline is read with the event as context.
- **The listing goes live in two steps.** The Play listing updates on the merge to `main`. The App
  Store listing and screenshots update at the next promote. For a while the two stores say different
  things. Accepted: the promote follows soon, and the wording never contradicts itself.
- **A re-capture brings unrelated diffs** (for example a system notification in a capture). Mitigation:
  the existing eyeball step, and a re-dispatch if needed.
- **Renders drift from `metadata/`** if the tagline changes without a re-render. Mitigation: the render
  script stamps the tagline it used into the PNG's metadata. A build check compares it with
  `metadata/screenshots/en-US.json` and fails with "re-render the graphic".

## Migration Plan

1. Merge the PR. Play's listing and feature graphic update with `android-deliver`. The site deploys
   with its normal job.
2. Promote the next build. The App Store listing text and screenshots, including frame 1, ship with it.
3. Rollback: revert the PR. The listing returns on the next merge or promote. The app strings return with
   the next build.
