## MODIFIED Requirements

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
if it was made offline or while the service was failing, once the service takes it, without the member doing
anything more. A member SHALL count as done only once the service has learned it from their phone: while their
phone cannot tell it, the photos SHALL NOT be deleted on account of that member before the 3-day and 30-day limits
above, and SHALL be deleted as for any finished event once it has.

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

#### Scenario: A leave the service failed to take still counts
- **WHEN** the last member leaves while the service is failing, and the service works again the next day
- **THEN** the event's photos are deleted within about a day after that, without the member doing anything more

#### Scenario: A member who cannot say they are done holds deletion back
- **WHEN** after the range ended one member has received every photo, but the service fails each time their phone
  tells it so, while every other member is done
- **THEN** the photos are not deleted on that member's account before the 3-day limit, and are deleted within
  about a day once their phone has told the service

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

### Requirement: The app never leaves an event on its own

The app SHALL NOT end a membership on its own — not when the event's range ends, not when the member is done,
not when the event is finished and not when its photos are deleted. A membership SHALL end only when the
member leaves, or when they open another event's invite and switch (capability `invite-link`). Once the
server confirms an event's photos were deleted — whether it is asked about the event, or answers so when the
member's phone shares photos or renames the event — the app SHALL keep the member on it, say it is over
(capability `sync-status`), and share nothing more to it. It SHALL NOT treat the event as deleted on any other
evidence: not while offline, not on a server error, not on an answer it does not understand, and not when the
server merely fails to find the event before the latest day its photos may live. Every doubt SHALL resolve toward
the event still running.

#### Scenario: A finished event stays on screen
- **WHEN** an event becomes finished while the member's phone is in their pocket
- **THEN** the next time they open SnapSync they are still in the event, shown as up to date

#### Scenario: A deleted event is not left silently
- **WHEN** a member opens SnapSync after the event's photos were deleted
- **THEN** they are still in the event, the app says it is over, and the photos they received are in their
  library

#### Scenario: Sharing into a deleted event marks it over
- **WHEN** the member's phone shares photos in the background and the service answers that the event's photos
  were deleted
- **THEN** the next time the member sees the joined screen it says the event is over, they are still in the
  event, and nothing more is shared to it

#### Scenario: A server error never ends a membership
- **WHEN** the member opens the app while the server is failing or the phone is offline
- **THEN** the member stays in the event and nothing says it is over

#### Scenario: A premature "gone" is disbelieved
- **WHEN** the server fails to find the event before the latest day its photos may live, without confirming
  they were deleted
- **THEN** the member stays in the event and sharing continues
