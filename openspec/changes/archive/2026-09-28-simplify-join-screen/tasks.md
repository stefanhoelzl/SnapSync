## 1. One range preset (model + reduction)

- [x] 1.1 Replace `FromChoice`/`UntilChoice` with `RangeChoice { WHOLE_EVENT, FROM_NOW, CUSTOM }` and one custom pair in `RangeForm` (D1); update `UiIntent` range intents
- [x] 1.2 Rework `RangeResolution` for the three presets (FROM_NOW → `[now, windowEnd]`, only when `nowAvailable`; CUSTOM clamped into the window); update `RangeResolutionTest`
- [x] 1.3 Reconfigure pre-fill: saved range == window → WHOLE_EVENT, else CUSTOM with the saved bounds; test it
- [x] 1.4 Move the rig's `/user` range intents (`test/rig` `UserCommands`) and the desktop mirror to the new vocabulary; update the `rig-channel` skill if it names the old presets

## 2. Access asked on Join (reduction)

- [x] 2.1 Remove `JoinPhase.Detailed.Step.ExplainAccess` and `onAcknowledgeAccess`; `deriveLoadedPhase` always yields Ready
- [x] 2.2 Expose `asksAccessOnJoin` on the join layer (no event configured ∧ permission NOT_DETERMINED, live)
- [x] 2.3 `onConfirmJoin`: when `asksAccessOnJoin`, `requestAccess()` then `commit()`; `onRetryJoin` never requests; tests for both, and for a granted/denied user joining with no request
- [x] 2.4 Drop `ResolvedRange.deletesLocal` / `ReadyLabels.deletes` if nothing else reads them

## 3. Components

- [x] 3.1 Extend `DateTimeRangePickerDialog` with an optional preset-chip row (Whole event · From now when available); chip selects + closes, OK commits CUSTOM, Cancel changes nothing; create screen passes none; tests incl. a window spanning two months
- [x] 3.2 Range row component: dates, "<preset> · <count>" subtitle (Counting… / N photos / 0 photos + note / nothing when unavailable), ✎ opens the dialog bounded to the window; replaces `AppRangePresetChoices`
- [x] 3.3 A two-switch card (Share + Receive under a divider) in the design system
- [x] 3.4 Access-explainer sheet (the four-point card, 4th point "Only photos in the range you chose", "Got it" dismisses)

## 4. Screens

- [x] 4.1 `ParticipationSections`: the two-switch card with the range row + exclusions, album as its own switch card; delete the old count row / minor album row
- [x] 4.2 Ready surface: drop the retention note; for `asksAccessOnJoin` show the notice line with ⓘ (opens the sheet) and label the confirm "Join & allow photos"
- [x] 4.3 Remove the ExplainAccess phase composable from `JoinFlowScreens.kt`
- [x] 4.4 Reconfigure screen renders the new sections unchanged otherwise
- [x] 4.5 Update `JoinScreenTest`, `StatusScreenTest`, `HostStatusActionsTest`, `TestActions` and the reconfigure tests

## 5. Harness, integration, verification

- [x] 5.1 Forge presets: replace "Explain access" with "Ready (access not asked)"; add range presets for from-now and custom
- [x] 5.2 Update `:test:integration` join journeys that went through the explainer
- [x] 5.3 Review every join/reconfigure state in the forge via the `ui-harness` driver at 390×844: default fits without scrolling, first-timer, sharing off, both off, count states, dialog with/without "From now"
- [x] 5.4 `./gradlew build` green (detekt tiers: any ceiling raise carries its forcing proof), `architectureDiagrams` refreshed if flows changed
- [ ] 5.5 Re-capture marketing screenshots (`screenshots.yml`), eyeball, commit

## 6. Specs

- [x] 6.1 At sync/archive, update `join-event`'s Purpose: it no longer promises the guest "learns how long the event's photos are kept"
- [x] 6.2 Run both archive gates from `openspec/config.yaml` and `validate --specs --strict`
