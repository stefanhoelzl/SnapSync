## MODIFIED Requirements

### Requirement: The joined screen is whole in every state

Once an event is joined, the app SHALL show one joined screen carrying the event's name, a statement that
this device has joined the event, the event's dates, its invite (capability `manage-membership`), a single
status line, and the membership actions — settings, share, leave and rename. Every one of those SHALL be
present whatever the status line says, including without photo access, before the event starts, and while
the device cannot be verified. The joined statement SHALL read the same for the member who created the event
and for every other member. Once the event has closed (capability `event-lifetime`), the joined screen SHALL
carry only the event's name, the joined statement, the event's dates, the status line and Leave.

#### Scenario: No access still shows everything
- **WHEN** a joined member has no photo access
- **THEN** the event name, the joined statement, the dates, the invite, settings, share, leave and rename
  are all present, with the status line asking for access

#### Scenario: Before the start everything is available
- **WHEN** a joined event has not started yet
- **THEN** the member can already share the invite, change settings, rename and leave

#### Scenario: Host and guest are told the same
- **WHEN** the member who created an event and a guest who scanned its invite each open the joined screen
- **THEN** both are told they have joined the event, in the same words

#### Scenario: A closed event keeps only Leave
- **WHEN** a member opens the joined screen of an event that has closed and is still receiving its last
  photos
- **THEN** the event name, the joined statement, the dates, the status line and Leave are shown, and no
  invite, share, settings or rename

### Requirement: A not-yet-started event says when it starts

Before the event's start, the joined screen's dates SHALL say how long until the event starts (as "The joined screen shows how long
the event lasts" requires), and the status line SHALL say that sharing
starts with the event, without restating the start, and SHALL NOT be tappable. The status line SHALL give way
to the ordinary status within a minute of the start passing while the app is open, without any other trigger.

#### Scenario: Before the start
- **WHEN** a member with photo access is joined to an event that starts in two days
- **THEN** the dates say the event starts in 2 days, and the status line says sharing starts with the event

#### Scenario: The line retires itself
- **WHEN** the app is open before the start and the start passes
- **THEN** within a minute the status line shows the ordinary sync status

### Requirement: An ended event is marked without stopping sync

After the end of the event's date range, the joined screen's dates SHALL say the event has ended — never
merged into the status line's text — and SHALL say so within a minute of the end passing while the app is
open. While the event has not closed and the status line reads "In sync", the screen SHALL also say, with the
photo counts, how many of the event's current members it is still waiting for to finish sharing (capability
`event-lifetime`). Ending SHALL change nothing else: arrows, status, counts and syncing continue exactly as
before (the end bounds only which photos may be shared — capability `event-lifetime`).

#### Scenario: Ended and still syncing
- **WHEN** the event's range has ended and uploads are still outstanding
- **THEN** the dates say the event has ended, the status line reads "Synchronization pending…" and the
  uploads continue

#### Scenario: The marker appears while open
- **WHEN** the app is open and the event's end passes
- **THEN** within a minute the dates say the event has ended

#### Scenario: Waiting for the others
- **WHEN** the range has ended, this member is in sync, and two of the event's five members have not yet
  finished sharing
- **THEN** the screen says the event is waiting for 2 of 5 members

## ADDED Requirements

### Requirement: One status line in a fixed priority

The joined screen SHALL show exactly one status line. When several conditions hold at once it SHALL show the
first that applies, in this order: photo access missing; the event has not started; the device cannot be
verified; the app is still reading its state; then "In sync" or synchronization in progress. Limited photo
access SHALL NOT count as missing access (capability `photo-access`).

#### Scenario: Missing access outranks a future start
- **WHEN** a joined member without photo access is in an event that starts tomorrow
- **THEN** the status line asks for photo access, so they can fix it before the event begins

#### Scenario: A future start outranks progress
- **WHEN** a member with access is joined to an event that has not started
- **THEN** the status line says sharing starts with the event, whatever work is outstanding

