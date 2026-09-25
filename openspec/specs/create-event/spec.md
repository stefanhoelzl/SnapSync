# create-event Specification

## Purpose
Serves the host: anyone without an event can start one from the app's front screen by naming it and
choosing the event's date range, and then joins it the same way every guest does. It promises that the
host decides, at creation, the one window of capture dates from which any member may ever share photos
(capability `photo-sharing`), that no account or photo access is needed to create, and that the host is
held to that window exactly like every guest. How long the event and its photos live is capability
`event-lifetime`. Decision record: changes/archive/2026-07-22-add-event-date-range

## Requirements
### Requirement: The front screen offers to start an event
While the device is in no event and no invite is being answered, the app SHALL show the create screen:
it asks for the event's name and its date range, offers a Create action, and passively hints that an
event can also be joined by scanning its QR code with the Camera app. The create screen SHALL describe
the app as sharing photos to an event, never as backing up the user's photo library.

#### Scenario: A user with no event sees the create screen
- **WHEN** the app is opened on a device that is in no event and no invite is open
- **THEN** the create screen is shown with a name field, the event's date range, a Create action, and a hint that scanning an event's QR code with the Camera app joins it

#### Scenario: The create screen frames sharing, not backup
- **WHEN** the create screen is shown
- **THEN** its wording frames the app as sharing photos with the event's members and never as backing up the user's library

### Requirement: The event name is required and at most 100 characters
The name field SHALL accept at most 100 characters, and Create SHALL be disabled while the name is empty
or only whitespace. Leading and trailing whitespace SHALL NOT become part of the event's name.

#### Scenario: An empty name cannot be submitted
- **WHEN** the name field is empty or contains only spaces
- **THEN** the Create action is disabled

#### Scenario: The name stops at 100 characters
- **WHEN** the host tries to type a 101st character
- **THEN** the field keeps the first 100 characters only

### Requirement: The host chooses the event's date range
The create screen SHALL always carry a date range and SHALL default it to the moment the screen opened
until the same time one day later; that default SHALL NOT drift while the host is on the screen, so the
range shown is exactly the range created. The host SHALL be able to edit both ends with a date-and-time
picker, choosing dates in the past (to bring photos already taken into the event) or in the future (to
create an event ahead of time). The picker SHALL prevent a range whose start is not before its end and a
range longer than 30 days. The screen SHALL show how long the chosen range lasts and state that only
photos taken during this window are shared.

#### Scenario: The default range is now until tomorrow, frozen when the screen opened
- **WHEN** the create screen opens at 18:04 and the host spends ten minutes typing a name before tapping Create
- **THEN** the range shown and the range created both run from 18:04 today to 18:04 tomorrow

#### Scenario: A past or future range can be chosen
- **WHEN** the host picks a range that started three weeks ago, or one that starts next month
- **THEN** the picker accepts it and the screen shows the new range

#### Scenario: A range longer than 30 days cannot be chosen
- **WHEN** the host tries to set an end more than 30 days after the start
- **THEN** the picker does not allow it, so no such event can be submitted

#### Scenario: An inverted range cannot be submitted
- **WHEN** the chosen start is not before the chosen end
- **THEN** the Create action is disabled

#### Scenario: The duration and the consequence are stated
- **WHEN** the host changes the range to span five days
- **THEN** the screen reads that the event lasts 5 days, beside the statement that only photos taken during the window are shared

### Requirement: Creating needs no photo access
Creating an event SHALL NOT require, request, or be blocked by photo-library access; a missing grant is
dealt with after joining (capability `photo-access`).

#### Scenario: A host who never granted photo access creates an event
- **WHEN** a host who has not granted, or has denied, photo access taps Create with a valid name and range
- **THEN** the event is created and the host is taken to join it, with no photo-access dialog raised by the create step

### Requirement: The creator joins through the same join screen as every guest
While the event is being created the screen SHALL show that it is working, keeping the host in place.
On success the host SHALL be taken to the join screen for the new event (capability `join-event`) with
its date range preselected as the full event window, and SHALL become a member only by confirming
there. The host SHALL be bound by the event's window exactly like any guest. Abandoning that join SHALL
leave the host in no event; the unused event is removed with its lifetime (capability `event-lifetime`).

#### Scenario: A created event opens its join screen
- **WHEN** the host taps Create and the event is created
- **THEN** the join screen for that event opens with its name and the full event window preselected, and the host is not a member until they tap Join

#### Scenario: The host gets no exemption from the window
- **WHEN** the host, on the join screen of the event they just created, tries to share from before the event's start or until after its end
- **THEN** the shared range is held to the event's start and end exactly as for any guest

#### Scenario: Cancelling after creating leaves the host in no event
- **WHEN** the host cancels on the join screen of the event they just created
- **THEN** the create screen is shown again and the device is in no event

### Requirement: A failed create says so and changes nothing
When an event cannot be created, the create screen SHALL return and SHALL show the failure as a message
above the Create action (never by marking the name field as wrong), keeping that message until the next
attempt. It SHALL tell a rejected name apart from the server being
unreachable. The name and date range the host entered SHALL still be there, so a retry is one tap. A
failed create SHALL NOT join the device to anything.

#### Scenario: Offline create reports the server as unreachable
- **WHEN** the host taps Create while the device is offline
- **THEN** a message above Create says the server could not be reached, the name field is not marked as wrong, and the device is still in no event

#### Scenario: A rejected name is reported as such
- **WHEN** the server refuses the event's name
- **THEN** a message above Create says the name was not accepted and suggests trying a different one

#### Scenario: A failed create keeps what the host entered
- **WHEN** a create fails
- **THEN** the create screen shows the name and date range the host had entered, and tapping Create again retries with them

#### Scenario: The failure message stays until the next attempt
- **WHEN** a create has failed and the host types a name without tapping Create
- **THEN** the failure message is still shown, and it disappears when the host taps Create again
