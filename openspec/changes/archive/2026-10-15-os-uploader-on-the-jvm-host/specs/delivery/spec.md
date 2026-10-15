# Spec Delta

## MODIFIED Requirements

### Requirement: A force-quit pauses the app's own background uploading only until the next opening

Background uploading SHALL resume at the latest the next time the member opens the app after a force-quit,
with nothing lost; until then, uploads that rely on the app's own wake-ups MAY stop. On iOS 26.1 and later
with full access, iOS's own uploader SHALL keep working after a force-quit. On Android, force-stopping the
app from Android's Settings SHALL pause all of its background uploading until the next opening; swiping the
app away from the recent apps SHALL NOT, unless the phone's maker makes swiping away a force-stop.

#### Scenario: Reopening after a force-quit resumes uploading
- **WHEN** the member force-quits the app on iOS 18 and later opens it
- **THEN** every photo taken meanwhile is uploaded, and background uploading continues after the app is
  left again

#### Scenario: Reopening after a force-stop on Android resumes uploading
- **WHEN** an Android member force-stops the app from Settings, takes photos, and later opens the app
- **THEN** nothing was uploaded in between, every photo taken meanwhile is uploaded, and background
  uploading continues after the app is left again

#### Scenario: Swiping the app away on Android keeps uploading
- **WHEN** an Android member on a phone that keeps Android's standard behaviour swipes the app away from the
  recent apps and then takes a photo
- **THEN** the photo is still uploaded in the background

#### Scenario: iOS's uploader keeps sharing after a force-quit
- **WHEN** a sharing member on iOS 26.1 or later with full access force-quits the app, takes photos during the event
  and does not open the app
- **THEN** the photos are uploaded in the background and become available to the other members

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

#### Scenario: A photo both uploaders pick up arrives once
- **WHEN** a sharing member on iOS 26.1 or later with full access takes a photo that iOS's uploader and the app
  each upload
- **THEN** the photo reaches the event, and the other members receive it once
