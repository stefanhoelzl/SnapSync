# Spec Delta

## MODIFIED Requirements

### Requirement: The joined screen is whole until the event is deleted

Once an event is joined, the app SHALL show one joined screen carrying the event's name, a statement that
this device has joined the event, the event's dates, a single status line, an explanation of how the event
works for this member, its invite (capability `invite-link`), and the membership actions — settings,
leave and rename (rename as capability `manage-membership` offers it). Every one of those SHALL be present whatever the status line says, including without photo
access, before the event starts, after it ended, and while the device cannot be verified. The invite and the
settings and leave actions SHALL stay in reach at the bottom of the screen however much the screen above them
holds. The joined statement SHALL read the same for the member who created the event and for every other
member. Once the event's photos have been deleted (capability `event-lifetime`), the joined screen SHALL stay
on that event and carry only the event's name, its dates, a statement — in place of the status line — that the
event is over and its photos were removed from SnapSync while everything already received stays in the
library, the photo counts as they last stood, and Leave; it SHALL offer no invite, settings or rename, and no
explanation of how the event works.

#### Scenario: No access still shows everything
- **WHEN** a joined member has no photo access
- **THEN** the event name, the joined statement, the dates, the explanation, the invite, settings, leave and
  rename are all present, with the status line asking for access

#### Scenario: Before the start everything is available
- **WHEN** a joined event has not started yet
- **THEN** the member can already share the invite, show its QR code, change settings, rename and leave

#### Scenario: After the end everything is still available
- **WHEN** a member opens the joined screen days after the event's range ended, its photos not yet deleted
- **THEN** the member can still share the invite, show its QR code, change settings, rename and leave

#### Scenario: Host and guest are told the same
- **WHEN** the member who created an event and a guest who scanned its invite each open the joined screen
- **THEN** both are told they have joined the event, in the same words

#### Scenario: The actions stay in reach on a small phone
- **WHEN** a member opens the joined screen on the smallest supported phone and its content is taller than
  the screen
- **THEN** the invite, settings and leave stay visible at the bottom while the content above them scrolls

#### Scenario: A deleted event is shown as over
- **WHEN** a member opens SnapSync after their event's photos were deleted
- **THEN** the joined screen still shows that event's name and dates, says the event is over, its photos were
  removed from SnapSync and what they received stays in their library, shows the counts as they last stood,
  and offers only Leave

