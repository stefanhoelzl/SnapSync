## ADDED Requirements

### Requirement: A member's share is settled only after the event has ended

A member's device SHALL settle which of its photos it shares to an event only after the event's range has
ended and only once it has looked at the photo library since then, so that every photo the member took
within their range before the end is part of what they share — including photos only in iCloud or taken
while the phone was offline. Settling SHALL NOT wait for the photos to finish uploading. Until the event
closes (see "A finished event closes"), a member's device that finds more photos to share SHALL still share
them.

#### Scenario: A photo from the last evening
- **WHEN** a member takes a photo an hour before the range ends and their phone stays off until the next
  day, before the event has closed
- **THEN** that photo is shared to the event

#### Scenario: Selecting more photos after the end
- **WHEN** a limited-access member adds in-range photos to their selection after the range ended, before
  the event closed
- **THEN** those photos are shared to the event

### Requirement: What a member shares is fixed once the event has closed

Once the event has closed, what each member shares to it SHALL NOT change: a photo already shared SHALL
stay available to every member still receiving, even if its owner deletes it, deselects it or loses photo
access, and no further photo SHALL be shared to it. Photos already on their way SHALL still arrive,
unless the event's photos are deleted first (see "An event's photos are deleted once it is finished, and after 30 days at the latest").

#### Scenario: A deletion after the close
- **WHEN** a member deletes a shared photo after the event has closed
- **THEN** members who have not yet received it still do

#### Scenario: A photo added after the close
- **WHEN** a limited-access member adds an in-range photo to their selection after the event has closed
- **THEN** it is not shared

#### Scenario: A last upload finishes after the close
- **WHEN** a member's photo is still uploading when the event closes because every member has settled
- **THEN** it completes and reaches the other members

### Requirement: The app leaves on its own once the event is finished for it
The app SHALL end the membership on its own, exactly as an explicit leave but without asking and without
showing anything, in the foreground or during a background wake, when either:
- the event has closed (see "A finished event closes"), every photo this member shares has reached the
  event, and every photo of the others that this member receives is in their library or was deleted by
  them there — a photo that repeatedly fails to arrive keeps the member in the event; or
- the server confirms the event has finished and its photos were deleted; or
- the member opens the app, the event is confirmed not to exist, and its announced deletion date has
  already passed.

It SHALL NOT end a membership on any other evidence: not while offline, not on a server error, not when
the server merely fails to find the event before its deletion date, and not for a membership that does
not yet know its deletion date. Every doubt SHALL resolve toward staying joined. The next time the member
opens the app after such a leave, it SHALL show the create screen.

#### Scenario: Everything received after the close
- **WHEN** the event closes and the member's phone, in their pocket, has every photo of the event
- **THEN** SnapSync leaves the event in the background, and the next time the member opens it they see
  the create screen

#### Scenario: A share-only member waits for their own uploads
- **WHEN** the event closes while a share-only member's last photos are still uploading
- **THEN** the member stays in the event until those photos have reached it, then leaves on their own

#### Scenario: A photo that will not arrive keeps the member
- **WHEN** the event has closed and one of the others' photos repeatedly fails to arrive in this member's
  library
- **THEN** the member stays in the event

#### Scenario: A member whose event finished without them
- **WHEN** a member's phone was off for a week and the event's photos were deleted meanwhile, and the
  phone is woken in the background
- **THEN** SnapSync learns the event has finished and leaves it, with the photos it had received still in
  the library

#### Scenario: A deleted event past its date is left automatically
- **WHEN** a member opens SnapSync after the event's deletion date, and the event has been deleted
- **THEN** the app shows the create screen with the device in no event

#### Scenario: A server error never ends a membership
- **WHEN** the member opens the app after the deletion date while the server is failing or the phone is offline
- **THEN** the member stays joined

#### Scenario: A premature "gone" is disbelieved
- **WHEN** the server fails to find the event before its deletion date without confirming it has finished
- **THEN** the member stays joined and sharing continues

## MODIFIED Requirements

### Requirement: Deletion is never announced and never touches anyone's library

Deleting an event or its photos SHALL NOT send any notification the member sees; each member's app
learns of it on its own and returns to no event (what the app then does: requirement "The app leaves on its own once the event is finished for it"). Photos already in a member's photo library SHALL stay there when the event or its
photos are deleted.

#### Scenario: A member opens the app after deletion
- **WHEN** an event's photos have been deleted and a member opens the app days later
- **THEN** no notification was ever shown, and the photos they had received are still in their library

### Requirement: A finished event closes

An event whose date range has ended SHALL close as soon as every member still in it has settled which of
its photos it shares — which each member's device does on its own after the end, at the latest when it
next runs (see "A member's share is settled only after the event has ended") — and in any case 3 days after the later of the range's end and
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
had everything (see "The app leaves on its own once the event is finished for it"); 3 days after the later of the range's end and the moment
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
