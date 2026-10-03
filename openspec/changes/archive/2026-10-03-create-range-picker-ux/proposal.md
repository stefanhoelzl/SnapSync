# Proposal

## Why

An Android host (Samsung Galaxy A54) reported that the create screen's end time "did not react". On a Samsung-sized
screen the time wheels start below the fold, and the one cue the host does see, the end's "pick a time" in
the accent colour, does nothing when tapped. Two more rough edges showed up in the same review. A blank end
minute wheel shows "--" for its whole scroll, so it never reads as choosing anything. And the start is frozen
at the moment the screen opened, so a create screen left in the background opens hours (or a day) stale:
measured on the A40 on 3 Oct, where it showed a start of "2 Oct, 17:24".

## What Changes

- **The next missing step is tappable.** While the end time is unset, tapping the range's "pick a time" or the
  "Pick an end time" line above Create brings the time wheels into view and briefly highlights the end's
  wheels. It sets no value. While the name is missing, tapping the "Name the event" line focuses the name field
  and raises the keyboard. A set end time is not a tap target.
- **The end time is set only when both its hour and its minute are chosen.** Moving the end's minute wheel
  while its hour is unset fills the hour with the current hour (held to the nearest allowed hour), and the
  minute wheel shows real minutes while it moves. Moving the hour wheel never fills the minute.
  **BREAKING (behaviour):** today settling the hour alone completes the end at `:00`.
- **The start follows the clock until the host touches the range.** While nothing in the range has been
  chosen, the start is now, to the minute: it advances while the screen is open and jumps to now when the app
  returns to the foreground, and the last day follows the start's day. Any choice in the range (a day, either end time, the
  start) freezes the start for the rest of that draft. Typing the name does not freeze it. Create sends the
  start as it is shown at the tap.
  **BREAKING (behaviour):** replaces "the preset SHALL NOT drift while the host is on the screen".
- **A long absence starts a fresh draft.** When the app returns to the foreground after 15 minutes or more in
  the background without an event having been created, the create screen starts over: no name, the start at
  now and following the clock, the last day the start's day, the end time unset.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `create-event`: the requirement "The host chooses the event's date range" changes: the start follows now until
  the range is touched, the end time needs both hour and minute, the next missing step is tappable, and a
  15-minute absence resets the draft.

## Impact

- UI: the create screen and its draft (`ui/screens` `CreateEventScreen`, `CreateDraft`), and the range picker's
  end-time wheels and summary (`ui/components` `AppEventRangePicker`, `AppSettlingWheels`, `EventRange`).
  The join and settings range dialog shares these components but always has a set end, so it is unaffected;
  its tests guard that.
- Presentation and model: the create layer needs to learn when the app returns to the foreground and how long
  it was away, so the screen can re-preset or reset its draft. Today the screen has no foreground signal.
- Tests: `EventRangeTest`, `AppEventRangePickerTest`, the `ui/screens` create-screen tests, and an integration
  test that drives a foreground through the control channel.
- No backend, wire or storage change; the create request carries the same fields.
