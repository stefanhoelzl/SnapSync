# Tasks

## 1. Model: the vocabulary the screen shows

- [x] 1.1 Add `Overlays.menuOpen`, `Overlays.reportNotice: ReportOutcome?`, `ReportOutcome { SENT, SAVED, NOT_SENT }`
      and `UiState.build: BuildLabel(version, buildNumber)` in `:domain:model`. Verify: UiState round-trips through
      its serializer in the existing model tests (extended for the new fields).
- [x] 1.2 Add the intents `MenuOpen`, `MenuDismiss`, `OpenLink(kind)` (`WEBSITE`, `PRIVACY_POLICY`) and
      `ReportNoticeDismiss` to `UiIntent`, and the link builders `websiteUrl()` / `privacyPolicyUrl()` over
      `LINK_ORIGIN` beside `EventLink` (design D5). Verify: a model unit test pins both URLs to the generated origin
      (`/#privacy` for the policy).
- [x] 1.3 Change `UserCommands.sendDiagnostics` to return `ReportOutcome` (design D7), and update every
      `UserCommands` builder (compose, rig, test fakes) so that `./gradlew compileKotlinJvm
      compileIosMainKotlinMetadata` passes.

## 2. Composition: answer the result and the build label

- [x] 2.1 In `UserCommandsComposition`, map `DumpResult` to `ReportOutcome` (Sent → SENT, Saved → SAVED,
      NotSent → NOT_SENT) and return it, keeping the existing log line. Verify: the mock-driven compose test (or a
      new one beside it) asserts each mapping, with the reporter configured, unconfigured, and with a refused file
      write.
- [x] 2.2 Fill `UiState.build` in `ComposedApp` from `BuildInfo.appVersion` and `diagnostics.buildNumber`, the
      same way `reportDestination` is filled. Verify: a `:test:integration` scenario reads the JVM host's
      `UiState.build` and finds `BuildInfoMock`'s values.

## 3. Presentation: the reduction

- [x] 3.1 Route the new intents in `UiIntents.kt` / `StatusContainerHost`: menu open and dismiss; "Report a
      problem" from the menu sets `menuOpen=false, reportingBug=true` in one edit (design D2); `OpenLink` goes to
      `commands.openLink(url)`. Verify: `StatusContainerHostSurfacesTest` covers open → report → dismiss and leaves
      no overlay set.
- [x] 3.2 Derive "menu available" and mask `menuOpen` where it is false: the reconfigure surface,
      `CreatingEvent`, and join `Committing` (design D3). Verify: host tests check that a create in flight hides
      the menu and closes an open one, and that update-required, join `LoadFailed` and the closed event keep it.
- [x] 3.3 Make `onSendDiagnostics` await the command, set `reportNotice` to the outcome, and clear it after a short
      timeout or on `ReportNoticeDismiss`. A later outcome replaces an earlier one (design D7–D8). Verify: host tests
      on a virtual clock check that each outcome sets the notice, that it clears after the timeout, that a tap
      dismisses it, and that cancelling the sheet sets nothing.

## 4. UI: button, drawer, notice

- [x] 4.1 In `:ui:components`, add the `onMenu` slot to `ScreenLayout` (an `IconButton` ☰ at the start of the title
      row, label still centred, content description "Menu"; the double-tap is unchanged) and an `AppMenuDrawer` over
      Material 3's `ModalNavigationDrawer`. Swipe-to-open is off while the drawer is closed. Verify: the components'
      jvmTest renders both and finds the "Menu" control in the semantics tree, and `ScreenHeadingTest` still passes.
- [x] 4.2 In `:ui:screens`, host the drawer in `StatusScreen`: "Report a problem" (set apart), "Website", "Privacy
      policy", and the footer "Version <v> (<build>)", not clickable. Wire `statusActions(dispatch)` to the new
      intents. Verify: `StatusScreenTest` clicks ☰ and each item and asserts the dispatched intents, and checks that
      the button is absent on the reconfigure surface and while a create is in flight.
- [x] 4.3 Render `reportNotice` as a short message at the bottom of the screen with the copy for sent, saved and
      could-not-send (calm wording, no claim of delivery). Verify: `StatusScreenTest` renders each outcome and
      taps to dismiss.
- [x] 4.4 Keep `DiagnosticDumpGestureTest` green, and update KDoc that calls the report "hidden" (StatusActions,
      UiState `Overlays`, ScreenLayout). Verify: `./gradlew :ui:screens:jvmTest :ui:components:jvmTest`.
- [x] 4.5 Run the detekt tiers. If a ceiling in `config/detekt/ui.yml` would rise, bundle the inputs instead, or
      state the forcing proof in the PR. Verify: `./gradlew detektUiTier detektComposeTier` passes.

## 5. Test surfaces and integration

- [x] 5.1 Classify the new host intents in `:test:rig`'s `/user` table (`rig/UserCommands.kt`). Following the rig's
      rule for overlays, they go in the exclusions with their reasons; the report stays `/user/sendDiagnostics`, and
      the notice is read from `/device/state`. Verify: `:test:control`'s tests pass.
- [x] 5.2 Make the drawer and the notice reachable in the desktop world harness through the real taps; no forged
      state. Verify: drive the harness headlessly (`ui-harness` skill): open the menu, send a report, and see the
      notice for each reporter setting.
- [x] 5.3 Extend `DiagnosticDumpIntegrationTest`: a report reaches the reporter mock with its note, the notice says
      sent and then clears, and the screen names the running build. The JVM rig host always composes with a reporter
      (`JvmRigHost`'s DSN), so the saved and could-not-send words are pinned one level down instead: the model's
      `DumpResult` → `ReportOutcome` mapping and presentation's per-outcome notice tests.
      Verify: `./gradlew :test:integration:test --tests '*DiagnosticDump*'`.

## 6. Site, screenshots and gates

- [x] 6.1 Rewrite the site's privacy text (`site/src/pages/index.astro`, "Bug reports you send") to name the menu's
      "Report a problem" as the way in. Verify: `site-build` passes locally (`cd site && npm run build`, or the
      repo's site task) and the rendered section reads correctly.
- [ ] 6.2 Run `./gradlew build` and `./gradlew architectureDiagrams`, and commit any changed diagrams. Verify: the
      build is green and `git status` shows no stale `architecture/`.
- [ ] 6.3 Re-capture the marketing screenshots (`gh workflow run screenshots.yml --ref <branch>`, both the iOS and
      Android artifacts), check every image by eye (☰ present, no system notification), and commit. Verify: the six
      iOS and six Android raws show ☰ in the title row.
- [x] 6.4 Run `npx --yes @fission-ai/openspec@1.13.2 validate app-menu-bug-report --strict` and the spec-identifier
      grep from `openspec/config.yaml` on both touched specs. Verify: valid, and no code identifiers.
