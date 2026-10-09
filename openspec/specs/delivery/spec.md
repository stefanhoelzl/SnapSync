# delivery Specification

## Purpose
Serves every member who shares or receives: photos travel between the members' phones in the background, and nobody
has to open SnapSync for it. It promises that on every supported iOS and Android version, under full or limited photo
access, the member's shareable photos (capability `photo-sharing`) are uploaded as the untouched originals and the
others' photos are downloaded and saved, each only in the directions the member chose; that a silent wake speeds
receiving up without being relied on; that a photo is never lost on the way — interruptions, crashes, a locked phone,
lost connectivity and failed attempts only delay it, and the worst case is that the same photo is uploaded twice; and
that nothing travels for an event the member has left. Whether photos may use mobile data is capability
`mobile-data`; what arrives in the library is capability `receiving-photos`.
Decision record: changes/archive/2026-09-22-both-uploaders-active

## Requirements

### Requirement: Photos travel without the app being opened

A shareable photo SHALL be uploaded, and a photo this member receives SHALL be downloaded and saved into the library,
without the member opening the app, on every supported iOS and Android version.

Uploading: on iOS 26.1 and later with full photo access, iOS itself SHALL be able to upload new photos even while the
app is not running. Below iOS 26.1, and under limited access, the app SHALL upload in the background on its own
wake-ups, iOS's background-upload completions, the silent wakes other members' photos cause, and on every opening of
the app. On Android the app SHALL upload in the background when a photo is added to the library, on its own wake-ups
while photos remain to upload, and on every opening of the app; Android has no uploader of its own that works while
the app is not running.

Receiving: downloads and saving into the library SHALL continue while the app is in the background or not running, and
SHALL finish without a visit to the app. A photo whose download finished but whose save the system cut short SHALL be
saved on the device's next background wake of any kind — another member's new photo, a finished transfer, or the
app's own scheduled background work — or, at the latest, the next time the member opens the app; it SHALL never be
lost or saved twice.

Neither direction SHALL be held back for Wi-Fi or external power: both SHALL use cellular data as well as Wi-Fi, except
that a member who chose not to use mobile data for photos has their uploads and downloads wait for an unrestricted
Wi-Fi (capability `mobile-data`); the phone's system MAY still schedule the app's background work at its discretion,
for example to save battery.

#### Scenario: A photo taken with the app closed reaches the event
- **WHEN** a sharing member takes a photo during the event and never opens the app
- **THEN** the photo is uploaded in the background and becomes available to the other members

#### Scenario: Uploads continue after the app is suspended
- **WHEN** the member leaves the app while uploads are in progress
- **THEN** the uploads continue in the background and further queued photos follow

#### Scenario: Uploads work below iOS 26.1
- **WHEN** a sharing member's phone runs iOS 18 through iOS 26.0
- **THEN** their photos are still uploaded in the background, by the app

#### Scenario: Uploads work under limited access
- **WHEN** a sharing member has granted limited access and selects an in-range photo
- **THEN** it is uploaded, without any dependency on iOS's own background uploader

#### Scenario: A new photo wakes the app on Android
- **WHEN** an Android member who has not opened the app for days takes an in-range photo with the camera
  app
- **THEN** it is uploaded in the background, once Android lets the app run

#### Scenario: Uploads wait for Wi-Fi when the member chose so
- **WHEN** a sharing member with mobile data off takes an in-range photo while on mobile data and never opens the app
- **THEN** the photo is uploaded in the background once the phone is on an unrestricted Wi-Fi

#### Scenario: Downloads finish in the background
- **WHEN** the member joins, downloads start, and the member leaves the app
- **THEN** the downloads complete and the photos are saved without the app being reopened

#### Scenario: A save that ran out of time completes later
- **WHEN** photos finished downloading but could not be saved before the system suspended the app
- **THEN** they are saved on the next background wake, without the member opening the app — or, if no
  wake comes, the next time the member opens it

