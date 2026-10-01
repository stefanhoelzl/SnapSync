# Proposal

## Why

Android is becoming a full member of SnapSync events through Google Play, but everything a visitor meets
before installing still points only at the App Store. The landing page, the event page an invite shows,
and the app's own "update required" notice all do this. And an Android guest who installs from an invite's
page must then find and open the invite a second time, because nothing carries it through the
installation. This change offers Google Play wherever the App Store is offered. It also carries the invite
through a Play installation, so the first launch lands on the join screen.

The Play listing is visible only to closed testers until production launch (phase 6), so every Play offer
stays hidden until the deployment names a Play page. This change ships dormant, and phase 6 switches it on
by setting one value.

## What Changes

- The deployment gains a **Play page address**, the Android counterpart of the App Store page. It is empty
  until production launch. It reaches the website and the Android build, the way the App Store page
  reaches the website and the iOS build.
- **The landing page and the event page offer Google Play beside the App Store** once the Play page is
  set. Both buttons show on every device, with no platform detection. The Play button uses Google's
  official badge art, served from SnapSync's own site.
- **The event page's Play button carries the invite** when the page holds a valid invite. The invite
  travels as the Play install referrer, the same payload the invite link carries after `#`. It never
  travels with an invalid invite, on the "invalid or expired link" view, or from the landing page.
- **The Android app opens the carried invite once**, on its first launch after a Play install from an
  invite's page, exactly as if the invite had been tapped. An organic install, or a referrer that is not
  an invite, does nothing. The invite is delivered at most once per install.
- **Android's "update required" notice offers Google Play**, labelled as Google Play. Until now Android
  offered no remedy at all. The iPhone notice is unchanged.
- **Privacy is restated honestly.** Following the event page's Play button hands the invite to Google.
  The invite is the event's key, so Google Play can then read the event's photos. The spec and the
  site's Privacy Policy say so. Nothing is sent unless the visitor follows that button.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `join-event`: "The invite's secret never reaches a web server" gains the one consented exception.
  "Without the app, the invite leads to the App Store and back" gains Google Play and the Android path
  that needs no second tap. The first launch after a Play install from an invite's page opens its join
  screen once.
- `privacy-security`: "The event's identity stays off the wire until it is needed" names Google Play as
  the one third party, reached only when the visitor follows the event page's Play button.
- `event-site`: the page offers Google Play beside the App Store once published, and Getting the app
  never interrupts a download from either store. The Purpose drops "SnapSync is iPhone-only".
- `web-site`: the landing page offers Google Play beside the App Store once published, and the
  external-links rule covers Google Play. The Purpose drops its App-Store-only wording.
- `app-update-required`: the notice's button opens the store the app is distributed through: the App
  Store on iPhone, Google Play on Android. When the build knows no store page, the notice offers none.

## Impact

- **Deployment:** a new `playStoreUrl` key in `deployments/components/android.json`, rendered to the site
  and to the Android build properties by `scripts/resolve-deployment.py`. The key is not rendered into
  the api bundle, which has no use for it. The stale doc claim that `GET /join` redirects to the App
  Store is corrected.
- **Site:** a new `PlayStoreButton.astro` component, the badge art committed under `site/`,
  `join.astro` (the referrer rewrite in its island) and `index.astro`.
- **Android:** a new dependency on the Play Install Referrer library (`:adapter:android`), an
  install-referrer reader beside `AndroidLinks`, wiring in the production `platformAdapters`,
  `BuildConfig.PLAY_STORE_URL`, and `AndroidBuildInfo`.
- **Shared code:** the pure referrer-to-invite translation in `:domain:model` beside the event-link
  codec. The store link reported by `BuildInfo` gains which store it is, and that reaches the update
  notice's label in `:ui:screens`. iOS, the mocks, the rig and the desktop harness follow the port's
  new shape.
- **Not touched:** the api, the `/join` page's bytes (still identical for every event), and the Play
  listing and its declarations (phase 5g).
