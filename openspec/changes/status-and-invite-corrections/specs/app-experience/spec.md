## MODIFIED Requirements

### Requirement: The first frame is never a guessed state

The first frame the app shows SHALL be derived from what the device actually holds: a joined member
SHALL see the joined screen (with the neutral line until state is read, or a condition that outranks it already
known — capability `sync-status`), and a member with no event the create screen. A read of the membership that
fails temporarily — including on a locked device — SHALL NOT drop a joined member to the create screen. While the
app cannot read whether the device is in an event, and has not read it since it started, it SHALL say that the
event cannot be read right now and that the member has not left it, and SHALL offer to try again, which shows that
it is working while it does; the app menu SHALL stay in reach. Once a read succeeds, the app SHALL show what it
found.

#### Scenario: Joined launch
- **WHEN** a joined member launches the app
- **THEN** the first frame is the joined screen with the neutral status line, or a condition the app already
  knows such as missing photo access, never a guessed status that later corrects itself

#### Scenario: A locked-device wake keeps the membership
- **WHEN** the app is woken in the background before the phone has been unlocked since restart
- **THEN** the membership is kept, nothing is reset, and the work is done after the next unlock

#### Scenario: An unreadable membership is told, not taken for a leave
- **WHEN** the app is opened and cannot read whether the device is in an event, having read nothing since it
  started
- **THEN** it says the event cannot be read right now and the member has not left it, offers to try again, and
  shows the joined screen once trying again succeeds

### Requirement: Failures are told calmly, never as a red field

A failure after something the member submitted SHALL be stated as a message beside the action, and a
problem the member did not cause — an invalid invite, an unreachable or missing event — SHALL be a
neutral notice. The app SHALL NOT mark a field the member typed in as erroneous for a failure that came
from elsewhere.

#### Scenario: A submission is refused
- **WHEN** the member creates an event and the request fails
- **THEN** the failure appears as a message below the Create action, and the name field is not reddened

### Requirement: Picking a date range is guided and only offers what is allowed

Picking the range a member shares SHALL happen in one dialog holding the dates and both times, committed by one
confirmation. The first tap on a day SHALL set the start, the second the end (the same day twice meaning
a single day), and a third SHALL start over; changing the days SHALL keep the chosen times. The event's own range
is picked on the create screen itself (capability `create-event`). Where a
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

### Requirement: Text entry sheets stay usable while typing

A sheet that asks for a line of text SHALL keep its field and both actions visible while the keyboard
is shown, SHALL keep confirm disabled while the text is empty or unchanged from what it opened with — except a
description the app wrote for the member to send, which may be confirmed as it stands (capability
`privacy-security`) — SHALL trim surrounding spaces, and SHALL show a refusal as a message rather than on the
field. While its action is running it SHALL stay open, show that it is working, and refuse both a second confirm and
dismissal.

#### Scenario: The keyboard does not cover confirm
- **WHEN** the member types into a text sheet with the keyboard up
- **THEN** the confirm and cancel actions remain visible and tappable

#### Scenario: Unchanged text cannot be submitted
- **WHEN** a sheet opens with an existing value and the member has not changed it
- **THEN** confirm is disabled

#### Scenario: A report the app wrote can be sent as it stands
- **WHEN** the app offers to report a problem it found and opens the report sheet with the description written
- **THEN** confirm is available without the member changing the text

#### Scenario: A running sheet cannot be dismissed
- **WHEN** the member has confirmed and the action is still running
- **THEN** the sheet stays open showing progress, and neither cancel, swiping it away, tapping outside it nor
  Android's back gesture closes it or moves it off the screen