#### Scenario: Downloads wait for Wi-Fi when the member chose so
- **WHEN** a receiving member with mobile data off is on mobile data while another member shares a photo
- **THEN** the photo arrives in their library in the background once the phone is on an unrestricted Wi-Fi

### Requirement: New photos are announced by a silent wake, and never only by it

When photos become available that this member can receive, the member's device SHALL be woken silently —
no alert, sound or badge — so it can fetch them in the background. A wake SHALL be sent only when a photo
has actually become available, or once when the event closes (capability `event-lifetime`) so each member
can finish and leave on its own (capability `event-lifetime`); wakes for one event MAY be combined into
one. Wakes are best effort: without one, new photos SHALL still arrive in the background, in a timely manner
as the phone's system allows, and at the latest the next time the member opens the app. A force-quit — on
Android, a force-stop from the phone's Settings — SHALL stop receiving until the member next opens the app;
every photo shared meanwhile SHALL then arrive. A wake for an event this device has left SHALL fetch nothing.
Failing to set up wakes SHALL never prevent joining, sharing or receiving.

#### Scenario: A wake brings new photos in the background
- **WHEN** another member's photo becomes available while this member's phone is in their pocket
- **THEN** the phone is woken silently and the photo is downloaded and saved without the app being opened

#### Scenario: No wake arrives
- **WHEN** the phone's system drops or delays the wake for a new photo, or the phone cannot receive wakes at
  all, and the member does not open the app
- **THEN** the photo still arrives in their library in the background, without the app being opened

#### Scenario: Opening the app catches up
- **WHEN** a new photo has not arrived yet and the member opens the app
- **THEN** the photo arrives

#### Scenario: A force-quit stops receiving until the next opening
- **WHEN** the member force-quits the app (on Android, force-stops it from Settings) and other members share
  photos meanwhile
- **THEN** nothing is received until the member opens the app again, and then every one of those photos
  arrives

#### Scenario: A left event's wakes are ignored
- **WHEN** the device receives a wake for an event it has left
- **THEN** nothing is downloaded

#### Scenario: A declared but unfinished photo wakes nobody
- **WHEN** another member's device starts uploading a photo that is not complete yet
- **THEN** no member is woken for it

#### Scenario: The close wakes every member once
- **WHEN** an event closes after all of its photos have already arrived everywhere
- **THEN** each member still in it is woken silently once and, having everything, leaves it

### Requirement: A force-quit pauses the app's own background uploading only until the next opening

Background uploading SHALL resume at the latest the next time the member opens the app after a force-quit,
with nothing lost; until then, uploads that rely on the app's own wake-ups MAY stop. On iOS 26.1 and later
with full access, iOS's own uploader SHALL keep working after a force-quit. On Android, force-stopping the
app from Android's Settings SHALL pause all of its background uploading until the next opening; swiping the
app away from the recent apps SHALL NOT, unless the phone's maker makes swiping away a force-stop.

#### Scenario: Reopening after a force-quit resumes uploading
- **WHEN** the member force-quits the app on iOS 18 and later opens it
- **THEN** every photo taken meanwhile is uploaded, and background uploading continues after the app is
  left again

#### Scenario: Reopening after a force-stop on Android resumes uploading
- **WHEN** an Android member force-stops the app from Settings, takes photos, and later opens the app
- **THEN** nothing was uploaded in between, every photo taken meanwhile is uploaded, and background
  uploading continues after the app is left again

#### Scenario: Swiping the app away on Android keeps uploading
- **WHEN** an Android member on a phone that keeps Android's standard behaviour swipes the app away from the
  recent apps and then takes a photo
- **THEN** the photo is still uploaded in the background

### Requirement: Each direction runs only if the member chose it

A membership that does not share (capability `join-event`) SHALL upload nothing, from any trigger, in the
foreground or the background, on every iOS and Android version, and SHALL not keep waking the device to
upload. A membership that does not receive SHALL download and save nothing, from any trigger, including wakes
for its event.

#### Scenario: Receive-only uploads nothing
- **WHEN** a receive-only member takes photos during the event and opens and leaves the app
- **THEN** none of their photos is uploaded, and no silent wake or background run uploads any

