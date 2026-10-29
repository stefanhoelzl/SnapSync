## ADDED Requirements

### Requirement: The joined screen shows one status line in a fixed priority

The joined screen SHALL show exactly one status line. When several conditions hold at once it SHALL show the
first that applies, in this order: the app is still reading its state; the member neither shares nor receives
(both switched off in the event's settings, capability `manage-membership`) and no work remains in either
direction — the line then reads "Not sharing or receiving"; the device has lost the event's key (as "A device that
lost the event's key asks for its invite" requires); photo access missing; no usable network (as "The
app says when it cannot reach the network" requires); the event has not started; the device cannot be
verified; the service is failing (as "A failing service is shown while photos wait" requires); then "Up to date"
or work in progress. Limited photo access SHALL NOT count as
missing access (capability `photo-access`).

#### Scenario: Missing access outranks a future start
- **WHEN** a joined member without photo access is in an event that starts tomorrow
- **THEN** the status line asks for photo access, so they can fix it before the event begins

#### Scenario: Missing access outranks a missing network
- **WHEN** a joined member without photo access has no network
- **THEN** the status line asks for photo access

#### Scenario: A lost key outranks missing access
- **WHEN** a joined member without photo access has lost the event's key
- **THEN** the status line asks for the event's invite

#### Scenario: A missing network outranks everything else
- **WHEN** a joined member with access has no network, in an event that has not started or while everything is
  up to date
- **THEN** the status line says the network is missing, with its cause

#### Scenario: A future start outranks progress
- **WHEN** a member with access is joined to an event that has not started
- **THEN** the status line says sharing starts with the event, whatever work is outstanding

#### Scenario: A failing service outranks progress
- **WHEN** a member with access is joined to a started event, photos wait to be shared, and the service is failing
- **THEN** the status line tells the service's condition rather than the progress

#### Scenario: Limited access shows ordinary progress
- **WHEN** a member with limited access is joined to a started event
- **THEN** the status line shows "Up to date" or progress, never the missing-access line

#### Scenario: Neither sharing nor receiving is said plainly
- **WHEN** a joined member has switched both sharing and receiving off and nothing is left to transfer
- **THEN** the status line reads "Not sharing or receiving", without counts, even if photo access is missing, the network is gone or the event has not started

#### Scenario: Switching both directions off mid-upload is said at once
- **WHEN** a member switches both directions off while a photo is still uploading
- **THEN** the status line reads "Not sharing or receiving" at once, with no progress for that upload, which is
  withdrawn (capability `manage-membership`)

### Requirement: Direction arrows show the work left and the transfer running

While work remains, the status line SHALL show an upload arrow when some of the member's photos are
not yet shared and a download arrow when some of the others' photos have not yet arrived. An arrow
SHALL pulse while a transfer in its direction is actually running and stay still while work waits; the
line SHALL read "Photos on their way…" when any arrow pulses — whichever direction it is — "Waiting for Wi-Fi…" when no arrow
pulses and the work waits because the member chose not to use mobile data for photos and the phone is on a
network that choice avoids (capability `mobile-data`), "Waiting for photos…" when no arrow pulses and the only
work left is photos the other members have declared but not yet finished sharing — work this device cannot do
anything about — and "Photos queued…" otherwise. "Up to date" SHALL be shown exactly when neither arrow is shown and the member shares or receives; with both switched off and neither arrow shown the line reads "Not sharing or receiving" ("The joined screen shows one status line in a fixed priority"). A direction the member switched
off has no work and therefore no arrow: switching it off withdraws the photos not yet shared, an upload under
way included, or stops the downloads under way (capability `manage-membership`).

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

#### Scenario: Switching sharing off mid-upload clears the upload arrow
- **WHEN** a member switches sharing off while a photo is uploading, and everything to receive has arrived
- **THEN** the upload arrow disappears at once and the line reads "Up to date"

#### Scenario: Photos waiting for Wi-Fi are named
- **WHEN** a member with mobile data off is on mobile data and has a photo waiting to upload
- **THEN** a still upload arrow is shown with "Waiting for Wi-Fi…"

#### Scenario: Reaching Wi-Fi replaces the waiting line
- **WHEN** that member's phone joins an unrestricted Wi-Fi while the app is open
- **THEN** the waiting line is replaced by "Photos on their way…" while the photo uploads

### Requirement: The joined screen counts the photos shared and received

Beneath the status line, the joined screen SHALL count, for each direction, the member's photos this
membership shares and how many of them are shared (capability `photo-sharing`, counted as the upload side of
"Progress counts only what this membership shares"), and the other members' photos there are to receive and
how many of them arrived (capability `receiving-photos`). A direction with work remaining SHALL show both
numbers; a complete direction SHALL show its total alone. A direction the member switched off SHALL say it is
off instead of numbers. The counts SHALL be shown only while the status line reads "Up to date" or
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

#### Scenario: Switching sharing off mid-upload counts nothing as shared
- **WHEN** a member with 3 photos still to share switches sharing off while one of them is uploading
- **THEN** the counts say the member is not sharing at once, with no shared-of-total numbers

#### Scenario: No counts while access is missing
- **WHEN** a joined member without photo access opens the joined screen
- **THEN** no counts are shown, only the status line asking for access

#### Scenario: No counts before the start or before the app has looked
- **WHEN** the event has not started, or the app has not yet read its state since it was opened
- **THEN** no counts are shown

#### Scenario: Limited access shows counts
- **WHEN** a member with limited access, 6 selected photos shared, is up to date
- **THEN** the counts read 6 shared, alongside the choices to widen access

## REMOVED Requirements

### Requirement: One status line in a fixed priority
**Reason**: Its scenario "Work left in switched-off directions is still shown" names an outcome the app does
not produce: switching sharing off withdraws the upload under way. OpenSpec cannot rename a single scenario,
so the requirement is replaced whole.
**Migration**: "The joined screen shows one status line in a fixed priority" carries every scenario, that one
rewritten. Claims on the old name are re-pointed to the new one.

### Requirement: Direction arrows show remaining work and live transfer
**Reason**: Its clause and scenario about work in a switched-off direction ("Work in a switched-off direction
is not masked") name a state no path produces. OpenSpec cannot rename a single scenario, so the requirement is
replaced whole.
**Migration**: "Direction arrows show the work left and the transfer running" carries every scenario, that
one rewritten. Claims on the old name are re-pointed to the new one.

### Requirement: The joined screen counts what was shared and received
**Reason**: Its clause and scenario about work in a switched-off direction ("Work in a switched-off direction
is counted") name a state no path produces. OpenSpec cannot rename a single scenario, so the requirement is
replaced whole.
**Migration**: "The joined screen counts the photos shared and received" carries every scenario, that one
rewritten. Claims on the old name are re-pointed to the new one.

## MODIFIED Requirements

### Requirement: A device that cannot be verified is shown, and never blamed on the member

The status line SHALL say, when the device holds no usable verification and the latest attempt to
obtain one failed although the service answered, that this device cannot be verified and that sharing is paused,
and SHALL add only what is true in every case: the app keeps retrying and no photo is lost (the promise itself:
capability `privacy-security`). It SHALL NOT be tappable, name a cause, or ask the member to do anything — except
that when the service has refused this phone as not genuine, the line SHALL say this phone was refused and name the
cause (capability `privacy-security`, "A refused phone is told why"), still adding that no photo is lost and still
not tappable; a member who wants to report it does so from the app's menu. When the attempt failed because the
service could not be reached, was having trouble or was being updated, the line SHALL instead tell that condition
(as "A failing service is shown while photos wait" requires), never that this device cannot be verified. It SHALL
NOT appear while the current verification still works, however close to renewal, and SHALL NOT be shown
on opening the app on the strength of a failure from before that opening — only once the attempt that
opening triggers has also failed. It SHALL clear as soon as verification succeeds. While the device has no
usable network, the network line SHALL be shown instead (as "The joined screen shows one status line in a fixed priority" requires).

#### Scenario: Unreachable server with an expired verification
- **WHEN** the device has a network, its verification has expired, and renewing it fails because the server
  cannot be reached
- **THEN** the status line says SnapSync cannot be reached and that the member's photos are safe, never that this
  device cannot be verified, with no button and no suggested remedy

#### Scenario: An update with an expired verification
- **WHEN** the device's verification has expired and renewing it fails because the service is being updated
- **THEN** the status line says SnapSync is being updated and is back shortly, never that this device cannot be
  verified

#### Scenario: The phone cannot give its proof
- **WHEN** the device's verification has expired, the service answers, and still no new verification can be
  obtained
- **THEN** the status line says the device cannot be verified, sharing is paused, the app keeps retrying and no
  photo is lost — with no button and no suggested remedy

#### Scenario: Offline with an expired verification
- **WHEN** the device's verification has expired and renewing it fails because the device is offline
- **THEN** the status line says the device is offline, not that it cannot be verified

#### Scenario: A verification about to be renewed is not an alarm
- **WHEN** the device's verification still works but is due for renewal and a renewal attempt fails
- **THEN** the status line shows the ordinary sync status

#### Scenario: Opening the app retries before alarming
- **WHEN** an earlier background attempt failed and the member opens the app
- **THEN** the ordinary status shows until the attempt triggered by opening also fails; if it succeeds
  the cannot-verify line never appears

#### Scenario: A refused phone's line names the cause
- **WHEN** a joined member's verification has expired and the service refuses the phone as not genuine when the app
  tries to verify it again
- **THEN** the status line says this phone was refused and why, and that no photo is lost, and it cannot be tapped

#### Scenario: A failure without a verdict names no cause
- **WHEN** a joined member's verification has expired, the service answers, and no new verification is obtained
  and no refusal is given
- **THEN** the status line says the device cannot be verified and sharing is paused, naming no cause

### Requirement: A failing service is shown while photos wait

The status line SHALL say, while photos wait to be shared or received (or the device's verification has expired)
and the app's latest attempt to reach SnapSync's service failed, which of three conditions applies (capability
`app-experience`, "A failing service is told as what it is"): SnapSync cannot be reached, SnapSync is having
trouble, or SnapSync is being updated and is back shortly. It SHALL add that the member's photos are safe and will
be shared and received once the service works. It SHALL NOT be tappable or ask the member to do anything, SHALL
NOT replace "Up to date" while nothing waits, and SHALL NOT be shown on opening the app on the strength of a
failure from before that opening — only once the attempt that opening triggers has also failed. It SHALL clear as
soon as an attempt succeeds. While the device has no usable network, the network line SHALL be shown instead (as
"The joined screen shows one status line in a fixed priority" requires).

#### Scenario: Photos wait while the service is in trouble
- **WHEN** a joined member has photos waiting to be shared and the service keeps answering that it failed
- **THEN** the status line says SnapSync is having trouble and that their photos are safe, and it cannot be tapped

#### Scenario: Photos wait during an update
- **WHEN** a joined member has photos waiting to be received while the service is being updated
- **THEN** the status line says SnapSync is being updated and is back shortly, and that their photos are safe

#### Scenario: Photos wait while the service cannot be reached
- **WHEN** a joined member with a network has photos waiting to be shared and the service does not answer
- **THEN** the status line says SnapSync cannot be reached and that their photos are safe, not that the device is
  offline

#### Scenario: Nothing waiting, nothing said
- **WHEN** a joined member is up to date and the service is failing
- **THEN** the status line reads "Up to date"

#### Scenario: Opening the app retries before telling
- **WHEN** a background attempt failed while photos waited and the member opens the app
- **THEN** the ordinary status shows until the attempt triggered by opening also fails; if it succeeds the
  service's line never appears

#### Scenario: The line clears once the service works
- **WHEN** the status line says SnapSync is having trouble and the next attempt succeeds
- **THEN** the status line shows the ordinary status again
