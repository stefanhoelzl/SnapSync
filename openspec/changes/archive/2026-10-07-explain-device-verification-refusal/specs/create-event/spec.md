# Spec Delta

## ADDED Requirements

### Requirement: The front screen tells a refused phone before it tries
While the app's latest attempt to verify this phone was refused (capability `privacy-security`, "A refused phone is
told why"), the create screen SHALL show that refusal in the place of the scan hint as soon as the app learns of it —
from the attempt it makes when opened — before the user taps anything. Create SHALL stay available: tapping it SHALL
first try to verify the phone again, so a create on a phone the service no longer refuses proceeds as any create,
and one still refused ends as "A failed create says so and changes nothing" requires. While the app says it cannot
reach the network, the network notice SHALL be shown instead (as "Without a network, Create waits" requires).

#### Scenario: A refused phone learns it on opening the app
- **WHEN** a user with no event opens the app on a phone the service refuses as not genuine
- **THEN** the create screen shows the refusal and its cause in place of the scan hint before they type anything,
  and Create can still be tapped

#### Scenario: A refusal the service stopped making heals on the next tap
- **WHEN** the front screen shows a refusal and the service no longer refuses this phone by the time the host taps
  Create
- **THEN** the event is created as on any genuine phone, without reopening the app

#### Scenario: Offline outranks a refusal
- **WHEN** the front screen shows a refusal and the device goes offline
- **THEN** the line below Create says the device is offline, and shows the refusal again once the network returns

## MODIFIED Requirements

### Requirement: A failed create says so and changes nothing
When an event cannot be created, the create screen SHALL return and SHALL show the failure as a message
directly below the Create action, in place of the hint about scanning a QR code (never by marking the name
field as wrong, and without pushing the date range out of view), keeping that message until the next
attempt. It SHALL tell a rejected name apart from the server being unreachable, and both apart from the service refusing
this phone as not genuine, which it SHALL show as that refusal (capability `privacy-security`, "A refused phone is
told why"). The name and date range the
host entered SHALL still be there, so a retry is one tap. A failed create SHALL NOT join the device to
anything.

#### Scenario: Offline create reports the server as unreachable
- **WHEN** the host taps Create as the device goes offline, before the app says it has no network
- **THEN** a message below Create says the server could not be reached, the name field is not marked as wrong, and the device is still in no event

#### Scenario: An unreachable server is reported as such
- **WHEN** the host taps Create while the device has a network and the server cannot be reached
- **THEN** a message below Create says the server could not be reached, the name field is not marked as wrong, and the device is still in no event

#### Scenario: A rejected name is reported as such
- **WHEN** the server refuses the event's name
- **THEN** a message below Create says the name was not accepted and suggests trying a different one

#### Scenario: A failed create keeps what the host entered
- **WHEN** a create fails
- **THEN** the create screen shows the name and the complete date range the host had entered, and tapping Create again retries with them

#### Scenario: The failure message stays until the next attempt
- **WHEN** a create has failed and the host types a name without tapping Create
- **THEN** the failure message is still shown in place of the scan hint, and it disappears — the scan hint returning — when the host taps Create again

#### Scenario: A refused phone's create says why
- **WHEN** the host taps Create on a phone the service refuses as not genuine
- **THEN** the message below Create says this phone was refused and why, never that the server could not be
  reached, and the device is still in no event
