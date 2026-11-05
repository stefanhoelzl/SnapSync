## MODIFIED Requirements

### Requirement: The joined screen shows one status line in a fixed priority

The joined screen SHALL show exactly one status line. When several conditions hold at once it SHALL show the
first that applies, in this order: the member neither shares nor receives (both switched off in the event's
settings, capability `manage-membership`) — the line then reads "Not sharing or receiving" once no work remains in
either direction, and the neutral line while the app is still reading its state; the device has lost the event's
key (as "A device that lost the event's key asks for its invite" requires); photo access missing; no usable network
(as "The app says when it cannot reach the network" requires); the event has not started; the device cannot be
verified; the service is failing (as "A failing service is shown while photos wait" requires); the app is still
reading its state; then "Up to date" or work in progress. Limited photo access SHALL NOT count as
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

#### Scenario: A known problem shows before the app has looked
- **WHEN** a joined member without photo access opens the app and it has not finished reading its state
- **THEN** the status line asks for photo access at once, without showing the neutral line first

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

### Requirement: "Up to date" is never claimed before the app has looked

The status line SHALL NOT read "Up to date" until the app has actually read, since it was opened, what the
member has to share, what has been shared, and what there is to receive. Until then it SHALL show a
neutral line saying it is loading, with no check mark, no arrows and no attention styling — unless a condition that
outranks it is already known (requirement "The joined screen shows one status line in a fixed priority"), which it
shows instead. A read that finds nothing to do SHALL settle to "Up to date".

#### Scenario: A cold launch shows the neutral line
- **WHEN** the app launches into a joined event, no condition that outranks reading is known, and it has not
  finished reading its state
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

### Requirement: The joined screen counts the photos shared and received

Beneath the status line, the joined screen SHALL count, for each direction, the member's photos this
membership shares and how many of them are shared (capability `photo-sharing`, counted as the upload side of
"Progress counts only what this membership shares"), and the other members' photos there are to receive and
how many of them arrived (capability `receiving-photos`). A direction with work remaining SHALL show both
numbers; a complete direction SHALL show its total alone. A direction the member switched off SHALL say it is
off instead of its progress. Receiving switched off SHALL also say how many of the others' photos arrived, once
none is still arriving and when any did, since they stay in the member's library; sharing switched off withdraws
what it shared, and shows no number. The counts SHALL be shown only while the status line reads "Up to date" or
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

#### Scenario: Receiving switched off keeps what arrived
- **WHEN** a member who received 40 of the others' photos switches receiving off, and none is still arriving
- **THEN** the counts say the member is not receiving, and that 40 photos were received

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
