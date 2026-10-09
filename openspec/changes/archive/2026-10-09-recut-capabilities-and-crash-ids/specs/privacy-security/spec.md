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
  deletes that photo before the event closes
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

### Requirement: The event's identity goes only to SnapSync's own service
Opening an invite link in a browser SHALL send the event's identity to SnapSync's own service, to show that
event, and to no other server. The event page SHALL NEVER send the event's identity to a third party, and
SHALL NOT let the browser pass the page's address on to any other site it fetches from or links to. The one
exception is a visitor's own act: when a visitor follows the event page's Google Play button, the page SHALL
hand that invite to Google Play so the Android app can open it once installed. Google Play then learns the
event's identity, and with it the ability to see the event's photos. The page SHALL hand it over only for a
valid invite and only through that button. Nothing else on the site SHALL carry an invite to Google Play,
including the landing page's Google Play button. An automatic failure report MAY carry the event's identity (requirement "Automatic failure reports are minimal
and anonymous"), which goes only to the operator's error-tracking service.

#### Scenario: Opening a link where no app is installed
- **WHEN** a visitor opens an invite link in a browser
- **THEN** only SnapSync's own service learns which event is being viewed

#### Scenario: Downloading passes no address on
- **WHEN** a visitor downloads an event's photos from its page
- **THEN** the requests for the photos do not carry the page's address

#### Scenario: Following the event page's Google Play button
- **WHEN** a visitor on a valid invite's page follows its Google Play button
- **THEN** Google Play receives that invite, and no other third party does

#### Scenario: An invalid invite is never handed over
- **WHEN** a visitor on the page for an invalid or expired invite follows its Google Play button
- **THEN** Google Play opens SnapSync's page and receives nothing from the invite

#### Scenario: The landing page carries no invite
- **WHEN** a visitor follows the landing page's Google Play button
- **THEN** Google Play opens SnapSync's page and receives no invite

### Requirement: Automatic failure reports are minimal and anonymous
Only builds distributed through the App Store, TestFlight or Google Play SHALL report failures automatically, and
they SHALL report only crashes, errors and the app freezing until the system closes it — with the recent app
activity leading up to them and technical facts such as device model, OS version, app version and how the app's
previous runs ended. They SHALL NOT carry analytics, usage tracking, performance monitoring or screen recording. A
report MAY carry the random identifiers SnapSync already exchanges with its own service — of the device, the event
and its photos — so the operator can find what a failure affected, and one random identifier per install created by
the reporting itself, so the operator can count how many installs a problem affects. A report SHALL NEVER carry an
event's key, nor anything that identifies the person using the app. Reports SHALL go only to the operator's
error-tracking service. Reporting SHALL change nothing the user sees or experiences.

#### Scenario: The app hits an error during an upload
- **WHEN** a distributed build hits an error while sharing a photo of an encrypted event
- **THEN** a report of the error reaches the operator; it may name the event and the device, and it does not contain
  the event's key

#### Scenario: The app freezes and the system closes it
- **WHEN** a Google Play build stops responding and the system closes it
- **THEN** a report of the freeze reaches the operator, and it does not contain the key of the event the phone is in

#### Scenario: A development build
- **WHEN** a build not distributed through the App Store, TestFlight or Google Play crashes
- **THEN** nothing is reported anywhere

## REMOVED Requirements

### Requirement: The invite link is the key to an event

**Reason**: moved to `invite-link`.
**Migration**: unchanged in capability `invite-link`.

### Requirement: Only the whole invite opens an event's photos

**Reason**: moved to `invite-link`.
**Migration**: unchanged in capability `invite-link`.

### Requirement: The invite handed to Google Play carries its key

**Reason**: moved to `invite-link`.
**Migration**: unchanged in capability `invite-link`.
