# manage-membership Specification

## Purpose
Serves a joined member — host or guest, who have the same powers — for everything they do with their
membership after joining: invite others with the event's QR code or link, rename the event for
everyone, change what they share and receive without leaving, and leave. It promises that leaving is
instant, works offline, and never deletes a photo anyone already has; that a membership survives app
updates, restarts, and a locked phone, and is never ended by a mere server error; and that the app ends a
membership on its own only once its event is confirmed deleted after its announced deletion date. What
a capture range admits is capability `photo-sharing`; the album is capability `event-album`.
Decision record: changes/archive/2026-07-21-add-reconfigure-membership

## Requirements
### Requirement: The joined screen always offers the invite
While the device is in an event, the joined screen SHALL show a scannable QR code of the event's invite
link (capability `join-event`) and a share action that hands the same link to the iOS share sheet, even
when photo access is missing. The QR code SHALL be dark on a light background in both light and dark
appearance. Its caption SHALL tell the member that someone else scans this code to join, not instruct
the member to scan. Sharing SHALL have no effect on the app's state, whether completed or cancelled.
Invite affordances SHALL NOT appear while the device is in no event.

#### Scenario: A host shares the invite before granting photo access
- **WHEN** a host who has not granted photo access has just joined their new event
- **THEN** the joined screen shows the event's QR code and a share action, and sharing sends the invite link through the iOS share sheet

#### Scenario: The QR stays scannable in dark mode
- **WHEN** the phone is in dark appearance
- **THEN** the QR code is still drawn dark on a light background

#### Scenario: The QR code and the shared link are the same invite
- **WHEN** one guest scans the member's QR code and another taps the link the member shared
- **THEN** both reach the join screen of the same event

#### Scenario: The caption addresses the member
- **WHEN** a member reads the caption beneath the QR code
- **THEN** it tells them that others scan this code to join, and does not tell them to scan anything

### Requirement: Whoever holds the invite can join and see everything
The app SHALL NOT restrict who may use an invite: anyone who scans the QR code or receives the link SHALL
be able to join the event, contribute photos to it, and receive all of its photos, within the event's
device limit (capability `event-lifetime`).

#### Scenario: A forwarded invite admits a stranger
- **WHEN** a member's invite link is forwarded to someone outside the group, who opens it
- **THEN** that person can join the event and receives its photos like any other member

### Requirement: Any member renames the event for everyone
Every member SHALL be able to rename the event from the joined screen. The rename dialog SHALL open with
the current name, accept at most 100 characters, and disable confirming while the name is empty or
unchanged; surrounding whitespace SHALL be dropped. On success the dialog SHALL close and the new name
SHALL show at once; every other member SHALL see it the next time they open the app, and the event's web
page shows it too (capability `event-site`). A rename SHALL change nothing but the name — not the invite,
the dates, or anyone's settings.

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

### Requirement: A member changes what they share and receive without leaving
The joined screen SHALL offer a settings action that opens the same choices as the join screen —
share and receive switches, the capture range, and the album — pre-filled with the membership's current
settings under the event's name. A range start that equals the event's start SHALL show as Event start
and any other as a custom time; an end that equals the event's end SHALL show as Event end and any other
as a custom time. Save SHALL apply all changes at once, without a confirmation dialog; Cancel SHALL
discard them. Both switches off SHALL disable Save with the reason stated. Changed range bounds SHALL
stay within the event's start and end. Changing settings SHALL keep the member in the event and SHALL
work offline.

#### Scenario: Settings open pre-filled
- **WHEN** a member who shares and receives with the album on opens settings
- **THEN** both switches and the album are on and the range shows the one they joined with

#### Scenario: Cancel discards changes
- **WHEN** the member changes several settings and taps Cancel
- **THEN** their settings are exactly as before

#### Scenario: A widened start is held to the event's start
- **WHEN** the member picks a start before the event's start and saves
- **THEN** the membership shares from the event's start

#### Scenario: Settings change offline
- **WHEN** the member saves new settings while the device is offline
- **THEN** the change takes effect and the member remains in the event

