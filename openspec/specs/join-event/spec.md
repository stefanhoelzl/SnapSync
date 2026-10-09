# join-event Specification

## Purpose
Serves the guest (and the host, who joins the event they just created the same way): an invite opens
SnapSync on a join screen where they see which event they are invited to, decide whether to share and whether to
receive, and choose the capture-date range they share from — and nothing is shared or joined until they confirm. It
promises that a guest who arrives late can still join until the event closes, and that a membership always has a
bounded capture range so a guest's whole camera roll is never uploaded. A device is in at most one event at a time.
The invite itself — its format, where it leads, and what opening another event's invite does — is capability
`invite-link`; what a chosen range admits is capability `photo-sharing`; the album choice is capability `event-album`.
Decision record: changes/archive/2026-07-06-add-event-join-confirmation

## Requirements

### Requirement: The join screen verifies the event before offering to join
The join screen SHALL open at once and load the event's details, offering Join only once they have
loaded. A missing event SHALL be shown as an invalid or expired invite with no way to join. A failure to
load while the device has a network (an unreachable server, a server error, or an incomplete answer such as an
event without a name) SHALL be shown with a Retry; a load that failed for want of a network is shown as "Without
a network, the join screen waits for one" requires. The join screen SHALL never rest in a waiting state with
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
Joining SHALL NOT be refused because the event's date range has passed. Until the event closes
(capability `event-lifetime`), a guest who opens the invite after the event's end SHALL join like anyone
else and share the photos they took within the event's range.

#### Scenario: A guest scans days after the party
- **WHEN** a guest opens the invite three days after the event's date range ended, before the event has
  closed
- **THEN** the join screen offers Join with the full event window preselected, and after joining their photos from the event's dates are shared

### Requirement: The join screen lets the member decide separately whether to share, whether to receive, and whether to keep an album
The join screen SHALL show the event's name and two switches — share my photos, and receive everyone's
photos — both on by default, and SHALL NOT make the user pick a named mode. Neither switch SHALL ever
flip the other. With sharing on, the screen SHALL state what is never shared (screenshots, screen
recordings, GIFs and pictures saved from chat apps, capability `photo-sharing`) and the capture range
being shared; with it off, it SHALL say that nothing of the user's leaves the phone and hide the range.
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

### Requirement: The shared capture range always has a lower bound inside the event window
With sharing on, the join screen SHALL let the user choose the range of capture dates they share,
defaulting to the whole event window. The choice SHALL be one of: the whole event; from now until the
event's end; or a custom range, picked on a calendar with a time for its start and its end. From now
SHALL be offered only while the event's window is running. A custom range SHALL be limited to the event's
window, and the joined range SHALL always lie within the event's start and end, whichever way the range
reached the app. A membership SHALL never exist without a lower bound: if the app cannot tell a
membership's range, it SHALL share nothing rather than the whole library.

#### Scenario: The default is the whole event window
- **WHEN** the join screen loads an event running from Friday 18:00 to Sunday 23:00
- **THEN** the shared range reads from Friday 18:00 until Sunday 23:00, as the whole event

#### Scenario: From now shares from this moment to the event's end
- **WHEN** the event is running and the user chooses from now
- **THEN** the shared range reads from the current time until the event's end

#### Scenario: Now is unavailable outside the window
- **WHEN** the event has not started yet, or has already ended
- **THEN** from now cannot be chosen and the default remains the whole event window

#### Scenario: A custom time cannot leave the window
- **WHEN** the user tries to pick a custom range starting before the event's start or ending after its end
- **THEN** the calendar holds the choice to the event's start or end

#### Scenario: A guest's older camera roll is never shared
- **WHEN** a guest with years of photos joins an event that started yesterday
- **THEN** only photos taken since the event's start, within its range, are shared — never their older photos

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
that keeps their choices, and SHALL NOT be left half-joined; if the membership was in fact established,
the user SHALL see the joined screen.

#### Scenario: A crafted link cannot skip the confirmation
- **WHEN** a user opens an invite link crafted to join without asking
- **THEN** the join screen is shown as for any invite, and nothing is joined, left or shared until they tap Join

#### Scenario: Confirming joins
- **WHEN** the user taps Join and the event accepts the device
- **THEN** the joined screen for that event is shown with the choices they made

#### Scenario: A failed join is retryable and leaves nothing behind
- **WHEN** the user taps Join and the connection drops before the event accepts the device
- **THEN** the join screen says joining failed, offers Retry and Cancel, and the device is in no event

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

### Requirement: A full event is reported as full
The join screen SHALL say that the event is full when it already holds its maximum number of devices
(capability `event-lifetime`), and offer only Cancel, never a Retry and never a generic failure. A
missing event or a lost connection SHALL NOT be reported as full.

#### Scenario: The eleventh device is turned away clearly
- **WHEN** a guest taps Join on an event that already holds its maximum number of devices
- **THEN** the screen says the event is full and there is no room to join, and offers only Cancel

### Requirement: A closed or finished event cannot be joined
The join screen SHALL say, when the invite's event has closed or has finished and its photos were deleted
(capability `event-lifetime`), that the event can no longer be joined, and offer only Cancel, never Join or
Retry. A device switching from another event SHALL remain in its current event. A lost connection SHALL
NOT be reported as closed.

#### Scenario: A guest scans after the close
- **WHEN** a guest opens the invite of an event that has closed
- **THEN** the join screen says the event can no longer be joined and offers only Cancel

#### Scenario: The event closes while the join screen is open
- **WHEN** a guest taps Join on an event that closed after the join screen loaded
- **THEN** they are not joined, and the screen says the event can no longer be joined

#### Scenario: A member does not lose their event to a closed invite
- **WHEN** a member opens the invite of a different event that has closed
- **THEN** they are told it can no longer be joined, and remain in their current event
