## RENAMED Requirements

- FROM: `### Requirement: iOS's access dialog is raised only by a deliberate tap`
- TO: `### Requirement: The system's access dialog is raised only by a deliberate tap`

- FROM: `### Requirement: Access changes made in iOS Settings are picked up on return`
- TO: `### Requirement: Access changes made in the phone's Settings are picked up on return`

## MODIFIED Requirements

### Requirement: The system's access dialog is raised only by a deliberate tap

The app SHALL raise the system's photo-access dialog — iOS's on iPhone, Android's on Android — only in
response to a deliberate user action — tapping the joined screen's access line (capability `sync-status`) or
confirming a join whose confirm action says it also allows photo access (capability `join-event`) — and
never merely because access has not been decided yet. Once the member has answered the dialog, the same tap
SHALL take the member to the app's page in the phone's Settings instead, on Android as on iPhone.

#### Scenario: Opening the app does not prompt
- **WHEN** a joined member whose access has never been decided opens the app
- **THEN** no access dialog appears until they tap the access line

#### Scenario: A refused member is taken to Settings
- **WHEN** a member who previously refused access taps the access line
- **THEN** the app's page in the phone's Settings opens and no dialog is raised

#### Scenario: A refused Android member is not asked twice
- **WHEN** an Android member who refused access once taps the access line
- **THEN** the app's page in Android's Settings opens, rather than Android's dialog a second time

### Requirement: Limited access is a working membership whose scope is the selection

Under limited access the photos the member selected in the system's photo selection — and only those —
SHALL be the photos considered for sharing, filtered exactly as a full library would be (capability
`photo-sharing`). Nothing outside the selection SHALL ever be uploaded, by the app or in the background.
"In sync" SHALL mean every selected, in-range photo is shared and everything received has arrived; limited
access SHALL NOT be shown as a problem. Limited access SHALL be offered wherever the phone's system offers
it — every supported iPhone, and Android 14 and later; below Android 14 access is full or none.

#### Scenario: Selected photos share through the ordinary rules
- **WHEN** a limited-access member has selected photos, some taken inside the event's range and some
  before it
- **THEN** only the selected photos inside the range are shared

#### Scenario: A photo outside the selection never leaves the device
- **WHEN** a limited-access member takes a new photo that is not in their selection
- **THEN** it is not uploaded, in the foreground or the background, until they add it to the selection

#### Scenario: In sync over the selection
- **WHEN** every selected in-range photo is shared and received photos have arrived
- **THEN** the status line reads "In sync"

#### Scenario: Android 14 offers a selection
- **WHEN** a member on Android 14 or later chooses to allow access to selected photos only
- **THEN** they are a working member sharing only the selected photos that are in DCIM, outside its excluded
  folders, and in range

### Requirement: The app offers its own routes to widen limited access

While access is limited, the joined screen SHALL offer two calm, always-present choices outside the
status line: "Choose more photos", which opens the system's photo selection to add to it, and "Allow full
access", which opens the app's page in the phone's Settings. Neither SHALL be an attention state, and
neither SHALL be offered under full access or no access. Photos added to the selection — through the
system's selection or in the phone's Settings — SHALL be shared like any other selected photo.

#### Scenario: Choosing more photos shares them
- **WHEN** a limited-access member taps "Choose more photos" and adds in-range photos
- **THEN** the system's photo selection appears and the added photos are shared

#### Scenario: Allow full access goes straight to Settings
- **WHEN** a limited-access member taps "Allow full access"
- **THEN** the app's page in the phone's Settings opens, with no in-app dialog in between

#### Scenario: Full access shows neither choice
- **WHEN** the member has full access
- **THEN** neither "Choose more photos" nor "Allow full access" is shown

### Requirement: Access changes made in the phone's Settings are picked up on return

A change to photo access made outside the app SHALL take effect the next time the app comes to the
foreground, without any action inside the app.

#### Scenario: Revoking access in Settings
- **WHEN** a member with access turns it off in the phone's Settings and returns to the app
- **THEN** the status line asks them to turn access on

#### Scenario: Granting access in Settings
- **WHEN** a member without access turns it on in the phone's Settings and returns to the app
- **THEN** the access line disappears and sharing begins
