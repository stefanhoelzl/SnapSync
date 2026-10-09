# manage-membership Specification

## Purpose
Serves a joined member — host or guest, who have the same powers — for everything they do with their
membership after joining: rename the event for everyone, change what they share and receive without leaving, and
leave — each until the event closes, after which only leaving remains. It promises that leaving is instant, works
offline, and never deletes a photo anyone already has, and that a membership survives app updates, restarts, and a
locked phone. Inviting others is capability `invite-link`; the app leaving on its own once the event is finished for it
is capability `event-lifetime`; what a capture range admits is capability `photo-sharing`; the album is capability
`event-album`.
Decision record: changes/archive/2026-07-21-add-reconfigure-membership

## Requirements

### Requirement: Any member renames the event for everyone
Until the event closes (capability `event-lifetime`), every member SHALL be able to rename the event from
the joined screen. The rename dialog SHALL open with the current name, accept at most 100 characters, and
disable confirming while the name is empty or unchanged; surrounding whitespace SHALL be dropped. On
success the dialog SHALL close and the new name SHALL show at once; every other member SHALL see it the
next time they open the app, and the event's web page shows it too (capability `event-site`). A rename
SHALL change nothing but the name — not the invite, the dates, or anyone's settings. Once the event has
closed, rename SHALL NOT be offered, and a rename confirmed just before the close SHALL fail like any
other refused rename.

#### Scenario: A guest fixes a typo in the name
- **WHEN** a member who did not create the event renames it from "Ana's 3oth" to "Ana's 30th"
- **THEN** the dialog closes, their joined screen shows "Ana's 30th", and another member sees the new name the next time they open SnapSync

#### Scenario: An unchanged or empty name cannot be submitted
- **WHEN** the rename field holds the current name, or only spaces
- **THEN** the confirm action is disabled

#### Scenario: A rename leaves everything else alone
- **WHEN** the event is renamed
- **THEN** its QR code, its dates, and every member's sharing and receiving settings are unchanged

#### Scenario: Cancelling a rename changes nothing
- **WHEN** the member edits the name and cancels
- **THEN** the event keeps its name

#### Scenario: A closed event cannot be renamed
- **WHEN** a member opens the joined screen of an event that has closed
- **THEN** no rename action is offered

### Requirement: A failed rename keeps the dialog and never ends the membership
When a rename fails, the dialog SHALL stay open with the typed name, and show the failure as a message
above confirm — telling a rejected name apart from a connection or server problem — never by marking the
name field as wrong. No failed rename SHALL change the member's membership, and in particular a rename
that finds the event missing SHALL NOT end it.

#### Scenario: Offline rename
- **WHEN** a member confirms a rename while offline
- **THEN** the dialog stays open with their text and says the event could not be renamed and to check the connection

#### Scenario: A rejected name
- **WHEN** the server refuses the new name
- **THEN** the dialog stays open and says the name was not accepted

#### Scenario: A rename that meets a missing event keeps the member joined
- **WHEN** a rename fails because the server no longer finds the event
- **THEN** the member stays joined, with the generic failure message and nothing torn down

### Requirement: Event settings change as they are made, without leaving
Until the event closes (capability `event-lifetime`), the joined screen SHALL offer a settings action
that opens the same choices as the join screen — share and receive switches, the capture range and the
album — showing the membership's current settings, over the joined screen, which stays partly visible above them. A
range equal to the event's whole window SHALL show as the whole event, and any other as a custom range.
Each change SHALL apply as it is made — a switch when it is flipped, the range when a preset is chosen or a
picked range is confirmed — with no Save or Cancel; changes made one after another SHALL apply in order, the
last one standing. Sharing and receiving MAY both be off: the member then stays in the event and nothing is
shared or received. The settings SHALL close by swiping them down, by going back, or by tapping the joined
screen above them, and closing SHALL change nothing. Changed range bounds SHALL stay within the event's
start and end. Changing settings SHALL keep the member in the event and SHALL work offline. Once the event
has closed, settings SHALL NOT be offered and the membership's settings SHALL stay as they were at the
close.

#### Scenario: Settings open pre-filled
- **WHEN** a member who shares and receives with the album on opens settings
- **THEN** both switches and the album are on and the range shows the one they joined with

#### Scenario: An Android membership from before the album opens with it off
- **WHEN** an Android member who joined before Android had the event album opens settings
- **THEN** the album choice is offered and is off

