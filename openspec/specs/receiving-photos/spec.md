# receiving-photos Specification

## Purpose

A member who receives expects the other members' event photos to simply appear in their own Photos
library — no gallery to open, nothing to tap. This capability promises that every complete photo another member shares
arrives, at full fidelity, sorted by when it was taken, and that the library is respected — a received photo the member
deletes never comes back, nothing arrives twice, their own photos are never sent back to them, received photos are
never shared back into the event as theirs, and no received photo takes up space twice. How photos travel in the
background is capability `delivery`; what the other members share is capability `photo-sharing`; grouping received
photos into an album is capability `event-album`.
Decision record: changes/archive/2026-06-30-add-photo-download

## Requirements

### Requirement: Other members' photos arrive in the photo library automatically

A receiving member SHALL get every photo the other members of their event share, once it is complete
(capability `photo-sharing`), saved into their phone's photo library without any action: the Photos library
on iPhone and, on Android, the camera folder, where received photos sit in the gallery's timeline beside the
member's own camera photos — or, while the event album is on, the album's own folder, which gallery apps show
as an album named after the event and whose photos sit in the same timeline (capability `event-album`). The
app SHALL have no gallery of its own. A member joining an event already under way SHALL receive its existing
photos straight away, whether or not the event's start date has passed. This device's own photos SHALL never
be downloaded back to it. Receiving SHALL work the same under full and under limited photo access (capability
`photo-access`).

#### Scenario: Receiving under limited access
- **WHEN** a member who granted limited access receives photos from others
- **THEN** they are saved into the library exactly as under full access

#### Scenario: A co-member's photo appears in the library
- **WHEN** another member of the event shares a photo
- **THEN** it appears in this member's photo library without them doing anything

#### Scenario: On Android, a received photo is in the camera roll
- **WHEN** an Android member with the event album off receives a photo another member took
- **THEN** it appears among the photos in their phone's camera folder, in any gallery app

#### Scenario: On Android with the album on, a received photo is in the event's album
- **WHEN** an Android member with the event album on receives a photo another member took
- **THEN** it appears in the event's album and in the gallery's timeline, in any gallery app, and not in the camera folder

#### Scenario: Joining a live event brings its history
- **WHEN** a member joins an event that already holds photos from others
- **THEN** those photos start arriving immediately

#### Scenario: Own photos are not received back
- **WHEN** this device's own shared photos are part of the event
- **THEN** none of them is downloaded or saved again on this device

### Requirement: Received photos keep full fidelity

A received photo SHALL be saved as the sender's original: full resolution and original format, its
original capture date (so it sorts where it was taken), and the filename it had on the sender's device with
a short SnapSync mark added before the extension — on Android with a number added when the folder it is saved
into already holds a file of that name. The mark SHALL identify the photo as received through SnapSync and SHALL
reveal no identifier of the sender, their device or the event. A moving photo SHALL
arrive moving, in the receiving phone's own form: on iPhone a received Live Photo, and a received Android
motion photo, SHALL be a working Live Photo; on Android a received Live Photo SHALL be a motion photo that
plays. Where making a photo move needs a different format on the receiving phone, the saved copy MAY take that
format — at full resolution, keeping its capture date and location and the sender's filename and mark apart
from its extension — while the photo shared in the event SHALL stay the sender's original, unchanged, for every
other member and for the event's download. A moving photo that cannot be made to move on the receiving phone
SHALL arrive as its still photo, at full resolution, exactly once. A video SHALL arrive as a video. A received
photo SHALL never be saved partially or broken: it is saved only once all its parts have arrived intact. Photos
received before a phone could keep them moving SHALL stay as they were saved.

#### Scenario: A Live Photo stays live
- **WHEN** another member shares a Live Photo and an iPhone member receives it
- **THEN** it arrives as a Live Photo that plays

#### Scenario: A Live Photo reaches Android as a motion photo
- **WHEN** an iPhone member shares a Live Photo and an Android member receives it
- **THEN** it arrives as a motion photo that plays in Google Photos, at full resolution, at its capture time and
  location, under the sender's filename with its SnapSync mark

#### Scenario: A HEIC Live Photo is saved on Android as a JPEG
- **WHEN** an Android member receives a Live Photo whose still the sender's iPhone saved as HEIC
- **THEN** their library holds it as a JPEG motion photo named like the sender's photo, with its SnapSync mark
  and a JPEG extension, while every other member and the event's download still get the sender's HEIC

#### Scenario: An Android motion photo reaches iPhone as a Live Photo
- **WHEN** an Android member shares a motion photo and an iPhone member receives it
- **THEN** it arrives as a Live Photo that plays

#### Scenario: A motion photo stays moving between Android phones
- **WHEN** an Android member shares a motion photo and another Android member receives it
- **THEN** it arrives as the same motion photo, unchanged

#### Scenario: A Live Photo reaches Android as its still
- **WHEN** an Android member receives a Live Photo that cannot be made into a motion photo on their phone
- **THEN** it arrives once, as the Live Photo's still photo at full resolution, and no second copy appears later

