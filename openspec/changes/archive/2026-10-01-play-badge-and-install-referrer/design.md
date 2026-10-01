# Design

## Context

See proposal.md, "Why". The state of the tree this design starts from:

- **Store links.** `deployments/components/apple.json` carries `appStoreUrl`. The resolver
  (`scripts/resolve-deployment.py`) renders it to the api bundle, the site's `deployment.json` and the
  iOS `Deployment.plist`. The Android build takes its deployment values from
  `build/deployment.properties` (the `PROPS` rendering) into `BuildConfig` (`app/android/build.gradle.kts`).
  `AndroidBuildInfo.appStoreUrl` is hard-coded `null`, so Android's update notice offers no button. The
  resolver's doc for `appStoreUrl` still says `GET /join` redirects app-less visitors to the App Store.
  It no longer does: `/join` serves the one constant page (`api/src/app.ts`, the `/join` route).
- **The site.** `AppStoreButton.astro` is a custom button with an inline Apple logo, not Apple's badge
  art. It appears twice on `/join` (in the `#app` view and in the `#invalid` view) and once on the landing
  page. The `/join` island already decodes the fragment (`decodeFragment`, `#v=3&d=<base64url(json)>`),
  but it returns only the `eventId`. The site's gates are `astro check`, prettier and
  `scripts/check-selfcontained.ts` (no off-origin subresources). There is no JavaScript unit test.
- **Links on Android.** `AndroidLinks` hands the core a `LinkDelivery` with the raw URL. What an event
  link is, and what opening one does, is the core's (`forwardEventLink`, `decodeEventUrl` in
  `EventLink.kt`). `MainActivity.onCreate` delivers the creating intent unless the activity was restored.
- **The update notice.** `BuildInfo.appStoreUrl` becomes `Layer.UpdateRequired.storeUrl`, and
  `CreateEventScreen` renders it as "Open the App Store" whatever the platform.

## Goals / Non-Goals

**Goals:**

- One deployment value turns every Play offer on: the site's two badges and Android's update notice.
- The referrer is decided in pure, JVM-tested code. The Android-only part is a thin reader whose
  once-per-install bookkeeping is device-tested behind a seam.
- `/join` stays byte-identical for every event. The referrer exists only at runtime in the visitor's
  browser.

**Non-Goals:**

- Platform detection on the site. Both badges show on every device.
- Referrers for anything but an invite. There is no campaign or attribution tracking.
- Restyling the existing App Store button into Apple's official badge.
- Rewriting the Privacy Policy for Android as a whole (push via FCM, Keystore attestation) and the Play
  data-safety form. Those are phase 5g. This change adds only the referrer's own disclosure.
- A real end-to-end referrer test. That needs a published listing (phase 6).

## Decisions

### D1. `playStoreUrl` is a literal, validated against the package name

It is a new inventory key with the renderings `SITE` and `PROPS`. It is not rendered to `JSON`: the api
never links to a store now that `/join` is a constant page. The key lives in
`deployments/components/android.json` as `""`, following the precedent the Firebase keys set ("EMPTY
until …"). An empty value means dormant everywhere: no site badge, and `BuildConfig.PLAY_STORE_URL`
empty, which leaves the Android update notice without a button. The resolver refuses a non-empty value
that is not exactly `https://play.google.com/store/apps/details?id=<androidPackageName>`, because the
site appends `&referrer=…` to it and a second query would break that.

- *Alternative: derive the URL from `androidPackageName` plus a `playPublished` boolean.* This avoids the
  duplication, but it breaks the symmetry with `appStoreUrl` that the site component and `BuildInfo`
  mirror. The validator removes the drift risk instead.

The same edit corrects `appStoreUrl`'s doc: it has two readers now, not three.

### D2. The Play badge is Google's official art, served same-origin, and absent when dormant

`PlayStoreButton.astro` renders nothing when `deployment.playStoreUrl` is empty. When it is set, it
renders an `<a>` like `AppStoreButton`'s (`target="_blank" rel="noopener noreferrer"`, with an
`aria-label`), wrapping Google's official "Get it on Google Play" badge (English). The art is committed
under `site/src/assets/` and imported, so Astro emits it into `_astro/`, same-origin as
`check-selfcontained.ts` requires. It sits beside the App Store button in a shared row that wraps on
narrow screens, with its visible height matched to the App Store button's. Google's usage rules apply:
the badge is unmodified, keeps its clear space and minimum size, and is used only as a link to the
listing. `noreferrer` also keeps the page's own address out of the request to Play.

