# sync-status Specification

## Purpose

Serves a joined member who wants one answer at a glance: is my part of the event getting there? The
joined screen says the device has joined, shows the event's dates and how long it has left, its invite while
it is open, a single status line and — beneath it — how many photos were shared and received. The status line
is honest in every direction: it never claims "In sync" before the app has actually looked, never hides work
in a direction the member uses, and says plainly when the event has not started, whom an ended event still
waits for, when access is missing, when the app cannot reach the network, or when the device cannot be
verified. The same promise covers how the app behaves as an iPhone app: it opens on a truthful first frame, keeps its place across backgrounding, does its
background work without showing anything, and guides the member through its controls without alarming them.
Decision record: changes/archive/2026-09-29-redesign-status-screen
## Requirements
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

### Requirement: One status line in a fixed priority

The joined screen SHALL show exactly one status line. When several conditions hold at once it SHALL show the
first that applies, in this order: photo access missing; no usable network (as "The app says when it cannot
reach the network" requires); the event has not started; the device cannot be verified; the app is still
reading its state; then "In sync" or synchronization in progress. Limited photo access SHALL NOT count as
missing access (capability `photo-access`).

#### Scenario: Missing access outranks a future start
- **WHEN** a joined member without photo access is in an event that starts tomorrow
- **THEN** the status line asks for photo access, so they can fix it before the event begins

#### Scenario: Missing access outranks a missing network
- **WHEN** a joined member without photo access has no network
- **THEN** the status line asks for photo access

#### Scenario: A missing network outranks everything else
- **WHEN** a joined member with access has no network, in an event that has not started or while everything is
  in sync
- **THEN** the status line says the network is missing, with its cause

#### Scenario: A future start outranks progress
- **WHEN** a member with access is joined to an event that has not started
- **THEN** the status line says sharing starts with the event, whatever work is outstanding

#### Scenario: Limited access shows ordinary progress
- **WHEN** a member with limited access is joined to a started event
- **THEN** the status line shows "In sync" or progress, never the missing-access line

### Requirement: Only missing access and a blocked network make the status line tappable

When photo access is missing, the status line SHALL be a tappable attention line: tapping it SHALL
raise the system's access dialog if access was never decided, and SHALL open the app's page in the phone's
Settings if it was refused (capability `photo-access`). When the network is blocked for SnapSync, the status line
SHALL be tappable too, and tapping it SHALL open the app's page in the phone's Settings. These two SHALL be the
only tappable status lines; the offline line SHALL NOT be tappable.

#### Scenario: Never asked
- **WHEN** access was never decided and the member taps the line
- **THEN** the system's photo-access dialog appears

#### Scenario: Previously refused
- **WHEN** access was refused and the member taps the line
- **THEN** the app's page in the phone's Settings opens

#### Scenario: Network blocked
- **WHEN** the status line says the network is blocked for SnapSync and the member taps it
- **THEN** the app's page in the phone's Settings opens

#### Scenario: Offline
- **WHEN** the status line says the device is offline and the member taps it
- **THEN** nothing happens

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

### Requirement: A device that cannot be verified is shown, and never blamed on the member

The status line SHALL say, when the device holds no usable verification and the latest attempt to
obtain one failed, that this device cannot be verified and that sharing is paused, and SHALL add only what
is true in every case: the app keeps retrying and no photo is lost (the promise itself: capability
`privacy-security`). It SHALL NOT be tappable, name a cause, or ask the member to do anything. It SHALL
NOT appear while the current verification still works, however close to renewal, and SHALL NOT be shown
on opening the app on the strength of a failure from before that opening — only once the attempt that
opening triggers has also failed. It SHALL clear as soon as verification succeeds. While the device has no
usable network, the network line SHALL be shown instead (as "One status line in a fixed priority" requires).

#### Scenario: Unreachable server with an expired verification
- **WHEN** the device has a network, its verification has expired, and renewing it fails because the server
  cannot be reached
- **THEN** the status line says the device cannot be verified, sharing is paused, the app keeps
  retrying and no photo is lost — with no button and no suggested remedy

#### Scenario: Offline with an expired verification
- **WHEN** the device's verification has expired and renewing it fails because the device is offline
- **THEN** the status line says the device is offline, not that it cannot be verified

#### Scenario: A verification about to be renewed is not an alarm
- **WHEN** the device's verification still works but is due for renewal and a renewal attempt fails
- **THEN** the status line shows the ordinary sync status

#### Scenario: Opening the app retries before alarming
- **WHEN** an earlier background attempt failed and the member opens the app
- **THEN** the ordinary status shows until the attempt triggered by opening also fails; if it succeeds
  the cannot-verify line never appears

### Requirement: The app says when it cannot reach the network

While the app is open, it SHALL tell the user when the device gives it no usable network, and SHALL say which of
two causes applies: the network is **blocked** for SnapSync — a setting of SnapSync's the user can change, such as
its mobile-data switch — or the device is **offline** — it has no network at all. When blocked, the notice SHALL
offer to open SnapSync's page in the phone's Settings; when offline it SHALL offer nothing, since only connecting
helps. The notice SHALL NOT appear for a drop that lasts only a few seconds, and SHALL clear as soon as the
network returns. A server that does not answer while the device has a network SHALL NOT be reported this way.
Mobile data being switched off for SnapSync while the device is on Wi-Fi SHALL NOT be reported, because the app
can use the network then. When the network returns while the app is open, the app SHALL resume its work at once —
what opening the app does — without the user doing anything. Where the notice appears and what it prevents is
each screen's: the joined screen's status line (below), the create screen (capability `create-event`) and the
join screen (capability `join-event`).

#### Scenario: Mobile data switched off for SnapSync, away from Wi-Fi
- **WHEN** the user has turned off mobile data for SnapSync and the device has only mobile data
- **THEN** the app says the network is blocked for SnapSync and offers to open its Settings page

#### Scenario: Airplane mode
- **WHEN** the device has no network at all
- **THEN** the app says the device is offline and offers no Settings action

#### Scenario: A brief drop shows nothing
- **WHEN** the network drops for a second or two and comes back
- **THEN** no notice appears

#### Scenario: Mobile data off but on Wi-Fi
- **WHEN** mobile data is switched off for SnapSync and the device is on Wi-Fi
- **THEN** no notice appears

#### Scenario: The server is down, not the network
- **WHEN** the device has a network but the server does not answer
- **THEN** no network notice appears; whatever failed says so as it does without this notice

#### Scenario: The network returns while the app is open
- **WHEN** the notice is shown and the network returns
- **THEN** the notice clears and the app resumes its work — received photos start arriving and the status
  updates — without the user leaving or reopening the app

### Requirement: "In sync" is never claimed before the app has looked

The status line SHALL NOT read "In sync" until the app has actually read, since it was opened, what the
member has to share, what has been shared, and what there is to receive. Until then it SHALL show a
neutral in-progress line with no check mark, no arrows and no attention styling. A read that finds
nothing to do SHALL settle to "In sync".

#### Scenario: A cold launch shows the neutral line
- **WHEN** the app launches into a joined event and has not finished reading its state
- **THEN** the status line is neutral and never shows "In sync"

#### Scenario: A short visit never shows a false settle
- **WHEN** the member opens the app and leaves it before any read completes
- **THEN** "In sync" was never shown during that visit

#### Scenario: Outstanding work never reads as a regression
- **WHEN** the first read completes and finds work outstanding
- **THEN** the line moves from the neutral line to synchronization in progress, never from "In sync"

#### Scenario: Nothing to share still settles
- **WHEN** the member shares nothing and everything to receive has arrived
- **THEN** the status line reads "In sync"

### Requirement: Direction arrows show remaining work and live transfer

While work remains, the status line SHALL show an upload arrow when some of the member's photos are
not yet shared and a download arrow when some of the others' photos have not yet arrived. An arrow
SHALL pulse while a transfer in its direction is actually running and stay still while work waits; the
line SHALL read "Synchronization ongoing…" when any arrow pulses and "Synchronization pending…"
otherwise. "In sync" SHALL be shown exactly when neither arrow is shown. A direction the member switched
off has no work and therefore no arrow — but if the app ever does work in a switched-off direction, that
arrow SHALL be shown rather than hidden.

#### Scenario: A new photo waiting to upload
- **WHEN** the member takes an in-range photo and no upload is running yet
- **THEN** a still upload arrow appears with "Synchronization pending…"

#### Scenario: Photos arriving
- **WHEN** the member's uploads are done and others' photos are downloading
- **THEN** only the download arrow shows, pulsing, with "Synchronization ongoing…"

#### Scenario: Receive-only ignores the member's own gallery
- **WHEN** a receive-only member has unshared photos in their library and all received photos have
  arrived
- **THEN** the status line reads "In sync"

#### Scenario: Work in a switched-off direction is not masked
- **WHEN** a receive-only member's device nevertheless has uploads outstanding
- **THEN** the upload arrow is shown and the line does not read "In sync"

### Requirement: Progress counts only what this membership shares

The upload side of the status SHALL count exactly the member's photos this membership shares
(capability `photo-sharing`) — a photo counts from the moment it is taken, photos the rules exclude
never count, photos received from others never count, and uploads made for other events never stand in
for this event's photos — so the status can always reach "In sync" and never reaches it early. The
download side SHALL grow as other members add photos.

#### Scenario: An excluded screenshot does not hold the status open
- **WHEN** the member takes a screenshot during the event and all their camera photos are shared
- **THEN** the status line reads "In sync"

#### Scenario: Earlier uploads do not mask this event's work
- **WHEN** the device uploaded many photos for a previous event and three of this event's photos are
  still unshared
- **THEN** the upload arrow shows until those three are shared

#### Scenario: New contributions reopen the download side
- **WHEN** another member adds photos while this member is "In sync"
- **THEN** the download arrow appears until the new photos have arrived

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

### Requirement: The status stays current while the app is open

While the app is in the foreground, any change in what has been shared or received SHALL reach the
status line within about two seconds, including uploads finished by iOS in the background and photos
the app just discovered. Opening the app SHALL show read counts promptly even if background work from
an earlier session has not finished. A failed read SHALL keep the last known status rather than
changing it.

#### Scenario: A background upload finishes while the app is open
- **WHEN** the app is open and iOS finishes uploading one of the member's photos in the background
- **THEN** the status line reflects it within about two seconds

#### Scenario: Photos discovered after opening leave "In sync"
- **WHEN** the app is open showing "In sync" and it discovers new photos from other members
- **THEN** within about two seconds the line leaves "In sync"

#### Scenario: A stuck earlier upload does not blank the status
- **WHEN** the member opens the app while an upload pass from an earlier session is still unwinding
- **THEN** the status line shows real progress rather than staying on the neutral line

#### Scenario: A failed read does not flip the status
- **WHEN** the status line reads "In sync" and one refresh fails to read
- **THEN** it keeps reading "In sync"

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

### Requirement: The app is a portrait iPhone app that follows the system appearance

The app SHALL run on iPhone in upright portrait only, never rotating, and SHALL follow the system's
light or dark appearance, with no white flash when opening in dark mode.

#### Scenario: Rotating the phone
- **WHEN** the member turns the phone to landscape
- **THEN** the app stays upright portrait

#### Scenario: Launching in dark mode
- **WHEN** the phone is in dark mode and the app is launched
- **THEN** the app opens dark without a white flash

### Requirement: The app keeps its place across backgrounding

Returning to the app from the background SHALL show the same screen the member left, with any open
surface and any half-typed text intact, and SHALL never present a blank or corrupted screen — unless the
membership ended on its own meanwhile (capability `manage-membership`), in which case it SHALL show the
create screen.

#### Scenario: Returning to an open settings surface
- **WHEN** the member opens settings, switches to another app, and comes back later
- **THEN** the settings surface is still open as they left it

#### Scenario: Returning after hours in the background
- **WHEN** the app was woken in the background several times and the member opens it hours later
- **THEN** it renders normally, never blank

#### Scenario: Returning after the event finished
- **WHEN** the member left the joined screen open, the event finished and the app left it in the
  background, and the member returns
- **THEN** the create screen is shown

### Requirement: Background wakes do their work without showing anything

The app SHALL do the sharing and receiving work of a background wake — a silent notification, a
scheduled task or a finished transfer — exactly as it would in the foreground, without presenting any
screen.

#### Scenario: A silent notification wakes the app
- **WHEN** another member adds photos and iOS wakes the app in the background
- **THEN** the new photos are fetched and imported, and no screen is shown

### Requirement: The first frame is never a guessed state

The first frame the app shows SHALL be derived from what the device actually holds: a joined member
SHALL see the joined screen (with the neutral line until state is read), and a member with no event the
create screen. A read of the membership that fails temporarily — including on a locked device — SHALL
NOT drop a joined member to the create screen.

#### Scenario: Joined launch
- **WHEN** a joined member launches the app
- **THEN** the first frame is the joined screen with the neutral status line, never a guessed status
  that later corrects itself

#### Scenario: A locked-device wake keeps the membership
- **WHEN** the app is woken in the background before the phone has been unlocked since restart
- **THEN** the membership is kept, nothing is reset, and the work is done after the next unlock

### Requirement: Taps keep working and never fire twice

After any action fails, every later tap SHALL still work. An action that is already running SHALL NOT
run again from a second tap, and its result SHALL NOT appear on a surface the member has since left.

#### Scenario: A failure does not freeze the app
- **WHEN** one action fails and the member then taps another
- **THEN** the second action runs

#### Scenario: Double tap
- **WHEN** the member taps an action twice before the screen reacts
- **THEN** it runs once

### Requirement: Failures are told calmly, never as a red field

A failure after something the member submitted SHALL be stated as a message above the action, and a
problem the member did not cause — an invalid invite, an unreachable or missing event — SHALL be a
neutral notice. The app SHALL NOT mark a field the member typed in as erroneous for a failure that came
from elsewhere.

#### Scenario: A submission is refused
- **WHEN** the member creates an event and the request fails
- **THEN** the failure appears as a message above the Create action, and the name field is not reddened

### Requirement: Controls are accessible and honour reduced motion

Every interactive row SHALL be a single control announcing its role and on/off or checked state; an
unavailable control SHALL stay present and be announced as unavailable rather than disappearing. With
iOS's reduce-motion setting on, the status arrows SHALL NOT pulse — a transferring arrow still shows as
transferring, without moving.

#### Scenario: Reduce motion
- **WHEN** reduce motion is on and a transfer is running
- **THEN** the arrow is shown as transferring without moving

#### Scenario: A dimmed choice
- **WHEN** VoiceOver reaches a choice that is currently unavailable
- **THEN** it is announced as one control, unavailable

### Requirement: Explanations always match the current choices

Every line that explains the consequence of a choice SHALL reflect the choices currently made, so the
screen never promises an album, a feed or a date range the membership will not produce.

#### Scenario: Changing a choice updates its explanation
- **WHEN** the member changes the date range they share
- **THEN** the line stating what will be shared updates to the new range at once

### Requirement: Picking a date range is guided and cannot go wrong

Picking a date range SHALL happen in one dialog holding the dates and both times, committed by one
confirmation. The first tap on a day SHALL set the start, the second the end (the same day twice meaning
a single day), and a third SHALL start over; changing the days SHALL keep the chosen times. Where a
range must lie inside the event's window, days and times outside it SHALL be unselectable and a picked
value SHALL be kept inside it. An end before the start SHALL be unreachable. Cancelling SHALL leave the
previous choice untouched. A "Now" choice that falls outside the event's window SHALL be shown disabled,
not hidden.

#### Scenario: A range inside the window
- **WHEN** the member picks a custom range for what they share
- **THEN** only days within the event's window can be picked, and the confirmed range lies inside it
  with its end after its start

#### Scenario: Third tap starts over
- **WHEN** a start and end day are picked and the member taps a third day
- **THEN** that day becomes the new start

#### Scenario: Cancel keeps the old choice
- **WHEN** the member opens the custom picker and cancels
- **THEN** the previous choice is unchanged

#### Scenario: Now before the event
- **WHEN** the member joins an event that has not started
- **THEN** the "Now" choice is visible but disabled

### Requirement: Text entry sheets stay usable while typing

A sheet that asks for a line of text SHALL keep its field and both actions visible while the keyboard
is shown, SHALL keep confirm disabled while the text is empty or unchanged from what it opened with,
SHALL trim surrounding spaces, and SHALL show a refusal as a message rather than on the field. While its
action is running it SHALL stay open, show that it is working, and refuse both a second confirm and
dismissal.

#### Scenario: The keyboard does not cover confirm
- **WHEN** the member types into a text sheet with the keyboard up
- **THEN** the confirm and cancel actions remain visible and tappable

#### Scenario: Unchanged text cannot be submitted
- **WHEN** a sheet opens with an existing value and the member has not changed it
- **THEN** confirm is disabled

#### Scenario: A running sheet cannot be dismissed
- **WHEN** the member has confirmed and the action is still running
- **THEN** the sheet stays open showing progress, and neither cancel nor swiping it away closes it
