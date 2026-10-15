# join-event Specification

## Purpose
Serves the guest (and the host, who joins the event they just created the same way): an invite opens
SnapSync on a join screen where they see which event they are invited to, decide whether to share and whether to
receive, and choose the capture-date range they share from — and nothing is shared or joined until they confirm. It
promises that a guest who arrives late can still join until the event's photos are deleted, and that a membership shares
only the bounded capture range its member chose, so a guest's whole camera roll is never uploaded. A device is in at most one event at a time.
The invite itself — its format, where it leads, and what opening another event's invite does — is capability
`invite-link`; what a chosen range admits is capability `photo-sharing`; the album choice is capability `event-album`.
Decision record: changes/archive/2026-07-06-add-event-join-confirmation

## Requirements

### Requirement: The join screen verifies the event before offering to join
The join screen SHALL open at once and load the event's details, offering Join only once they have
loaded. A missing event SHALL be shown as an invalid or expired invite with no way to join. A failure to
load while the device has a network (an unreachable server, a failing service, or an incomplete answer such as an
event without a name) SHALL be shown with a Retry, telling a failing service as the condition that applies
(capability `app-experience`, "A failing service is told as what it is"); a load that failed for want of a network
is shown as "Without a network, the join screen waits for one" requires. When the load failed because the service
is being updated, the join screen SHALL also load the event's details again by itself once the service suggests it
is worth asking again, while still offering Retry. The join screen SHALL never rest in a waiting state with
nothing to tap: whenever loading or joining ends, however it ends, the user SHALL be offered at least a way out.

#### Scenario: An event that does not exist cannot be joined
- **WHEN** a guest opens an invite for an event that no longer exists
- **THEN** the join screen says the invite is invalid or the event no longer exists, and offers only Cancel

#### Scenario: Offline, the event can be retried
- **WHEN** a guest opens an invite while offline
- **THEN** the join screen says the device is offline and offers Cancel, and loads the event by itself once the
  connection is back

#### Scenario: An unreachable server can be retried
- **WHEN** a guest opens an invite while the device has a network but the server cannot be reached
- **THEN** the join screen says the event could not be loaded and offers Retry and Cancel, and Retry loads it once the server answers

#### Scenario: A service in trouble can be retried
- **WHEN** a guest opens an invite and the service answers that it failed
- **THEN** the join screen says SnapSync is having trouble and offers Retry and Cancel, and Retry loads the event
  once the service works

#### Scenario: An update in progress loads by itself
- **WHEN** a guest opens an invite while the service is being updated, and the update ends with the join screen
  still open
- **THEN** the join screen says SnapSync is being updated and is back shortly, offers Retry and Cancel, and loads
  the event's details without the guest tapping anything

#### Scenario: An unexpected failure never strands the user
- **WHEN** loading the event or joining it fails in an unexpected way
- **THEN** the screen moves to one that offers at least Cancel, never an endless spinner

### Requirement: Without a network, the join screen waits for one

While the app says it cannot reach the network (capability `sync-status`), the join screen SHALL show the notice —
its cause, and when blocked the way to open SnapSync's Settings page — and Join SHALL be unavailable; Cancel SHALL
stay available. When the event's details could not be loaded because there was no network, the screen SHALL say
that instead of a generic load failure, SHALL offer no Retry while there is no network, and SHALL load the
event's details by itself once the network returns. A join already under way when the network drops SHALL NOT be
interrupted; it ends as any join ends (as "Joining happens only on confirmation and needs a connection"
requires).

#### Scenario: An invite opened offline loads once the network returns
- **WHEN** a guest opens an invite while the device is offline, and later the network returns with the join
  screen still open
- **THEN** the join screen says the device is offline and offers only Cancel, and once the network is back it
  loads the event's details without the guest tapping anything

#### Scenario: Offline after the details loaded
- **WHEN** the join screen shows an event's details and the device goes offline
- **THEN** the screen says the device is offline, Join cannot be tapped, and Cancel still can

#### Scenario: Blocked, the guest is offered Settings
- **WHEN** the join screen is open and the network is blocked for SnapSync
- **THEN** the screen says so and offers to open SnapSync's Settings page

### Requirement: A late guest can still join
Joining SHALL NOT be refused because the event's date range has passed. Until the event's photos are deleted
(capability `event-lifetime`), a guest who opens the invite after the event's end SHALL join like anyone
else and share the photos they took within the event's range, however long after the end that is.

#### Scenario: A guest scans days after the party
- **WHEN** a guest opens the invite three days after the event's date range ended, before its photos were
  deleted
- **THEN** the join screen offers Join with the full event window preselected, and after joining their photos from the event's dates are shared

#### Scenario: A guest of a back-dated event
- **WHEN** a host creates an event for a weekend that is already over and a guest opens its invite the next day
- **THEN** the guest joins like anyone else, and their photos from that weekend are shared

