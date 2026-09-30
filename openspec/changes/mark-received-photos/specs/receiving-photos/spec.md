## MODIFIED Requirements

### Requirement: Received photos keep full fidelity

A received photo SHALL be saved as the sender's original: full resolution and original format, its
original capture date (so it sorts where it was taken), and the filename it had on the sender's device with
a short SnapSync mark added before the extension — on Android with a number added when the camera folder
already holds a file of that name. The mark SHALL identify the photo as received through SnapSync and SHALL
reveal no identifier of the sender, their device or the event. On iPhone a received Live Photo SHALL be a
working Live Photo; on Android it SHALL arrive as its still photo. A video SHALL arrive as a video. A
received photo SHALL never be saved partially or broken: it is saved only once all its parts have arrived
intact.

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

### Requirement: Received photos are never shared back

A photo this device received SHALL never be uploaded back into the event as the member's own — not after
a crash, a restart, a leave or a switch, and not after a reinstall while it is still in the library,
carrying its SnapSync mark and visible to the app, whether the member rejoins sharing and receiving or
sharing only. For the photos a reinstall forgets (listed under "A deleted received photo never comes back"), a member who reinstalls and joins the same event again MAY share them as their own
when they lie inside their capture range, and the other members MAY then receive them a second time — an
accepted gap.

#### Scenario: A received photo stays out of the member's contribution
- **WHEN** a received photo lands in the library inside the member's capture range
- **THEN** it is not uploaded, not counted as the member's to share, and no member receives it twice

#### Scenario: A reinstall does not share received photos back
- **WHEN** a member with full photo access deletes and reinstalls the app and joins the same event again
  with a capture range covering photos they had received and still hold
- **THEN** those photos are not shared as theirs, and no other member receives them a second time

#### Scenario: A share-only rejoin does not share received photos back
- **WHEN** a member with full photo access reinstalls the app and rejoins the same event sharing only, with
  a capture range covering photos they had received and still hold
- **THEN** those photos are not shared as theirs

#### Scenario: A reinstall may share received photos back
- **WHEN** a member reinstalls and rejoins with a capture range covering a photo received by a version of
  the app that did not mark it
- **THEN** that photo may be shared as theirs, and the other members may receive it a second time
