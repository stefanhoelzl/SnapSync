# Tasks

## 1. End time: hour and minute (pure rules)

- [x] 1.1 Replace `EventRange.untilTime` with `untilHour` / `untilMinute` (`until` only when both set); update `untilAllowed`, `keepValidTime`, `restartAt`, `isValid` and the KDoc in `EventRange.kt` / `EventRangeDays.kt` / `RangeBounds.kt` (D4) — verify `./gradlew :ui:components:jvmTest` compiles and the existing `EventRangeTest` cases pass after mechanical updates
- [x] 1.2 Rework `settleUntilHour` (sets the hour only; a chosen minute kept while valid, else cleared under `blankEndTime` / moved when always set), add `fillUntilHour(now)` (now's hour or nearest allowed), and make `settleUntilMinute` require an hour — verify new `EventRangeTest` cases: hour alone leaves `until == null`; minute-first fills now's hour; now's hour invalid lands on the start's hour; join bounds (`blankEndTime = false`) keep a complete end through every settle

## 2. End-time wheels (UI)

- [x] 2.1 Give `SettlingWheel` an `onDragStart` callback and show `--` only while `blank && !dragged` (D5) — verify an `AppEventRangePickerTest` case: a swipe on the blank Until minute wheel shows numbers on the reading line mid-drag
- [x] 2.2 Thread `now` into `AppEventRangePicker` / `RangeEditor`, call `fillUntilHour` on the Until minute wheel's drag start, and render the Until hour / minute independently blank — verify `AppEventRangePickerTest`: minute drag fills the hour with now's hour and its settle completes the end; hour settle leaves the minute "--" and the summary still says "pick a time"
- [x] 2.3 Keep the join / settings dialog unchanged — verify `RangePickerDialogTest`, `JoinScreenTest` and the settings tests pass without changing their expectations

## 3. Tappable next steps (UI)

- [x] 3.1 Add `onPickEndTime` to `AppEventRangePicker`: the Ends summary is a `Role.Button` click target only while the end is incomplete; add a `BringIntoViewRequester` on the From/Until row and a highlight pulse on the Until box, static under `LocalReduceMotion` (D6) — verify `AppEventRangePickerTest`: tapping "pick a time" calls back; a complete end exposes no click action
- [x] 3.2 Make `StatusHint` optionally clickable; in `CreateEventScreen` wire "Pick an end time" and the summary to one handler (bring the wheels into view, pulse), and "Name the event" to the name field's `FocusRequester` + keyboard show — verify `ui/screens` tests: with the form scrolled to the top, tapping "Pick an end time" brings the Until wheels into the visible bounds and sets no end; tapping "Name the event" focuses the name field

## 4. Start follows the clock (UI)

- [x] 4.1 Add `rangeTouched` to `CreateDraft`; the picker's `onChange` sets it, the re-preset path does not (D2) — verify a `ui/screens` test: typing a name keeps it false, a day tap or wheel settle sets it
- [x] 4.2 Add the tick effect in `CreateFlow` / `CreateEventScreen`: while untouched, re-read `CutoffFormatter.nowLocal()` near each minute boundary and write `EventRange(from = nowToTheMinute())` (D3) — verify a `ui/screens` test over a settable clock: advancing it 10 minutes moves the shown start; after a range touch it does not; Create sends the shown start

## 5. Foreground life reaches the create layer

- [x] 5.1 Add the presence read-model (activation counter + last absence) in `feature/…/readmodel`, stamped from `lifecycleHandlers`' `onForeground` / `onBackground` with the process `Clock`; add it to `StatusSources` and `statusSourcesOf` (D1) — verify `ReadModelImportsTest` and `ModuleSetTest` pass and a unit test of the cell over a mock clock
- [x] 5.2 Add `CREATE_DRAFT_ABSENCE_LIMIT` (15 min) to `model/`; extend `Layer.CreateEvent` with a `draft: CreateDraftSession` (`activation`, `epoch`) and reduce them in `StatusContainerHost` (error kept, D7) — verify `StatusContainerHostTest` cases: a foreground bumps `activation`; one after ≥ 15 min away bumps `epoch`, one after 14 min does not
- [x] 5.3 In `CreateFlow`, key the remembered draft on the session's `epoch` and re-preset an untouched range on a new `activation` — verify `ui/screens` tests: new epoch → empty name, start now, end unset; new activation with an untouched range → start moves, name kept; with a touched range → nothing changes

## 6. Integration and docs

- [x] 6.1 Add a `:test:integration` test over the JVM rig host: on the create layer, `/os` background, advance the mocked clock 10 min, `/os` foreground → `UiState` keeps the draft epoch and bumps activation; 15 min → bumps the epoch — verify `./gradlew :test:integration:test --tests '*CreateDraft*'`
- [x] 6.2 Drive the create screen in the world harness (load `ui-harness`): tap "Pick an end time" from the top of the form and a blank minute drag; capture before/after — verify the wheels come into view highlighted and the hour fills
- [x] 6.3 Regenerate diagrams if the read-model changes a flow (`./gradlew architectureDiagrams`) and run `./gradlew build` — verify green, including the detekt tiers (no ceiling raised)
- [x] 6.4 Validate the change — verify `npx --yes @fission-ai/openspec@1.13.2 validate create-range-picker-ux --strict` passes
