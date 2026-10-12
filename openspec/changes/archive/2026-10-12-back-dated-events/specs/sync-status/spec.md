# Spec Delta

## MODIFIED Requirements

### Requirement: An ended event is marked without stopping sync

After the end of the event's date range, the joined screen's dates SHALL say the event has ended — never
merged into the status line's text — and SHALL say so within a minute of the end passing while the app is
open. While the status line reads "Up to date", the screen SHALL also say, with the photo counts, how many of
the members still in the event it is waiting for to finish sharing, without the event's total (capability
`event-lifetime`); once every member still in the event is done and this member is up to date, it SHALL
instead say that everyone has shared their photos. A guest joining late, or a member sharing more photos,
SHALL turn that back into the waiting line. Ending SHALL change nothing else: arrows, status, counts and
syncing continue exactly as before (the end bounds only which photos may be shared — capability
`event-lifetime`).

#### Scenario: Ended and still syncing
- **WHEN** the event's range has ended and uploads are still outstanding
- **THEN** the dates say the event has ended, the status line reads "Photos queued…" and the
  uploads continue

#### Scenario: The marker appears while open
- **WHEN** the app is open and the event's end passes
- **THEN** within a minute the dates say the event has ended

#### Scenario: Waiting for the others
- **WHEN** the range has ended, this member is up to date, and two of the event's five members have not yet
  finished sharing
- **THEN** the screen says the event is waiting for 2 members

#### Scenario: Everyone has shared
- **WHEN** the range has ended, this member is up to date, and every member still in the event has finished
  sharing and has everything
- **THEN** the screen says everyone has shared their photos

#### Scenario: A late guest turns it back
- **WHEN** the screen says everyone has shared and a guest joins the event and starts sharing
- **THEN** the screen says the event is waiting for 1 member again

### Requirement: The joined screen explains how the event works for this member

The joined screen SHALL explain, beneath the status line, how the event works for this member: what happens
to their photos, what happens to the group's photos, and what to do when photos are not arriving. The
explanation SHALL always be shown — before the start, while the event runs, after its end, with or without
photo access — until the event's photos are deleted (requirement "The joined screen is whole until the
event is deleted"), and SHALL NOT be dismissible. It SHALL follow the member's current choices and access
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
- **WHEN** a member opens the joined screen before the event starts, and again after it ended
- **THEN** both times the explanation names the same range of their photos that go to the group

#### Scenario: The remedy is always there
- **WHEN** a member opens the joined screen in any status
- **THEN** it tells them that opening the app makes photos that are not arriving catch up

## REMOVED Requirements

### Requirement: The joined screen is whole in every state
**Reason**: Its closed-event variant described an event that no longer closes (capability `event-lifetime`).
**Migration**: Replaced by "The joined screen is whole until the event is deleted", which keeps every other
promise unchanged and shows a deleted event as over instead of a closed one.

## ADDED Requirements

### Requirement: The joined screen is whole until the event is deleted

Once an event is joined, the app SHALL show one joined screen carrying the event's name, a statement that
this device has joined the event, the event's dates, a single status line, an explanation of how the event
works for this member, its invite (capability `invite-link`), and the membership actions — settings,
leave and rename. Every one of those SHALL be present whatever the status line says, including without photo
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

### Requirement: The joined screen shows how many members the event has

The joined screen SHALL always show how many members the event has now — devices that joined and have not
left, this one included — in every status, until the event's photos are deleted. The number SHALL be brought up
to date each time the app comes to the foreground; it MAY lag a join or a leave made while the app stays
open.

#### Scenario: The host alone
- **WHEN** a host has just created and joined an event nobody else has joined yet
- **THEN** the joined screen says the event has 1 member

#### Scenario: Guests joined
- **WHEN** three guests joined the host's event and the host opens SnapSync
- **THEN** the joined screen says the event has 4 members

#### Scenario: A member leaves
- **WHEN** one of four members leaves and another member next opens SnapSync
- **THEN** that member's joined screen says the event has 3 members

#### Scenario: Shown without counts too
- **WHEN** a joined member without photo access opens the joined screen
- **THEN** the number of members is shown although no photo counts are

#### Scenario: Not shown once deleted
- **WHEN** a member opens the joined screen of an event whose photos were deleted
- **THEN** no number of members is shown

### Requirement: A back-dated event says until when guests can join

For an event created after its date range had already ended, the joined screen SHALL say until which day
guests can join it — the day the host chose when creating it (capability `create-event`) — for as long as that
day has not passed, in the device's own timezone. It SHALL NOT be shown for an event created before its end,
nor once that day has passed (joining stays possible until the event's photos are deleted — capability
`event-lifetime`).

#### Scenario: A back-dated event
- **WHEN** a host creates on 12 October an event for 2–4 October and chooses 7 days, then opens the joined
  screen
- **THEN** besides its dates and that it has ended, the screen says guests can join until 19 October

#### Scenario: A regular event
- **WHEN** a member opens the joined screen of an event created before its range ended
- **THEN** the screen says nothing about until when guests can join

#### Scenario: The day has passed
- **WHEN** a member opens the joined screen of a back-dated event after the day the host chose
- **THEN** the screen no longer says until when guests can join
