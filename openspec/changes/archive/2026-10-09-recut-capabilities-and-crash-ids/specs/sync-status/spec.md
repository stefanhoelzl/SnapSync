## MODIFIED Requirements

### Requirement: The joined screen is whole in every state

Once an event is joined, the app SHALL show one joined screen carrying the event's name, a statement that
this device has joined the event, the event's dates, a single status line, an explanation of how the event
works for this member, its invite (capability `invite-link`), and the membership actions — settings,
leave and rename. Every one of those SHALL be present whatever the status line says, including without photo
access, before the event starts, and while the device cannot be verified. The invite and the settings and
leave actions SHALL stay in reach at the bottom of the screen however much the screen above them holds. The
joined statement SHALL read the same for the member who created the event and for every other member. Once
the event has closed (capability `event-lifetime`), the joined screen SHALL carry only the event's name, the
joined statement, the event's dates, the status line, the part of the explanation about receiving photos and
about photos not arriving, and Leave.

#### Scenario: No access still shows everything
- **WHEN** a joined member has no photo access
- **THEN** the event name, the joined statement, the dates, the explanation, the invite, settings, leave and
  rename are all present, with the status line asking for access

#### Scenario: Before the start everything is available
- **WHEN** a joined event has not started yet
- **THEN** the member can already share the invite, show its QR code, change settings, rename and leave

#### Scenario: Host and guest are told the same
- **WHEN** the member who created an event and a guest who scanned its invite each open the joined screen
- **THEN** both are told they have joined the event, in the same words

#### Scenario: The actions stay in reach on a small phone
- **WHEN** a member opens the joined screen on the smallest supported phone and its content is taller than
  the screen
- **THEN** the invite, settings and leave stay visible at the bottom while the content above them scrolls

#### Scenario: A closed event keeps only Leave
- **WHEN** a member opens the joined screen of an event that has closed and is still receiving its last
  photos
- **THEN** the event name, the joined statement, the dates, the status line, the explanation of receiving
  and of photos not arriving, and Leave are shown, and no invite, settings, rename or explanation of sharing

### Requirement: The joined screen explains how the event works for this member

The joined screen SHALL explain, beneath the status line, how the event works for this member: what happens
to their photos, what happens to the group's photos, and what to do when photos are not arriving. The
explanation SHALL always be shown — before the start, while the event runs, after its end, with or without
photo access — and SHALL NOT be dismissible. It SHALL follow the member's current choices and access
(capability `manage-membership`), the same on iPhone and Android:

- **Their photos.** With sharing on and full access, it SHALL say that the photos they take within the range
  they share go to the group, naming that range in the device's own calendar days — the same statement
  before the start, while the event runs and after its end. Under limited access it SHALL say that only
  their selected photos within that range are shared, with the routes to widen access beside it (capability
  `photo-access`).
- **The group's photos.** With receiving on, it SHALL say that the group's photos land in the member's
  gallery on their own — in the event's album, named, when the member chose one (capability `event-album`),
  and beside their own photos otherwise.
- **Photos not arriving.** It SHALL always say that when photos are not arriving, opening the app makes them
  catch up (capability `delivery`).

Where something is not happening, the explanation SHALL say so plainly and say how to change it, with a
direct route there: a direction the member switched off SHALL say they are not sharing, or not receiving, and
lead to the event's settings; missing photo access SHALL say that nothing is shared and nothing is received
and lead to the same access action the status line offers (capability `photo-access`). A direction switched
off by choice SHALL look different from one held back by missing access, which is a problem to fix.

#### Scenario: A sharing and receiving member
- **WHEN** a member with full access, sharing the whole event and receiving into an album, opens the joined
  screen
- **THEN** it says their photos taken within the event's dates go to the group, that the group's photos land
  in their gallery in the event's album, and that opening the app catches up photos that are not arriving

#### Scenario: The range the member chose is the one named
- **WHEN** a member shares only part of the event's range
- **THEN** the explanation names that part, not the event's whole range, and updates at once when they
  change it

