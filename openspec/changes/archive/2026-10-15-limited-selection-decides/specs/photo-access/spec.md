# Spec Delta

## MODIFIED Requirements

### Requirement: Limited access is a working membership whose scope is the selection

Under limited access the photos the member selected in the system's photo selection — and only those —
SHALL be the photos considered for sharing, and of those the ones inside the member's capture range are shared,
whatever else they are (capability `photo-sharing`, requirement "Under limited access the member's selection decides what a photo is"). Nothing outside the selection SHALL ever be uploaded, by the app or in the background.
"Up to date" SHALL mean every selected, in-range photo is shared and everything received has arrived; limited
access SHALL NOT be shown as a problem. Limited access SHALL be offered wherever the phone's system offers
it — every supported iPhone, and Android 14 and later; below Android 14 access is full or none.

#### Scenario: Selected photos share through the ordinary rules
- **WHEN** a limited-access member has selected photos, some taken inside the event's range and some
  before it
- **THEN** only the selected photos inside the range are shared

#### Scenario: A photo outside the selection never leaves the device
- **WHEN** a limited-access member takes a new photo that is not in their selection
- **THEN** it is not uploaded, in the foreground or the background, until they add it to the selection

#### Scenario: Up to date over the selection
- **WHEN** every selected in-range photo is shared and received photos have arrived
- **THEN** the status line reads "Up to date"

#### Scenario: Android 14 offers a selection
- **WHEN** a member on Android 14 or later chooses to allow access to selected photos only
- **THEN** they are a working member sharing only the selected photos that are in range, wherever on the phone
  they are stored

### Requirement: Switching between full and limited access never re-uploads

Moving from full to limited access SHALL narrow what is shared to the selection from then on: selected
photos already shared SHALL stay shared without being uploaded again, and photos outside the selection
SHALL be withdrawn like any de-selected photo (capability `photo-sharing`). Moving from limited to full
access SHALL share only the in-range photos not yet shared, re-uploading nothing. A switch SHALL also change
which kinds of photo are shared: a selected photo that only limited access shares (capability `photo-sharing`,
requirement "Under limited access the member's selection decides what a photo is") SHALL be withdrawn on moving to full access, and shared again — uploaded again, like a photo recovered after
being deleted — if access becomes limited with it still selected.

#### Scenario: Widening to full access
- **WHEN** a member who shared photos under limited access switches to full access
- **THEN** only newly in-scope photos from the event's range upload, and nothing already shared is sent
  again

#### Scenario: Widening right after changing the selection
- **WHEN** a limited-access member changes their selection and switches to full access before the app has
  finished looking at the changed selection
- **THEN** no shared photo is withdrawn because of the old selection, and nothing is uploaded again

#### Scenario: Narrowing to a selection
- **WHEN** a member who shared photos under full access switches to limited access with a selection
  that excludes some of them
- **THEN** the excluded photos leave the event for the other members, the selected ones stay without
  being uploaded again, and the status reflects the selection

#### Scenario: A selected screenshot leaves on widening to full access
- **WHEN** a limited-access member shared a selected screenshot taken during the event, then switches to full access
- **THEN** the screenshot stops being offered to the other members, members who already have it keep it, and
  nothing is uploaded again
