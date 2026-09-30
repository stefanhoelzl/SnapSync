## RENAMED Requirements

- FROM: `### Requirement: Other members' photos arrive in the Photos library automatically`
- TO: `### Requirement: Other members' photos arrive in the photo library automatically`

## MODIFIED Requirements

### Requirement: Other members' photos arrive in the photo library automatically

A receiving member SHALL get every photo the other members of their event share, once it is complete
(capability `photo-sharing`), saved into their phone's photo library without any action: the Photos library
on iPhone and, on Android, the camera folder, where received photos sit in the gallery's timeline beside the
member's own camera photos. The app SHALL have no gallery of its own. A member joining an event already
under way SHALL receive its existing photos straight away, whether or not the event's start date has
passed. This device's own photos SHALL never be downloaded back to it. Receiving SHALL work the same under
full and under limited photo access (capability `photo-access`).

#### Scenario: Receiving under limited access
- **WHEN** a member who granted limited access receives photos from others
- **THEN** they are saved into the library exactly as under full access

#### Scenario: A co-member's photo appears in the library
- **WHEN** another member of the event shares a photo
- **THEN** it appears in this member's photo library without them doing anything

#### Scenario: On Android, a received photo is in the camera roll
- **WHEN** an Android member receives a photo another member took
- **THEN** it appears among the photos in their phone's camera folder, in any gallery app

#### Scenario: Joining a live event brings its history
- **WHEN** a member joins an event that already holds photos from others
- **THEN** those photos start arriving immediately

#### Scenario: Own photos are not received back
- **WHEN** this device's own shared photos are part of the event
- **THEN** none of them is downloaded or saved again on this device

### Requirement: Received photos keep full fidelity

A received photo SHALL be saved as the sender's original: full resolution and original format, its
original capture date (so it sorts where it was taken), and the filename it had on the sender's device —
on Android with a number added when the camera folder already holds a file of that name. On iPhone a
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

### Requirement: Photos arrive without the app being opened

Downloads and saving into the library SHALL continue while the app is in the background or not running,
and SHALL finish without a visit to the app. A photo whose download finished but whose save the system cut
short SHALL be saved on the device's next background wake of any kind — another member's new photo, a
finished transfer, or the app's own scheduled background work — or, at the latest, the next time the
member opens the app; it SHALL never be lost or saved twice. Downloads SHALL use cellular data as well as
Wi-Fi.

#### Scenario: Downloads finish in the background
- **WHEN** the member joins, downloads start, and the member leaves the app
- **THEN** the downloads complete and the photos are saved without the app being reopened

#### Scenario: A save that ran out of time completes later
- **WHEN** photos finished downloading but could not be saved before the system suspended the app
- **THEN** they are saved on the next background wake, without the member opening the app — or, if no
  wake comes, the next time the member opens it

### Requirement: A deleted received photo never comes back

Once a received photo has been saved, the app SHALL NOT download or save it again, even after the member
deletes it from their library, leaves and rejoins, switches events, or the app restarts. A photo shared
into two events this device joins SHALL be saved once. Deleting the app forgets which photos this device
received: a member who reinstalls and joins an event again MAY receive that event's photos a second
time — an accepted gap.

#### Scenario: Deleting a received photo
- **WHEN** the member deletes a received photo and new photos later arrive
- **THEN** the deleted photo is not received again

#### Scenario: The same photo in two events
- **WHEN** a photo this device already received in one event is also part of another event it joins
- **THEN** it is not saved a second time

#### Scenario: A reinstall may receive again
- **WHEN** a member who received an event's photos deletes and reinstalls the app, then joins that event
  again
- **THEN** the event's photos may arrive in their library a second time

### Requirement: Received photos are never shared back

A photo this device received SHALL never be uploaded back into the event as the member's own — not after
a crash, a restart, a leave or a switch. Deleting the app forgets which photos this device received: a
member who reinstalls and joins the same event again MAY share photos they had received as their own when
those photos lie inside their capture range, and the other members MAY then receive them a second time —
an accepted gap.

#### Scenario: A received photo stays out of the member's contribution
- **WHEN** a received photo lands in the library inside the member's capture range
- **THEN** it is not uploaded, not counted as the member's to share, and no member receives it twice

#### Scenario: A reinstall may share received photos back
- **WHEN** a member deletes and reinstalls the app and joins the same event again with a capture range
  covering photos they had received
- **THEN** those photos may be shared as theirs, and the other members may receive them a second time
