# event-lifetime Specification

## Purpose

Serves hosts and guests of a short-lived event with two plain promises about how long it lasts and how
many can take part. The event's date range bounds only which photos may be shared and closes nothing,
so a guest who scans days late still joins and contributes. The event itself lives for a fixed 30 days
from its creation or its start, whichever is later, and is then deleted with all its photos on the
server — the retention promise every member relies on. It may be deleted sooner once everyone has left,
but that is not promised. At most 10 devices can ever take part, and leaving frees no place. Nothing
about an event except its name changes after it is created.
Decision record: changes/archive/2026-07-24-decouple-event-window-from-lifetime

## Requirements

### Requirement: The date range closes nothing

The end of an event's date range SHALL bound only which photos may be shared (capability
`photo-sharing`). Until the event is deleted, joining, sharing in-range photos, receiving, renaming and
leaving SHALL all keep working after the range has ended; no time limit other than deletion SHALL ever
close the event.

#### Scenario: A late guest still joins
- **WHEN** a guest scans the invite a week after the event's range ended, before the event is deleted
- **THEN** they can join and their photos taken within the range are shared

#### Scenario: Sync continues after the end
- **WHEN** a member still has in-range photos to upload after the range ended
- **THEN** they upload and reach the other members as before

### Requirement: An event and its photos are deleted 30 days after it begins to live

Every event SHALL be deleted 30 days after its creation or its start, whichever is later, and SHALL be
deleted no later than about a day after that moment. Deleting an event SHALL delete it and every photo
shared to it from the server, except a photo another still-existing event also holds. The lifetime
SHALL be fixed at creation and SHALL NOT be extended by anything afterwards, including a rename.

#### Scenario: An event created ahead of its start
- **WHEN** an event is created on 1 June for a range starting 20 June
- **THEN** it lives until 30 days after 20 June

#### Scenario: A back-dated event
- **WHEN** an event is created on 1 June for a range that started in May
- **THEN** it lives until 30 days after 1 June, so it is not born expired

#### Scenario: Photos leave the server with the event
- **WHEN** an event reaches the end of its lifetime
- **THEN** within about a day the event and the photos shared to it are gone from the server, and its
  invite no longer leads to it

#### Scenario: A rename does not extend the lifetime
- **WHEN** a member renames an event
- **THEN** the event is deleted at the same moment it would have been without the rename

### Requirement: An event may end sooner once everyone has left, but that is not promised

Nothing shown to users SHALL promise early deletion: an event whose every member has left MAY be
deleted before its lifetime ends, but it need not be, because a leave the server never heard about keeps the event alive until
its lifetime ends. An event that was created but never joined SHALL live until its lifetime ends.

#### Scenario: Everyone has left
- **WHEN** every member of an event has left and the server has been told
- **THEN** the event may be deleted within about a day, before its 30 days are up

#### Scenario: Created but never joined
- **WHEN** a host creates an event and cancels before confirming the join
- **THEN** the event still exists and its invite still works until its lifetime ends

### Requirement: Deletion is never announced and never touches anyone's library

Deleting an event SHALL NOT send any notification; each member's app SHALL learn of it the next time it
is opened (what the app then does: capability `manage-membership`). Photos already in a member's photo
library SHALL stay there when the event is deleted.

#### Scenario: A member opens the app after deletion
- **WHEN** an event has been deleted and a member opens the app days later
- **THEN** no notification was sent in between, and the photos they had received are still in their
  library

### Requirement: At most 10 devices ever take part, and leaving frees no place

An event SHALL admit at most 10 distinct devices over its whole life, counting devices that have left.
A device that left SHALL always be able to rejoin in its own place. A new device SHALL be refused once 10
have joined, even if some have left (what the joining guest is told: capability `join-event`). The
limit SHALL hold exactly, even when several devices join at the same moment.

#### Scenario: Leaving does not make room
- **WHEN** an event has had 10 devices and one leaves
- **THEN** an 11th device is still refused

#### Scenario: A returning device rejoins
- **WHEN** a device that left a full event scans its invite again while the event exists
- **THEN** it rejoins

#### Scenario: Simultaneous joins
- **WHEN** three places remain and five new devices join at the same moment
- **THEN** exactly three are admitted

### Requirement: A member who leaves keeps contributing what they shared

Photos a member shared before leaving SHALL remain part of the event for the other members, including
members who join later, until the event is deleted — unless the member withdraws them (capability
`photo-sharing`).

#### Scenario: A late joiner sees a departed member's photos
- **WHEN** a member shares photos and leaves, and a new guest joins afterwards
- **THEN** the new guest receives the departed member's photos

### Requirement: Only the name changes after creation

After an event is created, its name SHALL be the only thing anyone can change (capability
`manage-membership`). Its date range, its lifetime and its device limit SHALL stay exactly as created.

#### Scenario: Widening the range is impossible
- **WHEN** someone holding the invite tries to change an event's date range after creation
- **THEN** the range is unchanged, so no member's shared range can be widened after the fact