### Requirement: The join screen lets the member decide separately whether to share, whether to receive, and whether to keep an album
The join screen SHALL show the event's name and two switches — share my photos, and receive everyone's
photos — both on by default, and SHALL NOT make the user pick a named mode. Neither switch SHALL ever
flip the other. With sharing on, the screen SHALL state what is never shared (screenshots, screen
recordings, GIFs and pictures saved from chat apps, capability `photo-sharing`) — except under limited photo
access, where it SHALL instead state that the photos the user selected from the range are shared — and the
capture range being shared; with it off, it SHALL say that nothing of the user's leaves the phone and hide the range.
With both switches off, Join SHALL be disabled and the reason stated beside it. The screen SHALL also offer
the event-album choice (capability `event-album`), whose note names only the photos the current switches would
collect — on Android only received photos ever are. The screen SHALL NOT offer the choice whether photos may
use mobile data: that is the device's, made in the app menu (capability `mobile-data`), and a join follows it.

#### Scenario: Both switches start on
- **WHEN** the join screen has loaded an event
- **THEN** it shows the event's name, "share my photos" on with the shared range and the exclusions, and "receive everyone's photos" on

#### Scenario: Receive only
- **WHEN** the user turns sharing off and joins
- **THEN** none of the user's photos are shared to the event, and the event's photos arrive in their library (capability `receiving-photos`)

#### Scenario: Share only
- **WHEN** the user turns receiving off and joins
- **THEN** the user's photos in range are shared and none of the event's photos arrive in their library

#### Scenario: Both off blocks Join with a reason
- **WHEN** the user turns both switches off
- **THEN** Join is disabled, a line above it explains that a membership that neither shares nor receives does nothing, and neither switch turns itself back on

#### Scenario: The album choice on Android
- **WHEN** the join screen has loaded an event on an Android phone
- **THEN** it offers the two switches, the range, and the album choice switched on, whose note names the photos they receive

#### Scenario: Joining follows the device's mobile-data choice
- **WHEN** a user who turned mobile data off in the app menu loads an event and joins
- **THEN** the join screen offers no mobile-data choice, and the membership's photos travel only on Wi-Fi (capability `mobile-data`)

#### Scenario: Limited access is told what is shared
- **WHEN** a user whose photo access is limited to a selection loads an event with sharing on
- **THEN** the join screen says that the photos they selected from the range are shared, and names no exclusions

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

### Requirement: The join screen shows how many photos will be shared
With sharing on, the join screen SHALL show a live count of the user's own photos the chosen range would
share, equal to what will actually be shared once joined (capability `photo-sharing`). The count SHALL
update as the range changes, SHALL show that it is counting while it recomputes, and SHALL be computed on
the device without contacting any server. A count of zero SHALL add that new photos will be shared as
they are taken. The count SHALL be hidden when sharing is off, and omitted when photo access does not
allow counting or the count cannot be computed.

#### Scenario: The count follows the range
- **WHEN** the user moves the start of the range earlier so more of their photos fall inside it
- **THEN** the row briefly shows it is counting, then reads the new number of photos from their gallery that will be shared

#### Scenario: Zero is explained
- **WHEN** the chosen range contains none of the user's photos
- **THEN** the row reads that 0 photos from the gallery will be shared and that new photos will be shared as they are taken

#### Scenario: No count without access
- **WHEN** photo access was denied or has not been asked yet
- **THEN** the join screen shows no count row

### Requirement: Photo access is explained before the system ever asks
To a user who is in no event and has never been asked for photo access, the join screen SHALL state,
once it has loaded the event, that the phone's system will ask for photo access next, and SHALL offer on
request an explanation stating that photos they take are shared automatically, that the photo library is
needed both to share and to save others' photos, that choosing specific photos also works where the phone
offers it, and that only photos in the range they chose are shared. Viewing the explanation SHALL raise
nothing. For this user the confirm action SHALL say that it also allows photo access, and tapping it SHALL
raise the system's photo access dialog and join the event whatever they answer (without access, capability
`photo-access`). The system's dialog SHALL NOT be raised before that tap. A user who has already granted,
limited, or denied access SHALL see neither the notice nor the changed confirm action, and no dialog is
raised when they join (capability `photo-access`).

#### Scenario: A first-time guest is told before the system asks
- **WHEN** a guest who has never been asked for photo access opens an invite and the event loads
- **THEN** the join screen, with all its choices, says the system will ask for photo access next, the confirm action says it also allows photo access, and no access dialog appears

#### Scenario: The explanation is available on request
- **WHEN** that guest asks for the explanation
- **THEN** it names what joining does with their photos, and closing it leaves them on the join screen with no dialog raised

#### Scenario: Confirming raises the system's dialog and joins
- **WHEN** the guest taps the confirm action
- **THEN** the system's photo access dialog appears and the device joins the event with the choices they made, whether they then allow, limit or refuse access

