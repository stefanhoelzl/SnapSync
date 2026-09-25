# join-event Specification

## Purpose
Serves the guest (and the host, who joins the event they just created the same way): scanning an
event's QR code or tapping its invite link opens SnapSync on a join screen where they see which event
they are invited to, decide whether to share and whether to receive, choose the capture-date range
they share from, and learn how long the event's photos are kept — and nothing is shared or joined until
they confirm. It promises that an invite printed or sent today keeps opening in every future version,
that a guest who arrives late can still join, that a membership always has a bounded capture range so a
guest's whole camera roll is never uploaded, and that the invite's secret never reaches a web server. A
device is in at most one event at a time; opening another event's invite is a switch. What a chosen
range admits is capability `photo-sharing`; the album choice is capability `event-album`.
Decision record: changes/archive/2026-07-06-add-event-join-confirmation

## Requirements
### Requirement: The invite link format stays openable forever
An invite SHALL be the HTTPS link `https://snapsync.stho.net/join#v=3&d=<payload>`, where `<payload>` is
the unpadded base64url encoding of a UTF-8 JSON object whose `eventId` key holds the event's identifier
as a canonical UUID; an event's QR code SHALL encode exactly this link. Every future version of the app
SHALL open such a link and offer to join its event, so a QR code printed today keeps working. The link
SHALL carry nothing else a member relies on — no event name, no server address, no credential.

#### Scenario: A link made by an older version opens in a newer one
- **WHEN** a user of the current version scans a QR code that an earlier version produced for a still-existing event
- **THEN** the app opens on the join screen for that event

#### Scenario: The invite carries the event identifier only
- **WHEN** an event's invite link is decoded
- **THEN** it holds the event's identifier and nothing else — not its name, not a server address, not a credential

### Requirement: The invite's secret never reaches a web server
The event's identifier, which grants access to the event's photos, SHALL travel only in the part of the
invite link that browsers never transmit. Opening an invite on a device without SnapSync SHALL NOT send
the identifier to any web server as part of fetching the page.

#### Scenario: Opening an invite without the app reveals nothing to the server
- **WHEN** an invite link is opened in a browser on a device without SnapSync
- **THEN** the request the browser sends carries no part of the event's identifier, and the page served is the same for every event

### Requirement: An invite opens the app on its join screen
Tapping an invite link or scanning an event's QR code with the Camera app SHALL open SnapSync on the join
screen for that event, whether the app was not running, suspended, or open. Each opened invite SHALL be
acted on exactly once, even when iOS hands the same link to the app more than once. Opening the same
invite again after the user has joined or dismissed it SHALL be treated as a new invite; opening a
different invite while one is being answered SHALL replace it.

#### Scenario: Scanning with the app closed opens the join screen
- **WHEN** a guest scans an event's QR code with the Camera app while SnapSync is not running
- **THEN** SnapSync launches straight onto the join screen for that event

#### Scenario: Tapping an invite while the app is running opens the join screen
- **WHEN** a guest taps an invite link in a messenger while SnapSync is running in the background
- **THEN** SnapSync comes forward on the join screen for that event

#### Scenario: A link delivered twice opens one join screen
- **WHEN** iOS delivers the same invite to the app twice in quick succession
- **THEN** one join screen opens, and any choices already made on it are left untouched

#### Scenario: A different invite replaces the open one
- **WHEN** a join screen is open for one event and the user opens the invite of another event
- **THEN** the join screen now shows the other event

### Requirement: Without the app, the invite leads to the App Store and back
Opening an invite on a device without SnapSync SHALL show the event's web page (capability `event-site`),
which offers SnapSync on the App Store. Because iOS does not hand a link over through an installation, a
user who installs the app from there SHALL reach the event by opening the original invite again.

#### Scenario: Installing, then reopening the invite joins
- **WHEN** a guest without SnapSync opens an invite, installs the app from the page, and then taps the same invite again
- **THEN** SnapSync opens on the join screen for that event

### Requirement: A damaged invite is reported and changes nothing
An invite link that is malformed or truncated SHALL still open the app rather than dead-ending in a
browser, and SHALL NOT change the device's membership. On the create screen the app SHALL show a short
message that the QR code was not valid, which clears by itself after a few seconds.

#### Scenario: A truncated link shows a passing error
- **WHEN** a user in no event opens an invite link whose payload has been cut off
- **THEN** the app opens, the create screen shows that the QR code was not valid, and the message disappears by itself after a few seconds

#### Scenario: A damaged link never touches a membership
- **WHEN** a user who is in an event opens a malformed invite link
- **THEN** the user stays in their event with every setting unchanged

