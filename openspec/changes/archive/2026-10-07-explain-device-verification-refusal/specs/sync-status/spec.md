# Spec Delta

## MODIFIED Requirements

### Requirement: A device that cannot be verified is shown, and never blamed on the member

The status line SHALL say, when the device holds no usable verification and the latest attempt to
obtain one failed, that this device cannot be verified and that sharing is paused, and SHALL add only what
is true in every case: the app keeps retrying and no photo is lost (the promise itself: capability
`privacy-security`). It SHALL NOT be tappable, name a cause, or ask the member to do anything — except that when the service has
refused this phone as not genuine, the line SHALL say this phone was refused and name the cause (capability
`privacy-security`, "A refused phone is told why"), still adding that no photo is lost and still not tappable; a
member who wants to report it does so from the app's menu. It SHALL
NOT appear while the current verification still works, however close to renewal, and SHALL NOT be shown
on opening the app on the strength of a failure from before that opening — only once the attempt that
opening triggers has also failed. It SHALL clear as soon as verification succeeds. While the device has no
usable network, the network line SHALL be shown instead (as "One status line in a fixed priority" requires).

#### Scenario: Unreachable server with an expired verification
- **WHEN** the device has a network, its verification has expired, and renewing it fails because the server
  cannot be reached
- **THEN** the status line says the device cannot be verified, sharing is paused, the app keeps
  retrying and no photo is lost — with no button and no suggested remedy

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
- **WHEN** a joined member's verification has expired and renewing it fails because the server cannot be reached
- **THEN** the status line says the device cannot be verified and sharing is paused, naming no cause
