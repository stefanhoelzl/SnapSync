## MODIFIED Requirements

### Requirement: A member's share is settled only after their own range has ended

A member's device SHALL settle which of its photos it shares to an event only after the member's own capture
range has ended — for most members the event's end; earlier for a range that ends before it, later for one that
ends after it (capability `photo-sharing`) — and only once it has looked at the photo library since then, so
that every photo the member took within their range is part of what they share — including photos only in iCloud or taken
while the phone was offline. Only a look that read everything the member shares from — the library, or under
limited access the selection — and began after their range ended SHALL settle it: a look that could not be made, or
that began before the end, SHALL leave the share unsettled until a later look succeeds. A member who shares nothing
has nothing to look at, and their device SHALL settle once their range has ended. A member who leaves and joins the
event again SHALL settle again, whatever their device said before the leave. Settling SHALL NOT wait for the photos to finish uploading, and SHALL NOT stop
the member sharing: until the event's photos are deleted, a member's device that finds more photos to share
SHALL still share them.

A member SHALL count as **done** with the event once their device has settled what it shares, every photo it
shares has reached the event, and — if the member receives — every photo of the others is in their library or
was deleted by them there. Being done SHALL NOT be final: new photos from another member, or a new photo of
their own within their range, SHALL make the member not done until their device has caught up. A member who
left SHALL count as done with whatever of theirs had reached the event.

#### Scenario: A photo from the last evening
- **WHEN** a member takes a photo an hour before the range ends and their phone stays off until the next
  day
- **THEN** that photo is shared to the event

#### Scenario: Selecting more photos after the end
- **WHEN** a limited-access member adds in-range photos to their selection after the range ended, before
  the event's photos were deleted
- **THEN** those photos are shared to the event, and every other member receiving them is not done until
  they have arrived

#### Scenario: A late guest's photos reopen the others
- **WHEN** every member of an ended event is done and a late guest joins and shares photos
- **THEN** the other members are not done until those photos are in their libraries

#### Scenario: A range that ends after the event keeps it unfinished
- **WHEN** a member's range ends two days after the event's end, and every other member is done by then
- **THEN** the event is not finished before that member's range has ended and their device has settled, and
  their photos from those two days are shared

#### Scenario: A range that ends early is done early
- **WHEN** a member who shares but does not receive has a range ending on Saturday evening, their phone settles
  that evening once their photos have reached the event and then stays off, and the event ends on Sunday with
  every other member done
- **THEN** the event is finished without waiting for that phone

#### Scenario: A look that fails after the end settles nothing
- **WHEN** a member's range has ended and their phone cannot read their photo library, while every other member
  is done
- **THEN** the event is not finished, and once the phone reads the library it shares the in-range photos it finds
  and then settles

#### Scenario: A member who shares nothing settles at the end
- **WHEN** a member who only receives has a range that ends, and every photo of the others is in their library
- **THEN** that member is done without their phone looking at the library

#### Scenario: Rejoining after the end settles again
- **WHEN** a member whose range has ended and whose phone had settled leaves the event, then joins it again while
  the service cannot tell the phone which of its photos it already holds
- **THEN** the member's phone settles again, and the event can finish without
  waiting for its latest deletion date

