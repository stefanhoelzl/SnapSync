## ADDED Requirements

### Requirement: The host chooses the event's date range and its end time
The create screen SHALL always carry a date range whose start is preset to now, to the minute; how that preset
follows the clock is "The start follows the clock until the host chooses the range". The start shown SHALL be
exactly the start created. The range's last day SHALL be preset to the start's day, but the time the event ends
SHALL start unset and SHALL be chosen by the host; choosing a last day SHALL NOT fill in that time. The end time
SHALL count as chosen only once both its hour and its minute are chosen: choosing the end's hour SHALL NOT fill
in its minute, while starting to choose the end's minute with its hour unset SHALL fill in the current hour, or
the allowed hour nearest to it, and SHALL show real minutes while the host moves through them. Create
SHALL be disabled until the name is set and the end time is chosen. The host SHALL be able to change the
start's day and time and the end's day and time, choosing dates in the past (to bring photos already taken
into the event) or in the future (to create an event ahead of time); the start and end may fall on the same
day. The screen SHALL prevent a range whose start is not before its end and a range longer than 30 days.
While Create is disabled, the screen SHALL name the next thing the host still has to do: the name, then the
end time. Once the range is complete, the screen SHALL show how long it lasts. The screen SHALL state that
only photos taken during this window are shared.

#### Scenario: The start is preset to now
- **WHEN** the create screen opens at 18:04
- **THEN** the start shown is 18:04 today

#### Scenario: The last day starts as today, but not the end time
- **WHEN** the create screen opens
- **THEN** the range shows today as its last day and the end time as not yet set

#### Scenario: A name alone does not allow Create
- **WHEN** the host types a name and has not set the end time
- **THEN** Create is disabled and the screen says the end time is what is still needed

#### Scenario: Choosing a later last day does not fill in the end time
- **WHEN** the host has typed a name and chooses a later day as the event's last day
- **THEN** the end time is still shown as not yet set, and Create stays disabled

#### Scenario: Choosing only the end's hour does not set the end time
- **WHEN** the host has typed a name and chooses 22 as the end's hour without choosing a minute
- **THEN** the end's minute is still shown as not yet set, Create stays disabled, and the screen says the end time is what is still needed

#### Scenario: Choosing the end's minute first fills in the current hour
- **WHEN** it is 18:04, the end time is not set, and the host starts moving through the end's minutes
- **THEN** the end's hour shows 18 and the minutes show as numbers while they move, and the minute the host stops on completes the end time

#### Scenario: Setting the end time completes the range
- **WHEN** the host sets the end time
- **THEN** Create is enabled, and the screen shows how long the event lasts where it named the missing step

#### Scenario: The next missing step is named from the start
- **WHEN** the create screen opens with no name typed
- **THEN** Create is disabled and the screen says the event needs a name

#### Scenario: A past or future range can be chosen
- **WHEN** the host picks a range that started three weeks ago, or one that starts next month
- **THEN** the screen accepts it and shows the new range

#### Scenario: A same-day event needs only its end time
- **WHEN** the host types a name and sets an end time later today, without choosing another day
- **THEN** the range is complete and Create is enabled

#### Scenario: A range longer than 30 days cannot be chosen
- **WHEN** the host tries to set an end more than 30 days after the start
- **THEN** the screen does not allow it, so no such event can be submitted

#### Scenario: An inverted range cannot be chosen
- **WHEN** the last day is the start's day and the host tries to set an end time at or before the start
- **THEN** the screen does not allow it, so no such event can be submitted

#### Scenario: The duration and the consequence are stated
- **WHEN** the host completes a range that spans five days
- **THEN** the screen reads that the event lasts 5 days, beside the statement that only photos taken during the window are shared

