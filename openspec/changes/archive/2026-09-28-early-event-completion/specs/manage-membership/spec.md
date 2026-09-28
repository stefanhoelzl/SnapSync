## MODIFIED Requirements

### Requirement: Whoever holds the invite can join and see everything
The app SHALL NOT restrict who may use an invite: anyone who scans the QR code or receives the link SHALL
be able to join the event, contribute photos to it, and receive all of its photos, within the event's
device limit and until the event closes (capability `event-lifetime`).

#### Scenario: A forwarded invite admits a stranger
- **WHEN** a member's invite link is forwarded to someone outside the group, who opens it before the
  event closes
- **THEN** that person can join the event and receives its photos like any other member

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

### Requirement: A member changes what they share and receive without leaving
Until the event closes (capability `event-lifetime`), the joined screen SHALL offer a settings action that
opens the same choices as the join screen — share and receive switches, the capture range, and the album
— pre-filled with the membership's current settings under the event's name. A range start that equals
the event's start SHALL show as Event start and any other as a custom time; an end that equals the
event's end SHALL show as Event end and any other as a custom time. Save SHALL apply all changes at once,
without a confirmation dialog; Cancel SHALL discard them. Both switches off SHALL disable Save with the
reason stated. Changed range bounds SHALL stay within the event's start and end. Changing settings SHALL
keep the member in the event and SHALL work offline. Once the event has closed, settings SHALL NOT be
offered and the membership's settings SHALL stay as they were at the close.

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

#### Scenario: A closed event's settings are fixed
- **WHEN** a member opens the joined screen of an event that has closed
- **THEN** no settings action is offered, and what they share and receive stays as it was

## REMOVED Requirements

### Requirement: The joined screen always offers the invite
**Reason**: A closed event admits no one, so its invite is no longer offered; replaced by "The joined
screen offers the invite until the event closes".
**Migration**: Same invite, QR and caption rules, bounded by the close.

### Requirement: The app leaves on its own only for a confirmed-deleted event past its deletion date
**Reason**: A finished event now ends every membership on its own, before its deletion date and from a
background wake too; replaced by "The app leaves on its own once the event is finished for it".
**Migration**: The confirmed-deleted-past-its-date case and every "stay joined on doubt" case carry over
into the replacement.

## ADDED Requirements

### Requirement: The joined screen offers the invite until the event closes
The joined screen SHALL show, while the device is in an event that has not closed (capability
`event-lifetime`), a scannable QR code of the event's invite link (capability `join-event`) and a share
action that hands the same link to the iOS share sheet, even when photo access is missing. The QR code
SHALL be dark on a light background in both light and dark appearance. Its caption SHALL tell the member
that someone else scans this code to join, not instruct the member to scan. Sharing SHALL have no effect
on the app's state, whether completed or cancelled. Invite affordances SHALL NOT appear while the device
is in no event, nor once the event has closed.

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

#### Scenario: A closed event offers no invite
- **WHEN** the event closes while a member is looking at the joined screen
- **THEN** the QR code and the share action disappear

### Requirement: The app leaves on its own once the event is finished for it
The app SHALL end the membership on its own, exactly as an explicit leave but without asking and without
showing anything, in the foreground or during a background wake, when either:
- the event has closed (capability `event-lifetime`), every photo this member shares has reached the
  event, and every photo of the others that this member receives is in their library or was deleted by
  them there — a photo that repeatedly fails to arrive keeps the member in the event; or
- the server confirms the event has finished and its photos were deleted; or
- the member opens the app, the event is confirmed not to exist, and its announced deletion date has
  already passed.

It SHALL NOT end a membership on any other evidence: not while offline, not on a server error, not when
the server merely fails to find the event before its deletion date, and not for a membership that does
not yet know its deletion date. Every doubt SHALL resolve toward staying joined. The next time the member
opens the app after such a leave, it SHALL show the create screen.

#### Scenario: Everything received after the close
- **WHEN** the event closes and the member's phone, in their pocket, has every photo of the event
- **THEN** SnapSync leaves the event in the background, and the next time the member opens it they see
  the create screen

#### Scenario: A share-only member waits for their own uploads
- **WHEN** the event closes while a share-only member's last photos are still uploading
- **THEN** the member stays in the event until those photos have reached it, then leaves on their own

#### Scenario: A photo that will not arrive keeps the member
- **WHEN** the event has closed and one of the others' photos repeatedly fails to arrive in this member's
  library
- **THEN** the member stays in the event

#### Scenario: A member whose event finished without them
- **WHEN** a member's phone was off for a week and the event's photos were deleted meanwhile, and the
  phone is woken in the background
- **THEN** SnapSync learns the event has finished and leaves it, with the photos it had received still in
  the library

#### Scenario: A deleted event past its date is left automatically
- **WHEN** a member opens SnapSync after the event's deletion date, and the event has been deleted
- **THEN** the app shows the create screen with the device in no event

#### Scenario: A server error never ends a membership
- **WHEN** the member opens the app after the deletion date while the server is failing or the phone is offline
- **THEN** the member stays joined

#### Scenario: A premature "gone" is disbelieved
- **WHEN** the server fails to find the event before its deletion date without confirming it has finished
- **THEN** the member stays joined and sharing continues
