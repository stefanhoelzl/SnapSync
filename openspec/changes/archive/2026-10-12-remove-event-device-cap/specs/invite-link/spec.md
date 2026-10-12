# Spec Delta

## MODIFIED Requirements

### Requirement: Whoever holds the invite can join and see everything
The app SHALL NOT restrict who may use an invite: anyone who scans the QR code or receives the link SHALL
be able to join the event, contribute photos to it, and receive all of its photos, until the event closes
(capability `event-lifetime`).

#### Scenario: A forwarded invite admits a stranger
- **WHEN** a member's invite link is forwarded to someone outside the group, who opens it before the
  event closes
- **THEN** that person can join the event and receives its photos like any other member