### Requirement: The start follows the clock until the host chooses the range
While the host has chosen nothing in the range (no day, no end hour or minute, no change to the start), the
start SHALL be now, to the minute: it SHALL advance as the clock does while the create screen is shown, and
SHALL move to now when the app returns to the foreground; the last day SHALL move with it to the start's day.
The first choice the host makes in the range SHALL freeze the start where it is, and returning to the
foreground SHALL then keep it. Typing or changing the name SHALL NOT freeze the start. Create SHALL send the
start shown at the moment it is tapped.

#### Scenario: An untouched start keeps up with the clock
- **WHEN** the create screen opens at 18:04 and the host spends ten minutes typing a name without touching the range
- **THEN** the start shown is 18:14, and tapping Create once the end time is set creates the event starting 18:14

#### Scenario: An untouched start moves to now after a short absence
- **WHEN** the host opens the create screen at 18:04, types a name, switches to another app at 18:05 and comes back at 18:12
- **THEN** the start shown is 18:12 and the name is still there

#### Scenario: A start that moved past midnight takes the last day with it
- **WHEN** the create screen opens at 23:58 and the host touches nothing in the range until 00:03 the next day
- **THEN** the start shown is 00:03 on the new day and the last day is the new day

#### Scenario: A choice in the range freezes the start
- **WHEN** the create screen opens at 18:04, the host chooses the end's hour at 18:06, and the clock reaches 18:20
- **THEN** the start shown is still 18:06

#### Scenario: A frozen start survives a short absence
- **WHEN** the host has set the start to 15:00, switches to another app, and comes back 10 minutes later
- **THEN** the start shown is still 15:00, with the rest of the range as the host left it

### Requirement: The next missing step leads to where it is done
While the end time is not set, tapping the range's statement that the end time still has to be picked, or the
screen's line naming the end time as the next missing step, SHALL bring the end-time choice fully into view and
briefly mark it, without setting any time. While the name is missing, tapping the line naming the name as the
next missing step SHALL put the cursor in the name field with the keyboard raised. Once the end time is set, its
statement SHALL do nothing when tapped.

#### Scenario: Tapping the unset end time shows where to set it
- **WHEN** the end time is not set, the end-time choice is scrolled out of view, and the host taps the range's "pick a time"
- **THEN** the end-time choice scrolls fully into view and is briefly highlighted, and the end time is still not set

#### Scenario: Tapping the missing-step line for the end time shows where to set it
- **WHEN** the name is set, the end time is not, and the host taps the line above Create that says to pick an end time
- **THEN** the end-time choice scrolls fully into view and is briefly highlighted

#### Scenario: Tapping the missing-step line for the name starts typing it
- **WHEN** the name is empty and the host taps the line above Create that says to name the event
- **THEN** the name field is in view with the cursor in it and the keyboard raised

#### Scenario: A set end time is not a tap target
- **WHEN** the end time is set and the host taps the range's end statement
- **THEN** nothing changes and nothing scrolls

### Requirement: A long absence starts a fresh draft
When the app returns to the foreground after having been in the background for 15 minutes or more, and the
create screen is still showing because no event was created, the screen SHALL start over: an empty name, the
start at now and following the clock again, the last day the start's day, and the end time unset. After a
shorter absence the screen SHALL keep everything the host entered.

#### Scenario: Coming back after 15 minutes starts over
- **WHEN** the host has typed a name and chosen a range, leaves the app at 18:05, and comes back at 18:20
- **THEN** the create screen shows an empty name, a start of 18:20, today as the last day and the end time not set

#### Scenario: Coming back sooner keeps the draft
- **WHEN** the host has typed a name and chosen a range, leaves the app at 18:05, and comes back at 18:19
- **THEN** the create screen shows the name and range exactly as the host left them

## REMOVED Requirements

### Requirement: The host chooses the event's date range
**Reason**: Its start "SHALL NOT drift while the host is on the screen", and the end time completed on its hour alone. Both change: the start now follows the clock until the host chooses the range, and the end time needs its hour and its minute.
**Migration**: Replaced by "The host chooses the event's date range and its end time", which keeps every other outcome of this requirement unchanged, and by "The start follows the clock until the host chooses the range".
