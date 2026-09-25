# receiving-photos Specification

## Purpose

A member who receives expects the other members' event photos to simply appear in their own Photos
library — no gallery to open, nothing to tap. This capability promises that every complete photo another
member shares arrives automatically, at full fidelity, sorted by when it was taken, including while the
app is not open; that a silent wake speeds this up without being relied on; and that the library is
respected — a received photo the member deletes never comes back, nothing arrives twice, their own photos
are never sent back to them, and received photos are never shared back into the event as theirs. What the
other members share is capability `photo-sharing`; grouping received photos into an album is capability
`event-album`.

Decision record: changes/archive/2026-06-30-add-photo-download

## Requirements

### Requirement: Other members' photos arrive in the Photos library automatically

A receiving member SHALL get every photo the other members of their event share, once it is complete
(capability `photo-sharing`), saved into their Photos library without any action. The app SHALL have no
gallery of its own. A member joining an event already under way SHALL receive its existing photos
straight away, whether or not the event's start date has passed. This device's own photos SHALL never be
downloaded back to it. Receiving SHALL work the same under full and under limited photo access
(capability `photo-access`).

#### Scenario: Receiving under limited access
- **WHEN** a member who granted limited access receives photos from others
- **THEN** they are saved into the library exactly as under full access

#### Scenario: A co-member's photo appears in the library
- **WHEN** another member of the event shares a photo
- **THEN** it appears in this member's Photos library without them doing anything

#### Scenario: Joining a live event brings its history
- **WHEN** a member joins an event that already holds photos from others
- **THEN** those photos start arriving immediately

#### Scenario: Own photos are not received back
- **WHEN** this device's own shared photos are part of the event
- **THEN** none of them is downloaded or saved again on this device

### Requirement: Received photos keep full fidelity

A received photo SHALL be saved as the sender's original: full resolution and original format, its
original capture date (so it sorts where it was taken), and the filename it had on the sender's device. A
received Live Photo SHALL be a working Live Photo, and a video SHALL arrive as a video. A received photo
SHALL never be saved partially or broken: it is saved only once all its parts have arrived intact.

#### Scenario: A Live Photo stays live
- **WHEN** another member shares a Live Photo
- **THEN** it arrives as a Live Photo that plays

#### Scenario: A received photo sorts by capture time
- **WHEN** a photo taken yesterday is received today
- **THEN** it appears in the library at yesterday's capture time

#### Scenario: The sender's filename is kept
- **WHEN** a photo the sender knew as "IMG_4471.HEIC" is received
- **THEN** it carries that filename in the receiver's library, never an internal name

### Requirement: Photos arrive without the app being opened

Downloads and saving into the library SHALL continue while the app is in the background or not running,
and SHALL finish without a visit to the app. A photo whose download finished but whose save iOS cut short
SHALL be saved on the device's next background wake of any kind — another member's new photo, a finished
transfer, or the app's own scheduled background work — or, at the latest, the next time the member opens
the app; it SHALL never be lost or saved twice. Downloads SHALL use cellular data as well as Wi-Fi.

#### Scenario: Downloads finish in the background
- **WHEN** the member joins, downloads start, and the member leaves the app
- **THEN** the downloads complete and the photos are saved without the app being reopened

#### Scenario: A save that ran out of time completes later
- **WHEN** photos finished downloading but could not be saved before iOS suspended the app
- **THEN** they are saved on the next background wake, without the member opening the app — or, if no
  wake comes, the next time the member opens it

### Requirement: New photos are announced by a silent wake, and never only by it

When photos become available that this member can receive, the member's device SHALL be woken silently —
no alert, sound or badge — so it can fetch them in the background. A wake SHALL be sent only when a photo
has actually become available, and wakes for one event MAY be combined into one. Wakes are best effort:
without one, new photos SHALL arrive the next time the member opens the app. The app SHALL NOT poll in the
background. A wake for an event this device has left SHALL fetch nothing. Failing to set up wakes SHALL
never prevent joining, sharing or receiving.

#### Scenario: A wake brings new photos in the background
- **WHEN** another member's photo becomes available while this member's phone is in their pocket
- **THEN** the phone is woken silently and the photo is downloaded and saved without the app being opened

#### Scenario: No wake arrives
- **WHEN** iOS drops or delays the wake for a new photo
- **THEN** the photo arrives the next time the member opens the app

#### Scenario: A left event's wakes are ignored
- **WHEN** the device receives a wake for an event it has left
- **THEN** nothing is downloaded

#### Scenario: A declared but unfinished photo wakes nobody
- **WHEN** another member's device starts uploading a photo that is not complete yet
- **THEN** no member is woken for it

### Requirement: A member who only shares receives nothing

A membership that does not receive (capability `join-event`) SHALL download and save nothing, from any
trigger, including wakes for its event.

#### Scenario: Share-only receives nothing
- **WHEN** a share-only member's event gains photos from others and a wake arrives
- **THEN** nothing is downloaded or saved on their device

### Requirement: A deleted received photo never comes back

Once a received photo has been saved, the app SHALL NOT download or save it again, even after the member
deletes it from their library, leaves and rejoins, switches events, or the app restarts. A photo shared
into two events this device joins SHALL be saved once.

#### Scenario: Deleting a received photo
- **WHEN** the member deletes a received photo and new photos later arrive
- **THEN** the deleted photo is not received again

#### Scenario: The same photo in two events
- **WHEN** a photo this device already received in one event is also part of another event it joins
- **THEN** it is not saved a second time

### Requirement: Nothing arrives twice

A crash, the app being killed while a photo is being saved, or two background runs at once SHALL NOT
leave two copies of a received photo in the library. A save that was interrupted before it happened SHALL
still happen later.

#### Scenario: Killed while saving
- **WHEN** the app is killed while a received photo is being saved and later runs again
- **THEN** the library holds that photo exactly once

### Requirement: Received photos are never shared back

A photo this device received SHALL never be uploaded back into the event as the member's own — not after
a crash, a restart, a leave or a switch.

#### Scenario: A received photo stays out of the member's contribution
- **WHEN** a received photo lands in the library inside the member's capture range
- **THEN** it is not uploaded, not counted as the member's to share, and no member receives it twice

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

### Requirement: Received photos do not take up space twice

A received photo SHALL occupy space once: the temporary copy the app downloads SHALL be handed over to or
removed from the device once the photo is saved, and when the member leaves or switches events.

#### Scenario: Space is released after saving
- **WHEN** a received photo has been saved into the library
- **THEN** no second copy of it remains in the app's storage

### Requirement: Leaving or switching stops receiving for the old event

Downloads for an event SHALL stop when the member leaves it or switches to another event (capability
`manage-membership`), and no further photo from it SHALL be saved; photos already received stay in the
library. Receiving SHALL work again normally after a later join.

#### Scenario: Leaving stops downloads
- **WHEN** the member leaves while photos are downloading
- **THEN** no further photo of that event is saved, and those already saved remain

#### Scenario: Rejoining receives again
- **WHEN** the member leaves and later joins an event with photos from others
- **THEN** those photos download and are saved normally
