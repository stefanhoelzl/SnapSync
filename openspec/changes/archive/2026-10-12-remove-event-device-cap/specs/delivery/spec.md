# Spec Delta

## MODIFIED Requirements

### Requirement: New photos are announced by a silent wake, and never only by it

When photos become available that this member can receive, the member's device SHALL be woken silently —
no alert, sound or badge — so it can fetch them in the background. In a large event, each photo that becomes
available, and the event's close, MAY wake only some of the members, a different selection each time; a
member who is not woken SHALL receive exactly as when a wake is lost. A wake SHALL be sent only when a photo
has actually become available, or once when the event closes (capability `event-lifetime`) so each member
can finish and leave on its own (capability `event-lifetime`); wakes for one event MAY be combined into
one. Wakes are best effort: without one, new photos SHALL still arrive in the background, in a timely manner
as the phone's system allows, and at the latest the next time the member opens the app. A force-quit — on
Android, a force-stop from the phone's Settings — SHALL stop receiving until the member next opens the app;
every photo shared meanwhile SHALL then arrive. A wake for an event this device has left SHALL fetch nothing.
Failing to set up wakes SHALL never prevent joining, sharing or receiving.

#### Scenario: A wake brings new photos in the background
- **WHEN** another member's photo becomes available while this member's phone is in their pocket
- **THEN** the phone is woken silently and the photo is downloaded and saved without the app being opened

#### Scenario: No wake arrives
- **WHEN** the phone's system drops or delays the wake for a new photo, or the phone cannot receive wakes at
  all, and the member does not open the app
- **THEN** the photo still arrives in their library in the background, without the app being opened

#### Scenario: A large event wakes some members per photo
- **WHEN** a photo becomes available in an event with many members, and this member's phone is not among
  those woken for it
- **THEN** the photo still arrives in their library in the background, without the app being opened

#### Scenario: Opening the app catches up
- **WHEN** a new photo has not arrived yet and the member opens the app
- **THEN** the photo arrives

#### Scenario: A force-quit stops receiving until the next opening
- **WHEN** the member force-quits the app (on Android, force-stops it from Settings) and other members share
  photos meanwhile
- **THEN** nothing is received until the member opens the app again, and then every one of those photos
  arrives

#### Scenario: A left event's wakes are ignored
- **WHEN** the device receives a wake for an event it has left
- **THEN** nothing is downloaded

#### Scenario: A declared but unfinished photo wakes nobody
- **WHEN** another member's device starts uploading a photo that is not complete yet
- **THEN** no member is woken for it

#### Scenario: The close wakes every member once
- **WHEN** a small event closes after all of its photos have already arrived everywhere
- **THEN** each member still in it is woken silently once and, having everything, leaves it

#### Scenario: The close of a large event wakes some members
- **WHEN** an event with many members closes after all of its photos have already arrived everywhere
- **THEN** each member still in it is woken silently at most once; a woken member, having everything, leaves
  it, and one not woken leaves on its next background wake, at the latest the next time they open the app
