# photo-sharing Specification

## Purpose

Every member who shares relies on one promise: the photos they took during the event — and only those —
reach the other members, and nothing else from their camera roll does. This capability decides which of a
member's photos enter an event: a capture-date range that is always bounded and never wider than the
event's own dates, minus media that is certainly not a photo someone took at the event (screenshots,
screen recordings, compressed received media, messaging-app albums). Where the app cannot tell, it shares,
because a stray meme is visible and harmless while an event photo that silently never arrives is a failure
nobody can notice or fix. It also promises what the other members see of that contribution over time — a
photo appears only once it is complete, disappears when the member deletes or stops sharing it, and a
photo already in the event is not uploaded again because the member rejoins, switches events or
reinstalls. How the photos travel is capability `background-upload`; what arrives on the other side is
capability `receiving-photos`.

Decision record: changes/archive/2026-07-15-add-photo-selection-policy

## Requirements

### Requirement: Only photos taken inside the member's capture range are shared

A member SHALL share only photos whose capture date lies inside their membership's capture range, both
ends inclusive. The range SHALL always have a start and an end — no membership shares a whole camera roll
— and it SHALL never extend before the event's start date or after its end date, whatever the member
chose or an invite link carried; the member MAY narrow it at either end (chosen at join, capability
`join-event`; changed later, capability `manage-membership`). A photo with no capture date SHALL NOT be
shared.

#### Scenario: A photo from before the range stays private
- **WHEN** a member joins an event and their library holds photos taken before the start of their range
- **THEN** none of those photos is uploaded, listed to other members, or counted as shareable

#### Scenario: A photo from after the event's end stays private
- **WHEN** a member takes a photo after the event's end date
- **THEN** it is not shared, even though the member is still joined

#### Scenario: A photo taken exactly at the end is shared
- **WHEN** a photo's capture time equals the end of the member's range
- **THEN** it is shared

#### Scenario: The host cannot widen a member's choice
- **WHEN** the member picks a later start and an earlier end than the event's dates
- **THEN** only photos inside the member's own narrower range are shared

#### Scenario: A link cannot reach before the event
- **WHEN** an invite link carries a start earlier than the event's start date
- **THEN** the membership still shares nothing taken before the event's start

#### Scenario: An undated photo is not shared
- **WHEN** a photo in the library carries no capture date
- **THEN** it is not shared

### Requirement: The capture range stays on the device

A member's chosen capture range SHALL NOT be sent to any server; it is a private choice of that device.

#### Scenario: Joining sends no range
- **WHEN** a member joins with a chosen range
- **THEN** no request leaves the device carrying either end of the range

### Requirement: Nothing is shared before the event starts

While the event's start date lies in the future, no photo SHALL be uploaded to it. After the start
passes, sharing SHALL begin on the next ordinary occasion — a new photo, the app being opened, or iOS's
next background run — with no dedicated wake-up at the start time. Receiving is not held back by the
start date (capability `receiving-photos`).

#### Scenario: A joined member of a future event shares nothing yet
- **WHEN** a member is joined to an event that has not started and takes photos
- **THEN** none of them is uploaded

#### Scenario: Sharing begins after the start
- **WHEN** the start date passes and the member then takes a photo or opens the app
- **THEN** every photo inside the member's range starts to be shared

### Requirement: Media that is certainly not an event photo is excluded

Inside the range the member's contribution SHALL exclude: screenshots; screen recordings; images smaller
than 3 megapixels and videos smaller than 1280 × 720, unless the photo or video was edited on the device;
and photos in an album whose title exactly matches (ignoring case) a known messaging or social app —
such as WhatsApp, Telegram, Signal or Instagram. These floors SHALL be fixed values, never derived from
the device's camera, and a 1080p video SHALL be shared.

#### Scenario: A screenshot is never shared
- **WHEN** a member takes a screenshot during the event
- **THEN** it is not uploaded and no other member ever sees it

#### Scenario: A screen recording is never shared
- **WHEN** a member records their screen during the event
- **THEN** it is not shared

#### Scenario: A compressed image received from a messenger is excluded
- **WHEN** a 1600 × 1200 unedited image saved from a chat lands in the library during the event
- **THEN** it is not shared

#### Scenario: An ordinary animated GIF is excluded
- **WHEN** a small GIF saved from a messenger or the web lands in the library during the event
- **THEN** it is not shared, because it falls below the image floor

#### Scenario: A 1080p video is shared
- **WHEN** a member records a 1920 × 1080 video during the event
- **THEN** it is shared

#### Scenario: A cropped camera photo is shared
- **WHEN** a member crops a camera photo in Photos until it is smaller than 3 megapixels
- **THEN** it is still shared

#### Scenario: A photo in a WhatsApp album is excluded
- **WHEN** a photo in the library belongs to an album titled "whatsapp"
- **THEN** it is not shared; a photo in an album titled "WhatsApp Backup" or "Holiday 2026" is unaffected

### Requirement: When in doubt, a photo is shared

A photo that no exclusion certainly identifies as received or generated media SHALL be shared. Panoramas,
HDR, Live Photos, portrait-mode photos, cropped photos and photos from third-party camera apps SHALL be
shared. A full-resolution photo received by AirDrop or saved from Messages, and an edited or
full-resolution GIF, SHALL be shared too — an accepted gap.

