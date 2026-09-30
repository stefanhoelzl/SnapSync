## MODIFIED Requirements

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
original capture date (so it sorts where it was taken), and the filename it had on the sender's device —
on Android with a number added when the folder it is saved into already holds a file of that name. On iPhone a
received Live Photo SHALL be a working Live Photo; on Android it SHALL arrive as its still photo. A video
SHALL arrive as a video. A received photo SHALL never be saved partially or broken: it is saved only once
all its parts have arrived intact.

#### Scenario: A Live Photo stays live
- **WHEN** another member shares a Live Photo and an iPhone member receives it
- **THEN** it arrives as a Live Photo that plays

#### Scenario: A Live Photo reaches Android as its still
- **WHEN** another member shares a Live Photo and an Android member receives it
- **THEN** it arrives as the Live Photo's still photo, at full resolution

#### Scenario: A received photo sorts by capture time
- **WHEN** a photo taken yesterday is received today
- **THEN** it appears in the library at yesterday's capture time

#### Scenario: The sender's filename is kept
- **WHEN** a photo the sender knew as "IMG_4471.HEIC" is received
- **THEN** it carries that filename in the receiver's library, never an internal name

### Requirement: Received photos are never shared back

A photo this device received SHALL never be uploaded back into the event as the member's own — not after
a crash, a restart, a leave or a switch. Deleting the app forgets which photos this device received: a
member who reinstalls and joins the same event again MAY share photos they had received as their own when
those photos lie inside their capture range, and the other members MAY then receive them a second time —
an accepted gap. On Android the gap is narrower: a received photo in an event album's folder is never shared,
even after a reinstall (capability `photo-sharing`); only received photos in the camera folder are exposed.

#### Scenario: A received photo stays out of the member's contribution
- **WHEN** a received photo lands in the library inside the member's capture range
- **THEN** it is not uploaded, not counted as the member's to share, and no member receives it twice

#### Scenario: A reinstall may share received photos back
- **WHEN** a member deletes and reinstalls the app and joins the same event again with a capture range
  covering photos they had received
- **THEN** those photos may be shared as theirs, and the other members may receive them a second time

#### Scenario: On Android a reinstall never shares the album's photos back
- **WHEN** an Android member whose received photos are in the event's album deletes and reinstalls the app
  and joins the same event again with a capture range covering them
- **THEN** none of the album's photos is shared as theirs
