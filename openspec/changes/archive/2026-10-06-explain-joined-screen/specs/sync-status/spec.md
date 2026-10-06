# sync-status Specification

## RENAMED Requirements

- FROM: `### Requirement: "In sync" is never claimed before the app has looked`
- TO: `### Requirement: "Up to date" is never claimed before the app has looked`

## ADDED Requirements

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
  catch up (capabilities `background-upload`, `receiving-photos`).

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

## MODIFIED Requirements

### Requirement: The joined screen is whole in every state

Once an event is joined, the app SHALL show one joined screen carrying the event's name, a statement that
this device has joined the event, the event's dates, a single status line, an explanation of how the event
works for this member, its invite (capability `manage-membership`), and the membership actions — settings,
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

### Requirement: One status line in a fixed priority

The joined screen SHALL show exactly one status line. When several conditions hold at once it SHALL show the
first that applies, in this order: photo access missing; no usable network (as "The app says when it cannot
reach the network" requires); the event has not started; the device cannot be verified; the app is still
reading its state; then "Up to date" or work in progress. Limited photo access SHALL NOT count as
missing access (capability `photo-access`).

#### Scenario: Missing access outranks a future start
- **WHEN** a joined member without photo access is in an event that starts tomorrow
- **THEN** the status line asks for photo access, so they can fix it before the event begins

#### Scenario: Missing access outranks a missing network
- **WHEN** a joined member without photo access has no network
- **THEN** the status line asks for photo access

#### Scenario: A missing network outranks everything else
- **WHEN** a joined member with access has no network, in an event that has not started or while everything is
  up to date
- **THEN** the status line says the network is missing, with its cause

#### Scenario: A future start outranks progress
- **WHEN** a member with access is joined to an event that has not started
- **THEN** the status line says sharing starts with the event, whatever work is outstanding

#### Scenario: Limited access shows ordinary progress
- **WHEN** a member with limited access is joined to a started event
- **THEN** the status line shows "Up to date" or progress, never the missing-access line

### Requirement: "Up to date" is never claimed before the app has looked

The status line SHALL NOT read "Up to date" until the app has actually read, since it was opened, what the
member has to share, what has been shared, and what there is to receive. Until then it SHALL show a
neutral line saying it is loading, with no check mark, no arrows and no attention styling. A read that finds
nothing to do SHALL settle to "Up to date".

#### Scenario: A cold launch shows the neutral line
- **WHEN** the app launches into a joined event and has not finished reading its state
- **THEN** the status line is neutral and never shows "Up to date"

#### Scenario: A short visit never shows a false settle
- **WHEN** the member opens the app and leaves it before any read completes
- **THEN** "Up to date" was never shown during that visit

#### Scenario: Outstanding work never reads as a regression
- **WHEN** the first read completes and finds work outstanding
- **THEN** the line moves from the neutral line to work in progress, never from "Up to date"

#### Scenario: Nothing to share still settles
- **WHEN** the member shares nothing and everything to receive has arrived
- **THEN** the status line reads "Up to date"

### Requirement: Direction arrows show remaining work and live transfer

While work remains, the status line SHALL show an upload arrow when some of the member's photos are
not yet shared and a download arrow when some of the others' photos have not yet arrived. An arrow
SHALL pulse while a transfer in its direction is actually running and stay still while work waits; the
line SHALL read "Photos arriving…" when any arrow pulses, "Waiting for Wi-Fi…" when no arrow
pulses and the work waits because the member chose not to use mobile data for photos and the phone is on a
network that choice avoids (capability `mobile-data`), and "Photos queued…" otherwise. "Up to date" SHALL be shown exactly when neither arrow is shown. A direction the member switched
off has no work and therefore no arrow — but if the app ever does work in a switched-off direction, that
arrow SHALL be shown rather than hidden.

#### Scenario: A new photo waiting to upload
- **WHEN** the member takes an in-range photo and no upload is running yet
- **THEN** a still upload arrow appears with "Photos queued…"

#### Scenario: Photos arriving
- **WHEN** the member's uploads are done and others' photos are downloading
- **THEN** only the download arrow shows, pulsing, with "Photos arriving…"

#### Scenario: Receive-only ignores the member's own gallery
- **WHEN** a receive-only member has unshared photos in their library and all received photos have
  arrived
- **THEN** the status line reads "Up to date"

#### Scenario: Work in a switched-off direction is not masked
- **WHEN** a receive-only member's device nevertheless has uploads outstanding
- **THEN** the upload arrow is shown and the line does not read "Up to date"

#### Scenario: Photos waiting for Wi-Fi are named
- **WHEN** a member with mobile data off is on mobile data and has a photo waiting to upload
- **THEN** a still upload arrow is shown with "Waiting for Wi-Fi…"

#### Scenario: Reaching Wi-Fi replaces the waiting line
- **WHEN** that member's phone joins an unrestricted Wi-Fi while the app is open
- **THEN** the waiting line is replaced by "Photos arriving…" while the photo uploads

### Requirement: Progress counts only what this membership shares

The upload side of the status SHALL count exactly the member's photos this membership shares
(capability `photo-sharing`) — a photo counts from the moment it is taken, photos the rules exclude
never count, photos received from others never count, and uploads made for other events never stand in
for this event's photos — so the status can always reach "Up to date" and never reaches it early. The
download side SHALL grow as other members add photos.

#### Scenario: An excluded screenshot does not hold the status open
- **WHEN** the member takes a screenshot during the event and all their camera photos are shared
- **THEN** the status line reads "Up to date"

#### Scenario: Earlier uploads do not mask this event's work
- **WHEN** the device uploaded many photos for a previous event and three of this event's photos are
  still unshared
- **THEN** the upload arrow shows until those three are shared

#### Scenario: New contributions reopen the download side
- **WHEN** another member adds photos while this member is "Up to date"
- **THEN** the download arrow appears until the new photos have arrived

### Requirement: The joined screen counts what was shared and received

Beneath the status line, the joined screen SHALL count, for each direction, the member's photos this
membership shares and how many of them are shared (capability `photo-sharing`, counted as the upload side of
"Progress counts only what this membership shares"), and the other members' photos there are to receive and
how many of them arrived (capability `receiving-photos`). A direction with work remaining SHALL show both
numbers; a complete direction SHALL show its total alone. A direction the member switched off SHALL say it is
off instead of numbers — unless the device nevertheless has work in that direction, which SHALL be counted
like any other rather than hidden. The counts SHALL be shown only while the status line reads "Up to date" or
work in progress, and SHALL be hidden in every other status. They SHALL stay as current as the
status line and never contradict it: "Up to date" SHALL NOT be shown beside a direction with work remaining.

#### Scenario: Syncing in both directions
- **WHEN** 12 of the member's 15 photos are shared and 40 of 52 of the others' photos have arrived
- **THEN** the counts read 12 of 15 shared and 40 of 52 received

#### Scenario: In sync shows totals
- **WHEN** all 15 of the member's photos are shared and all 52 of the others' have arrived
- **THEN** the status line reads "Up to date" and the counts read 15 shared and 52 received

#### Scenario: Sharing switched off
- **WHEN** a receive-only member has 40 of 52 photos received
- **THEN** the counts say the member is not sharing, and 40 of 52 received

#### Scenario: Work in a switched-off direction is counted
- **WHEN** a receive-only member's device nevertheless has 3 uploads outstanding
- **THEN** the counts show those uploads as shared-of-total numbers rather than "not sharing"

#### Scenario: No counts while access is missing
- **WHEN** a joined member without photo access opens the joined screen
- **THEN** no counts are shown, only the status line asking for access

#### Scenario: No counts before the start or before the app has looked
- **WHEN** the event has not started, or the app has not yet read its state since it was opened
- **THEN** no counts are shown

#### Scenario: Limited access shows counts
- **WHEN** a member with limited access, 6 selected photos shared, is up to date
- **THEN** the counts read 6 shared, alongside the choices to widen access

### Requirement: The status stays current while the app is open

While the app is in the foreground, any change in what has been shared or received SHALL reach the
status line within about two seconds, including uploads finished by iOS in the background and photos
the app just discovered. Opening the app SHALL show read counts promptly even if background work from
an earlier session has not finished. A failed read SHALL keep the last known status rather than
changing it.

#### Scenario: A background upload finishes while the app is open
- **WHEN** the app is open and iOS finishes uploading one of the member's photos in the background
- **THEN** the status line reflects it within about two seconds

#### Scenario: Photos discovered after opening leave "In sync"
- **WHEN** the app is open showing "Up to date" and it discovers new photos from other members
- **THEN** within about two seconds the line leaves "Up to date"

#### Scenario: A stuck earlier upload does not blank the status
- **WHEN** the member opens the app while an upload pass from an earlier session is still unwinding
- **THEN** the status line shows real progress rather than staying on the neutral line

#### Scenario: A failed read does not flip the status
- **WHEN** the status line reads "Up to date" and one refresh fails to read
- **THEN** it keeps reading "Up to date"

### Requirement: An ended event is marked without stopping sync

After the end of the event's date range, the joined screen's dates SHALL say the event has ended — never
merged into the status line's text — and SHALL say so within a minute of the end passing while the app is
open. While the event has not closed and the status line reads "Up to date", the screen SHALL also say, with the
photo counts, how many of the event's current members it is still waiting for to finish sharing (capability
`event-lifetime`). Ending SHALL change nothing else: arrows, status, counts and syncing continue exactly as
before (the end bounds only which photos may be shared — capability `event-lifetime`).

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
- **THEN** the screen says the event is waiting for 2 of 5 members
