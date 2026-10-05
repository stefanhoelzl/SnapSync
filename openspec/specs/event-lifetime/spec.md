# event-lifetime Specification

## Purpose

Serves hosts and guests of a short-lived event with plain promises about how long it lasts and how many
can take part. The event's date range bounds which photos may be shared and does not by itself close the
event, so a guest who scans days late still joins and contributes — until the event closes. Once the range
has ended and every member has settled what it shares, or a few days after its last photo arrived, the
event closes: nobody joins and nothing about it changes any more. Its photos are deleted from the server
once every member has them, a few days after the close at the latest, and never later than 30 days after
its creation or its start — the retention promise every member relies on. Only a limited number of devices can
ever take part, and leaving frees no place. Until it closes, nothing about an event except its name changes.
Decision record: changes/archive/2026-09-28-early-event-completion
## Requirements
### Requirement: The date range closes nothing

The end of an event's date range SHALL bound only which photos may be shared (capability
`photo-sharing`); reaching it SHALL close nothing by itself. Until the event closes (see "A finished
event closes"), joining, sharing in-range photos, receiving, renaming and changing settings SHALL all keep
working after the range has ended.

#### Scenario: A late guest still joins
- **WHEN** a guest scans the invite a week after the event's range ended, while the event has not closed
- **THEN** they can join and their photos taken within the range are shared

#### Scenario: Sync continues after the end
- **WHEN** a member still has in-range photos to upload after the range ended
- **THEN** they upload and reach the other members as before

### Requirement: Deletion is never announced and never touches anyone's library

Deleting an event or its photos SHALL NOT send any notification the member sees; each member's app
learns of it on its own and returns to no event (what the app then does: capability
`manage-membership`). Photos already in a member's photo library SHALL stay there when the event or its
photos are deleted.

#### Scenario: A member opens the app after deletion
- **WHEN** an event's photos have been deleted and a member opens the app days later
- **THEN** no notification was ever shown, and the photos they had received are still in their library

### Requirement: A limited number of devices ever take part, and leaving frees no place

An event SHALL admit a limited number of distinct devices over its whole life, set by the service when the
event is created and counting devices that have left. Until the event closes, a device that left SHALL
always be able to rejoin in its own place. A new device SHALL be refused once the limit has been reached,
even if some have left (what the joining guest is told: capability `join-event`). The limit SHALL hold
exactly, even when several devices join at the same moment.

#### Scenario: Leaving does not make room
- **WHEN** an event has reached its device limit and one device leaves
- **THEN** a new device is still refused

#### Scenario: A returning device rejoins
- **WHEN** a device that left a full event scans its invite again before the event has closed
- **THEN** it rejoins

#### Scenario: Simultaneous joins
- **WHEN** three places remain and five new devices join at the same moment
- **THEN** exactly three are admitted

### Requirement: A member who leaves keeps contributing what they shared

Photos a member shared before leaving SHALL remain part of the event for the other members, including
members who join later, until the event's photos are deleted — unless the member withdraws them (capability
`photo-sharing`).

#### Scenario: A late joiner sees a departed member's photos
- **WHEN** a member shares photos and leaves, and a new guest joins afterwards
- **THEN** the new guest receives the departed member's photos

### Requirement: Only the name changes after creation

After an event is created, its name SHALL be the only thing about the event anyone can change (capability
`manage-membership`), and only until the event closes; a closed event SHALL NOT change at all. Its date
range, its lifetime and its device limit SHALL stay exactly as created.

#### Scenario: Widening the range is impossible
- **WHEN** someone holding the invite tries to change an event's date range after creation
- **THEN** the range is unchanged, so no member's shared range can be widened after the fact

#### Scenario: A closed event keeps its name
- **WHEN** a member tries to rename an event that has closed
- **THEN** the name stays as it was

### Requirement: A finished event closes

An event whose date range has ended SHALL close as soon as every member still in it has settled which of
its photos it shares — which each member's device does on its own after the end, at the latest when it
next runs (capability `photo-sharing`) — and in any case 3 days after the later of the range's end and
the moment the last photo reached the event. A closed event SHALL admit no new member (capability
`join-event`) and SHALL NOT change any more: no rename, no change to what any member shares, receives or
collects (capability `manage-membership`). Closing SHALL be final. An event created but never joined SHALL
NOT close before its 30 days are up.

#### Scenario: Everyone settled soon after the end
- **WHEN** an event's range ends on Sunday evening and every member's phone has settled what it shares by
  Monday morning
- **THEN** the event closes on Monday morning, without waiting 3 days

#### Scenario: A silent member does not hold the event open
- **WHEN** one member's phone stays off after the range ends while the others settle, and no photo
  reaches the event for 3 days after the end
- **THEN** the event closes 3 days after the end without that member

#### Scenario: Photos still arriving push the close back
- **WHEN** a member's last photos reach the event two days after the range ended while another member is
  still silent
- **THEN** the event closes 3 days after those last photos arrived, not 3 days after the end

#### Scenario: A late guest after the close
- **WHEN** a guest scans the invite after the event has closed
- **THEN** they cannot join

### Requirement: An event's photos are deleted once it is finished, and after 30 days at the latest

An event's photos SHALL be deleted from the server within about a day of whichever comes first: every
member who joined having left — whether by their own leave or on their own once the event closed and they
had everything (capability `manage-membership`); 3 days after the later of the range's end and the moment
its last photo arrived; or 30 days after the event's creation or its start, whichever is later. The first
two SHALL apply only to an event someone has joined; an event nobody ever joined lives until the third.
From then on the event SHALL admit no one, and its invite SHALL lead to no photos. The 30-day moment
SHALL be fixed at creation and SHALL NOT be extended by anything afterwards, including a rename or a
photo arriving. Deleting SHALL remove every photo shared to the event from the server, except a photo
another still-existing event also holds. A member's leave SHALL count toward this even if it was made
offline, once the device is online again.

#### Scenario: Everyone has everything
- **WHEN** an event has closed and every member has received all of its photos and left
- **THEN** within about a day its photos are gone from the server

#### Scenario: A leave made offline still counts
- **WHEN** the last member leaves while offline and is online again the next day
- **THEN** the event's photos are deleted within about a day after that, without the member doing
  anything more

#### Scenario: A member who never comes back
- **WHEN** an event has closed and one member never opens SnapSync again or deleted it without leaving
- **THEN** the event's photos are still deleted about 3 days after its last photo arrived

#### Scenario: An event created ahead of its start
- **WHEN** an event is created on 1 June for a range starting 20 June, and is still not finished in July
- **THEN** its photos are deleted no later than 30 days after 20 June

#### Scenario: A back-dated event
- **WHEN** an event is created on 1 June for a range that started in May
- **THEN** its photos are deleted no later than 30 days after 1 June, so it is not born expired

#### Scenario: A rename does not extend the lifetime
- **WHEN** a member renames an event before it closes
- **THEN** its photos are deleted no later than they would have been without the rename

#### Scenario: Created but never joined
- **WHEN** a host creates an event and cancels before confirming the join
- **THEN** the event and its invite still work until 30 days after its creation or start