- *Alternative: a hand-built button matching the App Store button.* Google's brand terms require the
  official badge for "Get it on Google Play", so this was rejected. The resulting visual mismatch with
  the custom App Store button is accepted. Replacing that button with Apple's badge is out of scope.

### D3. The `/join` island rewrites only the `#app` view's badge, with the canonical payload

`decodeFragment` returns `{ eventId, d }` instead of only `eventId`. On a successful decode, the island
sets the href of the Play badge inside `#app` to
`<playStoreUrl>&referrer=<encodeURIComponent("v=3&d=" + d)>`. The badge inside `#invalid` is never
touched, so an invite that decodes but whose event answers 404 (the island switches to `#invalid`) hands
Play nothing. The referrer is rebuilt from `v` and `d` alone. It is not the raw fragment, so anything
else a hand-edited link carries never reaches Google. The pure part, building the href from the
deployment URL and the fragment, is a small module under `site/src/lib/`. That keeps it testable without
a DOM (D7).

### D4. The referrer-to-invite translation is pure and lives beside the codec

`inviteLinkFromInstallReferrer(referrer: String): String?` goes in `:domain:model`'s `EventLink.kt`. It
decodes `"$LINK_ORIGIN/join#" + referrer` with the existing `decodeEventUrl`. On success it answers
`encodeEventUrl(payload)`, the canonical link. On anything else it answers `null`: an organic
`utm_source=google-play&utm_medium=organic`, garbage, a wrong version, or an empty referrer. Play hands
the referrer to the app URL-decoded once, so the input is exactly the `v=3&d=…` the page encoded.

This is the reason a not-an-invite referrer must be filtered before the Links port. The core reports an
unparsable link as a damaged invite. Every organic install would then open on an error.

### D5. A thin reader on Android, triggered from the first activity creation, at most once per install

`AndroidInstallReferrer` sits in `:adapter:android`'s `link/` package. It depends on the Play Install
Referrer library, reached through an internal `ReferrerSource` seam that answers `Referrer(String)`,
`NeverAvailable` (FEATURE_NOT_SUPPORTED) or `TryLater` (SERVICE_UNAVAILABLE, a disconnect or a developer
error). It records "handled" in its own small `SharedPreferences` file. This is a platform quirk's
bookkeeping (Play re-serves the same referrer for 90 days), like `AndroidLinks`' `restored` check. It is
not a service's state, so it does not go through the `Preferences` port.

- If the record says handled, it does nothing.
- On `Referrer`, it translates (D4). A link is delivered through `AndroidLinks` as a web link on the hook
  `installReferrer`, and the record is written after the delivery returns. A `null` translation is
  recorded without delivering.
- `NeverAvailable` is recorded. `TryLater` is not, so the next foreground start asks again.

**Trigger.** `MainActivity.onCreate` (not restored) calls the root. The root runs `PlatformAdapters`'
new `installReferrer` hook once per process. The production adapter set supplies the reader, and the rig
set supplies a no-op (a rig build has no Play and may have mocked Links). Starting from the activity,
rather than from `Application.onCreate`, guarantees a screen for the join screen to open on. A
background start (a push or work) never consumes the referrer.

**The Links port does not learn the source.** The delivery is an ordinary link. The core's existing rules
(acted on once, a different invite replaces the open one, reopening the current event changes nothing)
apply unchanged.

