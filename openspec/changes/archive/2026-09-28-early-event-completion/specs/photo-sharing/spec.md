## MODIFIED Requirements

### Requirement: Deleting or no longer sharing a photo withdraws it

Until the event closes (capability `event-lifetime`), a photo SHALL stop being offered to other members
when the member deletes it from their library, removes it from their limited selection, or stops sharing
it — by narrowing the range, adding it to a messaging-app album, or turning sharing off (capability
`manage-membership`). The withdrawal SHALL take effect from the device's next upload pass, also for a
photo that was still uploading. Members who already received it SHALL keep their copy, and a withdrawal
SHALL wake no other member. Losing photo access, or the app being unable to read the library for a
moment, SHALL NOT withdraw anything.

#### Scenario: A deleted photo is withdrawn
- **WHEN** a member deletes a photo that other members could receive
- **THEN** members who have not yet received it never do, and members who already have it keep it

#### Scenario: A photo deleted mid-upload is withdrawn
- **WHEN** a member deletes a photo whose upload is still in progress
- **THEN** it is withdrawn as soon as the device next looks at the library, even if its bytes reach the
  server afterwards

#### Scenario: Deselecting under limited access withdraws
- **WHEN** a limited-access member removes a shared photo from their selection
- **THEN** it stops being offered to other members

#### Scenario: Turning sharing off withdraws the member's photos
- **WHEN** a member switches from sharing and receiving to receiving only
- **THEN** none of their photos is offered to members who have not received them yet

#### Scenario: Revoked access withdraws nothing
- **WHEN** photo access is revoked or temporarily narrowed while photos are shared
- **THEN** every photo already shared stays available to the other members

#### Scenario: A deletion noticed late is still withdrawn
- **WHEN** a photo is deleted while the device does no work for days, and the event has not closed
- **THEN** it is withdrawn the next time the device looks at the library

### Requirement: Restoring or re-including a photo shares it again

Until the event closes (capability `event-lifetime`), a photo SHALL be offered to the other members again
when it is recovered from Recently Deleted, re-added to the selection, or brought back into scope by
widening the range or turning sharing back on. A photo that was already uploaded and was only out of
scope SHALL come back without being uploaded again, and bringing it back SHALL wake the other members.

#### Scenario: A recovered photo is shared again
- **WHEN** a member recovers a withdrawn photo from Recently Deleted
- **THEN** it is uploaded again and offered to the other members

#### Scenario: Narrow then widen re-uploads nothing
- **WHEN** a member narrows their range, then widens it back
- **THEN** the photos that fell out come back for the other members with no photo uploaded again

## ADDED Requirements

### Requirement: A member's share is settled only after the event has ended

A member's device SHALL settle which of its photos it shares to an event only after the event's range has
ended and only once it has looked at the photo library since then, so that every photo the member took
within their range before the end is part of what they share — including photos only in iCloud or taken
while the phone was offline. Settling SHALL NOT wait for the photos to finish uploading. Until the event
closes (capability `event-lifetime`), a member's device that finds more photos to share SHALL still share
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
unless the event's photos are deleted first (capability `event-lifetime`).

#### Scenario: A deletion after the close
- **WHEN** a member deletes a shared photo after the event has closed
- **THEN** members who have not yet received it still do

#### Scenario: A photo added after the close
- **WHEN** a limited-access member adds an in-range photo to their selection after the event has closed
- **THEN** it is not shared

#### Scenario: A last upload finishes after the close
- **WHEN** a member's photo is still uploading when the event closes because every member has settled
- **THEN** it completes and reaches the other members
