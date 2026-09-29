## MODIFIED Requirements

### Requirement: The joined screen offers the invite until the event closes
The joined screen SHALL show, while the device is in an event that has not closed (capability
`event-lifetime`), a scannable QR code of the event's invite link (capability `join-event`) and a share
action that hands the same link to the iOS share sheet, even when photo access is missing. The QR code
SHALL be dark on a light background in both light and dark appearance. The QR code SHALL be presented as an
invitation for other people to join the event, and its caption SHALL tell the member that others join by
scanning this code with their camera, not instruct the member to scan. Sharing SHALL have no effect on the
app's state, whether completed or cancelled. Invite affordances SHALL NOT appear while the device is in no
event, nor once the event has closed.

#### Scenario: A host shares the invite before granting photo access
- **WHEN** a host who has not granted photo access has just joined their new event
- **THEN** the joined screen shows the event's QR code and a share action, and sharing sends the invite link through the iOS share sheet

#### Scenario: The QR stays scannable in dark mode
- **WHEN** the phone is in dark appearance
- **THEN** the QR code is still drawn dark on a light background

#### Scenario: The QR code and the shared link are the same invite
- **WHEN** one guest scans the member's QR code and another taps the link the member shared
- **THEN** both reach the join screen of the same event

#### Scenario: The QR code reads as an invitation
- **WHEN** a member looks at the QR code on the joined screen
- **THEN** it is labelled as a way to invite others, not as sharing their photos

#### Scenario: The caption addresses the member
- **WHEN** a member reads the caption beneath the QR code
- **THEN** it tells them that others join by scanning this code with their camera, and does not tell them
  to scan anything

#### Scenario: A closed event offers no invite
- **WHEN** the event closes while a member is looking at the joined screen
- **THEN** the QR code and the share action disappear
