## Context

The create screen (`CreateEventScreen`) holds its form in a hoisted `CreateDraft` (`CreateDraft.kt`), which
survives the form → creating → form round trip of a failed create. `rememberCreateDraft` seeds it with
`[now, now + 1 day]`, frozen at first composition. The range is shown as a card (`AppEventDateRangeSection`)
whose pencil opens `DateTimeRangePickerDialog`: a single-month calendar with a tap-start/tap-end cycle, two
hour/minute wheel pairs, and one confirm. Nothing else uses that dialog or the card. The window arithmetic
lives in `CutoffFormatter` (`latestEnd`, `fitsEventWindow`, `humanizedDuration`). Create errors, and the
transient invalid-QR notice, are reduced into `Layer.CreateEvent.error` and rendered as an `AppErrorBanner`
above Create.

Observed problem: hosts type a name and tap Create. The pre-filled range is complete and valid, so nothing
stops them, and the window they get is almost never the event's. That window is the capture-date bound for
every member (capability `photo-sharing`), so a wrong window silently drops event photos.

The design was settled in an interview with the operator (2026-09-28), which went through several rejected
shapes (below). The final mockup is kept outside the repo; the decisions here are its record.

## Goals / Non-Goals

**Goals:**
- No event can be created without the host having actively chosen where it ends (day and time).
- Choosing that takes as few interactions as possible: a host at their own event needs no start interaction,
  and the end costs a day tap plus one wheel flick.
- The screen always says what is missing next, so a disabled Create is never a mystery.
- The action area never grows over the range controls.

**Non-Goals:**
- The join/reconfigure range surface (presets over the event window). Whether it adopts the inline controls
  is deferred.
- Any change to the backend, the create request, the 30-day limit, or event lifetime.
- The rig's `/user` create intent, which already carries explicit dates.

## Decisions

### D1 — Start preset to "now", last day preset to today, end TIME blank
The start keeps a preset: the moment the screen opened, cut to the minute and frozen. A host at their own
event needs no interaction for it; a slow typer's start sitting a few minutes in the past is harmless (it
admits on doubt). The last day is preset to the start's day too (operator, on the SE2: "the last day should
be today by default"), so a same-day event costs one wheel flick. What stays blank is the end **time**: that
is the one value that makes the range complete, so a host cannot create without having set it.
*Rejected:* both ends unset, then the end day unset (each cost a tap for the common case); a two-stage flow
with a `now + 8 h` preselection behind a "Choose dates" step (it still offered a pre-made range to accept
blindly); keeping a complete default and merely surfacing it (the original failure mode).

### D2 — One screen, inline calendar and wheels; taps cycle, endpoints drag, a long press sweeps
Name, then the calendar, then the From/Until time controls, all inline, with Create pinned at the bottom. On
an SE2 the form scrolls under the pinned action.
**Taps cycle start → end, beginning with the start already placed**: while the last day is pending, a day on
or after the start places it (so "from now until Sunday" is one tap) and a day before the start moves the
start; once the last day is placed, the next tap starts a new range on that day (both ends there, the end
time blank, the last day pending again). Days past `latestEnd` are greyed while the last day is pending.
**Dragging**: a drag that starts on the start or end day moves that endpoint at once (on a one-day range the
first movement decides which — back is the start, forward the end); a drag that starts anywhere else sweeps a
new range, but only after a **long press**. The long press is forced by the SE2: the calendar fills most of
the visible form, so an immediate sweep from any day would leave no place to scroll the form from.
*Rejected:* a separate full-screen dates step (an extra screen for one question); the existing dialog
(confirm step, and the range is out of view while it is closed); an immediate sweep (blocks scrolling).

### D3 — Four independent wheels, no Done; the end time starts blank
From-hour, From-minute, Until-hour and Until-minute are separate wheels with a 1-minute step (which keeps
"now", e.g. 18:04, exactly representable). Scrolling commits; there is no confirm. Minutes wrapping past 59
never carry into the hour.
The Until wheels start **blank**, and choosing a last day does not fill them: an end that appears pre-filled
is the default problem again, one level down. Scrolling (or tapping a row of) **either** Until wheel sets
the end; the untouched one fills in (hour → minutes `:00`; minutes → the hour the blank wheel sat over). A
blank wheel sits over the start's clock time, so the first flick moves from a familiar place.
*Rejected:* a bottom sheet with wheels and Done (day + hour + minute + Done per end); an hour-chip strip or
time-of-day presets (fewer taps, but the operator chose the precision of wheels); the end landing at 23:59
or at the start's clock time on the day tap (a pre-filled value to accept blindly); requiring both Until
wheels to be touched (minutes rarely matter).