#### Scenario: Share-only receives nothing
- **WHEN** a share-only member's event gains photos from others and a wake arrives
- **THEN** nothing is downloaded or saved on their device

### Requirement: Only the original is uploaded, complete

Each shared photo SHALL be uploaded as its original as captured — full resolution, original format,
original filename, and its embedded details such as the capture location intact — and a Live Photo SHALL be
uploaded with both its still and its video. Edits made in Photos (crops, filters, adjustments), their
rendered versions and RAW alternates SHALL NOT be uploaded; other members receive the original. On Android
the photo is uploaded as the file DCIM holds: a motion photo SHALL be uploaded as its single
file; an edit a gallery app saves as a new photo in DCIM SHALL be a photo of its own; and an
edit a gallery app saves over the photo before it was uploaded SHALL be what is uploaded. No iCloud or
Google account SHALL be required.

#### Scenario: A Live Photo travels whole
- **WHEN** a member shares a Live Photo
- **THEN** both its still and its video are uploaded

#### Scenario: An edit is not shared
- **WHEN** an iPhone member applies a filter to a photo before or after it was shared
- **THEN** the other members receive the unedited original

#### Scenario: The capture location travels with the photo
- **WHEN** a member shares a photo whose camera recorded where it was taken
- **THEN** the other members receive it with that location

#### Scenario: An Android motion photo travels as one file
- **WHEN** an Android member shares a motion photo
- **THEN** it is uploaded as the one file the camera saved

#### Scenario: No iCloud account
- **WHEN** the member's phone is signed out of iCloud, or an Android phone has no Google account
- **THEN** their photos upload normally

### Requirement: A photo is never lost on the way

Once a photo is shareable it SHALL eventually reach the event unless the member withdraws it (capability
`photo-sharing`): a failed attempt SHALL be retried without limit, and a crash, the app or iOS killing the
process, a reboot, a network loss, an expired credential or an upload finishing while the app is not
running SHALL only delay it. A finished upload SHALL NOT be uploaded again because the app was killed
right after it finished. The worst case SHALL be that the same photo is uploaded twice, which no other
member ever sees as a second copy.

#### Scenario: A failed upload is retried
- **WHEN** an upload fails because the server or network is unavailable
- **THEN** it is retried later until it succeeds

#### Scenario: The app is killed mid-upload
- **WHEN** the app is killed while photos are uploading and later runs again
- **THEN** every photo that had not finished is uploaded, and none that had finished is uploaded again

#### Scenario: Offline photos upload when the network returns
- **WHEN** a member takes photos with no network connection
- **THEN** they are uploaded once a connection is available, without the member doing anything

#### Scenario: A large backlog drains in steps
- **WHEN** a member has more photos queued than iOS accepts at once
- **THEN** the rest are uploaded in later passes, without the member doing anything

### Requirement: Download problems delay photos, never corrupt or lose them

A failed or incomplete download SHALL be retried; a download link that expired before it could be used
SHALL be renewed at the latest the next time the member opens the app; a download that returned an error
page or was cut short SHALL NOT be saved as a photo. When the event cannot be reached, photos already
downloaded SHALL still be saved. A photo the library refuses to accept SHALL stop being retried and SHALL
NOT hold back the other photos.

#### Scenario: A broken download is retried
- **WHEN** a download is cut off or the server answers with an error
- **THEN** nothing broken is saved, and the photo is downloaded again later

#### Scenario: A download delayed past a week
- **WHEN** a phone stays offline long enough for a pending download's link to expire
- **THEN** the next opening of the app renews it and the photo still arrives

#### Scenario: Offline, but already downloaded
- **WHEN** the event cannot be reached while some photos have finished downloading
- **THEN** those photos are still saved into the library

#### Scenario: A photo the library rejects
- **WHEN** the Photos library refuses a received photo's content
- **THEN** it is not retried over and over, and the other photos keep arriving

### Requirement: A finished upload is recognised no later than the next opening