#### Scenario: A change applies without a Save
- **WHEN** the member turns the album on and closes settings by swiping them down
- **THEN** the album is on, and reopening settings shows it on

#### Scenario: Closing changes nothing further
- **WHEN** the member opens settings and closes them by tapping the joined screen above, without changing anything
- **THEN** their settings are exactly as before

#### Scenario: The last of several quick changes stands
- **WHEN** the member flips the receive switch off, on and off again in quick succession
- **THEN** receiving is off

#### Scenario: Both directions may be off
- **WHEN** a receive-only member turns receiving off
- **THEN** the member stays in the event, sharing and receiving nothing, and the joined screen says so

#### Scenario: A range narrower than the event shows as custom
- **WHEN** a member who joined sharing from a time after the event's start opens settings
- **THEN** the range shows as a custom range with that start and the event's end

#### Scenario: A widened start is held to the event's start
- **WHEN** the member picks a start before the event's start and confirms it
- **THEN** the membership shares from the event's start

#### Scenario: Settings change offline
- **WHEN** the member changes a setting while the device is offline
- **THEN** the change takes effect and the member remains in the event

#### Scenario: A closed event's settings are fixed
- **WHEN** a member opens the joined screen of an event that has closed
- **THEN** no settings action is offered, and what they share and receive stays as it was

#### Scenario: Settings hold no mobile-data choice
- **WHEN** a member opens settings
- **THEN** no mobile-data choice is offered there; it is the device's, in the app menu (capability `mobile-data`)

### Requirement: Settings explain what a change does
The settings SHALL show the live count of photos that will be shared (as on the join screen,
capability `join-event`). Switching sharing off, or narrowing the shared range, SHALL first ask whether to
stop sharing those photos, stating that photos the member stops sharing reach no one new, that anyone who
already received them keeps them, and that photos the member received stay; it SHALL NOT suggest that
narrowing deletes or recalls photos from other members. Only confirming SHALL apply the change; declining
SHALL leave the switch or range as it was. Widening the range, and every other change, SHALL apply without
asking. Turning the album on SHALL say that the photos already synced are collected too — on Android, the
photos already received.

#### Scenario: Switching sharing off asks first
- **WHEN** the member switches sharing off
- **THEN** they are asked whether to stop sharing, told that anyone who already received those photos keeps them and that photos they received stay, and sharing stays on until they confirm

#### Scenario: Narrowing is described honestly
- **WHEN** the member confirms a range that starts later or ends earlier than the one in effect
- **THEN** before the range changes they are asked whether to stop sharing those photos, told that anyone who already received them keeps them, and nothing suggests the photos are deleted from other members

#### Scenario: Keeping sharing changes nothing
- **WHEN** the member is asked whether to stop sharing and chooses to keep sharing
- **THEN** the switch and the range stay as they were and nothing is withdrawn from the event

#### Scenario: Widening does not ask
- **WHEN** the member moves the start of their range earlier
- **THEN** the range changes at once, without a question

#### Scenario: Album-on mentions photos already synced
- **WHEN** an iPhone member turns the album on in settings
- **THEN** the settings say the album also collects the photos already synced

#### Scenario: On Android album-on mentions photos already received
- **WHEN** an Android member turns the album on in settings
- **THEN** the settings say the album also collects the photos already received, and that their own photos stay in the camera folder

### Requirement: Settings changes take effect immediately
A settings change SHALL take effect as soon as it applies, and newly enabled directions SHALL start at once
rather than waiting for iOS to schedule work:
turning sharing on SHALL start sharing, and turning receiving on SHALL start bringing in the event's
photos. Widening the range SHALL share the newly included older photos while photos already shared stay
shared. Narrowing the range or turning sharing off SHALL stop listing the excluded photos to the event;
members who already received them SHALL keep them, and a later widening SHALL bring them back to the
event. Turning sharing off SHALL let uploads already under way finish and count as shared; turning
receiving off SHALL stop downloads under way at once. Photos the member already received SHALL be
unaffected by any change.

#### Scenario: Turning sharing on starts right away
- **WHEN** a receive-only member turns sharing on, with photo access granted
- **THEN** their photos in range begin uploading without waiting for iOS's next background opportunity

#### Scenario: Turning receiving on starts right away
- **WHEN** a share-only member turns receiving on
- **THEN** the event's photos begin arriving in their library

