# Spec Delta

## MODIFIED Requirements

### Requirement: A late guest can still join
Joining SHALL NOT be refused because the event's date range has passed. Until the event's photos are deleted
(capability `event-lifetime`), a guest who opens the invite after the event's end SHALL join like anyone
else and share the photos they took within the event's range, however long after the end that is.

#### Scenario: A guest scans days after the party
- **WHEN** a guest opens the invite three days after the event's date range ended, before its photos were
  deleted
- **THEN** the join screen offers Join with the full event window preselected, and after joining their photos from the event's dates are shared

#### Scenario: A guest of a back-dated event
- **WHEN** a host creates an event for a weekend that is already over and a guest opens its invite the next day
- **THEN** the guest joins like anyone else, and their photos from that weekend are shared

## REMOVED Requirements

### Requirement: A closed or finished event cannot be joined
**Reason**: An event no longer closes (capability `event-lifetime`); the only event that can no longer be joined
is one whose photos were deleted.
**Migration**: Replaced by "An event whose photos were deleted cannot be joined", which keeps the same screen,
the same Cancel-only choice and the same protection of a member's current event.

## ADDED Requirements

### Requirement: An event whose photos were deleted cannot be joined
The join screen SHALL say, when the invite's event has finished and its photos were deleted (capability
`event-lifetime`), that the event can no longer be joined, and offer only Cancel, never Join or Retry. A device
switching from another event SHALL remain in its current event. A lost connection SHALL NOT be reported as an
event that can no longer be joined.

#### Scenario: A guest scans after the deletion
- **WHEN** a guest opens the invite of an event whose photos were deleted
- **THEN** the join screen says the event can no longer be joined and offers only Cancel

#### Scenario: The photos are deleted while the join screen is open
- **WHEN** a guest taps Join on an event whose photos were deleted after the join screen loaded
- **THEN** they are not joined, and the screen says the event can no longer be joined

#### Scenario: A member does not lose their event to a deleted event's invite
- **WHEN** a member opens the invite of a different event whose photos were deleted
- **THEN** they are told it can no longer be joined, and remain in their current event

#### Scenario: Offline is not "can no longer be joined"
- **WHEN** a guest taps Join while the phone has no connection
- **THEN** the screen does not say the event can no longer be joined

### Requirement: The join screen shows how many members the event has
Once the join screen has loaded the event, it SHALL show how many members the event has now — devices that
joined and have not left — beside the event's name, before the user decides to join.

#### Scenario: Members are shown before joining
- **WHEN** a guest opens the invite of an event four devices have joined, one of which has since left
- **THEN** the join screen says the event has 3 members

#### Scenario: The host's own join screen
- **WHEN** a host has just created an event and its join screen opens
- **THEN** the join screen says the event has 0 members
