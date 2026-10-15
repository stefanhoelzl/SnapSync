# Spec Delta

## MODIFIED Requirements

### Requirement: A locked phone delays uploads and never ends the membership

Background uploads SHALL work while the phone is locked, once it has been unlocked at least once since it
started. Before that first unlock the app SHALL do nothing rather than guess: it SHALL NOT treat the
member as having left, SHALL NOT change the device's identity, and SHALL resume normally after the unlock.

#### Scenario: Uploads while locked
- **WHEN** the member takes photos, locks the phone and puts it away
- **THEN** the photos upload in the background while the phone stays locked

#### Scenario: A wake before the first unlock after a restart
- **WHEN** iOS wakes the app in the background after a restart, before the member has unlocked the phone
- **THEN** nothing is uploaded, the member is still joined, and uploading resumes after the unlock

#### Scenario: A wake before the first unlock does not make the phone a new member
- **WHEN** iOS wakes the app in the background after a restart, before the member has unlocked the phone, and the
  member later unlocks it and opens the app
- **THEN** the phone is still the same member of the event, and the event's members do not count a new device
