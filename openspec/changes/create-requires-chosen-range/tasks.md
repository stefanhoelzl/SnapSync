## 1. Draft model

- [x] 1.1 Reshape `CreateDraft` so the end is optional in two steps (end day chosen; end time chosen); seed the start to the frozen "now" and leave the end unset (drop the `now + 1 day` seed in `rememberCreateDraft`)
- [x] 1.2 Let `CreateFlow` take an optional initial draft (default: the fresh one), and keep dropping the draft when the create flow is left
- [x] 1.3 Derive the next missing step (name → last day → end time → complete) as a pure function of the draft, with unit tests for each step and for the order

## 2. Inline range control (`:ui:components`)

- [x] 2.1 Build the inline calendar from the dialog's calendar grid and tap cycle (a later day sets the end, an earlier day moves the start, a tap on a complete range starts over and clears the end), greying days past `latestUntil`
- [x] 2.2 Build four independent wheels (From h/m, Until h/m) with a 1-minute step and no carry between minutes and hours; the Until wheels are inert until an end day exists
- [x] 2.3 Implement the blank Until state: after the day tap both Until wheels show blank over the start's clock time; scrolling either one sets the end and fills in the other (hour → `:00`, minute → the hour beneath)
- [x] 2.4 Make invalid ranges unreachable: on a same-day range, Until times at or before the start are blocked; the From wheels are bounded by a set end; the end is coerced within `latestUntil(from)`
- [x] 2.5 Retire `DateTimeRangePickerDialog` and `AppEventDateRangeSection` (no other callers), and port what still applies from `DateTimeRangePickerTest` to tests of the inline control (tap cycle, blank state, fill-in, same-day block, 30-day cap)

## 3. Create screen (`:ui:screens`)

- [x] 3.1 Lay out the name, then "When is it?" with the inline control, scrolling under the pinned action
- [x] 3.2 Add the one neutral next-step line above Create (visible from open); it shows `humanizedDuration` once the form is complete
- [x] 3.3 Enable Create only for a complete draft (non-blank name, end day, end time, `from < until`, `fitsEventWindow`), and submit only complete values
- [x] 3.4 Render `Layer.CreateEvent.error` (create failure and invalid-QR notice) as error text in place of the scan hint below Create; remove the banner above Create
- [x] 3.5 UI tests (`StatusScreenTest` / create tests): Create stays disabled with only a name, and with name + last day; enabled after an Until wheel flick; the next-step text at each step; the duration once complete; a failure shows below Create in place of the hint and keeps the name and range; the hint returns on the next attempt

## 4. Forge, harnesses and screenshots

- [x] 4.1 ~~Seed a completed draft for the forge `create` state~~ — dropped on rebase: `main` retired the forge; the `create` shot is the real app's fresh form (design D7)
- [x] 4.2 Check the desktop control panel and world inspector still drive create (they call create with explicit values); adjust any labels that reference the dialog
- [x] 4.3 Drive the create screen through the harness driver: screenshot opened, name typed, day tapped (blank), complete, same-day, and failed states, light and dark
- [ ] 4.4 Dispatch `screenshots.yml` on the branch, eyeball `create-{light,dark}.png`, and commit them (the `create` shot should now be stable across runs)

## 5. Last day preset to today (operator feedback on the SE2)

- [x] 5.0.1 Preset the last day to the start's day with the end time blank; taps cycle start → end beginning with the start placed; drop the "last day" step from the next-step line
- [x] 5.0.2 Drag an endpoint to move it; long press then sweep to select a new range; tests for both
- [x] 5.0.3 Update the spec delta, design (D1, D2, D3, D5, D7) and proposal

## 6. Verify

- [x] 6.1 `./gradlew build` green, including the `detekt*Tier` ceilings (no ceiling raised without a stated forcing proof)
- [x] 6.2 `./gradlew compileIosMainKotlinMetadata` green
- [x] 6.3 On the SE2: create an event end to end (name → last day → flick Until → Create), and confirm the form fits or scrolls cleanly with Create pinned
- [x] 6.4 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` and validate this change
