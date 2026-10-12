## MODIFIED Requirements

### Requirement: A link to a single photo lasts only as long as the photo is shared
A link to a single stored photo, as handed to a member's app, SHALL work only while that photo is part of the
event: once its member withdraws it (capability `photo-sharing`) or the event's photos are deleted (capability
`event-lifetime`), the link SHALL NOT serve it. A link to a single photo as handed to the event page SHALL work
for about an hour after the page received it, and never longer: a photo withdrawn within that hour MAY still be
served through such a link until it lapses, and once it has lapsed the link SHALL NOT serve the photo, whether
or not the photo is still shared. Either link names its event, so whoever holds one SHALL be able to reach what
the event's invite link reaches (requirement "The invite link is the key to an event", capability `invite-link`); neither the app nor the
event page SHALL show it to the user.

#### Scenario: A copied photo link after the photo is withdrawn
- **WHEN** someone copies the address of a single photo the member's app was given, and its member then
  deletes that photo, even after the event's range has ended
- **THEN** opening the copied address no longer serves the photo

#### Scenario: A copied photo link after the event's photos are deleted
- **WHEN** someone opens a copied single-photo address after the event's photos have been deleted
- **THEN** the photo is not served

#### Scenario: A copied photo link while the photo is shared
- **WHEN** someone opens a single-photo address the member's app was given 8 days later, while the photo is
  still part of the event
- **THEN** the photo is served, as it would be to anyone holding the event's invite link

#### Scenario: A photo link copied from the event page lapses
- **WHEN** someone copies the address of a single photo out of the event page and opens it two hours later,
  while the photo is still part of the event
- **THEN** the photo is not served, and opening the event page again offers it again

#### Scenario: A photo withdrawn while the event page is open
- **WHEN** a member deletes a photo while a visitor's event page is open, and the visitor then downloads the
  event
- **THEN** the withdrawn photo may still be in that download if the page received its link less than an
  hour before, and is not in a download from the event page opened afresh

## REMOVED Requirements

### Requirement: The notification token is used only to deliver new photos
**Reason**: The token no longer announces a close, which no longer exists.
**Migration**: Replaced by "The notification token is used only to announce new photos".

## ADDED Requirements

### Requirement: The notification token is used only to announce new photos
The token Apple gives the app for waking it SHALL be used only to tell that device that new photos are
ready in its event.

#### Scenario: Another member shares a photo
- **WHEN** another member's photo arrives in the event
- **THEN** the token is used to wake this device for it, and for nothing else

#### Scenario: Everyone has finished sharing
- **WHEN** the last member still sharing to the device's event finishes, and no new photo becomes available
- **THEN** the token is not used
