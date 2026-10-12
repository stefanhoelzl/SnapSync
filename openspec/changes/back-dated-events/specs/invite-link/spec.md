## MODIFIED Requirements

### Requirement: Whoever holds the invite can join and see everything
The app SHALL NOT restrict who may use an invite: anyone who scans the QR code or receives the link SHALL
be able to join the event, contribute photos to it, and receive all of its photos, within the event's
device limit and until the event's photos are deleted (capability `event-lifetime`) — also after the event's
range has ended.

#### Scenario: A forwarded invite admits a stranger
- **WHEN** a member's invite link is forwarded to someone outside the group, who opens it before the
  event's photos are deleted
- **THEN** that person can join the event and receives its photos like any other member

#### Scenario: An invite opened after the end still admits
- **WHEN** a guest opens the invite a week after the event's range ended, before its photos were deleted
- **THEN** they can join, share their photos taken within the range and receive the event's photos

### Requirement: Opening another event's invite asks before switching
When a member opens the invite of a different event, the app SHALL load that event and ask whether to
switch, naming both the current and the new event, without promising any participation and without a
count. Confirming SHALL leave the current event exactly as an explicit leave does (capability
`manage-membership`) — at once, offline included, never waiting on a server — and SHALL then show the
regular join screen for the new event, including the photo-access explanation where it applies.
Cancelling that join screen SHALL leave the device in no event. Declining the switch SHALL keep the
current event untouched. An invite to a missing event SHALL be reported without leaving; a failure to
load SHALL offer Retry.

The question SHALL be skipped only when switching cannot lose anything: the current event's range has ended,
every member still in it has shared everything they share (a member who left counts with what reached the
event; a member still in it who has not finished keeps the question), and this phone has received everything
it receives from the event — or the current event's photos were deleted. Then the app SHALL leave the current
event exactly as a confirmed switch does and show the new event's join screen directly. The app SHALL decide
this when the invite is opened, from the service's answer at that moment; while offline, on a server error,
or whenever it cannot tell, it SHALL ask as above.

#### Scenario: The switch question names both events
- **WHEN** a member of "Ana's 30th" opens the invite of "Ski Trip"
- **THEN** a confirmation asks whether to switch, saying they will leave "Ana's 30th" and join "Ski Trip", and shows no count

#### Scenario: Confirming leaves, then configures the new event
- **WHEN** the member confirms the switch
- **THEN** they have left "Ana's 30th" and see the full join screen for "Ski Trip", where they choose sharing, receiving, range, and album as on any first join

#### Scenario: Declining keeps the current event
- **WHEN** the member declines the switch
- **THEN** they remain in their current event with every setting unchanged

#### Scenario: Backing out after the leave leaves no event
- **WHEN** the member confirms the switch and then cancels on the new event's join screen
- **THEN** the device is in no event and the create screen is shown

#### Scenario: A switch to a vanished event does not leave
- **WHEN** a member opens the invite of a different event that no longer exists
- **THEN** they are told the invite is invalid or the event no longer exists, and remain in their current event

#### Scenario: A finished event is switched away from without asking
- **WHEN** "Ana's 30th" ended last week, every member still in it has shared all their photos, this phone has
  received all of them, and the member opens the invite of "Ski Trip"
- **THEN** no switch question is asked: they have left "Ana's 30th", keep every photo they received, and see
  the join screen for "Ski Trip"

#### Scenario: A member still sharing keeps the question
- **WHEN** "Ana's 30th" has ended but one member still in it has photos that have not reached the event, and
  the member opens the invite of "Ski Trip"
- **THEN** the switch question is asked as usual

#### Scenario: Offline keeps the question
- **WHEN** "Ana's 30th" has ended and everyone has shared everything, but the phone cannot reach the service
  when the member opens the invite of "Ski Trip"
- **THEN** the switch question is asked as usual

#### Scenario: A deleted event is switched away from without asking
- **WHEN** the photos of "Ana's 30th" were deleted and the member opens the invite of "Ski Trip"
- **THEN** no switch question is asked and the join screen for "Ski Trip" is shown

#### Scenario: Backing out after a switch without asking leaves no event
- **WHEN** the switch was made without asking and the member then cancels on the new event's join screen
- **THEN** the device is in no event and the create screen is shown

## REMOVED Requirements

### Requirement: The joined screen offers the invite until the event closes
**Reason**: Events no longer close; the invite is offered until the event's photos are deleted.
**Migration**: Replaced by "The joined screen offers the invite until the event's photos are deleted", which
keeps every other rule unchanged.

## ADDED Requirements

### Requirement: The joined screen offers the invite until the event's photos are deleted
The joined screen SHALL offer, while the device is in an event whose photos have not been deleted (capability
`event-lifetime`), two equal ways to invite: a share action that hands the event's invite link to the system share sheet, and an action that shows a scannable QR code of the same link. The
share sheet SHALL name the event as what is being shared. Both
SHALL be offered even when photo access is missing, and also after the event's range has ended. The QR code
SHALL be shown only on request, over the
joined screen, and SHALL close again without changing anything. The QR code SHALL be dark on a light background
in both light and dark appearance. Shown, the QR code SHALL be presented as an invitation to join this event,
and its caption SHALL be addressed to the member showing it, telling them to let family and friends scan it
with their camera. Sharing or showing the QR code SHALL have no effect on the app's state, whether completed
or cancelled. Invite affordances SHALL NOT appear while the device is in no event, nor once the event's photos
have been deleted, nor while the device cannot read an encrypted event's key — after it lost the key (capability `sync-status`)
or while the phone is locked since it was started — so an invite is only ever offered whole, never without its key
(see "An incomplete invite never joins an encrypted event"); a QR code already shown then closes. They SHALL return as soon as the key can be read
again.

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

#### Scenario: The share sheet names the event
- **WHEN** a member of the event "Anna's 40th" taps the share action, on iPhone or on Android
- **THEN** the system share sheet shows "Anna's 40th" as the title of what is being shared

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

#### Scenario: A back-dated event offers its invite
- **WHEN** a host has just created and joined an event whose range is already over
- **THEN** the joined screen offers to share the invite and to show its QR code

#### Scenario: A deleted event offers no invite
- **WHEN** the event's photos are deleted while a member is looking at the joined screen, with or without the
  QR code shown
- **THEN** the share action, the QR action and any shown QR code disappear

#### Scenario: A phone that lost the event's key offers no invite
- **WHEN** a member of an encrypted event whose phone lost the event's key opens the joined screen
- **THEN** it offers neither to share the invite nor to show its QR code
