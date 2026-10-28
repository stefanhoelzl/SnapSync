## MODIFIED Requirements

### Requirement: Media that is certainly not an event photo is excluded

Under full photo access, inside the range the member's contribution SHALL exclude: screenshots; screen recordings;
images smaller than 3 megapixels and videos with fewer pixels than 1280 × 720, unless the photo or video was edited on
the device; and photos in an album whose title exactly matches (ignoring case and surrounding spaces) a known messaging
or social app — such as WhatsApp, Telegram, Signal or Instagram — or the phone's own screenshots or screen recordings
(Screenshots, Screen recordings, ScreenRecorder); on Android an album is a subfolder of DCIM (requirement "On Android
under full access, only photos in the DCIM folder are shared"). These floors SHALL be the same on every phone, and a
1080p video SHALL be shared. Under limited access none of these exclusions SHALL apply to a photo the
member selected (requirement "Under limited access the member's selection decides what a photo is").

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

#### Scenario: A video just under the floor's pixel count is shared
- **WHEN** a member records a 1100 × 900 video during the event
- **THEN** it is shared, because it has more pixels than 1280 × 720 although it is narrower

#### Scenario: A photo in a user album named "Screenshots" is excluded
- **WHEN** an iPhone member keeps a photo in an album they named "Screenshots"
- **THEN** it is not shared

### Requirement: On Android under full access, only photos in the DCIM folder are shared

On Android, under full photo access, a member SHALL share only photos and videos stored in the phone's DCIM folder —
where camera apps save — and its subfolders, on the phone's own storage and on an SD card alike. Each subfolder of
DCIM SHALL count as an album for the album rule (requirement "Media that is certainly not an event photo is
excluded"), so a subfolder named for a messaging or social app, or for the phone's screenshots or screen recordings,
is excluded. Inside DCIM the capture range and the resolution floors SHALL apply too, to edited photos as well. A photo or video
outside DCIM SHALL NOT be shared,
however it got there and whatever its resolution — media saved from a messaging app or the web, downloads, and photos
from in-app cameras that save elsewhere — and the member SHALL have no way to add another folder. The folder inside
DCIM where SnapSync keeps the event albums (capability `event-album`) is a subfolder like any other: the photos the
member received there are kept out as every received photo is (capability `receiving-photos`), and a photo the member
put there themselves is shared under the same rules as any photo in DCIM. Under limited access (capability
`photo-access`) this requirement SHALL NOT apply: the member's selection decides, wherever on the phone a selected
photo is stored (requirement "Under limited access the member's selection decides what a photo is"). On iPhone this
requirement does not apply.

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

#### Scenario: Under limited access a selected photo outside DCIM is shared
- **WHEN** a limited-access Android member selects an in-range photo saved from the web, stored outside DCIM, and an
  in-range camera photo
- **THEN** both are shared

#### Scenario: A small image in DCIM is excluded
- **WHEN** a 1600 × 1200 image lands in the camera's folder during the event
- **THEN** it is not shared

#### Scenario: The event album's photos are not shared
- **WHEN** an Android member has received photos into an event's album, and they lie inside the capture range
  of the event they are sharing to
- **THEN** none of them is shared, as for any received photo (capability `receiving-photos`)

#### Scenario: An own photo moved into the event album is shared
- **WHEN** an Android member moves an in-range photo they took themselves into an event's album folder
- **THEN** it is shared like any of their photos in DCIM

### Requirement: What the member is told is shared is exactly what is shared

Once the event has started, four things SHALL always be the same set of photos: the count of shareable
photos the member is shown before joining or saving a change (capabilities `join-event`,
`manage-membership`), the photos their device uploads, the photos other members can receive, and the photos
their own progress counts (capability `sync-status`). Before the event's start, the count the member is
shown SHALL also include the photos in their range that wait for the start (requirement "Nothing is shared
before the event starts"), which the other three include once it has passed. An excluded photo SHALL appear
in none of them.

#### Scenario: The preview matches the upload
- **WHEN** a member confirms a range whose preview said "12 photos will be shared"
- **THEN** exactly those 12 photos are uploaded and offered to the other members

#### Scenario: Before the start the preview counts the photos that wait for it
- **WHEN** a member joins an event that has not started, with a range starting two days before the event's start,
  and the preview says "12 photos will be shared"
- **THEN** none of the 12 is uploaded before the event's start, and all 12 are uploaded and offered to the other
  members once it has passed

#### Scenario: An excluded photo cannot hold progress back
- **WHEN** the member's library holds photos after the range's end
- **THEN** they are neither uploaded nor counted as owed, so the member's own progress can complete

### Requirement: Deleting or no longer sharing a photo withdraws it

Until the event's photos are deleted (capability `event-lifetime`), a photo SHALL stop being offered to other members
when the member deletes it from their library (moving it to the trash counts), removes it from their limited
selection, moves it out of DCIM on Android under full access, or stops sharing it — by narrowing the range, adding it
to a messaging-app album under full access, or turning sharing off (capability `manage-membership`). The withdrawal
SHALL take effect the next time the device looks at the library, also for a photo that was still uploading. Members who already
received it SHALL keep their copy, and a withdrawal SHALL wake no other member. Losing photo access, or the app being
unable to read the library for a moment, SHALL NOT withdraw anything.

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
- **WHEN** an Android member with full photo access moves a shared photo from DCIM into a folder outside it
- **THEN** it stops being offered to other members, and members who already have it keep it

#### Scenario: Turning sharing off withdraws the member's photos
- **WHEN** a member switches from sharing and receiving to receiving only
- **THEN** none of their photos is offered to members who have not received them yet

#### Scenario: Revoked access withdraws nothing
- **WHEN** photo access is revoked while photos are shared
- **THEN** every photo already shared stays available to the other members

#### Scenario: A deletion noticed late is still withdrawn
- **WHEN** a photo is deleted while the device does no work for days, and the event's photos have not been deleted
- **THEN** it is withdrawn the next time the device looks at the library

#### Scenario: A deletion after the event's end is still withdrawn
- **WHEN** a member deletes a shared photo a week after the event's range ended, before the event's photos were deleted
- **THEN** members who have not yet received it never do, and members who already have it keep it

### Requirement: Restoring or re-including a photo shares it again

Until the event's photos are deleted (capability `event-lifetime`), a photo SHALL be offered to the other members again
when it is recovered from Recently Deleted or the trash, re-added to the selection, moved back into
DCIM on Android under full access, or brought back into scope by widening the range, taking it out of a
messaging-app album or turning sharing back on, and bringing it back SHALL wake the other members. A photo that was
already uploaded and was only out of scope — by the range, a messaging-app album or sharing being off — SHALL come
back without being uploaded again. A photo that had left what the device shares — deleted, removed from the selection,
moved out of DCIM, or withdrawn because full access excludes it for what it is (a screenshot, a screen recording, or
an image or video below the resolution floors that limited access had shared, capability `photo-access`) — comes back
as a recovered photo does: uploaded again.

#### Scenario: A recovered photo is shared again
- **WHEN** a member recovers a withdrawn photo from Recently Deleted
- **THEN** it is uploaded again and offered to the other members

#### Scenario: Narrow then widen re-uploads nothing
- **WHEN** a member narrows their range, then widens it back
- **THEN** the photos that fell out come back for the other members with no photo uploaded again

#### Scenario: A photo moved back into DCIM is shared again
- **WHEN** an Android member with full photo access moves a withdrawn photo back into DCIM before the event's photos are deleted
- **THEN** it is uploaded again and offered to the other members

#### Scenario: A photo re-added to the selection is shared again
- **WHEN** a limited-access member removes a shared photo from their selection, and later adds it back
- **THEN** it is uploaded again and offered to the other members

#### Scenario: A screenshot withdrawn by full access returns with limited access
- **WHEN** a member's selected screenshot was withdrawn when they switched to full access, and they switch back to
  limited access with it still selected
- **THEN** it is uploaded again and offered to the other members

