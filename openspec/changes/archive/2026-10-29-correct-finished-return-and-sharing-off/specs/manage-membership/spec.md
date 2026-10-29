## ADDED Requirements

### Requirement: A settings change takes effect as soon as it applies
A settings change SHALL take effect as soon as it applies, and newly enabled directions SHALL start at once
rather than waiting for iOS to schedule work:
turning sharing on SHALL start sharing, and turning receiving on SHALL start bringing in the event's
photos. Widening the range SHALL share the newly included older photos while photos already shared stay
shared. Narrowing the range or turning sharing off SHALL stop listing the excluded photos to the event;
members who already received them SHALL keep them, and a later widening SHALL bring them back to the
event. Turning sharing off SHALL withdraw an upload already under way like any other photo not yet shared
(capability `photo-sharing`) and SHALL start no new upload; turning receiving off SHALL stop downloads under
way at once. Photos the member already received SHALL be
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

#### Scenario: Turning sharing off withdraws the upload under way
- **WHEN** a member turns sharing off and confirms while a photo is uploading
- **THEN** that photo is not offered to the other members and does not count as shared, and no new uploads
  start

#### Scenario: Turning receiving off stops downloads
- **WHEN** a member turns receiving off while the event's photos are downloading
- **THEN** those downloads stop and no further photos arrive

## REMOVED Requirements

### Requirement: Settings changes take effect immediately
**Reason**: Its scenario "Turning sharing off lets uploads finish" says the opposite of what the app does and
of capability `photo-sharing`'s withdrawal rule. OpenSpec cannot rename a single scenario, so the requirement
is replaced whole.
**Migration**: "A settings change takes effect as soon as it applies" carries every scenario, with the one
under way withdrawn instead of finished. Claims on the old name are re-pointed to the new one.