#### Scenario: No album
- **WHEN** a receiving member chose not to have an album
- **THEN** the explanation says the group's photos land beside their own photos

#### Scenario: Sharing switched off
- **WHEN** a member has switched sharing off
- **THEN** the explanation says they are not sharing, and tapping its route opens the event's settings

#### Scenario: Receiving switched off
- **WHEN** a member has switched receiving off
- **THEN** the explanation says they are not receiving, and tapping its route opens the event's settings

#### Scenario: No photo access
- **WHEN** a joined member has no photo access
- **THEN** the explanation says nothing is shared and nothing is received, and tapping its route does what
  the status line's access action does

#### Scenario: Limited access
- **WHEN** a member with limited access opens the joined screen
- **THEN** the explanation says only their selected photos are shared, with the choices to choose more photos
  and to allow full access beside it

#### Scenario: Before the start and after the end
- **WHEN** a member opens the joined screen before the event starts, and again after it ended but before it
  closed
- **THEN** both times the explanation names the same range of their photos that go to the group

#### Scenario: The remedy is always there
- **WHEN** a member opens the joined screen in any status
- **THEN** it tells them that opening the app makes photos that are not arriving catch up

### Requirement: A device that lost the event's key asks for its invite
When the device is in an encrypted event but no longer holds the event's key — after the phone was restored onto a
new device, or the phone's own protection of the key was reset — the joined screen SHALL say so in its status line
and ask the member to open the event's invite again, for example from another member. The line SHALL NOT be
tappable. While the key is missing, nothing SHALL be uploaded or downloaded and the progress SHALL NOT advance, and
the member SHALL stay in the event with every setting unchanged. Opening the event's whole invite SHALL end it
(capability `invite-link`). A device that merely cannot read the key for a moment — a phone locked since it was
started — SHALL NOT show this line.

#### Scenario: A restored phone asks for the invite
- **WHEN** a member restores their phone onto a new device and opens SnapSync, and the event's key did not come with it
- **THEN** the joined screen's status line asks them to open the event's invite again, and no photo is uploaded or
  downloaded

#### Scenario: The invite ends it
- **WHEN** that member opens the event's whole invite
- **THEN** the status line returns to the event's ordinary status, and sharing and receiving resume where they stopped

#### Scenario: A locked phone is not mistaken for a lost key
- **WHEN** a member's phone is woken in the background while still locked since it was started
- **THEN** the joined screen does not ask for the invite once the phone is unlocked and the app opened

## REMOVED Requirements

### Requirement: The app is a portrait iPhone app that follows the system appearance

**Reason**: moved to `app-experience`.
**Migration**: unchanged in capability `app-experience`.

### Requirement: The first frame is never a guessed state

**Reason**: moved to `app-experience`.
**Migration**: unchanged in capability `app-experience`.

### Requirement: The app keeps its place across backgrounding

**Reason**: moved to `app-experience`.
**Migration**: unchanged in capability `app-experience`.

### Requirement: Background wakes do their work without showing anything

**Reason**: moved to `app-experience`.
**Migration**: unchanged in capability `app-experience`.

### Requirement: The app menu is one tap away on every screen

**Reason**: moved to `app-experience`.
**Migration**: unchanged in capability `app-experience`.

### Requirement: Taps keep working and never fire twice

**Reason**: moved to `app-experience`.
**Migration**: unchanged in capability `app-experience`.

### Requirement: Failures are told calmly, never as a red field

**Reason**: moved to `app-experience`.
**Migration**: unchanged in capability `app-experience`.

### Requirement: Explanations always match the current choices

**Reason**: moved to `app-experience`.
**Migration**: unchanged in capability `app-experience`.

### Requirement: Picking a date range is guided and cannot go wrong

**Reason**: moved to `app-experience`.
**Migration**: unchanged in capability `app-experience`.

### Requirement: Text entry sheets stay usable while typing

**Reason**: moved to `app-experience`.
**Migration**: unchanged in capability `app-experience`.

### Requirement: Controls are accessible and honour reduced motion

**Reason**: moved to `app-experience`.
**Migration**: unchanged in capability `app-experience`.
