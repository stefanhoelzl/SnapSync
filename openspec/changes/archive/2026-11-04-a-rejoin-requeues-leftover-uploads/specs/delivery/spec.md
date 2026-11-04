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

#### Scenario: A photo both uploaders pick up arrives once
- **WHEN** a sharing member on iOS 26.1 or later with full access takes a photo that iOS's uploader and the app
  each upload
- **THEN** the photo reaches the event, and the other members receive it once

#### Scenario: A photo whose upload a leave stopped is shared after rejoining
- **WHEN** a member leaves an event while one of their photos is still uploading, and later rejoins it while the phone
  cannot reach the service
- **THEN** the photo is uploaded once the phone can reach the service again, without the member doing anything
