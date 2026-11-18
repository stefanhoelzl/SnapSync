# Tasks

## 1. Measure on the SE2 first (design D4)

- [x] 1.1 Under the SE2 lock, on a rig build, read `canOpenURL` for `https://snapsync.stho.net` with and without
  `http`/`https` in `LSApplicationQueriesSchemes`. Measured with 2.3's own after-refusal read, built once without and
  once with the declaration, rather than a throwaway probe — it asks the same question and leaves nothing to remove.
  Verify: the two answers are written into design.md D4.
- [x] 1.2 Under the same lock, turn Screen Time's Content & Privacy Restrictions → Allowed Apps → Safari off (ask the
  user to do this on the phone), then tap the menu's Privacy Policy and read `openURL`'s answer from `debug.log`.
  Turn Safari back on. Verify: the answer (and whether the page opened anyway) is written into design.md D4 as the
  measured test of the Screen Time hypothesis.

## 2. The port carries the refusal's circumstances (design D3)

- [x] 2.1 In `core/ports`, give `Handoff.Refused` a platform-neutral `circumstances` value with three facts (in front,
  any app claims it, scene), each read or "could not read". Rewrite `Handoff`'s KDoc, which says the app acts on
  neither answer. Update `adapter/generic/mock`'s refusals and `AndroidSystemUi.start` to fill it from what each
  already knows. Verify: `./gradlew compileIosMainKotlinMetadata` and the ports' and mock's tests pass.
- [x] 2.2 In `SystemSurfaces.recorded`, log the facts as fields on the unchanged "nothing was handed off" `Error`
  line, and return the `Handoff`. Verify: `SystemSurfacesTest` gains a case asserting the line's event, level and
  three fields for a refusal with circumstances, and still passes for an accepted one.
- [x] 2.3 On iOS, add the after-refusal read to `UrlOpenerApi` (application state, `canOpenURL`, the window scene's
  activation state, on the main queue). Add it to the recording and replaying implementations in
  `HandoffContracts.kt`, and add `http`/`https` to `LSApplicationQueriesSchemes` only if 1.1 showed it is needed.
  Verify: `IosLinkOpenerReplayContractTest` passes against a re-recorded `LinkOpener@IOS_DEVICE_APP.rec` (SE2 lock,
  `rig contract run --name LinkOpener`), committed unedited. If 1.2 showed a refusal under the restriction, add a
  `LinkOpener` clause for it and record it too.

## 3. The command answers, and the member is told (design D1, D2)

- [x] 3.1 Add `OpenLinkRequest(id, link)` and `LinkOutcome { OPENED, NOT_OPENED }` beside `ReportRequest`/
  `ReportOutcome`. `OnOpenLinkTapped` answers on a `RequestStatusSink<LinkOutcome>`, with `ensure("answer")` settling
  `NOT_OPENED`; wire the status through the composition as the report's is. Verify: entry points have no unit tests
  by law — `:core:entrypoints` is covered by the integration suite (`docs/testing.md`, "What it covers") — so 3.4's
  integration tests are this task's check: `OPENED` for an opened page, `NOT_OPENED` for a refused one.
- [x] 3.2 Move `ReportFlow`'s clear-after-a-moment and dismiss mechanics into one presentation helper used by both
  notices. Add `Overlays.linkNotice: LinkNotice?` (site with its address, or a store) and `UiIntent.LinkNoticeDismiss`.
  `Surfaces.onOpenLink` and `StatusContainerHost.onOpenAppStore` await the answer and set the notice only for
  `NOT_OPENED`. The newer notice replaces the older one in the slot. Verify: `NoticeTimersTest` covers both notices
  (shown, cleared after the interval, tapped away, a newer one replacing an older one), and `StatusContainerHostMenuTest`
  shows no notice for an opened link. `UiStateSerializationTest` and `UiStateFieldContractTest` are updated for the
  new field.
- [x] 3.3 In `:ui:screens`, draw `linkNotice` with `AppNotice` in the report notice's slot, words in `strings.xml`
  (English and German) naming the site's address or the store by name. Verify: a screen test renders both notice
  kinds, and `AppMenuScreenTest` still passes. The site notice is reviewed on the SE2 itself, under the real refusal
  of 1.2 — the world inspector has no lever that refuses a page (nor one for a refused share), so the harness cannot
  reach this state.
- [x] 3.4 In a new `RefusedLinkIntegrationTest`, with the mock's `unclaimed` lever, claim the scenarios of
  "A page the phone will not open is told" with `@Verifies(spec = "app-experience", …)`: the Privacy Policy refused,
  the notice clearing itself, the App Store refused on the update-required notice, and the website opened with no
  notice. Verify: the four tests pass.

## 4. Contract and gate

- [x] 4.1 Sync the delta into `openspec/specs/app-experience/spec.md` in the same PR, so the claims gate finds the
  requirement its four claims name. Verify: `./gradlew build` is green (needs `deno` on PATH), and the tree it leaves
  is clean (`architecture/` regenerated and committed).
- [ ] 4.2 On the SE2 (lock), install the PR's rig build, set `unclaimed`-free real conditions, and confirm by finger
  that both menu links still open Safari with no notice. Verify: `debug.log` shows `OnOpenLinkTapped` with no "nothing
  was handed off" line. The PR description names SNAPSYNC-71 as evidence and carries no `Bugsink-Resolves` trailer
  (design D5).
