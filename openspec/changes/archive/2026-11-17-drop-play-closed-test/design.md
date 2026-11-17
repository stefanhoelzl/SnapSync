# Design

## Context

The site picks its Google Play offer from the resolved deployment. `site/src/lib/play.ts` maps `playStoreUrl` /
`playTestGroupUrl` to one of three offers (`listing`, `testing`, `none`). `PlayOffer.astro` renders the badge or
`PlayTesterSteps.astro`, and the landing page and the layout gate their Play-only copy (the install-referrer
privacy paragraph, the Google trademark line) on `kind !== "none"`. `scripts/resolve-deployment.py` lets each key be
empty and refuses the two set together. The decision record for that three-state design is
`changes/archive/2026-10-02-advertise-play-closed-test`. Since Google approved the production release, `android.json`
sets `playStoreUrl` and leaves `playTestGroupUrl` empty, and every deployment (`prod`, `maintenance`, `local`)
inherits both values.

The Android app reads `playStoreUrl` through the same resolved deployment (`PLAY_STORE_URL`). It already treats an
empty value as "no store page" (`AndroidBuildInfo.store`), and the iOS app treats a missing `appStoreUrl` the same way.

## Goals / Non-Goals

**Goals:**
- One Play state, the public listing. The site renders the badge with no offer computation.
- The resolver refuses an empty `playStoreUrl`, so leaving it empty fails the build instead of quietly hiding the badge.

**Non-Goals:**
- No change to the app. The update notice's no-store-page branch and the adapters' empty-value handling stay. They
  are the platform-neutral answer for any build that knows no store page, and they belong to
  `app-update-required`, not to the Play launch.
- No change to the install-referrer mechanism or to the invite handed to Google Play.
- No edit of the archived decision record. It stays the history of the closed test.

## Decisions

### D1. Delete the offer computation rather than shrink it to one case

`play.ts` and `PlayOffer.astro` go. The pages place `PlayStoreButton` directly with `deployment.playStoreUrl`, and the
`playOffered` conditions in `index.astro` and `Layout.astro` are removed, so their copy is always rendered. A
one-case `playOfferFor` would be an indirection with nothing left to decide.

*Alternative:* keep `PlayOffer.astro` as the one placement point. Rejected: the badge component already is that
point, and the wrapper would only forward its one prop.

### D2. `playStoreUrl` becomes required, at the resolver

`validate_play_store_url` stops accepting empty: the value must be exactly the listing of `androidPackageName`.
`playTestGroupUrl` is removed from the key inventory, so a deployment that still carries it fails as an unknown key,
as any stray key does. The inventory doc of `playStoreUrl` loses its "EMPTY until the Play listing is public" text.

*Alternative:* keep empty as "dormant" and let the site cope. Rejected: that keeps the `none` state alive in code
and tests for a deployment that cannot happen, and the clean-architecture rule says not to keep a compromise just
because removing it changes no behaviour.

### D3. Three requirements are renamed to drop their scenarios

OpenSpec 1.13.2 refuses a MODIFIED block that omits a scenario the current requirement has, and refuses REMOVED plus
ADDED under one name (the precedent is `changes/archive/2026-11-05-a-received-photo-always-has-a-sender-filename`,
D5). So each requirement that loses scenarios is REMOVED and ADDED back under a new name:
- `web-site`: "A public landing page describes SnapSync" → "The landing page describes SnapSync and offers both stores"
- `event-site`: "The page always offers the download and the app" → "The page always offers the download and both stores"
- `invite-link`: "Without the app, the invite leads to a store and back" → "Without the app, an invite leads to a store and back"

`app-update-required` keeps all its scenarios, so it is an ordinary MODIFIED block. Every `@Verifies(requirement = …)`
and `AcceptedUnclaimed` entry naming an old requirement moves to the new name. That covers `api/test/eventlink.test.ts`,
`site/scripts/pages.test.ts`, `InviteLinkIntegrationTest`, `InstallReferrerIntegrationTest`,
`AndroidInstallReferrerTest` and `AcceptedUnclaimed.kt`. The archived decision records keep the old names; they are
history.

*Rejected:* editing the main specs by hand under `skip_specs`. It is smaller, but it changes the contract outside the
CLI's delta and archive guard.

### D4. Scenario evidence moves, it is not dropped

The `@Verifies` annotations also follow the renamed and removed scenarios:
- `site/scripts/play.test.ts` is deleted. Its "badge alone" evidence (the `web-site` / `event-site` *Both stores are
  offered* scenarios, and `privacy-security`'s *The landing page carries no invite*) moves to `pages.test.ts`, which
  asserts the rendered landing and event pages link `deployment.playStoreUrl`. Its test that the listing carries the
  invite as the install referrer moves too, against `deployment.playStoreUrl`.
- `pages.test.ts`'s invalid-link test verifies *Both stores are offered for an invalid link too*. Its `playHrefs()`
  helper becomes the one listing URL.
- `AndroidInstallReferrerTest` and `InstallReferrerIntegrationTest` drop their *Becoming a tester…* annotation.
  Both already verify, or will now verify, *Installing from Google Play opens the join screen*.
- The `AcceptedUnclaimed` entry for *Following the steps installs SnapSync* is removed with its scenario.

## Risks / Trade-offs

- [The site deploy shows no Play badge if `android.json` loses `playStoreUrl`] → D2 makes that a build failure.
- [A future brand-new Play app (a renamed package) would need a closed test again] → Not planned. It would be a new
  change, and the archived decision record says how it was done.
- [The renamed requirements and scenarios break the `@Verifies` gate until every annotation follows] → D3 and D4 list
  every reference; the architecture gate in `./gradlew build` catches any that are missed.
- [An ADDED requirement would land at the end of its spec] → The sync placed each renamed requirement where the
  old one stood, so every spec keeps its reading order.
