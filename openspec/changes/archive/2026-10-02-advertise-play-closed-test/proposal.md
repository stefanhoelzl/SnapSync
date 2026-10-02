# Proposal

## Why

Google Play grants a new personal developer account production access only after a **closed test**: at
least 12 testers opted in for 14 consecutive days. SnapSync's Android app is approved on Play's closed
track (build 2118, 2026-10-02), but nothing a visitor meets tells them it exists. The site shows no Play
offer until the listing is public (`changes/archive/2026-10-01-play-badge-and-install-referrer`), and a
closed listing answers anyone who is not a tester with Play's "not found". Every Android visitor to the
landing page or to an invite's page is a potential tester, so the site should show them how to become
one while the test runs.

Access to a closed test is list-based. There is no single link that admits a stranger. SnapSync's list is
an open Google Group (`snapsync-beta`, anyone can join). So becoming a tester takes three steps: join the
group, opt in on Play's testing page, install from Play.

## What Changes

- **While the closed test runs, the landing page and the event page offer Google Play as three steps**,
  shown inline where the Google Play badge will later sit, beside the App Store button:
  1. join the testers' group;
  2. opt in as a tester on Google Play;
  3. install from Google Play. This step is the Google Play badge itself.
- **The event page's step 3 still carries the invite.** It is the same Google Play button as after
  launch, so a guest who becomes a tester from an invite's page still finds the app open on that event's
  join screen at first launch. The Privacy Policy's disclosure of that hand-over shows whenever the
  button does.
- **Deployment-driven, like the Play page address.** A new deployment value names the testers' group.
  When it is set and the Play page is not, the site shows the steps. When the Play page is set, the site
  shows only the badge. When neither is set, the site shows no Google Play offer at all. The resolver
  refuses a deployment that sets both.
- **The switch to production is a configuration change**: clear the group value and set the Play page
  address. The steps give way to the plain badge on the next site deploy, with no code change.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `web-site`: "A public landing page describes SnapSync". Before publication on Google Play, the landing
  page offers Google Play as tester steps while a closed test is open, and no Google Play at all
  otherwise. The Purpose's "once SnapSync is published there" widens the same way.
- `event-site`: "The page always offers the download and the app". The same tester steps on the event
  page during the closed test.
- `join-event`: "Without the app, the invite leads to a store and back". The Android install path, which
  opens the join screen at first launch, applies whenever the page offers Google Play, including through
  the tester steps. It no longer applies only after publication.

`privacy-security` is unchanged. Its rule already speaks of "the event page's Google Play button", and
step 3 is that button. `app-update-required` is unchanged: the Android update notice keeps offering no
button until the Play page is public.

## Impact

- **Deployment:** a new key `playTestGroupUrl` in `deployments/components/android.json`, rendered to the
  site only. `androidPackageName` additionally reaches the site, which builds Play's opt-in and listing
  addresses from it. New validation in `scripts/resolve-deployment.py` and its test.
- **Site (`site/`):** a new tester-steps component; `PlayStoreButton.astro` renders while the Play page is
  set or a closed test is open; the landing page, `/join`'s two views, the Privacy Policy paragraph and
  the footer trademark line follow the same "Google Play is offered" condition.
- **Docs:** `docs/deployment.md` (store pages and the switch to production).
- **Operational, outside this change:** recruit testers, apply for production, then the config flip and
  the production release. `promote.yml`'s `PLAY_TRACK` is still `alpha`, so a production promote through
  the workflow needs that constant changed. That is a separate `internal` change at the flip.
- No app, api or Android build change. The site deploys on every push to `main`.
