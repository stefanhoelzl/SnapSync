## MODIFIED Requirements

### Requirement: The join screen verifies the event before offering to join
The join screen SHALL open at once and load the event's details, offering Join only once they have
loaded. A missing event SHALL be shown as an invalid or expired invite with no way to join. A failure to
load while the device has a network (an unreachable server, a failing service, or an incomplete answer such as an
event without a name) SHALL be shown with a Retry, telling a failing service as the condition that applies
(capability `app-experience`, "A failing service is told as what it is"); a load that failed for want of a network
is shown as "Without a network, the join screen waits for one" requires. When the load failed because the service
is being updated, the join screen SHALL also load the event's details again by itself once the service suggests it
is worth asking again, while still offering Retry. The join screen SHALL never rest in a waiting state with
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

#### Scenario: A service in trouble can be retried
- **WHEN** a guest opens an invite and the service answers that it failed
- **THEN** the join screen says SnapSync is having trouble and offers Retry and Cancel, and Retry loads the event
  once the service works

#### Scenario: An update in progress loads by itself
- **WHEN** a guest opens an invite while the service is being updated, and the update ends with the join screen
  still open
- **THEN** the join screen says SnapSync is being updated and is back shortly, offers Retry and Cancel, and loads
  the event's details without the guest tapping anything

#### Scenario: An unexpected failure never strands the user
- **WHEN** loading the event or joining it fails in an unexpected way
- **THEN** the screen moves to one that offers at least Cancel, never an endless spinner

### Requirement: Joining happens only on confirmation and needs a connection
Nothing SHALL be shared, received, or joined before the user taps Join. No invite link, however it is
crafted, SHALL join, switch or start sharing without the user confirming on the join screen. On Join the screen SHALL show
that it is joining; on success the user SHALL see the joined screen and become a member at once, before
any photo has been shared. Joining SHALL NOT require photo access; without it the joined screen asks for
access (capability `photo-access`). If joining fails the user SHALL stay on the join screen with a Retry
that keeps their choices, telling a failing service as the condition that applies (capability `app-experience`,
"A failing service is told as what it is"), and SHALL NOT be left half-joined; the app SHALL NOT join again until
the user taps Retry. An answer the app does not understand SHALL NOT count as joined. If the membership was in
fact established, the user SHALL see the joined screen.

#### Scenario: A crafted link cannot skip the confirmation
- **WHEN** a user opens an invite link crafted to join without asking
- **THEN** the join screen is shown as for any invite, and nothing is joined, left or shared until they tap Join

#### Scenario: Confirming joins
- **WHEN** the user taps Join and the event accepts the device
- **THEN** the joined screen for that event is shown with the choices they made

#### Scenario: A failed join is retryable and leaves nothing behind
- **WHEN** the user taps Join and the connection drops before the event accepts the device
- **THEN** the join screen says joining failed, offers Retry and Cancel, and the device is in no event

#### Scenario: A join the service fails says which way
- **WHEN** the user taps Join and the service answers that it failed, or that it is being updated
- **THEN** the join screen says SnapSync is having trouble, or is being updated and is back shortly, offers Retry
  and Cancel, and the device is in no event

#### Scenario: An answer the app cannot read is not a join
- **WHEN** the user taps Join and the answer is not one the app understands
- **THEN** the join screen says SnapSync cannot be reached, offers Retry and Cancel, and the joined screen is not
  shown; a Retry that the event accepts shows the joined screen with the choices they made

#### Scenario: Cancel abandons the invite
- **WHEN** the user taps Cancel on the join screen
- **THEN** nothing is joined or shared and the create screen is shown