### Requirement: The join screen verifies the event before offering to join
The join screen SHALL open at once and load the event's details, offering Join only once they have
loaded. A missing event SHALL be shown as an invalid or expired invite with no way to join. A failure to
load (offline, a server error, or an incomplete answer such as an event without a name) SHALL be shown
with a Retry. The join screen SHALL never rest in a waiting state with nothing to tap: whenever loading
or joining ends, however it ends, the user SHALL be offered at least a way out.

#### Scenario: An event that does not exist cannot be joined
- **WHEN** a guest opens an invite for an event that no longer exists
- **THEN** the join screen says the invite is invalid or the event no longer exists, and offers only Cancel

#### Scenario: Offline, the event can be retried
- **WHEN** a guest opens an invite while offline
- **THEN** the join screen says the event could not be loaded and offers Retry and Cancel, and Retry loads it once the connection is back

#### Scenario: An unexpected failure never strands the user
- **WHEN** loading the event or joining it fails in an unexpected way
- **THEN** the screen moves to one that offers at least Cancel, never an endless spinner

### Requirement: A late guest can still join
Joining SHALL NOT be refused because the event's date range has passed. A guest who opens the invite
after the event's end SHALL join like anyone else and share the photos they took within the event's
range.

#### Scenario: A guest scans days after the party
- **WHEN** a guest opens the invite three days after the event's date range ended
- **THEN** the join screen offers Join with the full event window preselected, and after joining their photos from the event's dates are shared

### Requirement: The member decides separately whether to share and whether to receive
The join screen SHALL show the event's name and two switches — share my photos, and receive everyone's
photos — both on by default, and SHALL NOT make the user pick a named mode. Neither switch SHALL ever
flip the other. With sharing on, the screen SHALL state what is never shared (screenshots, screen
recordings, GIFs and pictures saved from chat apps, capability `photo-sharing`) and the capture range
being shared; with it off, it SHALL say that nothing of the user's leaves the phone and hide the range.
With both switches off, Join SHALL be disabled and the reason stated beside it. The screen SHALL also
offer the event-album choice (capability `event-album`), whose note names only the photos the current
switches would collect.

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

### Requirement: The shared capture range always has a lower bound inside the event window
With sharing on, the join screen SHALL let the user choose the range of capture dates they share,
defaulting to the whole event window. The start SHALL offer Event start, Now, and a custom time; the end
SHALL offer Event end and a custom time. Now SHALL be offered only while the event's window is running.
Custom times SHALL be limited to the event's window, and the joined range SHALL always lie within the
event's start and end, whichever way the range reached the app. A membership SHALL never exist without a
lower bound: if the app cannot tell a membership's range, it SHALL share nothing rather than the whole
library.

#### Scenario: The default is the whole event window
- **WHEN** the join screen loads an event running from Friday 18:00 to Sunday 23:00
- **THEN** the shared range reads from Friday 18:00 until Sunday 23:00

#### Scenario: Now is unavailable outside the window
- **WHEN** the event has not started yet, or has already ended
- **THEN** the Now option cannot be chosen and the default remains the whole event window

#### Scenario: A custom time cannot leave the window
- **WHEN** the user tries to pick a custom start before the event's start or a custom end after its end
- **THEN** the picker holds the choice to the event's start or end

#### Scenario: A guest's older camera roll is never shared
- **WHEN** a guest with years of photos joins an event that started yesterday
- **THEN** only photos taken since the event's start, within its range, are shared — never their older photos

#### Scenario: An unknown range shares nothing
- **WHEN** the app cannot read a membership's capture range
- **THEN** no photo is shared until the user joins again

#### Scenario: A choice survives a failed join
- **WHEN** the user picks custom start and end times, taps Join, the join fails, and they tap Retry
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

### Requirement: The join screen states when the event's photos are deleted
Before Join, the join screen SHALL state the date on which the event's shared photos are deleted, as
given by the event, together with the fixed rule that an event's photos are kept for at most 30 days from
the day it starts (capability `event-lifetime`). It SHALL state that date unconditionally, never as
depending on other members leaving. This is the only place the app states retention, and the host sees
it too.

#### Scenario: A guest sees the deletion date before joining
- **WHEN** the join screen loads an event
- **THEN** it shows the date its shared photos are deleted and that photos are kept for at most 30 days from the event's start

