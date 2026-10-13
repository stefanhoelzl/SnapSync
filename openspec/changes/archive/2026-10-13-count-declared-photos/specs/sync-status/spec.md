# Spec Delta

## MODIFIED Requirements

### Requirement: Progress counts only what this membership shares

The upload side of the status SHALL count exactly the member's photos this membership shares
(capability `photo-sharing`) — a photo counts from the moment it is taken, photos the rules exclude
never count, photos received from others never count, and uploads made for other events never stand in
for this event's photos — so the status can always reach "Up to date" and never reaches it early. The
download side SHALL count every photo the other members have declared they share in the event, from the moment
this device learns of it, not only the photos already complete enough to fetch — so a receiver's total matches
what the sharers are sharing rather than trailing it, and the download side never reads complete while a
declared photo is still on its way. It SHALL grow as other members add photos, and SHALL shrink when a
declared photo is withdrawn before it arrived: deleted, no longer within its sharer's range, or declared by a
member who left before it arrived. A photo that arrived from a member who later left SHALL keep counting.

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

#### Scenario: Photos still uploading count toward what there is to receive
- **WHEN** another member shares 200 photos, 7 of them have finished uploading and this device has received 6
- **THEN** this member's counts read 6 of 200 received, not 6 of 7

#### Scenario: A departing member's undelivered photos stop counting
- **WHEN** a member leaves while 50 of their declared photos have not finished uploading, and 20 of theirs had
  already arrived
- **THEN** the other members' totals drop by those 50 and keep the 20

#### Scenario: A withdrawn photo stops counting
- **WHEN** a member deletes a photo they had declared before it finished uploading
- **THEN** the other members' totals drop by one

### Requirement: Direction arrows show remaining work and live transfer

While work remains, the status line SHALL show an upload arrow when some of the member's photos are
not yet shared and a download arrow when some of the others' photos have not yet arrived. An arrow
SHALL pulse while a transfer in its direction is actually running and stay still while work waits; the
line SHALL read "Photos on their way…" when any arrow pulses — whichever direction it is — "Waiting for Wi-Fi…" when no arrow
pulses and the work waits because the member chose not to use mobile data for photos and the phone is on a
network that choice avoids (capability `mobile-data`), "Waiting for photos…" when no arrow pulses and the only
work left is photos the other members have declared but not yet finished sharing — work this device cannot do
anything about — and "Photos queued…" otherwise. "Up to date" SHALL be shown exactly when neither arrow is shown and the member shares or receives; with both switched off and neither arrow shown the line reads "Not sharing or receiving" ("One status line in a fixed priority"). A direction the member switched
off has no work and therefore no arrow — but if the app ever does work in a switched-off direction, that
arrow SHALL be shown rather than hidden.

#### Scenario: A new photo waiting to upload
- **WHEN** the member takes an in-range photo and no upload is running yet
- **THEN** a still upload arrow appears with "Photos queued…"

#### Scenario: Photos arriving
- **WHEN** the member's uploads are done and others' photos are downloading
- **THEN** only the download arrow shows, pulsing, with "Photos on their way…"

#### Scenario: Photos leaving
- **WHEN** everything to receive has arrived and the member's photos are uploading
- **THEN** only the upload arrow shows, pulsing, with "Photos on their way…" — never a line that says photos
  are arriving

#### Scenario: Waiting for the others' photos
- **WHEN** everything that can be received has arrived, the member's own photos are shared, and another member
  has declared photos that have not finished uploading
- **THEN** a still download arrow shows with "Waiting for photos…", and the line does not read "Up to date"

#### Scenario: The device's own waiting work comes first
- **WHEN** the member has a photo waiting to upload, no transfer is running, and another member has declared
  photos that have not finished uploading
- **THEN** the line reads "Photos queued…"

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
- **THEN** the waiting line is replaced by "Photos on their way…" while the photo uploads

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

#### Scenario: The receiver's total matches the sharer's
- **WHEN** one member has shared 7 of their 200 photos and another member has received 6 of them
- **THEN** the sharer's counts read 7 of 200 shared and the receiver's read 6 of 200 received

#### Scenario: Syncing in both directions
- **WHEN** 12 of the member's 15 photos are shared and 40 of 52 of the others' photos have arrived
- **THEN** the counts read 12 of 15 shared and 40 of 52 received

#### Scenario: Up to date shows totals
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

### Requirement: An ended event is marked without stopping sync

After the end of the event's date range, the joined screen's dates SHALL say the event has ended — never
merged into the status line's text — and SHALL say so within a minute of the end passing while the app is
open. While the status line reads "Up to date" or "Waiting for photos…", the screen SHALL also say, with the
photo counts, how many of the members still in the event it is waiting for to finish sharing, without the event's total (capability
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

#### Scenario: Waiting for the others' photos after the end
- **WHEN** the range has ended, this member has shared everything and holds everything that has arrived, and one
  of the event's members still has declared photos uploading
- **THEN** the status line reads "Waiting for photos…" and the screen says the event is waiting for 1 member

#### Scenario: Everyone has shared
- **WHEN** the range has ended, this member is up to date, and every member still in the event has finished
  sharing and has everything
- **THEN** the screen says everyone has shared their photos

#### Scenario: A late guest turns it back
- **WHEN** the screen says everyone has shared and a guest joins the event and starts sharing
- **THEN** the screen says the event is waiting for 1 member again
