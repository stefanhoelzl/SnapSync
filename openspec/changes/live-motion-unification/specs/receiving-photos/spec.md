## MODIFIED Requirements

### Requirement: Received photos keep full fidelity

A received photo SHALL be saved as the sender's original: full resolution and original format, its
original capture date (so it sorts where it was taken), and the filename it had on the sender's device —
on Android with a number added when the camera folder already holds a file of that name. A moving photo SHALL
arrive moving, in the receiving phone's own form: on iPhone a received Live Photo, and a received Android
motion photo, SHALL be a working Live Photo; on Android a received Live Photo SHALL be a motion photo that
plays. Where making a photo move needs a different format on the receiving phone, the saved copy MAY take that
format — at full resolution, keeping its capture date and location and the sender's filename apart from its
extension — while the photo shared in the event SHALL stay the sender's original, unchanged, for every other
member and for the event's download. A moving photo that cannot be made to move on the receiving phone SHALL
arrive as its still photo, at full resolution, exactly once. A video SHALL arrive as a video. A received photo
SHALL never be saved partially or broken: it is saved only once all its parts have arrived intact. Photos
received before a phone could keep them moving SHALL stay as they were saved.

#### Scenario: A Live Photo stays live
- **WHEN** another member shares a Live Photo and an iPhone member receives it
- **THEN** it arrives as a Live Photo that plays

#### Scenario: A Live Photo reaches Android as a motion photo
- **WHEN** an iPhone member shares a Live Photo and an Android member receives it
- **THEN** it arrives as a motion photo that plays in Google Photos, at full resolution, at its capture time and
  location, under the sender's filename

#### Scenario: A HEIC Live Photo is saved on Android as a JPEG
- **WHEN** an Android member receives a Live Photo whose still the sender's iPhone saved as HEIC
- **THEN** their camera folder holds it as a JPEG motion photo named like the sender's photo with a JPEG
  extension, while every other member and the event's download still get the sender's HEIC

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
- **THEN** it carries that filename in the receiver's library, never an internal name
