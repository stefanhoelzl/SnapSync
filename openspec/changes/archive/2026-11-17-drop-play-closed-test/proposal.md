# Proposal

## Why

SnapSync is published on Google Play: Google approved 0.6 for production in 2026-11, and the listing is public. The
web pages were built for three Play states: the public listing, an open closed test with its three tester steps,
and no Google Play at all. Only the first can happen now. The other two are dead branches in the specs, the site and
the deployment resolver, so they go.

## What Changes

- The landing page and the event page always offer Google Play beside the App Store, on every device. The closed
  test's three tester steps (join the testers' group, opt in, install) are removed, and so is the state where the
  pages offer the App Store only.
- An invite's page always leads an Android user to Google Play. The "become a tester from an invite's page" path is
  removed; installing from the page's Google Play button already covers it.
- The update notice's "no store page known" outcome stays, but its example "before SnapSync is published on Google
  Play" is no longer true and goes.
- **BREAKING (deployment config)**: the `playTestGroupUrl` deployment key is removed, and `playStoreUrl` becomes
  required. A deployment that leaves it empty is refused.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `web-site`: the landing page always offers both stores. The closed-test steps and the "App Store only" outcome go.
- `event-site`: the event page always offers both stores, for an invalid link too. The closed-test steps and the
  "App Store only" outcome go.
- `invite-link`: the page an invite opens without the app always offers Google Play. The tester-path scenario goes.
- `app-update-required`: the stale "before SnapSync is published on Google Play" example is dropped from the
  no-store-page outcome.

## Impact

- Site: `site/src/components/PlayTesterSteps.astro` and its `.playtest` styles in `site/src/styles/theme.css` are
  deleted. The Play offer logic (`site/src/lib/play.ts`, `PlayOffer.astro`) shrinks to the badge, and the
  `playOffered` conditions in `index.astro` and `Layout.astro` become unconditional. `site/scripts/play.test.ts`
  follows.
- Deployment resolver: `scripts/resolve-deployment.py` loses the `playTestGroupUrl` key and its rules, and requires
  `playStoreUrl`. `scripts/resolve_deployment_test.py` follows.
- Config: `deployments/components/android.json` loses `playTestGroupUrl`.
- Docs: `docs/deployment.md` (store pages, the Play closed test, the production switch).
- Android app: no code change. Every Android build already gets a non-empty `playStoreUrl` from the deployment.
  The adapter's handling of an empty value stays, because the update notice keeps its no-store-page outcome for
  builds that know none.
