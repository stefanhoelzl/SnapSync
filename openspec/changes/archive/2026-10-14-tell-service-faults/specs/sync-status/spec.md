## ADDED Requirements

### Requirement: A failing service is shown while photos wait

The status line SHALL say, while photos wait to be shared or received (or the device's verification has expired)
and the app's latest attempt to reach SnapSync's service failed, which of three conditions applies (capability
`app-experience`, "A failing service is told as what it is"): SnapSync cannot be reached, SnapSync is having
trouble, or SnapSync is being updated and is back shortly. It SHALL add that the member's photos are safe and will
be shared and received once the service works. It SHALL NOT be tappable or ask the member to do anything, SHALL
NOT replace "Up to date" while nothing waits, and SHALL NOT be shown on opening the app on the strength of a
failure from before that opening — only once the attempt that opening triggers has also failed. It SHALL clear as
soon as an attempt succeeds. While the device has no usable network, the network line SHALL be shown instead (as
"One status line in a fixed priority" requires).

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

## MODIFIED Requirements

### Requirement: One status line in a fixed priority

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

#### Scenario: Work left in switched-off directions is still shown
- **WHEN** a member switches both directions off while a photo is still uploading
- **THEN** the status line shows that upload's progress until it finishes, then reads "Not sharing or receiving"

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
usable network, the network line SHALL be shown instead (as "One status line in a fixed priority" requires).

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