#### Scenario: Cancelling abandons the join without asking
- **WHEN** the guest taps Cancel
- **THEN** the device is in no event, the create screen is shown, and no access dialog was raised

#### Scenario: A user who already answered the system joins without a dialog
- **WHEN** a user who previously denied or granted photo access opens an invite and joins
- **THEN** no notice about access is shown, the confirm action is the plain Join, and no dialog is raised

### Requirement: Joining happens only on confirmation and needs a connection
Nothing SHALL be shared, received, or joined before the user taps Join. No invite link, however it is
crafted, SHALL join, switch or start sharing without the user confirming on the join screen. On Join the screen SHALL show
that it is joining; on success the user SHALL see the joined screen and become a member at once, before
any photo has been shared. Joining SHALL NOT require photo access; without it the joined screen asks for
access (capability `photo-access`). If joining fails the user SHALL stay on the join screen with a Retry
that keeps their choices, telling a failing service as the condition that applies (capability `app-experience`,
"A failing service is told as what it is"), and SHALL NOT be left half-joined; the app SHALL NOT join again until
the user taps Retry. An answer the app does not understand SHALL NOT count as joined. If the membership was in
fact established, the user SHALL see the joined screen.

#### Scenario: A crafted link cannot skip the confirmation
- **WHEN** a user opens an invite link crafted to join without asking
- **THEN** the join screen is shown as for any invite, and nothing is joined, left or shared until they tap Join

#### Scenario: Confirming joins
- **WHEN** the user taps Join and the event accepts the device
- **THEN** the joined screen for that event is shown with the choices they made

#### Scenario: A failed join is retryable and leaves nothing behind
- **WHEN** the user taps Join and the connection drops before the event accepts the device
- **THEN** the join screen says joining failed, offers Retry and Cancel, and the device is in no event

#### Scenario: A join the service fails says which way
- **WHEN** the user taps Join and the service answers that it failed, or that it is being updated
- **THEN** the join screen says SnapSync is having trouble, or is being updated and is back shortly, offers Retry
  and Cancel, and the device is in no event

#### Scenario: An answer the app cannot read is not a join
- **WHEN** the user taps Join and the answer is not one the app understands
- **THEN** the join screen says SnapSync cannot be reached, offers Retry and Cancel, and the joined screen is not
  shown; a Retry that the event accepts shows the joined screen with the choices they made

#### Scenario: Cancel abandons the invite
- **WHEN** the user taps Cancel on the join screen
- **THEN** nothing is joined or shared and the create screen is shown

### Requirement: A refused phone is told why it cannot join
When the user taps Join and the service refuses this phone as not genuine, the join screen SHALL show that refusal
and its cause (capability `privacy-security`, "A refused phone is told why") — never a generic failure and never
that the connection dropped — with Retry, which first tries to verify the phone again, and Cancel. The device SHALL
be in no event.

#### Scenario: A refused guest is told why
- **WHEN** a guest taps Join on a phone the service refuses as not genuine
- **THEN** the join screen says this phone was refused and why, offers Retry and Cancel, and the device is in no event

#### Scenario: Retry after the service stops refusing
- **WHEN** a refused guest taps Retry after the service has stopped refusing their phone
- **THEN** the guest joins with the choices they made, as any guest does

### Requirement: An event whose photos were deleted cannot be joined
The join screen SHALL say, when the invite's event has finished and its photos were deleted (capability
`event-lifetime`), that the event can no longer be joined, and offer only Cancel, never Join or Retry. A device
switching from another event SHALL remain in its current event. A lost connection SHALL NOT be reported as an
event that can no longer be joined.

#### Scenario: A guest scans after the deletion
- **WHEN** a guest opens the invite of an event whose photos were deleted
- **THEN** the join screen says the event can no longer be joined and offers only Cancel

#### Scenario: The photos are deleted while the join screen is open
- **WHEN** a guest taps Join on an event whose photos were deleted after the join screen loaded
- **THEN** they are not joined, and the screen says the event can no longer be joined

#### Scenario: A member does not lose their event to a deleted event's invite
- **WHEN** a member opens the invite of a different event whose photos were deleted
- **THEN** they are told it can no longer be joined, and remain in their current event

#### Scenario: Offline is not "can no longer be joined"
- **WHEN** a guest taps Join while the phone has no connection
- **THEN** the screen does not say the event can no longer be joined

### Requirement: The join screen shows how many members the event has
Once the join screen has loaded the event, it SHALL show how many members the event has now — devices that
joined and have not left — beside the event's name, before the user decides to join.

#### Scenario: Members are shown before joining
- **WHEN** a guest opens the invite of an event four devices have joined, one of which has since left
- **THEN** the join screen says the event has 3 members

#### Scenario: The host's own join screen
- **WHEN** a host has just created an event and its join screen opens
- **THEN** the join screen says the event has 0 members