- *Alternative: read in `Application.onCreate`.* That would be earlier, but it could run on a background
  start with no UI, so it was rejected.
- *Alternative: record before delivering.* A crash between the two would lose the invite silently.
  Recording after means a crash re-offers it once, which the user can dismiss. Losing it was judged
  worse.

### D6. The build reports which store its link is for

`BuildInfo.appStoreUrl: String?` becomes `BuildInfo.store: StoreLink?`, with `StoreLink(url, kind)` and
`StoreKind { APP_STORE, GOOGLE_PLAY }` in `:domain:model`. Keeping the URL and its kind in one value means
they cannot be half-set. iOS answers `APP_STORE` over `bakedAppStoreUrl()`. Android answers
`GOOGLE_PLAY` over `BuildConfig.PLAY_STORE_URL`, or `null` when it is empty. `Layer.UpdateRequired`
carries the `StoreLink`, and `CreateEventScreen` labels the button from `kind` ("Open the App Store" /
"Open Google Play"). The copy stays in the UI.

- *Alternative: infer the store from the URL's host.* Fragile. Rejected.
- *Alternative: keep `appStoreUrl` and add a `storeKind` field.* It allows a URL with no kind. Rejected.

### D7. Testing before Play exists

| What | Where | Proves |
|---|---|---|
| D4 translation | `:domain:model` commonTest | valid → canonical link; organic, garbage, wrong version, empty, extra params → `null` / canonicalised |
| D1 key | `scripts/resolve_deployment_test.py` | renders to site and properties only; empty accepted; a foreign URL or an extra query refused |
| D3 href | `site/src/lib` module + a `deno test` wired into the site's `check` (so `site-build` runs it) | referrer present and encoded only for a valid fragment; plain URL otherwise; empty URL → no badge |
| D5 reader | `:adapter:android` `androidDeviceTest`, over a fake `ReferrerSource` and the real `SharedPreferences` | delivered once; not after handled; organic recorded but not delivered; `NeverAvailable` recorded; `TryLater` retried |
| D6 label | `:ui:screens` + `:domain:presentation` tests (the existing version-gate tests) | the button's label follows the kind; no button without a link |

The real `InstallReferrerClient` answering on a Play-installed build is the one thing untested until the
listing is public (phase 6). The seam is small enough that the gap is only the library's own behaviour.
The reader is not a port and has no port contract: it is an adapter-internal detail of the Links adapter,
and what the Links port promises is unchanged.

## Risks / Trade-offs

- [A remote install from Play's website carries no referrer] → Accepted and spec'd: the guest opens the
  invite again, as on iPhone.
- [Clearing the app's data within Play's 90 days re-offers the invite once] → Harmless. It is the same
  invite, it opens the join screen, and nothing is joined without confirmation. Documented in the
  reader's KDoc.
- [Google learns the event's identity, the event's key, when a visitor follows the button] → Accepted by
  the user, stated in `privacy-security` and in the site's Privacy Policy, and limited to a valid invite
  followed by that visitor's own tap.
- [The Play Install Referrer library under R8 (phase 5b)] → The library ships consumer keep rules. The
  5b store build is exercised by 5d. There is nothing to add here, and it is checked there.
- [Mismatched badge styles on the site] → Accepted (D2).
- [A different invite already open when the referrer arrives] → The core's "a different invite replaces
  the open one" applies. This needs a fresh install plus an invite tapped before the first activity
  starts, which is practically unreachable.
- [Phase 7 (`live-motion-unification`) also edits `:adapter:android`] → Expect a rebase at most. There is
  no overlap in files beyond the Gradle dependency list.

## Migration Plan

Merging ships dormant: `playStoreUrl` is `""`, so the site and the app behave as today, apart from the
update-notice refactor (same label on iPhone). Phase 6 sets the value in `android.json`, which turns on
both site badges on the next site deploy and the Android update button in the next Android build. Rolling
back means emptying the value again.

## Open Questions

- The badge's localisation. Only the English badge ships, matching the site's single language. This is
  deferrable.
