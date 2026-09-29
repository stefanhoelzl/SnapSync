## MODIFIED Requirements

### Requirement: The host chooses the event's date range
The create screen SHALL always carry a date range whose start is preset to the moment the screen opened,
to the minute; that preset SHALL NOT drift while the host is on the screen, so the start shown is exactly
the start created. The range's last day SHALL be preset to the start's day, but the time the event ends
SHALL start unset and SHALL be chosen by the host; choosing a last day SHALL NOT fill in that time. Create
SHALL be disabled until the name is set and the end time is chosen. The host SHALL be able to change the
start's day and time and the end's day and time, choosing dates in the past (to bring photos already taken
into the event) or in the future (to create an event ahead of time); the start and end may fall on the same
day. The screen SHALL prevent a range whose start is not before its end and a range longer than 30 days.
While Create is disabled, the screen SHALL name the next thing the host still has to do: the name, then the
end time. Once the range is complete, the screen SHALL show how long it lasts. The screen SHALL state that
only photos taken during this window are shared.

#### Scenario: The start is preset to when the screen opened, and does not drift
- **WHEN** the create screen opens at 18:04 and the host spends ten minutes typing a name before choosing the end
- **THEN** the start shown, and the start created, is 18:04 today

#### Scenario: The last day starts as today, but not the end time
- **WHEN** the create screen opens
- **THEN** the range shows today as its last day and the end time as not yet set

#### Scenario: A name alone does not allow Create
- **WHEN** the host types a name and has not set the end time
- **THEN** Create is disabled and the screen says the end time is what is still needed

#### Scenario: Choosing a later last day does not fill in the end time
- **WHEN** the host has typed a name and chooses a later day as the event's last day
- **THEN** the end time is still shown as not yet set, and Create stays disabled

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

### Requirement: A failed create says so and changes nothing
When an event cannot be created, the create screen SHALL return and SHALL show the failure as a message
directly below the Create action, in place of the hint about scanning a QR code (never by marking the name
field as wrong, and without pushing the date range out of view), keeping that message until the next
attempt. It SHALL tell a rejected name apart from the server being unreachable. The name and date range the
host entered SHALL still be there, so a retry is one tap. A failed create SHALL NOT join the device to
anything.

#### Scenario: Offline create reports the server as unreachable
- **WHEN** the host taps Create while the device is offline
- **THEN** a message below Create says the server could not be reached, the name field is not marked as wrong, and the device is still in no event

#### Scenario: A rejected name is reported as such
- **WHEN** the server refuses the event's name
- **THEN** a message below Create says the name was not accepted and suggests trying a different one

#### Scenario: A failed create keeps what the host entered
- **WHEN** a create fails
- **THEN** the create screen shows the name and the complete date range the host had entered, and tapping Create again retries with them

#### Scenario: The failure message stays until the next attempt
- **WHEN** a create has failed and the host types a name without tapping Create
- **THEN** the failure message is still shown in place of the scan hint, and it disappears — the scan hint returning — when the host taps Create again
