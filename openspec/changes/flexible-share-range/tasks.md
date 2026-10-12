# Tasks

## 1. The range rule: one check, refuse instead of clamp

- [x] 1.1 Replace `clampToFloor`/`clampToCeiling` in `domain/model/.../Cutoff.kt` with one pure share-range check (design D1: `from ≤ until`, at most the event-length limit, `until ≥ startsAt`, `from ≤ endsAt`), reusing the create screen's 30-day constant; verify model unit tests for each bound, including a range exactly 30 days long, one touching the event only at its start instant, and one a minute too long
- [x] 1.2 `JoinEvent` refuses an invalid `JoinChoice` (a join failure, logged at Error) and persists a valid range unchanged; verify feature tests in `:test:feature`: a range starting before the event is stored as chosen, a 31-day range and a non-overlapping range are refused with nothing joined, and the rig's honoured link hint with a bad range is refused
- [x] 1.3 `ReconfigureEvent` applies the same check (an invalid change applies nothing, logged at Error) and no longer re-clamps to `startsAt`/`endsAt`; verify feature tests: a widened start before the event is kept, an invalid change leaves the config untouched, and the narrowing confirm still fires for a later start
- [x] 1.4 Make the start gate explicit (design D7): the config door of `selectionRulesFor`/`selectionPolicyFor` takes `now` and floors at `max(minPhotoDate, startsAt)` while the event has not started; both uploaders' derivations (`SnapSyncApp`, `CycleGateRead`) pass `ConfigService`'s clock; verify model tests (before the start nothing earlier than the start is admitted; after it, the member's earlier floor is) and a feature test that a pre-start photo in range is not uploaded before the start and is after it
- [ ] 1.5 Update the KDoc that states the clamp invariant (`EventConfig`, `EventDates`, `JoinChoice`, `UserCommands`, `StatusContainerHost` NB comment) and remove the now-unused clamp helpers; verify `./gradlew build` (detekt tiers, no dead code)

## 2. Settle at the membership's own end (app + api)

- [x] 2.1 Move the settle condition fed to `DeviceManifestProducer.produce(settled = …)` from the event's end to the membership's `maxPhotoDate`, still requiring a discovery since then (design D4); verify feature tests: a range ending before the event settles before the event's end, one ending after it does not settle at the event's end, and widening past now publishes `final = false` again
- [x] 2.2 Keep `EventCompletion`'s receive-side report gated on the event's end, and confirm in its KDoc why (design D4); verify the existing completion tests still pass unchanged
- [x] 2.3 `api/src/routes/v2.ts` `applyPublish` stores `final` as declared (drop `&& ended`) and its KDoc says why; verify api tests: a final publish before the event's end sets `settled`, the event is not finished before its open-until date, and a non-final publish after it flips back to `sharing`
- [x] 2.4 Bring `BackendMock` to the same rule so the JVM rig matches the real api/; verify the Backend contract runs green against both the mock and `LiveEdge`

## 3. Received-photo recognition beyond the event's dates

- [x] 3.1 `MarkedPhotoLookup.markedIn`/`markedAmong` take a capture-date span instead of `EventStart`/`EventEnd`; `ReceivedPhotoAdoption.adoptOrFail` passes the min…max `creationDate` of the open foreign refs (design D5); verify a feature test: after a simulated reinstall, a marked received photo dated before the event's start is adopted, not shared, and not downloaded again
- [x] 3.2 `beforeSelectionPublished` withdraws recognition on any unknown marked primary, whatever its date, and never for photos the download store records; verify feature tests: a newly selected marked photo dated before the event pauses sharing until the full pass, and the app's own import does not
- [x] 3.3 Update `ReceivedPhotoAdoption`'s KDoc (the window it reads, the pre-check's rule); verify `./gradlew build`

## 4. Presentation: presets, bounds and the outside-reach fact

