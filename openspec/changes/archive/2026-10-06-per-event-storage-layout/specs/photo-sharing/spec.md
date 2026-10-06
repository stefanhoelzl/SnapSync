# Spec Delta

## ADDED Requirements

### Requirement: A photo already in an event is not uploaded again for it

A photo the event service already holds from this device SHALL NOT be uploaded again because the member
rejoins the event, switches to another event and back, reinstalls the app, or restores the phone from an
encrypted backup. A rejoined member's already-shared photos SHALL stay available to the other members
without a gap. A photo that also falls inside another event this device joins MAY be uploaded again for
that event. When the device cannot learn at join what the service already holds (for example because it
is offline), it MAY upload such photos again. A repeated upload SHALL never appear to anyone as a second
copy. No promise is made that an app update avoids repeated uploads.

#### Scenario: Rejoining shares without re-uploading
- **WHEN** a member leaves an event and later joins it again
- **THEN** their earlier photos are offered again and none is uploaded a second time

#### Scenario: A reinstalled app keeps its photos
- **WHEN** a member deletes and reinstalls the app and joins the event again
- **THEN** their already-shared photos are not uploaded again and other members do not receive them twice

#### Scenario: A photo in two events is shared to each
- **WHEN** a member shared a photo to one event and joins another event whose range also contains it
- **THEN** the photo is offered in the second event, it may be uploaded again for it, and no member of
  either event receives it twice

#### Scenario: An offline join may upload again, never duplicate
- **WHEN** a member joins while the service cannot be reached to learn what it already holds
- **THEN** the join still completes, photos may be uploaded again, and no member receives any photo twice

## REMOVED Requirements

### Requirement: A photo already in the event is not uploaded again
**Reason**: It also promised that a photo shared to one event is not uploaded again for another event it
falls inside. Each event now holds its own copy of what its members share, so that part no longer holds.
**Migration**: Replaced by "A photo already in an event is not uploaded again for it", which keeps the
rejoin, switch-back, reinstall, offline-join and no-second-copy promises unchanged.
