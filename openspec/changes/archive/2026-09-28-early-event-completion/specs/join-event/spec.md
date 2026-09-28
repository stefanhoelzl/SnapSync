## MODIFIED Requirements

### Requirement: A late guest can still join
Joining SHALL NOT be refused because the event's date range has passed. Until the event closes
(capability `event-lifetime`), a guest who opens the invite after the event's end SHALL join like anyone
else and share the photos they took within the event's range.

#### Scenario: A guest scans days after the party
- **WHEN** a guest opens the invite three days after the event's date range ended, before the event has
  closed
- **THEN** the join screen offers Join with the full event window preselected, and after joining their photos from the event's dates are shared

## ADDED Requirements

### Requirement: A closed or finished event cannot be joined
The join screen SHALL say, when the invite's event has closed or has finished and its photos were deleted
(capability `event-lifetime`), that the event can no longer be joined, and offer only Cancel, never Join or
Retry. A device switching from another event SHALL remain in its current event. A lost connection SHALL
NOT be reported as closed.

#### Scenario: A guest scans after the close
- **WHEN** a guest opens the invite of an event that has closed
- **THEN** the join screen says the event can no longer be joined and offers only Cancel

#### Scenario: The event closes while the join screen is open
- **WHEN** a guest taps Join on an event that closed after the join screen loaded
- **THEN** they are not joined, and the screen says the event can no longer be joined

#### Scenario: A member does not lose their event to a closed invite
- **WHEN** a member opens the invite of a different event that has closed
- **THEN** they are told it can no longer be joined, and remain in their current event
