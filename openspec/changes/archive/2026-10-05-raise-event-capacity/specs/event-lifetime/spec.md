# Spec Delta

## RENAMED Requirements

- FROM: `### Requirement: At most 10 devices ever take part, and leaving frees no place`
- TO: `### Requirement: A limited number of devices ever take part, and leaving frees no place`

## MODIFIED Requirements

### Requirement: A limited number of devices ever take part, and leaving frees no place

An event SHALL admit a limited number of distinct devices over its whole life, set by the service when the
event is created and counting devices that have left. Until the event closes, a device that left SHALL
always be able to rejoin in its own place. A new device SHALL be refused once the limit has been reached,
even if some have left (what the joining guest is told: capability `join-event`). The limit SHALL hold
exactly, even when several devices join at the same moment.

#### Scenario: Leaving does not make room
- **WHEN** an event has reached its device limit and one device leaves
- **THEN** a new device is still refused

#### Scenario: A returning device rejoins
- **WHEN** a device that left a full event scans its invite again before the event has closed
- **THEN** it rejoins

#### Scenario: Simultaneous joins
- **WHEN** three places remain and five new devices join at the same moment
- **THEN** exactly three are admitted
