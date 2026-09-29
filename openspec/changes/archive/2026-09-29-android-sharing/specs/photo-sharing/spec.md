## ADDED Requirements

### Requirement: On Android, only photos in the DCIM folder are shared

On Android a member SHALL share only photos and videos stored in the phone's DCIM folder — where camera apps
save — and its subfolders, on the phone's own storage and on an SD card alike. Each subfolder of DCIM SHALL
count as an album for the album rule (requirement "Media that is certainly not an event photo is excluded"),
so a subfolder named for a messaging or social app, or for the phone's screenshots or screen recordings, is
excluded. Inside DCIM the capture range and the resolution floors SHALL apply too, to edited photos as well,
because Android does not tell the app that a photo was edited. A photo or video outside DCIM SHALL NOT be
shared, however it got there and whatever its resolution — media saved from a messaging app or the web,
downloads, and photos from in-app cameras that save elsewhere — and the member SHALL have no way to add
another folder. Under limited access (capability `photo-access`) the member's selection SHALL be filtered the
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

## MODIFIED Requirements

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
