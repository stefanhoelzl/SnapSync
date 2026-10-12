## REMOVED Requirements

### Requirement: The shared capture range always has a lower bound inside the event window
**Reason**: A member's range is no longer held inside the event's dates: it may reach before the start or after
the end, bounded by the member's own choice (at most 30 days, overlapping the event).
**Migration**: Replaced by "The shared capture range is always bounded and chosen by the member", which keeps
the default (the event's dates), From now, the custom calendar, the unknown-range and retry promises, and puts
the choices on the screen.

## ADDED Requirements

### Requirement: The shared capture range is always bounded and chosen by the member
With sharing on, the join screen SHALL let the user choose the range of capture dates they share,
defaulting to the event's own dates. It SHALL offer three choices side by side on the screen, each one tap:
from the event's start (exactly the event's dates); from now until the event's end; and a custom range,
picked on a calendar with a time for its start and its end. The chosen range and the count of photos it would
share (see "The join screen shows how many photos will be shared") SHALL read beneath the choices. From now
SHALL be offered only while the event's dates are running, and SHALL NOT be shown otherwise. A custom range MAY
start before the event's start and MAY end after its end, but SHALL be at most 30 days long and SHALL overlap
the event's dates; the calendar SHALL make any other range impossible to pick. When the chosen range reaches
outside the event's dates, the screen SHALL say so — that it also shares photos from before the event, from
after it, or both. A range that breaks these rules, however it reached the app, SHALL be refused rather than
changed: nothing is joined with it. A membership SHALL never exist without both bounds: if the app cannot tell a
membership's range, it SHALL share nothing rather than the whole library.

#### Scenario: The default is the event's dates
- **WHEN** the join screen loads an event running from Friday 18:00 to Sunday 23:00
- **THEN** "from the event's start" is chosen and the shared range reads from Friday 18:00 until Sunday 23:00

#### Scenario: From now shares from this moment to the event's end
- **WHEN** the event is running and the user chooses from now
- **THEN** the shared range reads from the current time until the event's end

#### Scenario: Now is unavailable outside the window
- **WHEN** the event has not started yet, or has already ended
- **THEN** from now is not offered and the default remains from the event's start

#### Scenario: A custom range may start before the event
- **WHEN** the user picks a custom range from Thursday 09:00 to Sunday 23:00 for an event running from Friday
  18:00 to Sunday 23:00, and joins
- **THEN** the screen says that photos from before the event are shared too, and their photos taken from
  Thursday 09:00 on are shared

#### Scenario: A custom range may end after the event
- **WHEN** the user picks a custom range ending on Tuesday for an event that ends on Sunday, and joins
- **THEN** the screen says that photos from after the event are shared too, and their photos taken until
  Tuesday are shared

#### Scenario: A custom range is at most 30 days long
- **WHEN** the user picks a custom start 20 days before an event that lasts 15 days
- **THEN** the calendar does not allow an end later than 30 days after that start

#### Scenario: A custom range must overlap the event
- **WHEN** the user tries to pick a custom range that ends before the event's start, or starts after its end
- **THEN** the calendar does not allow it

#### Scenario: A guest's older camera roll is never shared
- **WHEN** a guest with years of photos joins an event that started yesterday, keeping the default choice
- **THEN** only photos taken since the event's start, within its range, are shared — never their older photos

#### Scenario: A range that breaks the rules is refused
- **WHEN** a range longer than 30 days, or one that does not overlap the event's dates, reaches the join in any
  way other than the calendar
- **THEN** nothing is joined and no photo is shared

#### Scenario: An unknown range shares nothing
- **WHEN** the app cannot read a membership's capture range
- **THEN** no photo is shared until the user joins again

#### Scenario: A choice survives a failed join
- **WHEN** the user picks a custom range, taps Join, the join fails, and they tap Retry
- **THEN** the retry joins with the range they picked, not a default
