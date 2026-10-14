## MODIFIED Requirements

### Requirement: A failed create says so and changes nothing
When an event cannot be created, the create screen SHALL return and SHALL show the failure as a message
directly below the Create action, in place of the hint about scanning a QR code (never by marking the name
field as wrong, and without pushing the date range out of view), keeping that message until the next
attempt. It SHALL tell a rejected name apart from a failing service, which it SHALL tell as the condition that
applies (capability `app-experience`, "A failing service is told as what it is"), and both apart from the service
refusing this phone as not genuine, which it SHALL show as that refusal (capability `privacy-security`, "A refused
phone is told why"). The name and date range the
host entered SHALL still be there, so a retry is one tap. Create SHALL stay available, and the app SHALL NOT try
again until the host taps it. A failed create SHALL NOT join the device to
anything.

#### Scenario: Offline create reports the server as unreachable
- **WHEN** the host taps Create as the device goes offline, before the app says it has no network
- **THEN** a message below Create says the server could not be reached, the name field is not marked as wrong, and the device is still in no event

#### Scenario: An unreachable server is reported as such
- **WHEN** the host taps Create while the device has a network and the server cannot be reached
- **THEN** a message below Create says the server could not be reached, the name field is not marked as wrong, and the device is still in no event

#### Scenario: A service in trouble is reported as such
- **WHEN** the host taps Create and the service answers that it failed
- **THEN** a message below Create says SnapSync is having trouble, without asking the host to check their
  connection, and the device is still in no event

#### Scenario: An update in progress is reported as brief
- **WHEN** the host taps Create while the service is being updated
- **THEN** a message below Create says SnapSync is being updated and is back shortly, Create can still be tapped,
  nothing is created until the host taps it again, and the device is still in no event

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
