## MODIFIED Requirements

### Requirement: Leaving asks first, then is instant and works offline
The joined screen SHALL offer Leave, also while photo access is missing, and SHALL ask "Leave this
event?" with Stay and Leave before doing anything — except for an event whose photos were deleted, where
there is nothing left to share or receive and Leave SHALL leave at once without asking. Confirming SHALL
immediately stop sharing and receiving — cancelling uploads and downloads under way — and return to the
create screen, without waiting for any server and also while offline. The app SHALL either leave completely
or, if it cannot, remain fully joined so Leave can be tried again; it SHALL never be left half-joined. Leave
SHALL NOT appear while the device is in no event.

#### Scenario: Leaving while offline
- **WHEN** a member taps Leave and confirms while the device is offline
- **THEN** the create screen is shown at once and nothing more is shared or received for that event

#### Scenario: Choosing Stay changes nothing
- **WHEN** a member taps Leave and then Stay
- **THEN** they remain in the event with sharing and receiving as before

#### Scenario: Leave cancels transfers under way
- **WHEN** a member leaves while photos are uploading and downloading
- **THEN** those transfers stop, and no photo that had not finished is added to the event or to their library afterwards

#### Scenario: Leaving a deleted event does not ask
- **WHEN** a member taps Leave on the joined screen of an event whose photos were deleted
- **THEN** no question is asked, the create screen is shown at once, and every photo they received stays in their library

### Requirement: Leaving deletes no one's photos
Leaving SHALL NOT delete or withdraw any photo: the photos the member already shared SHALL remain
available to the other members, and the photos the member received SHALL remain in their library.
Leaving and later rejoining the same event SHALL be possible until the event's photos are deleted (capability
`event-lifetime`).

#### Scenario: Shared photos outlive the member's leave
- **WHEN** a member who shared 40 photos leaves the event
- **THEN** the other members still receive those 40 photos

#### Scenario: Received photos stay
- **WHEN** a member leaves after receiving the event's photos
- **THEN** every received photo remains in their photo library

## REMOVED Requirements

### Requirement: Any member renames the event for everyone
**Reason**: There is no close any more; renaming works until the event's photos are deleted.
**Migration**: Replaced by "Any member renames the event for everyone until its photos are deleted".

### Requirement: Event settings change as they are made, without leaving
**Reason**: There is no close any more; settings work until the event's photos are deleted.
**Migration**: Replaced by "Event settings change as they are made, without leaving, until the photos are deleted".

## ADDED Requirements

### Requirement: Any member renames the event for everyone until its photos are deleted
Until the event's photos are deleted (capability `event-lifetime`), every member SHALL be able to rename the event from
the joined screen. The rename dialog SHALL open with the current name, accept at most 100 characters, and
disable confirming while the name is empty or unchanged; surrounding whitespace SHALL be dropped. On
success the dialog SHALL close and the new name SHALL show at once; every other member SHALL see it the
next time they open the app, and the event's web page shows it too (capability `event-site`). A rename
SHALL change nothing but the name — not the invite, the dates, or anyone's settings. Once the event's
photos have been deleted, rename SHALL NOT be offered, and a rename confirmed just before the deletion SHALL
fail like any other refused rename.

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

#### Scenario: An ended event can still be renamed
- **WHEN** a member renames an event a week after its range ended, before its photos were deleted
- **THEN** the event is renamed for everyone

#### Scenario: A deleted event cannot be renamed
- **WHEN** a member opens the joined screen of an event whose photos were deleted
- **THEN** no rename action is offered

### Requirement: Event settings change as they are made, without leaving, until the photos are deleted
Until the event's photos are deleted (capability `event-lifetime`), the joined screen SHALL offer a settings action
that opens the same choices as the join screen — share and receive switches, the capture range and the
album — showing the membership's current settings, over the joined screen, which stays partly visible above them. A
range equal to the event's whole window SHALL show as the whole event, and any other as a custom range.
Each change SHALL apply as it is made — a switch when it is flipped, the range when a preset is chosen or a
picked range is confirmed — with no Save or Cancel; changes made one after another SHALL apply in order, the
last one standing. Sharing and receiving MAY both be off: the member then stays in the event and nothing is
shared or received. The settings SHALL close by swiping them down, by going back, or by tapping the joined
screen above them, and closing SHALL change nothing. Changed range bounds SHALL stay within the event's
start and end. Changing settings SHALL keep the member in the event and SHALL work offline. Once the event's
photos have been deleted, settings SHALL NOT be offered.

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

#### Scenario: Settings still change after the end
- **WHEN** a member turns receiving on a week after the event's range ended, before its photos were deleted
- **THEN** the change applies and the event's photos start arriving

#### Scenario: A deleted event offers no settings
- **WHEN** a member opens the joined screen of an event whose photos were deleted
- **THEN** no settings action is offered

#### Scenario: Settings hold no mobile-data choice
- **WHEN** a member opens settings
- **THEN** no mobile-data choice is offered there; it is the device's, in the app menu (capability `mobile-data`)
