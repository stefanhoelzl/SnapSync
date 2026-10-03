## ADDED Requirements

### Requirement: Without a network, the join screen waits for one

While the app says it cannot reach the network (capability `sync-status`), the join screen SHALL show the notice —
its cause, and when blocked the way to open SnapSync's Settings page — and Join SHALL be unavailable; Cancel SHALL
stay available. When the event's details could not be loaded because there was no network, the screen SHALL say
that instead of a generic load failure, SHALL offer no Retry while there is no network, and SHALL load the
event's details by itself once the network returns. A join already under way when the network drops SHALL NOT be
interrupted; it ends as any join ends (as "Joining happens only on confirmation and needs a connection"
requires).

#### Scenario: An invite opened offline loads once the network returns
- **WHEN** a guest opens an invite while the device is offline, and later the network returns with the join
  screen still open
- **THEN** the join screen says the device is offline and offers only Cancel, and once the network is back it
  loads the event's details without the guest tapping anything

#### Scenario: Offline after the details loaded
- **WHEN** the join screen shows an event's details and the device goes offline
- **THEN** the screen says the device is offline, Join cannot be tapped, and Cancel still can

#### Scenario: Blocked, the guest is offered Settings
- **WHEN** the join screen is open and the network is blocked for SnapSync
- **THEN** the screen says so and offers to open SnapSync's Settings page

## MODIFIED Requirements

### Requirement: The join screen verifies the event before offering to join
The join screen SHALL open at once and load the event's details, offering Join only once they have
loaded. A missing event SHALL be shown as an invalid or expired invite with no way to join. A failure to
load while the device has a network (an unreachable server, a server error, or an incomplete answer such as an
event without a name) SHALL be shown with a Retry; a load that failed for want of a network is shown as "Without
a network, the join screen waits for one" requires. The join screen SHALL never rest in a waiting state with
nothing to tap: whenever loading or joining ends, however it ends, the user SHALL be offered at least a way out.

#### Scenario: An event that does not exist cannot be joined
- **WHEN** a guest opens an invite for an event that no longer exists
- **THEN** the join screen says the invite is invalid or the event no longer exists, and offers only Cancel

#### Scenario: Offline, the event can be retried
- **WHEN** a guest opens an invite while offline
- **THEN** the join screen says the device is offline and offers Cancel, and loads the event by itself once the
  connection is back

#### Scenario: An unreachable server can be retried
- **WHEN** a guest opens an invite while the device has a network but the server cannot be reached
- **THEN** the join screen says the event could not be loaded and offers Retry and Cancel, and Retry loads it once the server answers

#### Scenario: An unexpected failure never strands the user
- **WHEN** loading the event or joining it fails in an unexpected way
- **THEN** the screen moves to one that offers at least Cancel, never an endless spinner
