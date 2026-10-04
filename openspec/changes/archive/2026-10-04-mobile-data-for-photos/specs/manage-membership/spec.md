# Spec Delta

## MODIFIED Requirements

### Requirement: A member changes their settings without leaving
Until the event closes (capability `event-lifetime`), the joined screen SHALL offer a settings action
that opens the same choices as the join screen — share and receive switches, the capture range, the
album, and whether photos may use mobile data (capability `mobile-data`) — pre-filled with the membership's current settings under the event's name. A saved
range equal to the event's whole window SHALL show as the whole event, and any other as a custom range.
Save SHALL apply all changes at once, without a confirmation dialog; Cancel SHALL discard them. Both
switches off SHALL disable Save with the reason stated. Changed range bounds SHALL stay within the event's
start and end. Changing settings SHALL keep the member in the event and SHALL work offline. Once the event
has closed, settings SHALL NOT be offered and the membership's settings SHALL stay as they were at the
close.

#### Scenario: Settings open pre-filled
- **WHEN** a member who shares and receives with the album on opens settings
- **THEN** both switches and the album are on and the range shows the one they joined with

#### Scenario: An Android membership from before the album opens with it off
- **WHEN** an Android member who joined before Android had the event album opens settings
- **THEN** the album choice is offered and is off

#### Scenario: Cancel discards changes
- **WHEN** the member changes several settings and taps Cancel
- **THEN** their settings are exactly as before

#### Scenario: A range narrower than the event shows as custom
- **WHEN** a member who joined sharing from a time after the event's start opens settings
- **THEN** the range shows as a custom range with that start and the event's end

#### Scenario: A widened start is held to the event's start
- **WHEN** the member picks a start before the event's start and saves
- **THEN** the membership shares from the event's start

#### Scenario: Settings change offline
- **WHEN** the member saves new settings while the device is offline
- **THEN** the change takes effect and the member remains in the event

#### Scenario: A closed event's settings are fixed
- **WHEN** a member opens the joined screen of an event that has closed
- **THEN** no settings action is offered, and what they share and receive stays as it was

#### Scenario: Settings show the mobile-data choice
- **WHEN** a member who joined with mobile data off opens settings
- **THEN** the mobile-data choice is shown off

### Requirement: Saved settings take effect immediately
After Save, newly enabled directions SHALL start at once rather than waiting for iOS to schedule work:
turning sharing on SHALL start sharing, and turning receiving on SHALL start bringing in the event's
photos. Widening the range SHALL share the newly included older photos while photos already shared stay
shared. Narrowing the range or turning sharing off SHALL stop listing the excluded photos to the event;
members who already received them SHALL keep them, and a later widening SHALL bring them back to the
event. Turning sharing off SHALL let uploads already under way finish and count as shared; turning
receiving off SHALL stop downloads under way at once. Photos the member already received SHALL be
unaffected by any change. Changing whether photos may use mobile data SHALL govern every transfer that
starts after Save, while a transfer already under way keeps the rule it started with (capability
`mobile-data`).

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

#### Scenario: Turning mobile data off governs what starts next
- **WHEN** a member on mobile data turns the mobile-data choice off and saves
- **THEN** no photo transfer starts over mobile data from then on, and transfers already under way are not cancelled
