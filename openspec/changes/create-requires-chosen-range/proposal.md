## Why

The create screen pre-fills the event's date range with "now until the same time tomorrow", so a host can
type a name, tap Create, and get an event whose capture window they never looked at. That default is almost
always wrong for a real celebration, holiday or trip, and because the window bounds every member's shared
photos (capability `photo-sharing`), a wrong window silently leaves event photos out, with no way to fix it.
Creating must therefore require the host to choose where the event ends.

## What Changes

- **BREAKING (UX):** the create screen no longer pre-fills a complete range. The start is preset to the
  moment the screen opened and the last day to today, but the **end time is blank**. Create stays disabled
  until the host has a name **and** has set the end time.
- The date range is picked **on the create screen itself**, directly below the name: an inline calendar and
  inline hour and minute controls for the start and the end, which move independently. The separate picker
  dialog and its confirm step are gone.
- Choosing a later last day (one tap, or dragging the end) does not complete the range. The end time stays
  blank until the host sets it, and setting either the hour or the minute is enough; the other fills in.
- A same-day event is allowed; an end at or before the start cannot be chosen (as today, an inverted range
  cannot be submitted). The 30-day limit is unchanged.
- One line above Create always tells the host what is missing next: the name, then the end time. Once the
  form is complete, that line shows how long the event lasts.
- A failed create, and the transient "QR code was not valid" notice, now show **below** Create, in place of
  the scan-to-join hint, instead of as a banner above Create. That way the action area never grows over the
  range controls. What was entered is still kept, and the message still stays until the next attempt.
- The `create` marketing screenshot is re-captured: it now shows the fresh form (the start now, today as the
  last day, the end time blank).

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `create-event`: the create screen is revised. The date range has no complete default: the start is
  preset to "now" and the last day to today, the end time must be chosen, and Create is disabled until it
  is. The screen names the next missing step, and a failure message appears below Create instead of above
  it.

## Impact

- **UI (`:ui:screens`, `:ui:components`):** the create screen's form and its draft, a new inline range
  control (calendar, start and end hour/minute wheels, and a blank "not yet set" end), the next-step and
  duration line, and the message slot below Create. The dialog range picker has no other caller and is
  retired, along with its test.
- **Presentation (`:domain:presentation`):** the window arithmetic (`latestEnd`, `fitsEventWindow`,
  `humanizedDuration`) is reused unchanged. The reduction of create errors into the create layer is
  unchanged; only where the screen renders the message moves.
- **Screenshots:** `screenshots/create-{light,dark}.png` must be re-captured (the real app over mocks at a
  fixed clock, `Shots.kt`) and committed.
- **`join-event` needs no delta:** its invalid-QR scenario only says the create screen shows the
  notice, never where, so moving the notice below Create keeps it true.
- **Unaffected:** the backend, the create request, the rig's `/user` create intent (it already carries
  explicit dates), and the join screen's range surface.
