# Spec Delta

## MODIFIED Requirements

### Requirement: The invite link format stays openable forever
An invite SHALL be one of three HTTPS links, each naming the event by its identifier as a canonical UUID:
- the path form `https://snapsync.stho.net/join/<eventId>`;
- the keyed path form `https://snapsync.stho.net/join/<eventId>#k=<key>`, where `<key>` is the event's key as
  unpadded base64url — the invite of an encrypted event;
- the fragment form `https://snapsync.stho.net/join#v=3&d=<payload>`, where `<payload>` is the unpadded
  base64url encoding of a UTF-8 JSON object whose `eventId` key holds the event's identifier.

Every future version of the app SHALL open all three forms and offer to join their event, so a QR code printed
today keeps working. Every event this version creates SHALL be encrypted, and its QR code and the link the app
shares SHALL be the keyed path form. An event created without encryption keeps the fragment form as its QR code
and shared link until every app in use opens the path form. An event's QR code SHALL encode exactly the link the
app shares. Beyond the event's key, the link SHALL carry nothing a member relies on — no event name, no server
address, no other credential.

#### Scenario: A link made by an older version opens in a newer one
- **WHEN** a user of the current version scans a QR code that an earlier version produced for a still-existing event
- **THEN** the app opens on the join screen for that event

#### Scenario: A path-form link opens the join screen
- **WHEN** a user of the current version taps an invite of the path form for a still-existing event
- **THEN** the app opens on the join screen for that event

#### Scenario: A new event's invite carries its key
- **WHEN** a host creates an event and shares its invite
- **THEN** the shared link and the event's QR code are the keyed path form, carrying that event's key

#### Scenario: A link shared today opens in an app from before this version
- **WHEN** a member of an event created without encryption shares its invite and a guest whose app predates the
  path form taps it
- **THEN** the guest's app opens on the join screen for that event

#### Scenario: The invite carries the event identifier only
- **WHEN** an event's invite link is decoded, in any form
- **THEN** it holds the event's identifier and, for an encrypted event, its key — not its name, not a server address,
  not any other credential

### Requirement: Reopening the current event's invite changes nothing
Opening the invite of the event the device is already in SHALL do nothing: no join screen, no change to
the member's settings, and no interruption of sharing or receiving. The one exception is a device that has lost
the joined event's key (capability `sync-status`): opening that event's whole invite SHALL give the device the key
back, with still no join screen and no change to the member's settings, and sharing and receiving SHALL resume.
An invite of that event without the key, or with another, SHALL change nothing.

#### Scenario: A member rescans their own event
- **WHEN** a member scans the QR code of the event they are already in
- **THEN** the joined screen stays as it was and their range, switches, and album choice are unchanged

#### Scenario: Reopening the invite restores a lost key
- **WHEN** a member whose device lost the joined event's key opens that event's whole invite
- **THEN** the joined screen no longer asks for the invite, their range, switches and album choice are unchanged,
  and their photos are shared and received again

#### Scenario: An incomplete invite does not restore a lost key
- **WHEN** a member whose device lost the joined event's key opens that event's invite without its key
- **THEN** the joined screen still asks for the event's invite and nothing else changes

### Requirement: Without the app, the invite leads to a store and back
Opening an invite on a device without SnapSync SHALL show the event's web page (capability `event-site`),
which offers SnapSync on the App Store and, whenever that page offers Google Play (to testers during a
closed test, or once SnapSync is published there), on Google Play. Because iOS
does not hand a link over through an installation, an iPhone user who installs the app from there SHALL
reach the event by opening the original invite again.

An Android user who installs SnapSync by following that page's Google Play button, on the same phone, SHALL
find the app open on the join screen for that event at its first launch, without opening the invite again —
for an encrypted event too, whose key the page hands over with the invite (capability `privacy-security`).
The join screen SHALL behave exactly as for a tapped invite: nothing is joined until they confirm. The
carried invite SHALL be acted on at most once per installation. An installation that did not come through
an invite's page SHALL open the app as usual, on no join screen and with no damaged-invite report. Opening
the original invite again SHALL keep working on both platforms.

#### Scenario: Installing, then reopening the invite joins
- **WHEN** a guest without SnapSync opens an invite on an iPhone, installs the app from the page, and then taps the same invite again
- **THEN** SnapSync opens on the join screen for that event

#### Scenario: Installing from Google Play opens the join screen
- **WHEN** a guest without SnapSync opens an invite on an Android phone, follows the page's Google Play button, installs SnapSync and opens it for the first time
- **THEN** SnapSync opens on the join screen for that event

#### Scenario: Installing from Google Play opens an encrypted event's join screen
- **WHEN** a guest without SnapSync opens an encrypted event's whole invite on an Android phone, follows the page's
  Google Play button, installs SnapSync and opens it for the first time
- **THEN** SnapSync opens on the join screen for that event, not on the incomplete-invite screen

#### Scenario: Becoming a tester from an invite's page opens the join screen
- **WHEN** a guest without SnapSync opens an invite on an Android phone during the closed test, follows the page's steps to become a tester, installs SnapSync from the page's Google Play button and opens it for the first time
- **THEN** SnapSync opens on the join screen for that event

#### Scenario: The carried invite opens only once
- **WHEN** that guest joins or dismisses the join screen, and later opens SnapSync again
- **THEN** the carried invite's join screen does not return

#### Scenario: An ordinary installation opens no join screen
- **WHEN** someone installs SnapSync from Google Play without coming from an invite's page, and opens it
- **THEN** the app opens as usual, with no join screen and no report of a damaged invite

#### Scenario: Installing from a computer carries no invite
- **WHEN** a visitor follows the Google Play button on an invite's page in a desktop browser and installs SnapSync onto their phone from there
- **THEN** the app opens without a join screen, and opening the invite on the phone opens its join screen

## ADDED Requirements

### Requirement: An incomplete invite never joins an encrypted event
An invite of an encrypted event that does not carry that event's key — cut short in sharing, or carrying another
key — SHALL open the join screen and say, once the event has loaded, that the invite is incomplete and only the whole
invite opens the event. It SHALL offer only Cancel and never Join, and SHALL NOT change the device's membership. An
invite carrying a key for an event that is not encrypted SHALL be treated the same way. Opening the whole invite
afterwards SHALL open the join screen as usual.

#### Scenario: An invite without its key
- **WHEN** a guest opens an encrypted event's invite whose key was cut off
- **THEN** the join screen says the invite is incomplete, offers only Cancel, and the guest joins nothing

#### Scenario: The whole invite still opens the event
- **WHEN** that guest cancels and then opens the event's whole invite
- **THEN** the join screen for that event opens and offers Join

#### Scenario: A member is not moved by an incomplete invite
- **WHEN** a member of one event opens another encrypted event's invite without its key
- **THEN** the app says the invite is incomplete and the member stays in their event with every setting unchanged
