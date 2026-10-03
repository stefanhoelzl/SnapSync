# Design

## Context

The create form's state is a Compose-side `CreateDraft`: a name plus an `EventRange`. `CreateFlow` remembers it
across the form → creating → form round trip that a failed create makes. The start is frozen at first composition
(`nowToTheMinute(cutoff)`). Nothing on the screen learns about a foreground. Foregrounds arrive only at the
`Lifecycle` port's `onForeground` / `onBackground` handlers in `compose/EntryHandlers.kt`, which drive the
foreground flow and the tail and assemble the status host. The UI sees only `UiState`, and
`Layer.CreateEvent` carries just `error`.

`EventRange` stores the end time as one `LocalTime?` (`untilTime`). The wheels in `AppSettlingWheels.kt` are
controlled. A blank wheel shows `--` on its centred row for the whole scroll, because the label tests
`blank && i == center`. A settle of either Until wheel completes the end: an hour settle fills `:00`, and a
minute settle takes the start's hour (`EventRange.settleUntilHour/Minute`). The join and settings dialog
(`RangePickerDialog`) shares `RangeEditor` and the wheels, but its bounds set `blankEndTime = false`, so its
end is always set.

The create form scrolls (`verticalScroll` in `CreateEventScreen`) above a pinned Create area. On a Samsung
1080×2340 screen the wheels start below the fold (measured on the A40, Play build 2124).

## Goals / Non-Goals

