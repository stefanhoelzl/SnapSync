## ADDED Requirements

### Requirement: Without a network, Create waits

While the app says it cannot reach the network (capability `sync-status`), the create screen SHALL keep its
content and SHALL show the notice — its cause, and when blocked the way to open SnapSync's Settings page — in the
place of the scan hint or the failure message below Create, and Create SHALL be unavailable. When the network
returns, Create SHALL be available again and the line SHALL show what it showed before, a failure message from an
earlier attempt included. A create already under way when the network drops SHALL NOT be interrupted; it ends as
any create ends (as "A failed create says so and changes nothing" requires).

#### Scenario: Offline, Create is unavailable
- **WHEN** a user with no event opens the app while the device is offline
- **THEN** the create screen is shown with its name and date range, the line below Create says the device is
  offline, and Create cannot be tapped

#### Scenario: Blocked, the user is offered Settings
- **WHEN** the create screen is shown and the network is blocked for SnapSync
- **THEN** the line below Create says so and offers to open SnapSync's Settings page

#### Scenario: The network returns
- **WHEN** the network returns while the create screen shows the notice
- **THEN** Create can be tapped again and the line shows the scan hint, or the failure message an earlier
  attempt left

#### Scenario: A create under way is not interrupted
- **WHEN** the host taps Create and the network drops before the event is created
- **THEN** the create either completes or fails as it would have, and the notice shows only once it has ended

## MODIFIED Requirements

### Requirement: A failed create says so and changes nothing
When an event cannot be created, the create screen SHALL return and SHALL show the failure as a message
directly below the Create action, in place of the hint about scanning a QR code (never by marking the name
field as wrong, and without pushing the date range out of view), keeping that message until the next
attempt. It SHALL tell a rejected name apart from the server being unreachable. The name and date range the
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