#### Scenario: Moving the start earlier shares older photos
- **WHEN** a member moves the start of their range earlier
- **THEN** their photos in the newly included period are shared to the event, and photos already shared stay shared

#### Scenario: Narrowing withdraws listings but not received copies
- **WHEN** a member moves the start of their range later and confirms stopping to share those photos
- **THEN** photos now outside the range stop being offered to members who have not yet received them, and members who already have them keep them

#### Scenario: Narrowing then widening restores the photos
- **WHEN** a member narrows their range, and later widens it back
- **THEN** the previously withdrawn photos are offered to the event again

#### Scenario: Turning sharing off lets uploads finish
- **WHEN** a member turns sharing off and confirms while a photo is uploading
- **THEN** that upload completes and the photo counts as shared, and no new uploads start

#### Scenario: Turning receiving off stops downloads
- **WHEN** a member turns receiving off while the event's photos are downloading
- **THEN** those downloads stop and no further photos arrive

### Requirement: A settings change that cannot be saved says so and applies nothing
If a settings change cannot be saved, the changed control SHALL return to the setting still in effect, the
settings SHALL stay open and say that the change could not be saved, and none of the change's effects SHALL
happen.

#### Scenario: A failed save applies nothing
- **WHEN** a member turns receiving off, and saving it fails
- **THEN** the receive switch shows on again, the settings say the change could not be saved, and downloads continue as before

#### Scenario: A failed album change creates no album
- **WHEN** a member turns the album on, and saving it fails
- **THEN** the album switch shows off again and no album is created

### Requirement: Leaving asks first, then is instant and works offline
The joined screen SHALL offer Leave, also while photo access is missing, and SHALL ask "Leave this
event?" with Stay and Leave before doing anything. Confirming SHALL immediately stop sharing and
receiving — cancelling uploads and downloads under way — and return to the create screen, without
waiting for any server and also while offline. The app SHALL either leave completely or, if it cannot,
remain fully joined so Leave can be tried again; it SHALL never be left half-joined. Leave SHALL NOT
appear while the device is in no event.

#### Scenario: Leaving while offline
- **WHEN** a member taps Leave and confirms while the device is offline
- **THEN** the create screen is shown at once and nothing more is shared or received for that event

#### Scenario: Choosing Stay changes nothing
- **WHEN** a member taps Leave and then Stay
- **THEN** they remain in the event with sharing and receiving as before

#### Scenario: Leave cancels transfers under way
- **WHEN** a member leaves while photos are uploading and downloading
- **THEN** those transfers stop, and no photo that had not finished is added to the event or to their library afterwards

### Requirement: Leaving deletes no one's photos
Leaving SHALL NOT delete or withdraw any photo: the photos the member already shared SHALL remain
available to the other members, and the photos the member received SHALL remain in their library.
Leaving and later rejoining the same event SHALL be possible until the event closes (capability
`event-lifetime`).

#### Scenario: Shared photos outlive the member's leave
- **WHEN** a member who shared 40 photos leaves the event
- **THEN** the other members still receive those 40 photos

#### Scenario: Received photos stay
- **WHEN** a member leaves after receiving the event's photos
- **THEN** every received photo remains in their photo library

### Requirement: A membership survives updates, restarts, and a locked phone
A membership SHALL persist across app updates, the app being closed or killed, and phone restarts. The
app SHALL NOT treat a membership it temporarily cannot read — for example while the phone is locked or
has not been unlocked since it restarted — as having left: it SHALL neither end the membership nor show
the create screen because of it, and sharing resumes once the phone is unlocked. Restoring the phone
from a backup SHALL bring the membership back.

#### Scenario: An app update keeps the event
- **WHEN** the app is updated while the device is in an event
- **THEN** after the update the device is still in that event with the same settings

#### Scenario: A locked phone never looks like a leave
- **WHEN** iOS wakes SnapSync in the background while the phone is locked, including before its first unlock after a restart
- **THEN** the membership is kept, and when the user next unlocks and opens the app it shows the joined screen

### Requirement: Deleting the app counts as leaving
Deleting and reinstalling SnapSync SHALL leave the device in no event; the user rejoins by opening the
event's invite again (capability `join-event`).

#### Scenario: A reinstall starts unjoined
- **WHEN** a member deletes SnapSync and installs it again
- **THEN** the app opens on the create screen, and opening the event's invite offers to join it
