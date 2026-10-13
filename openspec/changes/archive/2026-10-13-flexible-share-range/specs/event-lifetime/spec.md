## RENAMED Requirements

- FROM: `### Requirement: A member's share is settled only after the event has ended`
- TO: `### Requirement: A member's share is settled only after their own range has ended`

## MODIFIED Requirements

### Requirement: A member's share is settled only after their own range has ended

A member's device SHALL settle which of its photos it shares to an event only after the member's own capture
range has ended — for most members the event's end; earlier for a range that ends before it, later for one that
ends after it (capability `photo-sharing`) — and only once it has looked at the photo library since then, so
that every photo the member took within their range is part of what they share — including photos only in iCloud or taken
while the phone was offline. Settling SHALL NOT wait for the photos to finish uploading, and SHALL NOT stop
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

### Requirement: An event's photos are deleted once it is finished, and after 30 days at the latest

An event SHALL be **finished** once its open-until date has passed and every member still in it is done
(see "A member's share is settled only after their own range has ended"); a guest joining afterwards SHALL make it
unfinished again until they are done too.

An event's photos SHALL be deleted from the server within about a day of whichever comes first: the event
being finished; 3 days after the latest of its open-until date, the last time a device joined, and the moment
its last photo arrived; or every member who joined having left. These SHALL apply only to an event someone
has joined. In any case, the event and its photos SHALL be deleted 30 days after its open-until date; an event
nobody ever joined lives until then. That latest moment SHALL be fixed at creation and SHALL NOT be extended by
anything afterwards, including a rename, a join or a photo arriving. From deletion on the event SHALL admit no
one, and its invite SHALL lead to no photos. Deleting SHALL remove every photo shared to the event from the
server, except a photo another still-existing event also holds. A member's leave SHALL count toward this even
if it was made offline, once the device is online again.

#### Scenario: Everyone has everything
- **WHEN** an event's range ended on Sunday evening and by Monday morning every member has shared and
  received all of its photos
- **THEN** within about a day its photos are gone from the server, without waiting 3 days

#### Scenario: A member who never comes back
- **WHEN** one member's phone stays off after the range ends while the others are done, and no photo reaches
  the event and nobody joins for 3 days after the end
- **THEN** the event's photos are deleted about 3 days after the end

#### Scenario: Photos still arriving push deletion back
- **WHEN** a member's last photos reach the event two days after the range ended while another member is
  still silent
- **THEN** the photos are deleted about 3 days after those last photos arrived, not 3 days after the end

#### Scenario: A late guest keeps the event alive
- **WHEN** a guest joins two days after the end while another member is still silent
- **THEN** the photos are kept at least 3 days after that join, and the guest receives them

#### Scenario: A leave made offline still counts
- **WHEN** the last member leaves while offline and is online again the next day
- **THEN** the event's photos are deleted within about a day after that, without the member doing
  anything more

#### Scenario: A back-dated event
- **WHEN** a host creates on 1 June an event for a weekend in May, chooses 7 days, and is done at once
- **THEN** its photos are not deleted before 8 June, so guests who scan the invite that week still join and
  receive them

#### Scenario: An event created ahead of its start
- **WHEN** an event is created on 1 June for a range from 20 to 22 June, and one member stays silent
- **THEN** its photos are deleted no later than 30 days after 22 June

#### Scenario: The latest day
- **WHEN** an event keeps receiving photos and new members long after its open-until date
- **THEN** its photos are deleted no later than 30 days after its open-until date

#### Scenario: A rename does not extend the lifetime
- **WHEN** a member renames an event after its range ended
- **THEN** its photos are deleted no later than they would have been without the rename

#### Scenario: Created but never joined
- **WHEN** a host creates an event and cancels before confirming the join
- **THEN** the event and its invite still work until 30 days after its open-until date
