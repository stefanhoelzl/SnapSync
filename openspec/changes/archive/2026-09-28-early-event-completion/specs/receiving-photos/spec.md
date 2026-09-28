## MODIFIED Requirements

### Requirement: New photos are announced by a silent wake, and never only by it

When photos become available that this member can receive, the member's device SHALL be woken silently —
no alert, sound or badge — so it can fetch them in the background. A wake SHALL be sent only when a photo
has actually become available, or once when the event closes (capability `event-lifetime`) so each member
can finish and leave on its own (capability `manage-membership`); wakes for one event MAY be combined into
one. Wakes are best effort: without one, new photos SHALL arrive the next time the member opens the app.
The app SHALL NOT poll in the background. A wake for an event this device has left SHALL fetch nothing.
Failing to set up wakes SHALL never prevent joining, sharing or receiving.

#### Scenario: A wake brings new photos in the background
- **WHEN** another member's photo becomes available while this member's phone is in their pocket
- **THEN** the phone is woken silently and the photo is downloaded and saved without the app being opened

#### Scenario: No wake arrives
- **WHEN** iOS drops or delays the wake for a new photo
- **THEN** the photo arrives the next time the member opens the app

#### Scenario: A left event's wakes are ignored
- **WHEN** the device receives a wake for an event it has left
- **THEN** nothing is downloaded

#### Scenario: A declared but unfinished photo wakes nobody
- **WHEN** another member's device starts uploading a photo that is not complete yet
- **THEN** no member is woken for it

#### Scenario: The close wakes every member once
- **WHEN** an event closes after all of its photos have already arrived everywhere
- **THEN** each member still in it is woken silently once and, having everything, leaves it
