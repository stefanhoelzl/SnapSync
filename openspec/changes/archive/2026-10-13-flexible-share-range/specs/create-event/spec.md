## MODIFIED Requirements

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
photos taken between these dates are shared, and that each member can widen or narrow their own range
(capability `join-event`).

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
- **THEN** the screen reads that the event lasts 5 days, beside the statement that photos taken between these dates are shared and that each member can widen or narrow their own range

### Requirement: The creator joins through the same join screen as every guest
While the event is being created the screen SHALL show that it is working, keeping the host in place.
On success the host SHALL be taken to the join screen for the new event (capability `join-event`) with
the event's own dates preselected as the range they share, and SHALL become a member only by confirming
there. The host SHALL be bound by the same range rule as any guest (capability `join-event`). Abandoning that join SHALL
leave the host in no event; the unused event is removed with its lifetime (capability `event-lifetime`).

#### Scenario: A created event opens its join screen
- **WHEN** the host taps Create and the event is created
- **THEN** the join screen for that event opens with its name and the event's own dates preselected, and the host is not a member until they tap Join

#### Scenario: The host gets no exemption from the window
- **WHEN** the host, on the join screen of the event they just created, tries to share a range longer than 30 days or one that does not overlap the event's dates
- **THEN** the calendar does not allow it, exactly as for any guest

#### Scenario: Cancelling after creating leaves the host in no event
- **WHEN** the host cancels on the join screen of the event they just created
- **THEN** the create screen is shown again and the device is in no event
