## MODIFIED Requirements

### Requirement: The event's identity stays off the wire until it is needed
Opening an invite link SHALL NOT transmit the event's identity to any server: a browser or the operating
system receives only the page address, never the part of the link that names the event. The event page
SHALL send the event's identity only to SnapSync's own service, to load that event, and SHALL NEVER send it
to any other third party. The one exception is a visitor's own act: when a visitor follows the event page's
Google Play button, the page SHALL hand that invite to Google Play so the Android app can open it once
installed. Google Play then learns the event's identity, and with it the ability to see the event's photos.
The page SHALL hand it over only for a valid invite and only through that button. Nothing else on the site
SHALL carry an invite to Google Play, including the landing page's Google Play button. The event's
identity SHALL NOT appear in any automatic failure report.

#### Scenario: Opening a link where no app is installed
- **WHEN** a visitor opens an invite link in a browser
- **THEN** the request that loads the page carries nothing that identifies the event

#### Scenario: The event page loads the event
- **WHEN** the event page loads the event's name and photo list
- **THEN** it asks only SnapSync's own service, and no other host learns which event is being viewed

#### Scenario: Following the event page's Google Play button
- **WHEN** a visitor on a valid invite's page follows its Google Play button
- **THEN** Google Play receives that invite, and no other third party does

#### Scenario: An invalid invite is never handed over
- **WHEN** a visitor on the page for an invalid or expired invite follows its Google Play button
- **THEN** Google Play opens SnapSync's page and receives nothing from the invite

#### Scenario: The landing page carries no invite
- **WHEN** a visitor follows the landing page's Google Play button
- **THEN** Google Play opens SnapSync's page and receives no invite
