# photo-access Specification

## MODIFIED Requirements

### Requirement: Missing access never hides or blocks the event

A member without photo access SHALL still see the full joined screen — the event, its invite, and
every membership action — with a single status line inviting them to turn access on; the screen's explanation
of how the event works (capability `sync-status`) SHALL say that nothing is shared or received and offer the
same action, and no other part of the screen SHALL ask for access. Without access nothing
of the member's SHALL be shared, and the event SHALL remain joined and unchanged until access is
granted.

#### Scenario: Denied access keeps the event usable
- **WHEN** a joined member has refused photo access
- **THEN** they can still show the invite's QR code, share the link, open settings, rename and leave, the
  status line asks them to turn access on, and the explanation says nothing is shared or received

#### Scenario: Granting access later starts sharing
- **WHEN** a joined member without access grants it
- **THEN** their photos in the event's range start sharing without any further step

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

#### Scenario: In sync over the selection
- **WHEN** every selected in-range photo is shared and received photos have arrived
- **THEN** the status line reads "Up to date"

#### Scenario: Android 14 offers a selection
- **WHEN** a member on Android 14 or later chooses to allow access to selected photos only
- **THEN** they are a working member sharing only the selected photos that are in DCIM, outside its excluded
  folders, and in range

### Requirement: A selection the app has not yet looked at withdraws nothing

The app SHALL NOT withdraw any photo from the event, and SHALL NOT report the member as up to date, until
it has read the member's limited selection after launch or after access became limited.

#### Scenario: Reopening the app under limited access
- **WHEN** a limited-access member reopens the app and their selection has not been read yet
- **THEN** none of their shared photos disappears from the event, and the status line does not read
  "Up to date" until the selection has been read
