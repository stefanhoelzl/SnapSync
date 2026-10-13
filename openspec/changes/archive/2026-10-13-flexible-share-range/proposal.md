# Proposal

## Why

Two things get in the way when a guest decides which of their photos an event gets.

1. **The presets are hidden.** "The whole event" and "From now" exist, but only as chips inside the calendar
   dialog behind the range row's ✎. The common choice ("share from now", "share the whole event") takes two taps
   and a dialog.
2. **The event's dates are a hard wall.** Today a member's range is clamped into the event's start and end. That
   fails both reasons a guest has for reaching past it: the host set the start too late (the party began at noon,
   the event says 15:00), or the guest wants related photos in (the trip there, the evening before, the morning
   after). The clamp protects the promise "a guest's whole camera roll is never uploaded". That promise is really
   about consent: a membership shares only what its member chose, and the range is always bounded. A deliberate
   choice of a few extra days keeps it.

## What Changes

- **Quick options on the screen.** The share section of the join screen and of the joined screen's settings shows
  one row of choices: **From event start · From now · Custom**. The chosen range and the live photo count read
  beneath them, and Custom opens the calendar. "From now" is offered only while the event is running. The
  calendar dialog keeps only the calendar: its two preset chips go. "The whole event" is renamed "From event
  start", because once a range can reach past the event, "whole" reads as the widest choice.
- **A member's range may reach outside the event's dates.** The rule changes from "always inside the event's
  start and end" to: **a membership shares only the range its member selected**, which is always bounded, **at
  most 30 days long**, and **overlaps the event's dates**. It may start before the event's start and end after
  its end, but only through Custom. "From event start" is exactly the event's dates. The calendar makes a range
  breaking the rule unreachable, and a range that arrives any other way is refused, never silently changed.
- **When the screen shows an outside reach.** When the chosen range reaches outside the event, a line under the
  count says so: "Also photos from before / after / before and after the event."
- **Upload timing is unchanged.** Photos from before the event's start upload once the event starts, as today.
  Photos taken after the event's end but inside the member's range are shared until the event's photos are
  deleted, like any late photo.
- **A member's share is settled at the end of their own range,** not the event's. It settles earlier when their
  range ends early and later when it ends late. A member whose range ends after the event's end is therefore not
  done before it ends, so the event is not finished before then. The existing deletion bounds (3 days after the
  last activity, 30 days after the open-until date) still apply unchanged.
- **Received photos stay recognised wherever they are dated.** Another member's photos can now be dated outside
  the event's dates. After a reinstall or a newly selected photo, they are still recognised as received: never
  shared back as the member's own and never downloaded twice.
- **Copy.** The explanation sheet's "Nothing older is shared." becomes "Everything else stays on your phone." The
  create screen no longer says "*Only* photos taken between these dates are shared". The privacy policy and the
  store listing describe the chosen range.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `join-event`: the three choices (From event start · From now · Custom); the range rule (bounded, at most 30
  days, overlapping the event, may reach outside it); the outside-reach line; an invalid range is refused; the
  purpose's "whole camera roll" promise is restated as "only the range the member selected".
- `photo-sharing`: a member's range is no longer held inside the event's dates; it is bounded by the member's own
  choice (at most 30 days, overlapping the event). Photos from before the start still wait for the start. The
  purpose is updated to match.
- `manage-membership`: settings show the same three choices; a range equal to the event's dates shows as From
  event start; changed bounds follow the join rule instead of being held to the event's dates.
- `event-lifetime`: a member's share is settled once *their own* range has ended (renamed requirement), and the
  finished rule refers to it.
- `create-event`: the host's note no longer says *only* these dates are shared; the creator is bound by the same
  range rule as every guest, not by the event's window.
- `receiving-photos`: the share-back promise gains scenarios for received photos dated outside the event's
  dates (a reinstall, a limited selection); the requirement text already promises it.
- `app-experience`: the range picker greys out what breaks the range rule and marks the event's own days; the
  "Now" choice leaves the dialog (it is a chip on the screen, hidden outside the event's dates).

## Impact

- **UI** (`:ui:screens`, `:ui:components`, `:ui:state`): `ParticipationSections`/`ShareBody` and
  `AppShareRangeRow` become a chip row plus range and count text; `RangePickerDialog` loses its preset chips and
  marks the event's days; new and changed strings in English and German (`range_*`, `share_detail_*`,
  `share_outside_*`, `access_range_body`, `create_window_note`, the `share_range_*` keys); `RangeView` gains what
  the outside-reach line needs.
- **Presentation** (`:domain:presentation`): `RangeForm`/`PendingJoin` and the `range/` rules
  (`RangeBounds`). The join and reconfigure range bounds become "event start − 30 days … event end + 30 days,
  at most 30 days, overlapping"; preset naming changes.
- **Model/feature** (`:domain:model`, `:domain:feature`): `clampToFloor`/`clampToCeiling` in `JoinEvent` and
  `ReconfigureEvent` are replaced by a validation that refuses. The settle trigger (`EventCompletion`,
  `DeviceManifestProducer`) moves from the event's end to the membership's own range end.
  `ReceivedPhotoAdoption` searches over the union's capture-date span, and its selection pre-check drops its date
  window. `MarkedPhotoLookup` follows.
- **api** (`api/src/routes/v2.ts`): a final manifest is accepted before the event's end (the `final && ended`
  guard), and the finished and deletion computation stays correct when a device settles early.
- **Docs and context:** `openspec/config.yaml` MISSION ("a member shares photos taken within a chosen sub-range
  of the event window") and the CLAUDE.md overview ("lower bound clamped to the event start, upper to the event
  end") are restated; `metadata/listing/en-US.json` and the site's privacy policy (`site/src/pages/index.astro`).
- **No migration:** every existing membership's range already lies inside its event, so it satisfies the new
  rule.
