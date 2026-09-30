## MODIFIED Requirements

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
