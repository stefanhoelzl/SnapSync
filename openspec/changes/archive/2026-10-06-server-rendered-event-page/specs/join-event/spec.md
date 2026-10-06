# Spec Delta

## MODIFIED Requirements

### Requirement: The invite link format stays openable forever
An invite SHALL be one of two HTTPS links, each naming the event by its identifier as a canonical UUID:
- the path form `https://snapsync.stho.net/join/<eventId>`;
- the fragment form `https://snapsync.stho.net/join#v=3&d=<payload>`, where `<payload>` is the unpadded
  base64url encoding of a UTF-8 JSON object whose `eventId` key holds the event's identifier.

Every future version of the app SHALL open both forms and offer to join their event, so a QR code printed
today keeps working. Until every app in use opens the path form, an event's QR code and the link the app
shares SHALL be the fragment form; an event's QR code SHALL encode exactly the link the app shares. The
link SHALL carry nothing else a member relies on — no event name, no server address, no credential.

#### Scenario: A link made by an older version opens in a newer one
- **WHEN** a user of the current version scans a QR code that an earlier version produced for a still-existing event
- **THEN** the app opens on the join screen for that event

#### Scenario: A path-form link opens the join screen
- **WHEN** a user of the current version taps an invite of the path form for a still-existing event
- **THEN** the app opens on the join screen for that event

#### Scenario: A link shared today opens in an app from before this version
- **WHEN** a member shares an event's invite and a guest whose app predates the path form taps it
- **THEN** the guest's app opens on the join screen for that event

#### Scenario: The invite carries the event identifier only
- **WHEN** an event's invite link is decoded, in either form
- **THEN** it holds the event's identifier and nothing else — not its name, not a server address, not a credential

## REMOVED Requirements

### Requirement: The invite's secret never reaches a web server
**Reason**: Link previews and a server-rendered event page need the event's identifier in the part of the link a
browser sends. Opening an invite in a browser now sends the identifier to SnapSync's own service.
**Migration**: What a browser may send, and to whom, is now `privacy-security`'s "The event's identity goes only to
SnapSync's own service", which keeps the Google Play exception.
