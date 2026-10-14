## MODIFIED Requirements

### Requirement: A photo is never lost on the way

Once a photo is shareable it SHALL eventually reach the event unless the member withdraws it (capability
`photo-sharing`): a failed attempt SHALL be retried without limit, and a crash, the app or iOS killing the
process, a reboot, a network loss, a failing service, an expired credential or an upload finishing while the app
is not running SHALL only delay it. A finished upload SHALL NOT be uploaded again because the app was killed
right after it finished. The worst case SHALL be that the same photo is uploaded twice, which no other
member ever sees as a second copy. A photo the other members already receive SHALL NOT be withdrawn from them
because the service failed while the member's phone was telling it what the member shares.

#### Scenario: A failed upload is retried
- **WHEN** an upload fails because the server or network is unavailable
- **THEN** it is retried later until it succeeds

#### Scenario: Photos shared while the service fails reach the others later
- **WHEN** a member's phone tells the service which photos it shares while the service is failing, or gets an
  answer it does not understand
- **THEN** once the service works again the other members receive those photos without the member doing
  anything, and none they already received is withdrawn meanwhile

#### Scenario: The app is killed mid-upload
- **WHEN** the app is killed while photos are uploading and later runs again
- **THEN** every photo that had not finished is uploaded, and none that had finished is uploaded again

#### Scenario: Offline photos upload when the network returns
- **WHEN** a member takes photos with no network connection
- **THEN** they are uploaded once a connection is available, without the member doing anything

#### Scenario: A large backlog drains in steps
- **WHEN** a member has more photos queued than iOS accepts at once
- **THEN** the rest are uploaded in later passes, without the member doing anything

### Requirement: Only new photos are announced by a silent wake, and never only by it

When photos become available that this member can receive, the member's device SHALL be woken silently —
no alert, sound or badge — so it can fetch them in the background. In a large event, each photo that becomes
available MAY wake only some of the members, a different selection each time; a member who is not woken SHALL
receive exactly as when a wake is lost. A wake SHALL be sent only when a photo has actually become available;
wakes for one event MAY be combined into one. Wakes are best effort: without
one, new photos SHALL still arrive in the background, in a timely manner as the phone's system allows, and at
the latest the next time the member opens the app. A force-quit — on Android, a force-stop from the phone's
Settings — SHALL stop receiving until the member next opens the app; every photo shared meanwhile SHALL then
arrive. A wake for an event this device has left SHALL fetch nothing. Failing to set up wakes — on the phone or
because the service failed to take it — SHALL never prevent joining, sharing or receiving.

#### Scenario: A wake brings new photos in the background
- **WHEN** another member's photo becomes available while this member's phone is in their pocket
- **THEN** the phone is woken silently and the photo is downloaded and saved without the app being opened

#### Scenario: No wake arrives
- **WHEN** the phone's system drops or delays the wake for a new photo, or the phone cannot receive wakes at
  all, and the member does not open the app
- **THEN** the photo still arrives in their library in the background, without the app being opened

#### Scenario: The service could not set up wakes
- **WHEN** the service fails each time the member's phone sets up its wakes, and another member shares a photo
- **THEN** the member still joins, shares and receives, and the photo arrives in their library in the
  background or at the latest the next time they open the app

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

#### Scenario: Everyone being done wakes nobody
- **WHEN** an event's range has ended and the last member still sharing finishes, after all of its photos have
  already arrived everywhere
- **THEN** no member is woken for it, and every member stays in the event
