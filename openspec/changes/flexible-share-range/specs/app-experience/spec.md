## REMOVED Requirements

### Requirement: Picking a date range is guided and cannot go wrong
**Reason**: A member's range may now reach outside the event's window, and the "Now" choice moved out of the
dialog onto the screen, where it is hidden outside the event's dates.
**Migration**: Replaced by "Picking a date range is guided and only offers what is allowed", which keeps the tap rules, the unreachable inverted range and Cancel, and
bounds a member's range by the join rule while marking the event's days.

## ADDED Requirements

### Requirement: Picking a date range is guided and only offers what is allowed

Picking a date range SHALL happen in one dialog holding the dates and both times, committed by one
confirmation. The first tap on a day SHALL set the start, the second the end (the same day twice meaning
a single day), and a third SHALL start over; changing the days SHALL keep the chosen times. Where a
range is bounded — an event's range, and the range a member shares (at most 30 days, overlapping the event's
dates, capability `join-event`) — days and times that would break the bound SHALL be unselectable and a
picked value SHALL be kept within it. When picking the range a member shares, the event's own days SHALL stay
marked in the calendar. An end before the start SHALL be unreachable. Cancelling SHALL leave the previous
choice untouched. The dialog SHALL hold only the calendar and the times: the quick choices for what a member
shares are on the screen beside it (capability `join-event`).

#### Scenario: A shared range around the event
- **WHEN** the member picks a custom range for what they share
- **THEN** the event's days are marked, only days that keep the range at most 30 days long and overlapping the
  event can be picked, and the confirmed range has its end after its start

#### Scenario: Third tap starts over
- **WHEN** a start and end day are picked and the member taps a third day
- **THEN** that day becomes the new start

#### Scenario: Cancel keeps the old choice
- **WHEN** the member opens the custom picker and cancels
- **THEN** the previous choice is unchanged