#### Scenario: A panorama is shared
- **WHEN** a member takes a panorama during the event
- **THEN** it is shared

#### Scenario: A full-resolution AirDropped photo is shared
- **WHEN** a member receives a full-resolution photo by AirDrop during the event
- **THEN** it is shared, because nothing certainly marks it as not taken by the member

### Requirement: Under limited access the messaging-app album rule cannot apply

A photo a limited-access member selected SHALL be shared even if it belongs to a messaging-app album,
because albums are not visible to the app under limited photo access (capability `photo-access`). The
capture range and every other exclusion SHALL still apply.

#### Scenario: A selected WhatsApp photo uploads under limited access
- **WHEN** a limited-access member selects an in-range, full-resolution photo from a WhatsApp album

#### Scenario: A selected screenshot is still excluded
- **WHEN** a limited-access member selects a screenshot taken during the event
- **THEN** it is not shared

### Requirement: What the member is told is shared is exactly what is shared

Four things SHALL always be the same set of photos: the count of shareable photos the member is shown
before joining or saving a change (capabilities `join-event`, `manage-membership`), the photos their
device uploads, the photos other members can receive, and the photos their own progress counts
(capability `sync-status`). An excluded photo SHALL appear in none of them.

#### Scenario: The preview matches the upload
- **WHEN** a member confirms a range whose preview said "12 photos will be shared"
- **THEN** exactly those 12 photos are uploaded and offered to the other members

#### Scenario: An excluded photo cannot hold progress back
- **WHEN** the member's library holds photos after the range's end
- **THEN** they are neither uploaded nor counted as owed, so the member's own progress can complete

### Requirement: Other members see a photo only once it is complete

Another member SHALL be offered a photo only when every part of it has been uploaded — a Live Photo only
once both its still and its video are there. A photo still uploading, or whose upload failed and is being
retried, SHALL never appear to another member as a partial or broken photo. Photos SHALL become available
to others one by one as they complete, while the member's device is still working through a backlog.

#### Scenario: Half a Live Photo is never offered
- **WHEN** a Live Photo's still has been uploaded and its video has not
- **THEN** no other member receives either part yet

#### Scenario: Photos flow while a backlog uploads
- **WHEN** a member joins with hundreds of in-range photos
- **THEN** other members start receiving completed photos before the whole backlog has uploaded

### Requirement: Deleting or no longer sharing a photo withdraws it

A photo SHALL stop being offered to other members when the member deletes it from their library, removes
it from their limited selection, or stops sharing it — by narrowing the range, adding it to a
messaging-app album, or turning sharing off (capability `manage-membership`). The withdrawal SHALL take
effect from the device's next upload pass, also for a photo that was still uploading. Members who already
received it SHALL keep their copy, and a withdrawal SHALL wake no other member. Losing photo access, or
the app being unable to read the library for a moment, SHALL NOT withdraw anything.

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
- **WHEN** a photo is deleted while the device does no work for days
- **THEN** it is withdrawn the next time the device looks at the library

### Requirement: Restoring or re-including a photo shares it again

A photo SHALL be offered to the other members again when it is recovered from Recently Deleted, re-added
to the selection, or brought back into scope by widening the range or turning sharing back on. A photo
that was already uploaded and was only out of scope SHALL come back without being uploaded again, and
bringing it back SHALL wake the other members.

#### Scenario: A recovered photo is shared again
- **WHEN** a member recovers a withdrawn photo from Recently Deleted
- **THEN** it is uploaded again and offered to the other members

#### Scenario: Narrow then widen re-uploads nothing
- **WHEN** a member narrows their range, then widens it back
- **THEN** the photos that fell out come back for the other members with no photo uploaded again

### Requirement: A photo already in the event is not uploaded again

A photo the event service already holds from this device SHALL NOT be uploaded again because the member
rejoins the event, switches to another event and back, reinstalls the app, or restores the phone from an
encrypted backup; nor SHALL a photo shared to one event be uploaded again when it also falls inside
another event this device joins. A rejoined member's already-shared photos SHALL stay available to the
other members without a gap. When the device cannot learn at join what the service already holds (for
example because it is offline), it MAY upload such photos again; a repeated upload SHALL never appear to
anyone as a second copy. No promise is made that an app update avoids repeated uploads.

#### Scenario: Rejoining shares without re-uploading
- **WHEN** a member leaves an event and later joins it again
- **THEN** their earlier photos are offered again and none is uploaded a second time

#### Scenario: A reinstalled app keeps its photos
- **WHEN** a member deletes and reinstalls the app and joins the event again
- **THEN** their already-shared photos are not uploaded again and other members do not receive them twice

#### Scenario: A photo in two events uploads once
- **WHEN** a member shared a photo to one event and joins another event whose range also contains it
- **THEN** the photo is offered in the second event without being uploaded again

#### Scenario: An offline join may upload again, never duplicate
- **WHEN** a member joins while the service cannot be reached to learn what it already holds
- **THEN** the join still completes, photos may be uploaded again, and no member receives any photo twice
