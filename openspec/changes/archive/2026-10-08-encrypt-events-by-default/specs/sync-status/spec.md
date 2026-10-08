# Spec Delta

## MODIFIED Requirements

### Requirement: One status line in a fixed priority

The joined screen SHALL show exactly one status line. When several conditions hold at once it SHALL show the
first that applies, in this order: the app is still reading its state; the member neither shares nor receives
(both switched off in the event's settings, capability `manage-membership`) and no work remains in either
direction — the line then reads "Not sharing or receiving"; the device has lost the event's key (as "A device that
lost the event's key asks for its invite" requires); photo access missing; no usable network (as "The
app says when it cannot reach the network" requires); the event has not started; the device cannot be
verified; then "Up to date" or work in progress. Limited photo access SHALL NOT count as
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

#### Scenario: Limited access shows ordinary progress
- **WHEN** a member with limited access is joined to a started event
- **THEN** the status line shows "Up to date" or progress, never the missing-access line

#### Scenario: Neither sharing nor receiving is said plainly
- **WHEN** a joined member has switched both sharing and receiving off and nothing is left to transfer
- **THEN** the status line reads "Not sharing or receiving", without counts, even if photo access is missing, the network is gone or the event has not started

#### Scenario: Work left in switched-off directions is still shown
- **WHEN** a member switches both directions off while a photo is still uploading
- **THEN** the status line shows that upload's progress until it finishes, then reads "Not sharing or receiving"

## ADDED Requirements

### Requirement: A device that lost the event's key asks for its invite
When the device is in an encrypted event but no longer holds the event's key — after the phone was restored onto a
new device, or the phone's own protection of the key was reset — the joined screen SHALL say so in its status line
and ask the member to open the event's invite again, for example from another member. The line SHALL NOT be
tappable. While the key is missing, nothing SHALL be uploaded or downloaded and the progress SHALL NOT advance, and
the member SHALL stay in the event with every setting unchanged. Opening the event's whole invite SHALL end it
(capability `join-event`). A device that merely cannot read the key for a moment — a phone locked since it was
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
