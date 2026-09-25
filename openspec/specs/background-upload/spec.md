# background-upload Specification

## Purpose

A member who shares expects their event photos to reach everyone else without ever opening SnapSync —
they take photos, put the phone away, and the photos travel. This capability promises that on every
supported iOS version, under full or limited photo access, the member's shareable photos (capability
`photo-sharing`) are uploaded in the background, as the untouched originals, and that a photo is never
lost on the way: interruptions, crashes, a locked phone, lost connectivity and failed attempts only
delay it, and the worst case is that the same photo is uploaded twice. It also promises what uploading
never does — raise a permission prompt in the background, upload for a member who only receives, or
continue after the member leaves.

Decision record: changes/archive/2026-09-22-both-uploaders-active

## Requirements

### Requirement: Photos upload without the app being opened

A shareable photo SHALL be uploaded without the member opening the app, on every supported iOS version.
On iOS 26.1 and later with full photo access, iOS itself SHALL be able to upload new photos even while
the app is not running. Below iOS 26.1, and under limited access, the app SHALL upload in the background
on its own wake-ups, iOS's background-upload completions, the silent wakes other members' photos cause,
and on every opening of the app. The app SHALL NOT hold uploads back for Wi-Fi or external power; iOS
MAY still schedule its own background work at its discretion.

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

### Requirement: A force-quit pauses the app's own background uploading only until the next opening

Background uploading SHALL resume at the latest the next time the member opens the app after a force-quit,
with nothing lost; until then, uploads that rely on the app's own wake-ups MAY stop. On iOS 26.1 and later
with full access, iOS's own uploader SHALL keep working after a force-quit.

#### Scenario: Reopening after a force-quit resumes uploading
- **WHEN** the member force-quits the app on iOS 18 and later opens it
- **THEN** every photo taken meanwhile is uploaded, and background uploading continues after the app is
  left again

### Requirement: Only the original is uploaded, complete

Each shared photo SHALL be uploaded as its original as captured — full resolution, original format,
original filename — and a Live Photo SHALL be uploaded with both its still and its video. Edits made in
Photos (crops, filters, adjustments), their rendered versions and RAW alternates SHALL NOT be uploaded;
other members receive the original. No iCloud account SHALL be required.

#### Scenario: A Live Photo travels whole
- **WHEN** a member shares a Live Photo
- **THEN** both its still and its video are uploaded

#### Scenario: An edit is not shared
- **WHEN** a member applies a filter to a photo before or after it was shared
- **THEN** the other members receive the unedited original

#### Scenario: No iCloud account
- **WHEN** the member's phone is signed out of iCloud
- **THEN** their photos upload normally

### Requirement: A member who only receives never uploads

A membership that does not share (capability `join-event`) SHALL upload nothing, from any trigger, in the
foreground or the background, on every iOS version, and SHALL not keep waking the device to upload.

#### Scenario: Receive-only uploads nothing
- **WHEN** a receive-only member takes photos during the event and opens and leaves the app
- **THEN** none of their photos is uploaded, and no silent wake or background run uploads any

### Requirement: Losing photo access stops new uploads but lets in-flight ones finish

When photo access is revoked (capability `photo-access`), no new upload SHALL start; uploads already in
progress SHALL be allowed to finish and be recorded. When access returns, uploading SHALL resume with
nothing lost and nothing uploaded again that already finished. Background work SHALL NEVER raise a photo
permission prompt.

#### Scenario: Revoking access mid-upload
- **WHEN** the member revokes photo access in Settings while photos are uploading
- **THEN** the uploads in progress complete, and no further photo starts uploading

#### Scenario: Access returns
- **WHEN** access is granted again after a revoke
- **THEN** the remaining photos upload and the finished ones are not uploaded again

#### Scenario: No background permission prompt
- **WHEN** iOS wakes the app or its uploader in the background before the member has ever answered the
  photo access question
- **THEN** no permission prompt appears and nothing is uploaded

### Requirement: Leaving or switching stops every upload

Every upload for an event SHALL stop when the member leaves it, or switches to another event (which
leaves the first — capability `manage-membership`), including uploads in progress, and no further photo
SHALL be uploaded to it from any trigger. Photos already uploaded stay in the event (capability
`manage-membership`). After a switch, uploads SHALL go only to the new event, and photos already stored
from this device SHALL NOT be uploaded again (capability `photo-sharing`).

#### Scenario: Leaving stops uploads in progress
- **WHEN** the member leaves while photos are uploading
- **THEN** those uploads stop and nothing more is uploaded to the event, in the foreground or background

#### Scenario: A switch redirects uploads
- **WHEN** the member switches to a different event while the old one still had photos uploading
- **THEN** no further photo goes to the old event, and the new event's photos start uploading

#### Scenario: Rescanning the joined event changes nothing
- **WHEN** the member scans the invite of the event they are already in while photos are uploading
- **THEN** the uploads continue undisturbed

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

### Requirement: A new event takes effect for background uploads at once

After a join, a switch or a leave made in the app, the next background upload SHALL act on the new
membership, without the member relaunching anything.

#### Scenario: iOS's uploader follows a switch
- **WHEN** iOS's background uploader was running for one event and the member switches to another in the
  app
- **THEN** the next background upload goes to the new event and none goes to the old one
