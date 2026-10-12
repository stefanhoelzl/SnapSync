# Spec Delta

## MODIFIED Requirements

### Requirement: Only the name changes after creation

After an event is created, its name SHALL be the only thing about the event anyone can change (capability
`manage-membership`), and only until the event closes; a closed event SHALL NOT change at all. Its date
range and its lifetime SHALL stay exactly as created.

#### Scenario: Widening the range is impossible
- **WHEN** someone holding the invite tries to change an event's date range after creation
- **THEN** the range is unchanged, so no member's shared range can be widened after the fact

#### Scenario: A closed event keeps its name
- **WHEN** a member tries to rename an event that has closed
- **THEN** the name stays as it was

## REMOVED Requirements

### Requirement: A limited number of devices ever take part, and leaving frees no place
**Reason**: An event no longer has a device limit. The limit protected the backend's wake fan-out, which is now
bounded per photo instead (capability `delivery`), and nothing else that grows with the number of devices
needed it. Decision record: this change's design.md.
**Migration**: None for members. Every event, including those created before this change, admits any number of
devices. Rejoining after a leave, until the event closes, stays promised by `manage-membership` ("Leaving
deletes no one's photos").
