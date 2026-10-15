# Spec Delta

## MODIFIED Requirements

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

#### Scenario: A join whose key the phone cannot keep
- **WHEN** the user taps Join on an encrypted event's invite and the phone refuses to keep the event's key
- **THEN** the join screen says joining failed, offers Retry and Cancel, the device is in no event and the event does
  not count it as a member; a Retry once the phone keeps the key shows the joined screen with the choices they made
