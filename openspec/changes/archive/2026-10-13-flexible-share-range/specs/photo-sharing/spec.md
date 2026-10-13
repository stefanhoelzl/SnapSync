## REMOVED Requirements

### Requirement: Only photos taken inside the member's capture range are shared
**Reason**: The range is no longer held inside the event's dates whatever the member chose; it is the member's
own choice, bounded to at most 30 days and overlapping the event.
**Migration**: Replaced by "Only photos inside the range the member chose are shared", which keeps the inclusive ends, the always-bounded range, the undated-photo
rule and that neither the host nor a link can widen a member's range.

## ADDED Requirements

### Requirement: Only photos inside the range the member chose are shared

A member SHALL share only photos whose capture date lies inside their membership's capture range, both
ends inclusive. The range SHALL always have a start and an end, and SHALL be the range the member chose —
by default the event's own dates (chosen at join, capability `join-event`; changed later, capability
`manage-membership`). No membership shares a whole camera roll: the range SHALL be at most 30 days long and
SHALL overlap the event's dates. It MAY reach before the event's start or after its end only as far as the
member chose; neither the host nor an invite link can widen it. A photo with no capture date SHALL NOT be
shared.

#### Scenario: A photo from before the range stays private
- **WHEN** a member joins an event and their library holds photos taken before the start of their range
- **THEN** none of those photos is uploaded, listed to other members, or counted as shareable

#### Scenario: A photo from after the range's end stays private
- **WHEN** a member whose range ends with the event takes a photo after the event's end date
- **THEN** it is not shared, even though the member is still joined

#### Scenario: A photo after the event inside the member's range is shared
- **WHEN** a member chose a range ending two days after the event's end, and takes a photo the day after the
  event's end
- **THEN** it is shared to the event, like any photo inside their range

#### Scenario: A photo before the event inside the member's range is shared
- **WHEN** a member chose a range starting the evening before the event's start, and took photos that evening
- **THEN** those photos are shared to the event

#### Scenario: A photo taken exactly at the end is shared
- **WHEN** a photo's capture time equals the end of the member's range
- **THEN** it is shared

#### Scenario: The host cannot widen a member's choice
- **WHEN** the member picks a later start and an earlier end than the event's dates
- **THEN** only photos inside the member's own narrower range are shared

#### Scenario: A link cannot widen a member's range
- **WHEN** an invite link carries a range of capture dates
- **THEN** the membership shares only the range the member chose on the join screen

#### Scenario: An undated photo is not shared
- **WHEN** a photo in the library carries no capture date
- **THEN** it is not shared

## MODIFIED Requirements

### Requirement: Nothing is shared before the event starts

While the event's start date lies in the future, no photo SHALL be uploaded to it. After the start
passes, sharing SHALL begin on the next ordinary occasion — a new photo, the app being opened, or the
phone's next background run — with no dedicated wake-up at the start time. Receiving is not held back by the
start date (capability `receiving-photos`).

#### Scenario: A joined member of a future event shares nothing yet
- **WHEN** a member is joined to an event that has not started and takes photos
- **THEN** none of them is uploaded

#### Scenario: Sharing begins after the start
- **WHEN** the start date passes and the member then takes a photo or opens the app
- **THEN** every photo inside the member's range starts to be shared

#### Scenario: Photos from before the start wait for it
- **WHEN** a member joins an event that has not started, with a range starting two days before the event's
  start, and holds photos from those two days
- **THEN** none of them is uploaded before the event's start, and they are shared once it has passed