### Requirement: Settings explain what a change does
The settings screen SHALL show the live count of photos that will be shared (as on the join screen,
capability `join-event`). It SHALL state that sharing less stops listing those photos to the event while
anyone who already received them keeps them, and that photos the member received stay; it SHALL NOT
suggest that narrowing deletes or recalls photos from other members. Turning the album on SHALL say that
the photos already synced are collected too.

#### Scenario: Narrowing is described honestly
- **WHEN** the member opens settings
- **THEN** the screen says that sharing less stops listing those photos to the event and that anyone who already received them keeps them

#### Scenario: Album-on mentions photos already synced
- **WHEN** the member turns the album on in settings
- **THEN** the screen says the album also collects the photos already synced

### Requirement: Saved settings take effect immediately
After Save, newly enabled directions SHALL start at once rather than waiting for iOS to schedule work:
turning sharing on SHALL start sharing, and turning receiving on SHALL start bringing in the event's
photos. Widening the range SHALL share the newly included older photos while photos already shared stay
shared. Narrowing the range or turning sharing off SHALL stop listing the excluded photos to the event;
members who already received them SHALL keep them, and a later widening SHALL bring them back to the
event. Turning sharing off SHALL let uploads already under way finish and count as shared; turning
receiving off SHALL stop downloads under way at once. Photos the member already received SHALL be
unaffected by any change.

#### Scenario: Turning sharing on starts right away
- **WHEN** a receive-only member turns sharing on, with photo access granted, and saves
- **THEN** their photos in range begin uploading without waiting for iOS's next background opportunity

#### Scenario: Turning receiving on starts right away
- **WHEN** a share-only member turns receiving on and saves
- **THEN** the event's photos begin arriving in their library

#### Scenario: Moving the start earlier shares older photos
- **WHEN** a member moves the start of their range earlier and saves
- **THEN** their photos in the newly included period are shared to the event, and photos already shared stay shared

#### Scenario: Narrowing withdraws listings but not received copies
- **WHEN** a member moves the start of their range later and saves
- **THEN** photos now outside the range stop being offered to members who have not yet received them, and members who already have them keep them

#### Scenario: Narrowing then widening restores the photos
- **WHEN** a member narrows their range, saves, and later widens it back
- **THEN** the previously withdrawn photos are offered to the event again

#### Scenario: Turning sharing off lets uploads finish
- **WHEN** a member turns sharing off while a photo is uploading
- **THEN** that upload completes and the photo counts as shared, and no new uploads start

#### Scenario: Turning receiving off stops downloads
- **WHEN** a member turns receiving off while the event's photos are downloading
- **THEN** those downloads stop and no further photos arrive

### Requirement: A settings change that cannot be saved says so and applies nothing
If the new settings cannot be saved, the settings screen SHALL stay open with the member's unsaved
choices and say that nothing changed, and none of the change's effects SHALL happen.

#### Scenario: A failed save applies nothing
- **WHEN** a member turns the album on and receiving off, and saving fails
- **THEN** the settings screen stays open saying the settings could not be saved, no album is created, and downloads continue as before

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
Leaving and later rejoining the same event SHALL be possible while the event exists (capability
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

### Requirement: The app leaves on its own only for a confirmed-deleted event past its deletion date
The app SHALL return to the create screen on its own, exactly as after an explicit leave, when the member
opens the app and the event is confirmed not to exist while its announced deletion date has already
passed. It SHALL NOT end a membership on any other evidence: not while offline, not on a server error, not
when the server claims the event is gone before its deletion date, not from a background wake, and not
for a membership that does not yet know its deletion date. Every doubt SHALL resolve toward staying
joined.

#### Scenario: A deleted event past its date is left automatically
- **WHEN** a member opens SnapSync after the event's deletion date, and the event has been deleted
- **THEN** the app shows the create screen with the device in no event

#### Scenario: A server error never ends a membership
- **WHEN** the member opens the app after the deletion date while the server is failing or the phone is offline
- **THEN** the member stays joined

#### Scenario: A premature "gone" is disbelieved
- **WHEN** the server reports the event missing before its deletion date
- **THEN** the member stays joined and sharing continues

#### Scenario: Background wakes never end a membership
- **WHEN** iOS wakes SnapSync in the background while the event has in fact been deleted
- **THEN** the membership is kept until the member next opens the app
