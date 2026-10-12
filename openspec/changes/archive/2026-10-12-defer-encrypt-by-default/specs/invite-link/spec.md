# Spec Delta

## REMOVED Requirements

### Requirement: The invite link format stays openable forever
**Reason**: It stated that every event this version creates is encrypted and shares the keyed path form; this version
creates plain events again, so a guest on any app version in use can open the invite. Its whole content, with that
statement and its scenario replaced, continues as "Every invite form opens in every future version".
**Migration**: None for users: every link form keeps opening; a new event's invite is the fragment form.

## ADDED Requirements

### Requirement: Every invite form opens in every future version
An invite SHALL be one of three HTTPS links, each naming the event by its identifier as a canonical UUID:
- the path form `https://snapsync.stho.net/join/<eventId>`;
- the keyed path form `https://snapsync.stho.net/join/<eventId>#k=<key>`, where `<key>` is the event's key as
  unpadded base64url — the invite of an encrypted event;
- the fragment form `https://snapsync.stho.net/join#v=3&d=<payload>`, where `<payload>` is the unpadded
  base64url encoding of a UTF-8 JSON object whose `eventId` key holds the event's identifier.

Every future version of the app SHALL open all three forms and offer to join their event, so a QR code printed
today keeps working. Every event this version creates SHALL be created without encryption, so its invite opens in
every app version still in use: its QR code and the link the app shares SHALL be the fragment form until every app in
use opens the path form. An encrypted event (one a later version creates) SHALL share the keyed path form. An event's QR code SHALL encode exactly the link the
app shares. Beyond the event's key, the link SHALL carry nothing a member relies on — no event name, no server
address, no other credential.

#### Scenario: A link made by an older version opens in a newer one
- **WHEN** a user of the current version scans a QR code that an earlier version produced for a still-existing event
- **THEN** the app opens on the join screen for that event

#### Scenario: A path-form link opens the join screen
- **WHEN** a user of the current version taps an invite of the path form for a still-existing event
- **THEN** the app opens on the join screen for that event

#### Scenario: A new event's invite opens in every app in use
- **WHEN** a host creates an event and shares its invite, and a guest whose app predates the path form taps it
- **THEN** the guest's app opens on the join screen for that event

#### Scenario: An encrypted event's invite carries its key
- **WHEN** a member of an encrypted event shares its invite
- **THEN** the shared link and the event's QR code are the keyed path form, carrying that event's key

#### Scenario: A link shared today opens in an app from before this version
- **WHEN** a member of an event created without encryption shares its invite and a guest whose app predates the
  path form taps it
- **THEN** the guest's app opens on the join screen for that event

#### Scenario: The invite carries the event identifier only
- **WHEN** an event's invite link is decoded, in any form
- **THEN** it holds the event's identifier and, for an encrypted event, its key — not its name, not a server address,
  not any other credential

## MODIFIED Requirements

### Requirement: Only the whole invite opens an event's photos
An encrypted event SHALL have a key of its own, made on the host's phone and carried to everyone else only inside the
event's invite. Its photos SHALL be stored only encrypted
under that key, and SHALL be opened only on a member's phone or in the browser of someone holding the whole invite
(capability `event-site`). SnapSync's service SHALL never be given the event's key. The one exception is an iPhone
whose system uploads photos in the background on the app's behalf: there, for each photo, the service SHALL receive
a key that opens only that photo, use it to encrypt that photo as it arrives, and keep neither the key nor the
unencrypted photo. The key SHALL NOT appear in any log, failure report or user-sent bug report. A member's phone
SHALL never upload an encrypted event's photo unencrypted: when it cannot read the key, it uploads nothing.

#### Scenario: The stored photos cannot be opened without the invite
- **WHEN** someone with access to SnapSync's storage, but not the invite, reads an encrypted event's stored photos
- **THEN** they cannot open any of them

#### Scenario: The service never learns the event's key
- **WHEN** the members of an encrypted event join, share and receive photos
- **THEN** the event's key never reaches SnapSync's service, except as one photo's own key for a photo the iPhone's
  system uploads in the background

#### Scenario: A bug report carries no key
- **WHEN** a member of an encrypted event sends a detailed bug report
- **THEN** the report does not contain the event's key

#### Scenario: A phone that cannot read the key uploads nothing
- **WHEN** a member's phone cannot read the event's key
- **THEN** none of their photos is uploaded until it can, and none is ever uploaded unencrypted
