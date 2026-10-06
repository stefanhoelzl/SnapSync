# photo-sharing Specification

## Purpose

Every member who shares relies on one promise: the photos they took during the event — and only those —
reach the other members, and nothing else from their camera roll does. This capability decides which of a
member's photos enter an event: a capture-date range that is always bounded and never wider than the
event's own dates, minus media that is certainly not a photo someone took at the event (screenshots,
screen recordings, compressed received media, messaging-app albums). On iPhone, where the app cannot tell,
it shares, because a stray meme is visible and harmless while an event photo that silently never arrives
is a failure nobody can notice or fix; on Android only the phone's camera folder (DCIM) is shared, for
cleaner events. It also promises what the other members see of that contribution over
time — a photo appears only once it is complete, disappears when the member deletes or stops sharing it
until the event closes, after which what each member shares is fixed, and a photo already in the event is
not uploaded again because the member rejoins, switches events and back, or reinstalls. How the photos
travel is capability `background-upload`; what arrives on the other side is capability `receiving-photos`.

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
passes, sharing SHALL begin on the next ordinary occasion — a new photo, the app being opened, or the
phone's next background run — with no dedicated wake-up at the start time. Receiving is not held back by the
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
such as WhatsApp, Telegram, Signal or Instagram — or the phone's own screenshots or screen recordings
(Screenshots, Screen recordings, ScreenRecorder); on Android an album is a subfolder of DCIM (requirement "On
Android, only photos in the DCIM folder are shared"). These floors SHALL be fixed values, never derived from
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

#### Scenario: A photo in a user album named "Screenshots" is excluded
- **WHEN** an iPhone member keeps a photo in an album they named "Screenshots"
- **THEN** it is not shared

### Requirement: When in doubt, a photo is shared

On iPhone, a photo that no exclusion certainly identifies as received or generated media SHALL be shared.
Panoramas, HDR, Live Photos, portrait-mode photos, cropped photos and photos from third-party camera apps
SHALL be shared. A full-resolution photo received by AirDrop or saved from Messages, and an edited or
full-resolution GIF, SHALL be shared too — an accepted gap. On Android the DCIM rule (requirement "On
Android, only photos in the DCIM folder are shared") decides instead: inside DCIM, panoramas, HDR, motion
photos, portrait-mode photos, photos from third-party camera apps and anything else no exclusion identifies
SHALL be shared; outside it nothing is, even when nothing marks it as received.

#### Scenario: A panorama is shared
- **WHEN** a member takes a panorama during the event
- **THEN** it is shared

#### Scenario: A full-resolution AirDropped photo is shared
- **WHEN** a member receives a full-resolution photo by AirDrop during the event
- **THEN** it is shared, because nothing certainly marks it as not taken by the member

#### Scenario: An Android motion photo is shared
- **WHEN** an Android member takes a motion photo with the phone's camera app during the event
- **THEN** it is shared

### Requirement: On Android, only photos in the DCIM folder are shared

On Android a member SHALL share only photos and videos stored in the phone's DCIM folder — where camera apps
save — and its subfolders, on the phone's own storage and on an SD card alike. Each subfolder of DCIM SHALL
count as an album for the album rule (requirement "Media that is certainly not an event photo is excluded"),
so a subfolder named for a messaging or social app, or for the phone's screenshots or screen recordings, is
excluded. Inside DCIM the capture range and the resolution floors SHALL apply too, to edited photos as well,
because Android does not tell the app that a photo was edited. A photo or video outside DCIM SHALL NOT be
shared, however it got there and whatever its resolution — media saved from a messaging app or the web,
downloads, and photos from in-app cameras that save elsewhere — and the member SHALL have no way to add
another folder. The folder inside DCIM where SnapSync keeps the event albums (capability `event-album`)
SHALL NOT be shared either, with everything in it — the photos the member received there, and anything the
member put there themselves. Under limited access (capability `photo-access`) the member's selection SHALL be filtered the
same way. On iPhone this requirement does not apply.

#### Scenario: A camera photo is shared
- **WHEN** an Android member takes a photo with the phone's camera app during the event
- **THEN** it is shared

#### Scenario: A photo on the SD card is shared
- **WHEN** the phone's camera app saves the member's event photos to an SD card
- **THEN** they are shared like photos on the phone's own storage

#### Scenario: A third-party camera app that saves into DCIM is shared
- **WHEN** an Android member takes event photos with a camera app that saves them to its own folder inside DCIM
- **THEN** they are shared

#### Scenario: A full-resolution photo saved from a messenger is not shared
- **WHEN** an Android member saves a full-resolution photo from a chat during the event, and it lands outside DCIM
- **THEN** it is not shared

#### Scenario: A screen recording in DCIM is not shared
- **WHEN** an Android member records their screen during the event and the phone saves the recording to a
  "Screen recordings" folder inside DCIM
- **THEN** it is not shared

#### Scenario: A selected photo outside DCIM is not shared
- **WHEN** a limited-access Android member selects an in-range photo saved from the web and an in-range camera photo
- **THEN** only the camera photo is shared

#### Scenario: A small image in DCIM is excluded
- **WHEN** a 1600 × 1200 image lands in the camera's folder during the event
- **THEN** it is not shared

#### Scenario: The event album's photos are not shared
- **WHEN** an Android member has received photos into an event's album, and they lie inside the capture range
  of the event they are sharing to
- **THEN** none of them is shared, even when the device no longer knows it received them

### Requirement: Under limited access the messaging-app album rule cannot apply

On iPhone, a photo a limited-access member selected SHALL be shared even if it belongs to a messaging-app
album, because albums are not visible to the app under limited photo access (capability `photo-access`). The
capture range and every other exclusion SHALL still apply. On Android the album rule SHALL apply under
limited access too, because a photo's folder stays visible.

#### Scenario: A selected WhatsApp photo uploads under limited access
- **WHEN** a limited-access iPhone member selects an in-range, full-resolution photo from a WhatsApp album
- **THEN** it is shared

#### Scenario: A selected screenshot is still excluded
- **WHEN** a limited-access member selects a screenshot taken during the event
- **THEN** it is not shared

#### Scenario: A selected photo from a WhatsApp folder is excluded on Android
- **WHEN** a limited-access Android member selects an in-range photo in a "WhatsApp" folder inside DCIM
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

Until the event closes (capability `event-lifetime`), a photo SHALL stop being offered to other members
when the member deletes it from their library (moving it to the trash counts), removes it from their limited
selection, moves it out of DCIM on Android, or stops sharing it — by narrowing the range, adding
it to a messaging-app album, or turning sharing off (capability `manage-membership`). The withdrawal SHALL
take effect from the device's next upload pass, also for a photo that was still uploading. Members who
already received it SHALL keep their copy, and a withdrawal SHALL wake no other member. Losing photo access,
or the app being unable to read the library for a moment, SHALL NOT withdraw anything.

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

#### Scenario: Moving a photo out of DCIM withdraws it
- **WHEN** an Android member moves a shared photo from DCIM into a folder outside it
- **THEN** it stops being offered to other members, and members who already have it keep it

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
when it is recovered from Recently Deleted or the trash, re-added to the selection, moved back into
DCIM on Android, or brought back into scope by widening the range or turning sharing back on. A
photo that was already uploaded and was only out of scope SHALL come back without being uploaded again, and
bringing it back SHALL wake the other members.

#### Scenario: A recovered photo is shared again
- **WHEN** a member recovers a withdrawn photo from Recently Deleted
- **THEN** it is uploaded again and offered to the other members

#### Scenario: Narrow then widen re-uploads nothing
- **WHEN** a member narrows their range, then widens it back
- **THEN** the photos that fell out come back for the other members with no photo uploaded again

#### Scenario: A photo moved back into DCIM is shared again
- **WHEN** an Android member moves a withdrawn photo back into DCIM before the event closes
- **THEN** it is offered to the other members again

### Requirement: A photo already in an event is not uploaded again for it

A photo the event service already holds from this device SHALL NOT be uploaded again because the member
rejoins the event, switches to another event and back, reinstalls the app, or restores the phone from an
encrypted backup. A rejoined member's already-shared photos SHALL stay available to the other members
without a gap. A photo that also falls inside another event this device joins MAY be uploaded again for
that event. When the device cannot learn at join what the service already holds (for example because it
is offline), it MAY upload such photos again. A repeated upload SHALL never appear to anyone as a second
copy. No promise is made that an app update avoids repeated uploads.

#### Scenario: Rejoining shares without re-uploading
- **WHEN** a member leaves an event and later joins it again
- **THEN** their earlier photos are offered again and none is uploaded a second time

#### Scenario: A reinstalled app keeps its photos
- **WHEN** a member deletes and reinstalls the app and joins the event again
- **THEN** their already-shared photos are not uploaded again and other members do not receive them twice

#### Scenario: A photo in two events is shared to each
- **WHEN** a member shared a photo to one event and joins another event whose range also contains it
- **THEN** the photo is offered in the second event, it may be uploaded again for it, and no member of
  either event receives it twice

#### Scenario: An offline join may upload again, never duplicate
- **WHEN** a member joins while the service cannot be reached to learn what it already holds
- **THEN** the join still completes, photos may be uploaded again, and no member receives any photo twice

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