#### Scenario: Limited access shows ordinary progress
- **WHEN** a member with limited access is joined to a started event
- **THEN** the status line shows "In sync" or progress, never the missing-access line

### Requirement: The joined screen shows how long the event lasts

The joined screen SHALL show the event's date range in the device's own timezone — as its first and last
calendar day, or, for an event that starts and ends on the same day, as that day with its start and end
times — together with how far the event is in its life: how long until it starts, how long until it ends,
or that it has ended. The time until a start or an end SHALL be given in whole days while a day or more
remains, then in hours, then in minutes. While the app is open, what the dates say SHALL stay correct to
within a minute without any other trigger.

#### Scenario: A running event counts down in days
- **WHEN** a member opens the joined screen of an event running from 12 to 14 July, with two days and five
  hours left
- **THEN** the screen shows 12 to 14 July and that the event ends in 2 days

#### Scenario: The last day counts in hours, then minutes
- **WHEN** five hours of the event remain, and later forty minutes
- **THEN** the screen says it ends in 5 hours, and later that it ends in 40 minutes

#### Scenario: A same-day event shows its times
- **WHEN** a member is joined to an event running today from 18:00 to 23:00
- **THEN** the screen shows today, 18:00 to 23:00, and how long until it ends

#### Scenario: Dates in the device's timezone
- **WHEN** an event starts at 22:00 UTC on 13 July and the device is in a UTC+2 timezone
- **THEN** the range shown begins on 14 July

### Requirement: The joined screen counts what was shared and received

Beneath the status line, the joined screen SHALL count, for each direction, the member's photos this
membership shares and how many of them are shared (capability `photo-sharing`, counted as the upload side of
"Progress counts only what this membership shares"), and the other members' photos there are to receive and
how many of them arrived (capability `receiving-photos`). A direction with work remaining SHALL show both
numbers; a complete direction SHALL show its total alone. A direction the member switched off SHALL say it is
off instead of numbers — unless the device nevertheless has work in that direction, which SHALL be counted
like any other rather than hidden. The counts SHALL be shown only while the status line reads "In sync" or
synchronization in progress, and SHALL be hidden in every other status. They SHALL stay as current as the
status line and never contradict it: "In sync" SHALL NOT be shown beside a direction with work remaining.

#### Scenario: Syncing in both directions
- **WHEN** 12 of the member's 15 photos are shared and 40 of 52 of the others' photos have arrived
- **THEN** the counts read 12 of 15 shared and 40 of 52 received

#### Scenario: In sync shows totals
- **WHEN** all 15 of the member's photos are shared and all 52 of the others' have arrived
- **THEN** the status line reads "In sync" and the counts read 15 shared and 52 received

#### Scenario: Sharing switched off
- **WHEN** a receive-only member has 40 of 52 photos received
- **THEN** the counts say the member is not sharing, and 40 of 52 received

#### Scenario: Work in a switched-off direction is counted
- **WHEN** a receive-only member's device nevertheless has 3 uploads outstanding
- **THEN** the counts show those uploads as shared-of-total numbers rather than "not sharing"

#### Scenario: No counts while access is missing
- **WHEN** a joined member without photo access opens the joined screen
- **THEN** no counts are shown, only the status line asking for access

#### Scenario: No counts before the start or before the app has looked
- **WHEN** the event has not started, or the app has not yet read its state since it was opened
- **THEN** no counts are shown

#### Scenario: Limited access shows counts
- **WHEN** a member with limited access, 6 selected photos shared, is in sync
- **THEN** the counts read 6 shared, alongside the choices to widen access

## REMOVED Requirements

### Requirement: One status line, no numbers, in a fixed priority
**Reason**: The joined screen counts photos again ("The joined screen counts what was shared and received");
the "no numbers" half no longer holds.
**Migration**: The priority order moves unchanged to "One status line in a fixed priority".
