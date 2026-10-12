# Spec Delta

## MODIFIED Requirements

### Requirement: The date range closes nothing

The end of an event's date range SHALL bound only which photos may be shared (capability
`photo-sharing`); reaching it SHALL close nothing. Until the event's photos are deleted (see "An event's
photos are deleted once it is finished, and after 30 days at the latest"), joining, rejoining, sharing
in-range photos, withdrawing them, receiving, renaming and changing settings SHALL all keep working after the
range has ended, including after the event's open-until date (see "An event created after its end stays open
as long as the host chose").

#### Scenario: A late guest still joins
- **WHEN** a guest scans the invite a week after the event's range ended, while its photos have not been
  deleted
- **THEN** they can join and their photos taken within the range are shared

#### Scenario: Sync continues after the end
- **WHEN** a member still has in-range photos to upload after the range ended
- **THEN** they upload and reach the other members as before

#### Scenario: A rename after the end
- **WHEN** a member renames an event days after its range ended, while its photos have not been deleted
- **THEN** the new name shows for every member

### Requirement: A limited number of devices ever take part, and leaving frees no place

An event SHALL admit a limited number of distinct devices over its whole life, set by the service when the
event is created and counting devices that have left. Until the event's photos are deleted, a device that
left SHALL always be able to rejoin in its own place. A new device SHALL be refused once the limit has been
reached, even if some have left (what the joining guest is told: capability `join-event`). The limit SHALL
hold exactly, even when several devices join at the same moment.

#### Scenario: Leaving does not make room
- **WHEN** an event has reached its device limit and one device leaves
- **THEN** a new device is still refused

#### Scenario: A returning device rejoins
- **WHEN** a device that left a full event scans its invite again before the event's photos have been
  deleted
- **THEN** it rejoins

#### Scenario: Simultaneous joins
- **WHEN** three places remain and five new devices join at the same moment
- **THEN** exactly three are admitted

### Requirement: A member's share is settled only after the event has ended

A member's device SHALL settle which of its photos it shares to an event only after the event's range has
ended and only once it has looked at the photo library since then, so that every photo the member took
within their range before the end is part of what they share — including photos only in iCloud or taken
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

### Requirement: An event's photos are deleted once it is finished, and after 30 days at the latest

An event SHALL be **finished** once its open-until date has passed and every member still in it is done
(see "A member's share is settled only after the event has ended"); a guest joining afterwards SHALL make it
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

### Requirement: Deletion is never announced and never touches anyone's library

Deleting an event or its photos SHALL NOT send any notification the member sees; each member's app learns of
it on its own and keeps the member on the event, telling them it is over (requirement "The app never leaves an
event on its own"; what the screen shows: capability `sync-status`). Photos already in a member's photo
library SHALL stay there when the event or its photos are deleted.

#### Scenario: A member opens the app after deletion
- **WHEN** an event's photos have been deleted and a member opens the app days later
- **THEN** no notification was ever shown, the app says the event is over, and the photos they had received
  are still in their library

## REMOVED Requirements

### Requirement: A finished event closes
**Reason**: An event no longer closes. A close assumed every member had joined before the end, so an event
created after its end closed before any guest could scan it. Deletion is now the only end of an event's life.
**Migration**: Joining, sharing, receiving, renaming and settings keep working until the event's photos are
deleted; "finished" (everyone done after the open-until date) now triggers deletion directly — see "An
event's photos are deleted once it is finished, and after 30 days at the latest". Events that closed before
this change keep their close until they are deleted.

### Requirement: What a member shares is fixed once the event has closed
**Reason**: With no close there is no moment after which a member's share freezes; a member keeps the
ability to withdraw or add in-range photos until deletion.
**Migration**: A photo a member deletes or stops sharing is no longer offered to members who have not yet
received it, until deletion (capability `photo-sharing`); copies already received stay in every library.

### Requirement: The app leaves on its own once the event is finished for it
**Reason**: Leaving on its own made an event vanish from the screen without a word, and for a back-dated
event it left the host before any guest joined.
**Migration**: The app never ends a membership on its own — see "The app never leaves an event on its own".
Opening another event's invite skips the switch question when nothing can be lost (capability
`invite-link`); a deleted event is shown as over (capability `sync-status`).

### Requirement: Only the name changes after creation
**Reason**: There is no close any more; a scenario about a closed event no longer describes anything.
**Migration**: Replaced by "Only the name changes after creation, until the photos are deleted".

## ADDED Requirements

### Requirement: An event created after its end stays open as long as the host chose

Every event SHALL have an **open-until date**, fixed at creation: the end of its range for an event created
before its end, and for an event created after its end, the date the host chose when creating it (capability
`create-event`). The event SHALL NOT be finished — and its photos SHALL NOT be deleted for being finished or
idle — before its open-until date. The open-until date SHALL NOT change which photos are shared; that remains
the range's alone.

#### Scenario: Guests join a party that is over
- **WHEN** a host creates on Monday an event for Saturday's party, choosing 7 days, and shares the invite
- **THEN** guests who scan it during the following week join and share their photos taken on Saturday, and
  receive everyone's

#### Scenario: The host alone does not finish it
- **WHEN** the host of a back-dated event has shared and received everything an hour after creating it
- **THEN** the event's photos are kept, and the invite still works, until at least the chosen date

#### Scenario: The range still bounds the photos
- **WHEN** a guest of a back-dated event took photos the day after the party, before the chosen date
- **THEN** those photos are not shared

### Requirement: The app never leaves an event on its own

The app SHALL NOT end a membership on its own — not when the event's range ends, not when the member is done,
not when the event is finished and not when its photos are deleted. A membership SHALL end only when the
member leaves, or when they open another event's invite and switch (capability `invite-link`). Once the
server confirms an event's photos were deleted, the app SHALL keep the member on it and say it is over
(capability `sync-status`). It SHALL NOT treat the event as deleted on any other evidence: not while offline,
not on a server error, and not when the server merely fails to find the event before the latest day its
photos may live. Every doubt SHALL resolve toward the event still running.

#### Scenario: A finished event stays on screen
- **WHEN** an event becomes finished while the member's phone is in their pocket
- **THEN** the next time they open SnapSync they are still in the event, shown as up to date

#### Scenario: A deleted event is not left silently
- **WHEN** a member opens SnapSync after the event's photos were deleted
- **THEN** they are still in the event, the app says it is over, and the photos they received are in their
  library

#### Scenario: A server error never ends a membership
- **WHEN** the member opens the app while the server is failing or the phone is offline
- **THEN** the member stays in the event and nothing says it is over

#### Scenario: A premature "gone" is disbelieved
- **WHEN** the server fails to find the event before the latest day its photos may live, without confirming
  they were deleted
- **THEN** the member stays in the event and sharing continues

### Requirement: Only the name changes after creation, until the photos are deleted

After an event is created, its name SHALL be the only thing about the event anyone can change (capability
`manage-membership`), and only until its photos are deleted. Its date range, its open-until date, its
lifetime and its device limit SHALL stay exactly as created.

#### Scenario: Widening the range is impossible
- **WHEN** someone holding the invite tries to change an event's date range after creation
- **THEN** the range is unchanged, so no member's shared range can be widened after the fact

#### Scenario: The open-until date cannot be extended
- **WHEN** someone holding the invite tries to move an event's open-until date later after creation
- **THEN** the date is unchanged, and so is the latest day its photos are deleted
