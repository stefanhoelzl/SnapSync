# Spec Delta

## MODIFIED Requirements

### Requirement: Limited access is a working membership whose scope is the selection

Under limited access the photos the member selected in the system's photo selection — and only those —
SHALL be the photos considered for sharing, filtered exactly as a full library would be (capability
`photo-sharing`). Nothing outside the selection SHALL ever be uploaded, by the app or in the background.
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
- **THEN** they are a working member sharing only the selected photos that are in DCIM, outside its excluded
  folders, and in range

#### Scenario: Narrowing access after joining keeps unselected photos private
- **WHEN** a member on iOS 26.1 or later joins with full access, switches to limited access, and takes a new photo
  that is not in their selection without opening the app
- **THEN** it is not uploaded, until they add it to the selection