**Goals:**
- One place decides when a foreground re-presets or resets the draft, and it is testable without a device
  (`:test:integration` over the JVM rig host's `/os` foreground and its mocked clock).
- The range rules stay pure and unit-tested in `EventRange*.kt`; the composables only route gestures.
- The join and settings dialog behaves exactly as today.

**Non-Goals:**
- Persisting the draft across process death. A killed process already starts a fresh draft, which is the
  outcome the 15-minute rule wants anyway.
- Making the wheels fit above the fold. The tap-to-scroll fixes the dead tap; a denser layout can come later.
- Any change to the join and settings dialog's behaviour.

## Decisions

### D1. Foreground life reaches the create layer as reduced state, not as a Compose lifecycle hook
The core keeps a small presence cell. The lifecycle handlers stamp it: `onBackground` records the instant from
the process `Clock`, and `onForeground` bumps an activation counter and records how long the app was away. The
cell is a read-model (`feature/…/readmodel`, so `ReadModelImportsTest` admits it into presentation) handed to
`StatusSources`. `StatusContainerHost` folds it into its local state and reduces it into `Layer.CreateEvent.draft`,
a `CreateDraftSession` (`model/`) of two monotonic values:
- `activation`: bumps on every foreground;
- `epoch`: bumps on a foreground whose absence was at least `CREATE_DRAFT_ABSENCE_LIMIT`
  (15 minutes, a `model/` constant beside the session).

The UI keys the remembered `CreateDraft` on `epoch` (a new epoch is a fresh draft), and re-presets an
untouched start on each new `activation`.

*Why:* the 15-minute decision is behaviour the spec states, so it belongs where `:test:integration` can drive
it: `/os` foreground and background plus the mocked clock, asserting `UiState`. A Compose `LifecycleOwner`
would need a new `lifecycle-runtime-compose` dependency in `:ui:screens`. Its meaning also differs per platform:
iOS's transient inactive states, Android's activity versus process lifecycle. And the harness and rig could not
reach it, since every UI state must come from the real levers and none may be forged.
*Rejected:* re-deriving the start at Create time. It would break "the start shown is the start created".

### D2. "Touched" lives in the draft; the tick and the re-preset are writes that do not touch
`CreateDraft` gains `rangeTouched`. The picker's `onChange` (every host gesture: a day tap, drag or sweep, any
wheel settle, the minute drag's hour fill) sets it. The clock tick and the foreground re-preset write
`range` directly and leave it false. While it is false, the range is by construction the preset: start = now, last
day = start's day, end blank. So a re-preset is simply `EventRange(from = nowToTheMinute())`, and there is
no end to keep valid. The name never counts as a touch.

### D3. The tick is a UI effect over the injected clock
While the create screen is composed and `rangeTouched` is false, a `LaunchedEffect` re-reads
`CutoffFormatter.nowLocal()`. It sleeps until the next minute boundary, with a short cap, so a mocked clock
that jumps is picked up within a second. When the minute differs, it writes the new preset. Create already
sends `draft.range.from`, which is exactly what is shown.
*Why the UI and not the presentation layer:* the tick only matters while the screen is visible, and the
presentation container should not run a timer for an invisible form. The rule that the tick follows (D2) is
pure and unit-tested.

### D4. The end time becomes two optional fields; completion needs both
`EventRange.untilTime: LocalTime?` becomes `untilHour: Int?` and `untilMinute: Int?`, with
`until = both set ? LocalDateTime(endDay, h:m) : null`. Rules (pure, in `EventRange.kt`):
- `settleUntilHour(h)`: sets the hour (nearest hour with an allowed minute); keeps a chosen minute only while
  `h:m` is still allowed, else clears it under `blankEndTime`, or moves it to the nearest allowed minute when
  the end is always set;
- `fillUntilHour(now)`: for a minute drag that starts while the hour is blank. It sets the hour to now's hour,
  or the nearest allowed hour (the same `nearestTime` search). Per the interview, when now's hour is not
  allowed that search lands on the start's hour;
- `settleUntilMinute(m)`: requires an hour (filled above), and is pulled to the nearest allowed minute.

With `blankEndTime = false` (join and settings), both fields are always set, so the dialog's behaviour is
unchanged. `keepValidTime`, `restartAt` and the `RangeBounds` doc are updated to the two-field shape.
*Rejected:* keeping `LocalTime?` plus a separate "hour only" flag. Two optional fields say the same thing
with one fewer invariant.

### D5. The wheels learn a drag start, and a blank wheel stops showing `--` once dragged
`SettlingWheel` already sees `DragInteraction.Start`. It gains an `onDragStart` callback. The Until minute
wheel's callback calls `fillUntilHour(now)` when the hour is blank, and the hour wheel then follows the value
(it is controlled). The centred label shows `--` only while `blank && !dragged`, so a dragged minute wheel shows
real minutes. Settling still reports where the host's drag came to rest, as today.
The picker needs "now" for the fill. `AppEventRangePicker` takes it as a parameter (`now: () -> LocalDateTime`,
or the current hour), supplied from the create screen's `CutoffFormatter`. `:ui:components` stays clock-free.

### D6. Tap targets are plain callbacks; the screen owns scrolling and focus
- `AppEventRangePicker` gets an optional `onPickEndTime: (() -> Unit)?`. When it is non-null and the end is
  incomplete, the Ends summary is a `clickable` target with `Role.Button`. When the end is complete it renders as
  today, with no click.
- `RangeEditor` puts a `BringIntoViewRequester` on the From/Until row, and exposes a `highlightUntil` counter.
  Each bump runs a short accent-border pulse on the Until box: an `Animatable` alpha, static under
  `LocalReduceMotion`.
- `CreateEventScreen` wires both tap targets to one handler: bring the wheels into view, then bump the
  highlight. The handler also serves the "Pick an end time" line, which `StatusHint` renders clickable when it
  is given an `onClick`.
- The "Name the event" line requests a `FocusRequester` on `AppTextField`, brings it into view, and calls
  `LocalSoftwareKeyboardController.show()`.

### D7. The reset keeps the failure message
A 15-minute reset clears the draft (D1) but leaves `Layer.CreateEvent.error`. That message is reduced state
owned by `create-event`'s "A failed create says so", which says it stays until the next attempt.

## Risks / Trade-offs

- [A transient inactive state on iOS (the app switcher, Control Center, a permission prompt) counts as background
  and foreground] → It only re-presets an untouched start to now, which is harmless. The 15-minute absence a
  transient state would need is not realistic.
- [The tick fires while the host scrolls the form] → The tick writes only an untouched range, so no wheel the
  host is holding changes value. An untouched From wheel visibly moves forward one minute, which is the intent.
- [The wheels follow a value change with an animation] → A tick moves the From wheels by one row. Under reduce
  motion they snap (existing `moveTo`).
- [Changing `untilTime` ripples through the join and settings dialog's tests] → Those tests are the regression
  guard for "unaffected"; they must pass unchanged in meaning.
- [The A54 report is not confirmed to be the below-the-fold case] → The tap target fixes the most likely cause
  either way; the reporter can be asked once the build is out.

## Migration Plan

UI-only and stateless across launches: no stored data and no wire change. Rollback is reverting the PR.