### Requirement: Photo access is explained before iOS ever asks
The app SHALL show an explanation first to a user who is in no event and has never been asked for photo
access, once the join screen has loaded the event — naming the event and stating that photos they take are shared
automatically, that the photo library is needed both to share and to save others' photos, that choosing
specific photos also works, and that only photos from the date they choose next are shared. iOS's photo
access dialog SHALL be raised only by the user confirming this explanation, after which the join
choices are shown. Cancel SHALL abandon the join. A user who has already granted, limited, or denied
access SHALL NOT see the explanation (capability `photo-access`).

#### Scenario: A first-time guest is told before iOS asks
- **WHEN** a guest who has never been asked for photo access opens an invite and the event loads
- **THEN** an explanation naming the event is shown, and no iOS dialog appears until they tap "I understand"

#### Scenario: Confirming raises iOS's dialog and continues
- **WHEN** the guest taps "I understand"
- **THEN** iOS's photo access dialog appears over the join choices for that event

#### Scenario: Cancelling the explanation abandons the join
- **WHEN** the guest taps Cancel on the explanation
- **THEN** the device is in no event and the create screen is shown

#### Scenario: A user who already answered iOS goes straight to the choices
- **WHEN** a user who previously denied or granted photo access opens an invite
- **THEN** the join choices are shown directly and no dialog is raised

### Requirement: Joining happens only on confirmation and needs a connection
Nothing SHALL be shared, received, or joined before the user taps Join. On Join the screen SHALL show
that it is joining; on success the user SHALL see the joined screen and become a member at once, before
any photo has been shared. Joining SHALL NOT require photo access; without it the joined screen asks for
access (capability `photo-access`). If joining fails the user SHALL stay on the join screen with a Retry
that keeps their choices, and SHALL NOT be left half-joined; if the membership was in fact established,
the user SHALL see the joined screen.

#### Scenario: Confirming joins
- **WHEN** the user taps Join and the event accepts the device
- **THEN** the joined screen for that event is shown with the choices they made

#### Scenario: A failed join is retryable and leaves nothing behind
- **WHEN** the user taps Join and the connection drops before the event accepts the device
- **THEN** the join screen says joining failed, offers Retry and Cancel, and the device is in no event

#### Scenario: Cancel abandons the invite
- **WHEN** the user taps Cancel on the join screen
- **THEN** nothing is joined or shared and the create screen is shown

### Requirement: A full event is reported as full
The join screen SHALL say that the event is full when it already holds its maximum number of devices
(capability `event-lifetime`), and offer only Cancel, never a Retry and never a generic failure. A
missing event or a lost connection SHALL NOT be reported as full.

#### Scenario: The eleventh device is turned away clearly
- **WHEN** a guest taps Join on an event that already holds its maximum number of devices
- **THEN** the screen says the event is full and there is no room to join, and offers only Cancel

### Requirement: Reopening the current event's invite changes nothing
Opening the invite of the event the device is already in SHALL do nothing: no join screen, no change to
the member's settings, and no interruption of sharing or receiving.

#### Scenario: A member rescans their own event
- **WHEN** a member scans the QR code of the event they are already in
- **THEN** the joined screen stays as it was and their range, switches, and album choice are unchanged

### Requirement: Opening another event's invite asks before switching
When a member opens the invite of a different event, the app SHALL load that event and ask whether to
switch, naming both the current and the new event, without promising any participation and without a
count. Confirming SHALL leave the current event exactly as an explicit leave does (capability
`manage-membership`) — at once, offline included, never waiting on a server — and SHALL then show the
regular join screen for the new event, including the photo-access explanation where it applies.
Cancelling that join screen SHALL leave the device in no event. Declining the switch SHALL keep the
current event untouched. An invite to a missing event SHALL be reported without leaving; a failure to
load SHALL offer Retry.

#### Scenario: The switch question names both events
- **WHEN** a member of "Ana's 30th" opens the invite of "Ski Trip"
- **THEN** a confirmation asks whether to switch, saying they will leave "Ana's 30th" and join "Ski Trip", and shows no count

#### Scenario: Confirming leaves, then configures the new event
- **WHEN** the member confirms the switch
- **THEN** they have left "Ana's 30th" and see the full join screen for "Ski Trip", where they choose sharing, receiving, range, and album as on any first join

#### Scenario: Declining keeps the current event
- **WHEN** the member declines the switch
- **THEN** they remain in their current event with every setting unchanged

#### Scenario: Backing out after the leave leaves no event
- **WHEN** the member confirms the switch and then cancels on the new event's join screen
- **THEN** the device is in no event and the create screen is shown

#### Scenario: A switch to a vanished event does not leave
- **WHEN** a member opens the invite of a different event that no longer exists
- **THEN** they are told the invite is invalid or the event no longer exists, and remain in their current event
