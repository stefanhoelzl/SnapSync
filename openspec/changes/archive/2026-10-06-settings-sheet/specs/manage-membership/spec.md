## MODIFIED Requirements

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

### Requirement: Saved settings take effect immediately
A settings change SHALL take effect as soon as it applies, and newly enabled directions SHALL start at once
rather than waiting for iOS to schedule work:
turning sharing on SHALL start sharing, and turning receiving on SHALL start bringing in the event's
photos. Widening the range SHALL share the newly included older photos while photos already shared stay
shared. Narrowing the range or turning sharing off SHALL stop listing the excluded photos to the event;
members who already received them SHALL keep them, and a later widening SHALL bring them back to the
event. Turning sharing off SHALL let uploads already under way finish and count as shared; turning
receiving off SHALL stop downloads under way at once. Photos the member already received SHALL be
unaffected by any change. Changing whether photos may use mobile data SHALL govern every transfer that
starts after the change, while a transfer already under way keeps the rule it started with (capability
`mobile-data`).

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

#### Scenario: Turning mobile data off governs what starts next
- **WHEN** a member on mobile data turns the mobile-data choice off
- **THEN** no photo transfer starts over mobile data from then on, and transfers already under way are not cancelled

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

## REMOVED Requirements

### Requirement: A member changes their settings without leaving
**Reason**: Settings no longer have Save and Cancel; each change applies as it is made, and both directions may be off.
**Migration**: Replaced by "Settings change as they are made, without leaving" below; the joined screen's settings action, what the settings offer and the closed-event rule carry over unchanged.

## ADDED Requirements

### Requirement: Settings change as they are made, without leaving
Until the event closes (capability `event-lifetime`), the joined screen SHALL offer a settings action
that opens the same choices as the join screen — share and receive switches, the capture range, the
album, and whether photos may use mobile data (capability `mobile-data`) — showing the membership's current
settings, over the joined screen, which stays partly visible above them. A
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

#### Scenario: Settings show the mobile-data choice
- **WHEN** a member who joined with mobile data off opens settings
- **THEN** the mobile-data choice is shown off

