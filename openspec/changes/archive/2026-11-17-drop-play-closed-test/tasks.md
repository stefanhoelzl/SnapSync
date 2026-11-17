# Tasks

## 1. Deployment resolver

- [ ] 1.1 Remove the `playTestGroupUrl` key from `scripts/resolve-deployment.py`'s inventory, together with `PLAY_TEST_GROUP` and `validate_play_test_group_url`, and remove the key from `deployments/components/android.json`; drop `SITE` from `androidPackageName`'s renderings, since only `play.ts` read it there; verify `python3 scripts/resolve-deployment.py` resolves `prod`, `maintenance` and `local`, and that `site/src/deployment.json` no longer carries the key
- [ ] 1.2 Make `validate_play_store_url` refuse an empty value (it must be exactly the listing of `androidPackageName`) and rewrite the `playStoreUrl` inventory doc without the "EMPTY until the Play listing is public" text; verify by `scripts/resolve_deployment_test.py`: remove `PlayTestGroupUrlTest`, drop `playTestGroupUrl` from the fixtures and the site-key list, and add a case refusing an empty `playStoreUrl`; the suite passes

## 2. Site

- [x] 2.1 Delete `site/src/lib/play.ts`, `site/src/components/PlayOffer.astro` and `site/src/components/PlayTesterSteps.astro`; make `PlayStoreButton` read `deployment.playStoreUrl` itself, as `AppStoreButton` reads `appStoreUrl`, and place it where `PlayOffer` stood (`index.astro`, both views of `join.astro`); verify the site builds (`npm run build` in `site/`) and `check-selfcontained.ts` passes
- [x] 2.2 Remove the `playOffered` conditions in `index.astro` (the install-referrer privacy paragraph) and `Layout.astro` (the Google trademark line), so both always render, and update the comments in `PlayStoreButton.astro` that describe the three states; verify the built landing page contains both texts
- [x] 2.3 Remove the `.playtest` rules and the `.cta:has(.playtest)` rule from `site/src/styles/theme.css`; verify by grep that no `playtest` class remains in `site/src`
- [x] 2.4 Move the scenario evidence (design D4): delete `site/scripts/play.test.ts`; in `pages.test.ts`, assert that the landing page and the event page link `deployment.playStoreUrl` (`@Verifies` web-site / event-site *Both stores are offered*, privacy-security *The landing page carries no invite*), retarget the invalid-link test to *Both stores are offered for an invalid link too*, remove `playHrefs()` (every page now links the one listing), and drop the referrer-on-listing test, which compared the derived listing with the public one; `invite.test.ts` already tests the referrer on the listing; rename every `requirement =` in `pages.test.ts` and `api/test/eventlink.test.ts` to the new requirement names (design D3); verify `npm run check:unit` in `site/` passes

## 3. App-side scenario evidence

- [x] 3.1 Drop the *Becoming a tester from an invite's page opens the join screen* `@Verifies` from `AndroidInstallReferrerTest` (retargeting it to *Installing from Google Play opens the join screen* and rewording the "tester steps" comment) and from `InstallReferrerIntegrationTest` (rewording its "tester's install" comment); remove the *Following the steps installs SnapSync* entry from `test/architecture/.../AcceptedUnclaimed.kt`; rename every `requirement =` naming "Without the app, the invite leads to a store and back" (`AndroidInstallReferrerTest`, `InstallReferrerIntegrationTest`, `InviteLinkIntegrationTest`) and every `AcceptedUnclaimed` entry naming "A public landing page describes SnapSync" to the new names (design D3); verify `./gradlew build` passes, including the spec-coverage gate, and that a grep for the three old requirement names outside `openspec/` finds nothing

## 4. Specs, docs and integration

- [x] 4.1 Edit the `## Purpose` of `openspec/specs/web-site/spec.md` directly: "(and to Google Play once SnapSync is published there, or the steps to get it there as a tester while a closed test runs)" becomes "and to Google Play"; verify by grep that no main spec mentions a closed test or testers' group outside this change's deltas once synced
- [x] 4.2 Update `docs/deployment.md`: the store-pages bullet (`playStoreUrl` is required, no "empty until public"), the Play closed-test bullet and production switch (replaced by a short history of the closed test's end with the 2026-11 production launch, citing its decision records), and the `android.json` key list in the layout table; verify by grep that `playTestGroupUrl` appears nowhere outside `openspec/` but in that history
- [x] 4.3 Run `./gradlew build` (Deno on PATH) and confirm it passes and leaves the tree clean
