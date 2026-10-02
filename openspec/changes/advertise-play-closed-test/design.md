# Design

## Context

See proposal.md, "Why". The state of the tree this design starts from:

- **`playStoreUrl`** (`deployments/components/android.json`, `""`) is rendered to `SITE` and `PROPS` by
  `scripts/resolve-deployment.py`. The resolver accepts it only empty or exactly
  `https://play.google.com/store/apps/details?id=<androidPackageName>`, because `/join` appends
  `&referrer=…`. `androidPackageName` is rendered to `JSON` only, so the site does not see it today.
- **Five places on the site** read `deployment.playStoreUrl`: `PlayStoreButton.astro`, which renders
  nothing while it is empty and is used on the landing page and in both `/join` views; the Privacy
  Policy's referrer paragraph (`index.astro`, around line 363); and the footer's Google Play trademark line
  (`Layout.astro`, around line 121). The `/join` island rewrites the `#app` view's badge href through
  `playHrefFor` (`src/lib/invite.ts`), keyed on `data-play-store`.
- **The site deploys on every push to `main`** (`deploy.yml`'s `site` job). Site values are baked at site
  build time; there is no api or app build involved.
- **The closed track** (`PLAY_TRACK: alpha`) holds build 2118 (0.4), approved 2026-10-02, available in
  Germany only (`metadata/play/declarations.md`). Its tester list is the Google Group `snapsync-beta`
  (anyone can join).
- **Play addresses.** Opt-in page: `https://play.google.com/apps/testing/<package>`. The listing is the
  same `store/apps/details?id=<package>` address for testers and, after launch, for everyone. A non-tester
  following it during the closed test gets Play's "not found".

## Goals / Non-Goals

**Goals:**

- One deployment value turns the tester steps on, and the existing `playStoreUrl` turns them off. The
  production flip is an edit to `android.json` only.
- The event page's install-referrer path keeps working through the steps, unchanged.
- Every place that already follows "is Google Play offered" (badge, privacy paragraph, trademark line)
  follows the same single condition.

**Non-Goals:**

- The Android app. Its update notice keeps reading `playStoreUrl` through `PROPS` and stays without a
  button until launch.
- A separate beta page. The steps are inline.
- An iOS beta (TestFlight public link).
- Platform detection. The steps show on every device, as the badge does.
- Counting testers or showing progress. The Play Console does that.
- `promote.yml`'s track. Changing `PLAY_TRACK` to `production` is the flip's own `internal` change.

## Decisions

### D1. One new key, `playTestGroupUrl`; the opt-in and listing addresses are derived

`playTestGroupUrl` is a new inventory key with the rendering `SITE` only. It lives in
`deployments/components/android.json` as `https://groups.google.com/g/snapsync-beta`. `androidPackageName`
gains the `SITE` rendering, and the site builds the opt-in page
(`https://play.google.com/apps/testing/<package>`) and the listing
(`https://play.google.com/store/apps/details?id=<package>`) from it.

The resolver enforces:

- `playTestGroupUrl` is empty or matches `https://groups.google.com/g/<name>`.
- `playTestGroupUrl` and `playStoreUrl` are not both set. A published app with leftover tester steps
  would contradict the spec's "with no tester steps".

The site's single derived condition is `playOffered = playStoreUrl !== "" || playTestGroupUrl !== ""`.
The listing address is `playStoreUrl` when set, otherwise the derived listing. Both are the same string
by the resolver's rule, so the referrer append (`&referrer=`) stays valid.

- *Alternative: three keys (group, opt-in, listing).* The opt-in and listing addresses are fixed
  functions of the package name, so storing them only adds drift. Rejected.
- *Alternative: a `playBeta` boolean plus a group key.* The group key's presence already says "beta". A
  boolean that could be true with no group to join adds a state that renders nothing useful. Rejected.
- *Alternative: render the derived listing as a resolver output.* The resolver renders keys, not derived
  values, and the site already derives URLs in `src/lib`. Rejected for consistency.

### D2. Step 3 is the existing Google Play badge

The tester steps are a new `PlayTesterSteps.astro` that renders steps 1 and 2 as links with short
explanations, then the existing `PlayStoreButton` as step 3. `PlayStoreButton` renders when `playOffered`
is true, linking to the listing and keeping `data-play-store`. So:

- The `/join` island's `#app` rewrite and `playHrefFor` are unchanged. The invite still rides only on the
  `#app` view's badge, only for a valid invite.
- `privacy-security`'s "the event page's Google Play button" stays literally true, so that spec needs no
  change.
- At the flip, `PlayStoreButton` is already what renders. The steps component simply stops rendering.

Each page places one `PlayOffer.astro`, which renders `PlayTesterSteps` when `playTestGroupUrl` is set,
`PlayStoreButton` alone when `playStoreUrl` is set, and nothing otherwise. The landing page and both
`/join` views swap their `<PlayStoreButton />` for `<PlayOffer />`.

- *Alternative: a plain text link for step 3.* That would split "the Google Play button" into two
  things, and the privacy and join-event specs would have to name both. Rejected.

### D3. The steps' copy

The copy lives in the component, not the spec. It must say:

- Step 1: join the `snapsync-beta` group with the **same Google account the phone's Play Store uses**.
- Step 2: tap "Become a tester" on Google Play's page. It can take a few minutes to apply.
- Step 3: install from Google Play. The app is available in **Germany** only for now. The badge sits
  centred under the list, in line with the App Store button above it.

Every link opens separately (`target="_blank" rel="noopener noreferrer"`, an `aria-label` saying so), per
`web-site`'s external-links rule. Steps 1 and 2 carry nothing from the page, so the invite is never
handed to Google Groups or to the opt-in page.

### D4. The privacy paragraph and the trademark line follow `playOffered`

The Privacy Policy's referrer paragraph and the footer's Google Play trademark line switch from
`playStoreUrl` to `playOffered`. During the closed test, step 3 on `/join` hands the invite to Google
Play exactly as the badge will after launch, so the disclosure must already be on the site. The badge art
is shown in both modes, so the trademark notice is needed in both.

### D5. Testing

| What | Where | Proves |
|---|---|---|
| D1 key and rule | `scripts/resolve_deployment_test.py` | renders to site only; empty accepted; a non-groups URL refused; both keys set refused; `androidPackageName` reaches the site |
| D1 derivation | a `site/src/lib` module + its `deno test` in `check:unit` | listing and opt-in addresses from the package; `playOffered` truth table; `playHrefFor` over the derived listing appends the referrer |
| D2/D4 rendering | `npm run build` with each of the three deployment states, grepping the built HTML | steps / badge only / nothing; privacy paragraph and trademark line present exactly when Google Play is offered |
| Self-containment | `check:selfcontained` | the new links are off-origin links, not subresources |

The real path (join the group, opt in, install, first launch opens the invite) is checked by hand on the
A40, only if the user agrees. It is the one thing no gate can reach.

## Risks / Trade-offs

- [A tester abroad opts in but cannot install] → The closed track is Germany only, and step 3 says so.
  Widening countries is a store decision, out of scope.
- [A visitor uses a Google account other than the phone's] → Play shows "not found" on the phone. Step 1's
  copy names the requirement.
- [Workspace accounts blocked from joining an outside group] → Not mentioned on the page (the user
  chose to keep step 1 short). Such a visitor sees the group refuse them and can retry with a personal
  account.
- [Opt-in takes minutes to apply] → Step 2's copy says so. Nothing to build.
- [The install referrer is lost on the detour through steps 1 and 2] → It is not carried through them at
  all. Only step 3's link carries it, and a fresh listing tap is the same path as after launch. Not yet
  measured on a closed listing; the A40 check above covers it.
- [Group or opt-in page addresses change] → Both are Google's long-standing public formats. If one
  changes, it is a config or one-line fix.
- [Forgetting to clear `playTestGroupUrl` at the flip] → The resolver refuses both set, so the flip PR
  cannot merge half-done.

## Migration Plan

Merging switches the steps on at the next site deploy, since `playTestGroupUrl` ships set. Rolling back
means emptying it. At production launch, one `android.json` edit empties `playTestGroupUrl` and sets
`playStoreUrl`. That one value also gives the Android update notice its button in the next Android build.
In the same `internal` change (or just before it), `promote.yml`'s `PLAY_TRACK` becomes `production`, if
the production release goes through the workflow rather than the Play Console.