- [x] 4.1 Add `RangeBounds.aroundEvent(start, end, longest)` with an earliest end per start (design D2), and use it for join and reconfigure in place of `within`; verify `range/` tests: start days `start − 30 d … end` are pickable, end days are limited to `max(from, start) … from + 30 d`, a drag or wheel never yields an invalid range, and the create screen's `lastingAtMost` behaviour is unchanged
- [ ] 4.2 Rename `RangeChoice.WHOLE_EVENT` → `EVENT_START` across `:ui:state`, presentation (`RangeResolution`, `RangeForm`, `PendingJoin`) and the rig client; a reconfigure range equal to the event's dates resolves to `EVENT_START`; verify presentation reducer tests and `:test:control` compile and pass
- [x] 4.3 `RangeView` gains the outside-reach fact (`BEFORE`/`AFTER`/`BOTH`/none) and the event-day span for the calendar view, computed in presentation from `from/until` against the window; verify presentation tests for each case, including a range touching the event's start exactly (none)

## 5. Screens: chip row, calendar-only dialog, copy

- [x] 5.1 `AppShareRangeRow` becomes the chip row (From event start · From now when offered · Custom) with the range label and count beneath, no bordered row and no ✎; tapping a selected Custom reopens the calendar; verify `:ui:components`/`:ui:screens` click tests through `statusActions` for each chip, and that From now is absent outside the window
- [x] 5.2 `RangePickerDialog` drops its `presets` parameter and marks the event's days with a spoken label; verify a component test that the presets are gone, and a semantics test that an event day reads "…, event day"
- [x] 5.3 The outside-reach line under the count on the join screen and in settings; verify `:ui:screens` tests for before, after, both, and none
- [x] 5.4 Strings EN + DE per design D6: add `range_event_start`, `share_outside_before/after/both` and `calendar_event_day`; change `range_custom` (DE "Anpassen"), `share_detail_counting`/`share_detail_count` (no preset prefix), `access_range_body`, `create_window_note` and `share_range_change`; drop `range_whole_event`, `share_range_whole_event` and `share_range_from_now`; verify `./gradlew build` (HardCodedUiText, unused resources) passes
- [x] 5.5 Review every state in the world harness through the `ui-harness` driver (join screen with each chip, a custom range before and after the event, the calendar with marked event days, settings), in light and dark; verify by reading back `/phone.png` for each
- [x] 5.6 Refresh the marketing screenshots only if a `Shots.kt` state shows the join screen; verify `ShotsTest` in `./gradlew build`

## 6. Integration and outside the app

- [ ] 6.1 `:test:integration`: a guest joins with a custom range starting the day before the event, and the pre-start photos upload only after the start; a guest with a range ending two days after the event keeps the event unfinished until then; a share-only guest whose range ends early is done before the event ends; tag each test `@Verifies` with the delta scenarios it covers; verify the tests pass on the JVM rig with both backends
- [x] 6.2 Privacy policy (`site/src/pages/index.astro`, "Photos you share") and store listing (`metadata/listing/en-US.json`) per design D6; verify the `site-build` and `metadata` gates
- [x] 6.3 Restate `openspec/config.yaml` MISSION ("a chosen sub-range of the event window") and the CLAUDE.md overview ("lower bound clamped to the event start, upper to the event end") to the new rule; edit the Purpose paragraphs of `join-event` ("so a guest's whole camera roll is never uploaded") and `photo-sharing` ("never wider than the event's own dates"); verify `npx --yes @fission-ai/openspec@1.13.2 validate --specs --strict` and the two archive gates in `openspec/config.yaml`
- [ ] 6.5 At sync (the renames reach `openspec/specs/` only then, and `VerifiesGateTest` reads those): re-point every `@Verifies` on a renamed or replaced requirement — `event-lifetime` "A member's share is settled only after the event has ended" (EventCompletionIntegrationTest), `join-event` "The shared capture range always has a lower bound inside the event window" (JoinGateIntegrationTest), `photo-sharing` "Only photos taken inside the member's capture range are shared" (AdmittedSetIntegrationTest, NarrowedScopeIntegrationTest ×2, UploadCycleIntegrationTest), and add the new scenarios' claims from 6.1; verify `:test:architecture:test --tests '*VerifiesGateTest*'` after the sync
- [x] 6.4 Regenerate `architecture/` if any flow or module edge moved; verify `./gradlew architectureDiagrams` leaves no diff and `./gradlew build` passes