#### Scenario: A motion photo iPhone does not recognise arrives as its still
- **WHEN** an iPhone member receives a moving photo from Android in a form iPhone cannot make into a Live Photo
- **THEN** it arrives once, as its still photo at full resolution

#### Scenario: A received photo sorts by capture time
- **WHEN** a photo taken yesterday is received today
- **THEN** it appears in the library at yesterday's capture time

#### Scenario: The sender's filename is kept
- **WHEN** a photo the sender knew as "IMG_4471.HEIC" is received
- **THEN** it carries a filename of the form "IMG_4471.snapsync-….HEIC" in the receiver's library, never an
  internal name

#### Scenario: A photo without a sender filename is still marked
- **WHEN** a photo whose sender filename is unknown is received
- **THEN** it carries a SnapSync-marked filename in the receiver's library

### Requirement: A deleted received photo never comes back

Once a received photo has been saved, the app SHALL NOT download or save it again, even after the member
deletes it from their library, leaves and rejoins, switches events, or the app restarts. A photo shared
into two events this device joins SHALL be saved once. Deleting the app SHALL NOT make a received photo
that is still in the library, carrying its SnapSync mark and visible to the app, arrive again: joining an
event recognises such photos as already received. Deleting the app does forget the rest — a member who
reinstalls and joins an event again MAY receive a second time a photo they had deleted before the
reinstall, a photo received by a version of the app that did not mark it, a photo the app cannot see
because photo access is limited to a selection, a photo shared into the event after that join, or any
photo when the event could not be reached at that join — an accepted gap.

#### Scenario: Deleting a received photo
- **WHEN** the member deletes a received photo and new photos later arrive
- **THEN** the deleted photo is not received again

#### Scenario: The same photo in two events
- **WHEN** a photo this device already received in one event is also part of another event it joins
- **THEN** it is not saved a second time

#### Scenario: A reinstall recognises received photos
- **WHEN** a member with full photo access who received an event's photos deletes and reinstalls the app,
  then joins that event again
- **THEN** the received photos still in their library do not arrive a second time

#### Scenario: A reinstall may receive again
- **WHEN** a member deletes a received photo, then deletes and reinstalls the app and joins that event again
- **THEN** that photo may arrive in their library a second time

#### Scenario: A reinstall under limited access may receive again
- **WHEN** a member whose photo access is limited to a selection reinstalls the app and joins the event again
  without selecting the photos they had received
- **THEN** those photos may arrive in their library a second time

### Requirement: Nothing arrives twice

A crash, the app being killed while a photo is being saved, or two background runs at once SHALL NOT
leave two copies of a received photo in the library. A save that was interrupted before it happened SHALL
still happen later.

#### Scenario: Killed while saving
- **WHEN** the app is killed while a received photo is being saved and later runs again
- **THEN** the library holds that photo exactly once

### Requirement: Received photos are never shared back

A photo this device received SHALL never be uploaded back into the event as the member's own — not after
a crash, a restart, a leave or a switch, and not after a reinstall while it is still in the library,
carrying its SnapSync mark and visible to the app, whether the member rejoins sharing and receiving or
sharing only. For the photos a reinstall forgets (listed under "A deleted received photo never comes back"), a
member who reinstalls and joins the same event again MAY share them as their own when they lie inside their
capture range, and the other members MAY then receive them a second time — an accepted gap. On Android the gap
is narrower still: a received photo in an event album's folder is never shared, even after a reinstall
(capability `photo-sharing`).

#### Scenario: A received photo stays out of the member's contribution
- **WHEN** a received photo lands in the library inside the member's capture range
- **THEN** it is not uploaded, not counted as the member's to share, and no member receives it twice

#### Scenario: A reinstall does not share received photos back
- **WHEN** a member with full photo access deletes and reinstalls the app and joins the same event again
  with a capture range covering photos they had received and still hold
- **THEN** those photos are not shared as theirs, and no other member receives them a second time

#### Scenario: On Android a reinstall never shares the album's photos back
- **WHEN** an Android member whose received photos are in the event's album deletes and reinstalls the app
  and joins the same event again with a capture range covering them
- **THEN** none of the album's photos is shared as theirs

#### Scenario: A share-only rejoin does not share received photos back
- **WHEN** a member with full photo access reinstalls the app and rejoins the same event sharing only, with
  a capture range covering photos they had received and still hold
- **THEN** those photos are not shared as theirs

#### Scenario: A reinstall may share received photos back
- **WHEN** a member reinstalls and rejoins with a capture range covering a photo received by a version of
  the app that did not mark it
- **THEN** that photo may be shared as theirs, and the other members may receive it a second time

### Requirement: Received photos do not take up space twice

A received photo SHALL occupy space once: the temporary copy the app downloads SHALL be handed over to or
removed from the device once the photo is saved, and when the member leaves or switches events.

#### Scenario: Space is released after saving
- **WHEN** a received photo has been saved into the library
- **THEN** no second copy of it remains in the app's storage
