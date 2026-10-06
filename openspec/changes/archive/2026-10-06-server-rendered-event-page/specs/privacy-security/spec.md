# Spec Delta

## MODIFIED Requirements

### Requirement: The invite link is the key to an event
Anyone who holds an event's invite link or QR code SHALL be able to join the event with the app (capability
`join-event`) and to see, in a browser, the event's name, its dates, how many members it has and how many
have finished sharing, and to download all of its shared photos (capability `event-site`). There SHALL be no
other gate — no account, approval or password — so sharing the QR code shares the event. Without the link,
an event's photos SHALL NOT be discoverable: the service offers no listing or search of events, and stored
photos are not publicly browsable.

#### Scenario: A forwarded link grants access
- **WHEN** a member forwards the invite link to someone outside the event
- **THEN** that person can see the event's name, its dates and its members' count, and download its shared photos in a browser

#### Scenario: Without the link there is no way in
- **WHEN** someone without the invite link looks for an event's photos on SnapSync's web address or its storage
- **THEN** they can find neither the event nor any of its photos

## REMOVED Requirements

### Requirement: The event's identity stays off the wire until it is needed
**Reason**: Link previews and a server-rendered event page need the event's identity in the part of the link a browser
sends, so opening an invite in a browser now sends it to SnapSync's own service.
**Migration**: Replaced by "The event's identity goes only to SnapSync's own service", which keeps every promise except
"the request that loads the page carries nothing that identifies the event".

## ADDED Requirements

### Requirement: The event's identity goes only to SnapSync's own service
Opening an invite link in a browser SHALL send the event's identity to SnapSync's own service, to show that
event, and to no other server. The event page SHALL NEVER send the event's identity to a third party, and
SHALL NOT let the browser pass the page's address on to any other site it fetches from or links to. The one
exception is a visitor's own act: when a visitor follows the event page's Google Play button, the page SHALL
hand that invite to Google Play so the Android app can open it once installed. Google Play then learns the
event's identity, and with it the ability to see the event's photos. The page SHALL hand it over only for a
valid invite and only through that button. Nothing else on the site SHALL carry an invite to Google Play,
including the landing page's Google Play button. The event's identity SHALL NOT appear in any automatic
failure report.

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