An upload whose photo has reached the event SHALL be recognised as finished no later than the next time
the member opens the app, even when iOS never reports the completion to the app (capability `sync-status`
shows it).

#### Scenario: A completion iOS never reported
- **WHEN** photos reached the event while iOS withheld the completion — for example after access was
  narrowed to limited
- **THEN** opening the app recognises them as uploaded, and they are not uploaded again

### Requirement: A locked phone delays uploads and never ends the membership

Background uploads SHALL work while the phone is locked, once it has been unlocked at least once since it
started. Before that first unlock the app SHALL do nothing rather than guess: it SHALL NOT treat the
member as having left, SHALL NOT change the device's identity, and SHALL resume normally after the unlock.

#### Scenario: Uploads while locked
- **WHEN** the member takes photos, locks the phone and puts it away
- **THEN** the photos upload in the background while the phone stays locked

#### Scenario: A wake before the first unlock after a restart
- **WHEN** iOS wakes the app in the background after a restart, before the member has unlocked the phone
- **THEN** nothing is uploaded, the member is still joined, and uploading resumes after the unlock

### Requirement: Losing photo access stops new uploads but lets in-flight ones finish

When photo access is revoked (capability `photo-access`), no new upload SHALL start; uploads already in
progress SHALL be allowed to finish and be recorded. On Android, where revoking access stops the app, an
upload cut off that way SHALL be completed once access returns. When access returns, uploading SHALL resume
with nothing lost and nothing uploaded again that already finished. Background work SHALL NEVER raise a
photo permission prompt.

#### Scenario: Revoking access mid-upload
- **WHEN** the member revokes photo access in Settings while photos are uploading
- **THEN** the uploads in progress complete, and no further photo starts uploading

#### Scenario: Revoking access mid-upload on Android
- **WHEN** an Android member revokes photo access in Settings while a photo is uploading, and later grants
  it again
- **THEN** no photo starts uploading while access is off, and the interrupted photo is uploaded after
  access returns

#### Scenario: Access returns
- **WHEN** access is granted again after a revoke
- **THEN** the remaining photos upload and the finished ones are not uploaded again

#### Scenario: No background permission prompt
- **WHEN** the phone's system wakes the app or its uploader in the background before the member has ever
  answered the photo access question
- **THEN** no permission prompt appears and nothing is uploaded

### Requirement: A new event takes effect for background uploads at once

After a join, a switch or a leave made in the app, the next background upload SHALL act on the new
membership, without the member relaunching anything.

#### Scenario: iOS's uploader follows a switch
- **WHEN** iOS's background uploader was running for one event and the member switches to another in the
  app
- **THEN** the next background upload goes to the new event and none goes to the old one

### Requirement: Leaving or switching stops every transfer for the old event

Every upload and every download for an event SHALL stop when the member leaves it, or switches to another event
(which leaves the first — capability `manage-membership`), including those in progress, and no further photo SHALL
be uploaded to it or saved from it, from any trigger. Photos already uploaded stay in the event, and photos already
received stay in the library (capability `manage-membership`). After a switch, uploads SHALL go only to the new
event, and photos already stored from this device SHALL NOT be uploaded again (capability `photo-sharing`).
Receiving SHALL work again normally after a later join.

#### Scenario: Leaving stops uploads in progress
- **WHEN** the member leaves while photos are uploading
- **THEN** those uploads stop and nothing more is uploaded to the event, in the foreground or background

#### Scenario: Leaving stops downloads
- **WHEN** the member leaves while photos are downloading
- **THEN** no further photo of that event is saved, and those already saved remain

#### Scenario: A switch redirects uploads
- **WHEN** the member switches to a different event while the old one still had photos uploading
- **THEN** no further photo goes to the old event, and the new event's photos start uploading

#### Scenario: Rescanning the joined event changes nothing
- **WHEN** the member scans the invite of the event they are already in while photos are uploading
- **THEN** the uploads continue undisturbed

#### Scenario: Rejoining receives again
- **WHEN** the member leaves and later joins an event with photos from others
- **THEN** those photos download and are saved normally
