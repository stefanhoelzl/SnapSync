# Spec Delta

## MODIFIED Requirements

### Requirement: Every invite form opens in every future version
An invite SHALL be one of three HTTPS links, each naming the event by its identifier as a canonical UUID:
- the path form `https://snapsync.stho.net/join/<eventId>`;
- the keyed path form `https://snapsync.stho.net/join/<eventId>#k=<key>`, where `<key>` is the event's key as
  unpadded base64url — the invite of an encrypted event. It MAY carry the event's name for link previews, as
  `https://snapsync.stho.net/join/<eventId>?n=<name>#k=<key>`, where `<name>` is the name percent-encoded as UTF-8;
- the fragment form `https://snapsync.stho.net/join#v=3&d=<payload>`, where `<payload>` is the unpadded
  base64url encoding of a UTF-8 JSON object whose `eventId` key holds the event's identifier.

Every future version of the app SHALL open all three forms, the keyed path form with and without a preview name, and
offer to join their event, so a QR code printed today keeps working. Every event this version creates SHALL be
created without encryption, so its invite opens in every app version still in use: its QR code and the link the app
shares SHALL be the fragment form until every app in use opens the path form. An encrypted event (one a later version
creates) SHALL share the keyed path form, carrying the event's name as it stands when the invite is shared. An
event's QR code SHALL encode exactly the link the app shares, without its preview name: a QR code is scanned, never
previewed. Beyond the event's key, the link SHALL carry nothing a
member relies on — no server address, no other credential — and the preview name SHALL be relied on by nothing: the
app SHALL NOT show it, and SHALL open an invite whose preview name is missing, stale or altered exactly like the same
invite carrying the event's current name.

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
- **THEN** the shared link and the event's QR code are the keyed path form, carrying that event's key; the shared
  link also carries the event's name for previews, the QR code does not

#### Scenario: A link shared today opens in an app from before this version
- **WHEN** a member of an event created without encryption shares its invite and a guest whose app predates the
  path form taps it
- **THEN** the guest's app opens on the join screen for that event

#### Scenario: The invite carries the event identifier only
- **WHEN** an event's invite link is decoded, in any form
- **THEN** it holds the event's identifier and, for an encrypted event, its key and possibly its name for previews —
  not a server address, not any other credential

#### Scenario: A stale preview name changes nothing in the app
- **WHEN** an encrypted event "Ana's 3oth" is renamed to "Ana's 30th", and a guest opens an invite shared before the
  rename
- **THEN** the join screen names the event "Ana's 30th"

#### Scenario: An invite without a preview name still opens
- **WHEN** a guest opens an encrypted event's whole invite that carries no preview name
- **THEN** the join screen opens for that event, naming it, and offers Join

## ADDED Requirements

### Requirement: The service keeps an encrypted event's name only encrypted
An encrypted event's name SHALL be stored by SnapSync's service only encrypted under the event's key, so that,
like its photos, it opens only on a member's phone or in the browser of someone holding the whole invite (capability
`event-site`). Renaming the event SHALL keep it so. The service SHALL see the readable name only as the preview name of
an invite it is asked to show, and only to show that page or preview; it SHALL keep that name in no record, log or
failure report (capability `privacy-security`). An encrypted event created before this rule took effect MAY keep its
name readable until its photos are deleted. A plain event's name SHALL stay readable to the service.

#### Scenario: The stored event does not reveal its name
- **WHEN** someone with access to SnapSync's stored events, but not the invite, reads an encrypted event
- **THEN** they can read its dates and how many members it has, but not its name

#### Scenario: A renamed event's new name is stored encrypted
- **WHEN** a member renames an encrypted event
- **THEN** every member sees the new name, and SnapSync's stored event still does not reveal it

#### Scenario: Showing a preview leaves no name behind
- **WHEN** a messenger fetches the preview of an encrypted event's invite that carries its name
- **THEN** the preview names the event, and nothing SnapSync's service keeps afterwards contains the name
