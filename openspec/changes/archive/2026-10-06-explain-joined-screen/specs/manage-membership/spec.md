# manage-membership Specification

## MODIFIED Requirements

### Requirement: The joined screen offers the invite until the event closes
The joined screen SHALL offer, while the device is in an event that has not closed (capability
`event-lifetime`), two equal ways to invite: a share action that hands the event's invite link (capability
`join-event`) to the system share sheet, and an action that shows a scannable QR code of the same link. Both
SHALL be offered even when photo access is missing. The QR code SHALL be shown only on request, over the
joined screen, and SHALL close again without changing anything. The QR code SHALL be dark on a light background
in both light and dark appearance. Shown, the QR code SHALL be presented as an invitation to join this event,
and its caption SHALL be addressed to the member showing it, telling them to let family and friends scan it
with their camera. Sharing or showing the QR code SHALL have no effect on the app's state, whether completed
or cancelled. Invite affordances SHALL NOT appear while the device is in no event, nor once the event has
closed.

#### Scenario: A host shares the invite before granting photo access
- **WHEN** a host who has not granted photo access has just joined their new event
- **THEN** the joined screen offers to share the invite link and to show its QR code, and sharing sends the
  invite link through the system share sheet

#### Scenario: The QR code is shown on request
- **WHEN** a member taps the action to show the QR code
- **THEN** the event's QR code appears over the joined screen, and closing it returns to the joined screen
  unchanged

#### Scenario: The QR code is not shown until asked for
- **WHEN** a member opens the joined screen
- **THEN** no QR code is shown until they ask for it

#### Scenario: The QR stays scannable in dark mode
- **WHEN** the phone is in dark appearance and the member shows the QR code
- **THEN** the QR code is still drawn dark on a light background

#### Scenario: The QR code and the shared link are the same invite
- **WHEN** one guest scans the member's QR code and another taps the link the member shared
- **THEN** both reach the join screen of the same event

#### Scenario: The QR code reads as an invitation
- **WHEN** a member shows the QR code
- **THEN** it is labelled as an invitation to join this event, not as sharing their photos

#### Scenario: The caption addresses the member
- **WHEN** a member reads the caption beneath the shown QR code
- **THEN** it tells them to let family and friends scan it with their camera, and does not tell them to scan
  anything

#### Scenario: A closed event offers no invite
- **WHEN** the event closes while a member is looking at the joined screen, with or without the QR code shown
- **THEN** the share action, the QR action and any shown QR code disappear
