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
directly below the Create action, in place of the hint about scanning a QR code (never by marking the name
field as wrong, and without pushing the date range out of view), keeping that message until the next
attempt. It SHALL tell a rejected name apart from the server being unreachable. The name and date range the
host entered SHALL still be there, so a retry is one tap. A failed create SHALL NOT join the device to
anything.

#### Scenario: Offline create reports the server as unreachable
- **WHEN** the host taps Create as the device goes offline, before the app says it has no network
- **THEN** a message below Create says the server could not be reached, the name field is not marked as wrong, and the device is still in no event

#### Scenario: An unreachable server is reported as such
- **WHEN** the host taps Create while the device has a network and the server cannot be reached
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

### Requirement: Without a network, Create waits

While the app says it cannot reach the network (capability `sync-status`), the create screen SHALL keep its
content and SHALL show the notice — its cause, and when blocked the way to open SnapSync's Settings page — in the
place of the scan hint or the failure message below Create, and Create SHALL be unavailable. When the network
returns, Create SHALL be available again and the line SHALL show what it showed before, a failure message from an
earlier attempt included. A create already under way when the network drops SHALL NOT be interrupted; it ends as
any create ends (as "A failed create says so and changes nothing" requires).

#### Scenario: Offline, Create is unavailable
- **WHEN** a user with no event opens the app while the device is offline
- **THEN** the create screen is shown with its name and date range, the line below Create says the device is
  offline, and Create cannot be tapped

#### Scenario: Blocked, the user is offered Settings
- **WHEN** the create screen is shown and the network is blocked for SnapSync
- **THEN** the line below Create says so and offers to open SnapSync's Settings page

#### Scenario: The network returns
- **WHEN** the network returns while the create screen shows the notice
- **THEN** Create can be tapped again and the line shows the scan hint, or the failure message an earlier
  attempt left

#### Scenario: A create under way is not interrupted
- **WHEN** the host taps Create and the network drops before the event is created
- **THEN** the create either completes or fails as it would have, and the notice shows only once it has ended