### D4 — Constraints make bad ranges unreachable, not refused
As with today's picker, an invalid range cannot be picked rather than being rejected on submit. On a same-day
range, Until hours before the start's hour, and within that hour the minutes up to the start's minute, are
unreachable. The From wheels are bounded by a set end in the same way, and the end is kept within
`latestEnd(from)`, coerced as the dialog coerced it today. `fitsEventWindow` and `from < until` remain in
Create's enablement as a restatement only.

### D5 — One next-step line above Create, then the duration
A single neutral-grey line above Create, visible from the moment the screen opens, names the **next** missing
step in a fixed order: name → end time. Once the form is complete, the same slot shows
`humanizedDuration` ("Event lasts …"). It is guidance, not an error, hence neutral styling, and one line
rather than a checklist keeps the height constant.
*Rejected:* an amber checklist plus field outlines; revealing guidance only after a tap on the disabled button.

### D6 — Errors take the scan hint's slot below Create
The create failure, and the transient invalid-QR notice that shares `Layer.CreateEvent.error`, render as
error-coloured text **in place of** the "Or scan a QR code…" hint below Create, not as a banner above it. The
action area keeps a constant height, so a failure never pushes the time wheels out of view. The reduction is
unchanged; only the render site moves. The message still stays until the next attempt, and the hint returns
when it clears.

### D7 — Draft shape, and the marketing shot shows the fresh form
`CreateDraft` holds an `EventRange`: the start, the last day, a nullable end time, and which end the next
day tap moves — so "last day shown, time blank" is representable. Create submits only a complete draft. The
draft still lives in `CreateFlow` and is still dropped when the create flow is left, so a later visit (e.g.
after cancelling the new event's join) starts afresh with a new "now".
The interview chose a *completed* form for the `create` marketing shot, to be seeded through the forge. While
this change was in flight, `main` retired the forge: every shot is now the real app driven through the control
channel over mocks at a fixed clock (`Shots.kt`). The draft is composition state no intent can fill, so the
`create` shot shows the fresh form — name empty, today as the last day, the end time blank, Create disabled —
at the mocked instant, which is stable across runs. Showing a completed form would need the draft in
`UiState` and a `/user` intent to fill it; that is left for a later change if the listing wants it.

### D8 — One range picker: the join and settings surfaces use the create screen's
While this change was in flight, `main`'s join rework adopted the old range dialog for the Custom range, so the
app briefly had two pickers sharing only the month grid. They are unified (operator decision, interview
2026-09-29): the picker is ONE component — `RangeEditor` (both ends in words, the calendar with its taps,
endpoint drags and long-press sweep, the four settling wheels) and its rules — framed two ways: inline in a
card on the create screen, and in the existing popup (title, Whole event / From now chips, Cancel / OK) behind
the range row on join and settings.
- **One difference, the bounds** (`RangeBounds`): create has no window and a 30-day length; join is held to
  the event's `[start, end]`, days outside it always greyed, times outside it struck through.
- **The end time is always set on join** — the event's end or the current custom end. The blank end time is a
  create-only rule (it prevents a silent default; on join the whole event IS the right default). Where a day
  moves make the end time invalid, it moves to the nearest valid time instead of blanking.
- **Join opens on the chosen range, complete**: the first tap on a day starts a new range; narrowing an end is
  a drag of that end. OK is enabled only while the range is valid; Cancel changes nothing.
- **Deleted**: the old dialog's own calendar and `TimeWheels`, and the single-date `DateTimePickerDialog` with
  its `CalendarGrid` (no caller since the join rework). Their tests moved to the unified picker.
- **No spec delta**: `join-event` promises a custom range "picked on a calendar with a time for its start and
  its end", which stays true; dragging and sweeping are interaction detail (the swap test).
*Rejected:* the picker inline in the join screen (long on an SE2, no Cancel); a bottom sheet; identical rules
with the last day pending on open (narrowing the end would be one tap, but a first tap starting a new range is
what a complete range should do).

## Risks / Trade-offs

- [A host with no fixed end still has to pick one] → Intended: the window is a promise to every guest, and
  the 30-day cap already bounds it.
- [An inline calendar plus four wheels is tall on an SE2] → The form scrolls with Create pinned (the same
  grammar as today). Measured on the SE2 (iOS 26.6): only two calendar rows fit above the pinned action on
  open, so the current week and the wheels need a scroll — which is why the sweep waits for a long press.
- [A blank wheel is a new state with no platform precedent] → The dashed band and the next-step line name
  it; cover it with a UI test so a regression to "pre-filled" is caught.
- [Moving the error below Create changes where hosts have learned to look] → It sits right under the button
  they just tapped, and the scan hint it replaces is the least important line on the screen.
- [Marketing screenshots go stale] → Re-capture `create-{light,dark}.png` in the same PR (screenshots
  workflow); eyeball them before committing.

## Migration Plan

UI-only and client-side: it ships with the next build. Rollback is reverting the PR. No stored state
changes shape (the draft is composition state, never persisted).

## Open Questions

- None open. (The join/settings unification, deferred at first, is D8.)
