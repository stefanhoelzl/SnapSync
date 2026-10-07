# Spec Delta

## ADDED Requirements

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

## REMOVED Requirements

### Requirement: Settings change as they are made, without leaving
**Reason**: The event settings no longer offer the mobile-data choice; it moved to the app menu as a choice of the device.
**Migration**: Replaced by "Event settings change as they are made, without leaving", identical apart from the mobile-data choice, which is the device's (capability `mobile-data`).

### Requirement: Saved settings take effect immediately
**Reason**: Changing the mobile-data choice is no longer a settings change; what a change of it governs is stated once, in `mobile-data`.
**Migration**: Replaced by "Settings changes take effect immediately", identical apart from the mobile-data choice, which is the device's (capability `mobile-data`).
