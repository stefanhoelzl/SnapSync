# invite-link Specification

## Purpose
Serves everyone who invites or is invited: an event's invite — its link or QR code — is how anyone gets in, and the
only thing they need. It promises that an invite printed or sent today keeps opening in every future version; that
whoever holds the whole invite can join the event and see its photos, with no account, approval or password, while
one cut short opens nothing; that it opens SnapSync on its join screen, or leads through the store and back when the
app is missing; that a damaged invite, or the invite of the event already joined, changes nothing, while another
event's invite asks before switching; and that the joined screen offers the invite to pass on until the event closes.
What the join screen does is capability `join-event`; what the web page does with an invite is capability
`event-site`. Decision record: changes/archive/2026-10-08-encrypt-events-by-default

## Requirements

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

### Requirement: Whoever holds the invite can join and see everything
The app SHALL NOT restrict who may use an invite: anyone who scans the QR code or receives the link SHALL
be able to join the event, contribute photos to it, and receive all of its photos, within the event's
device limit and until the event closes (capability `event-lifetime`).

#### Scenario: A forwarded invite admits a stranger
- **WHEN** a member's invite link is forwarded to someone outside the group, who opens it before the
  event closes
- **THEN** that person can join the event and receives its photos like any other member

### Requirement: Only the whole invite opens an event's photos
Every event this version creates SHALL be encrypted with a key of its own, made on the host's phone and carried to
everyone else only inside the event's invite. Its photos SHALL be stored only encrypted
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
- **WHEN** a host creates an event and its members join, share and receive photos
- **THEN** the event's key never reaches SnapSync's service, except as one photo's own key for a photo the iPhone's
  system uploads in the background

#### Scenario: A bug report carries no key
- **WHEN** a member of an encrypted event sends a detailed bug report
- **THEN** the report does not contain the event's key

#### Scenario: A phone that cannot read the key uploads nothing
- **WHEN** a member's phone cannot read the event's key
- **THEN** none of their photos is uploaded until it can, and none is ever uploaded unencrypted

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

### Requirement: An invite opens the app on its join screen
Tapping an invite link or scanning an event's QR code with the Camera app SHALL open SnapSync on the join
screen for that event, whether the app was not running, suspended, or open. Each opened invite SHALL be
acted on exactly once, even when iOS hands the same link to the app more than once. Opening the same
invite again after the user has joined or dismissed it SHALL be treated as a new invite; opening a
different invite while one is being answered SHALL replace it.

#### Scenario: Scanning with the app closed opens the join screen
- **WHEN** a guest scans an event's QR code with the Camera app while SnapSync is not running
- **THEN** SnapSync launches straight onto the join screen for that event

#### Scenario: Tapping an invite while the app is running opens the join screen
- **WHEN** a guest taps an invite link in a messenger while SnapSync is running in the background
- **THEN** SnapSync comes forward on the join screen for that event

#### Scenario: A link delivered twice opens one join screen
- **WHEN** iOS delivers the same invite to the app twice in quick succession
- **THEN** one join screen opens, and any choices already made on it are left untouched

#### Scenario: A different invite replaces the open one
- **WHEN** a join screen is open for one event and the user opens the invite of another event
- **THEN** the join screen now shows the other event

### Requirement: Without the app, the invite leads to a store and back
Opening an invite on a device without SnapSync SHALL show the event's web page (capability `event-site`),
which offers SnapSync on the App Store and, whenever that page offers Google Play (to testers during a
closed test, or once SnapSync is published there), on Google Play. Because iOS
does not hand a link over through an installation, an iPhone user who installs the app from there SHALL
reach the event by opening the original invite again.

An Android user who installs SnapSync by following that page's Google Play button, on the same phone, SHALL
find the app open on the join screen for that event at its first launch, without opening the invite again —
for an encrypted event too, whose key the page hands over with the invite (see "The invite handed to Google Play carries its key").
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

### Requirement: The invite handed to Google Play carries its key
When a visitor follows an encrypted event's page's Google Play button (as "The event's identity goes only to
SnapSync's own service" allows, capability `privacy-security`), the invite the page hands to Google Play SHALL include the event's key, so the app
installed from it can join the event. Google Play then holds the whole invite. The page SHALL hand it over only
through that button and only from a page opened with the whole invite.

#### Scenario: Following the button from an encrypted event's page
- **WHEN** a visitor on an encrypted event's page, opened with its whole invite, follows its Google Play button
- **THEN** Google Play receives that whole invite, key included, and no other third party receives any of it

#### Scenario: A page without the key hands over no key
- **WHEN** a visitor on an encrypted event's page opened without its key follows its Google Play button
- **THEN** Google Play receives no key

### Requirement: A damaged invite is reported and changes nothing
An invite link that is malformed or truncated SHALL still open the app rather than dead-ending in a
browser, and SHALL NOT change the device's membership. Whatever screen the user is on — the create
screen, the joined screen or an open join screen — the app SHALL show a short message that the QR code
was not valid, which clears by itself after a few seconds.

#### Scenario: A truncated link shows a passing error
- **WHEN** a user in no event opens an invite link whose payload has been cut off
- **THEN** the app opens, the create screen shows that the QR code was not valid, and the message disappears by itself after a few seconds

#### Scenario: A damaged link never touches a membership
- **WHEN** a user who is in an event opens a malformed invite link
- **THEN** the user stays in their event with every setting unchanged, and the joined screen shows that the QR code was not valid until the message clears by itself

#### Scenario: A damaged link while a join screen is open
- **WHEN** a join screen is open and the user opens a malformed invite link
- **THEN** the join screen stays as it was and shows that the QR code was not valid until the message clears by itself

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

### Requirement: Opening another event's invite asks before switching
When a member opens the invite of a different event, the app SHALL load that event and ask whether to
switch, naming both the current and the new event, without promising any participation and without a
count. Confirming SHALL leave the current event exactly as an explicit leave does (capability
`manage-membership`) — at once, offline included, never waiting on a server — and SHALL then show the
regular join screen for the new event, including the photo-access explanation where it applies.
Cancelling that join screen SHALL leave the device in no event. Declining the switch SHALL keep the
current event untouched. An invite to a missing event SHALL be reported without leaving; a failure to
load SHALL offer Retry.

#### Scenario: The switch question names both events
- **WHEN** a member of "Ana's 30th" opens the invite of "Ski Trip"
- **THEN** a confirmation asks whether to switch, saying they will leave "Ana's 30th" and join "Ski Trip", and shows no count

#### Scenario: Confirming leaves, then configures the new event
- **WHEN** the member confirms the switch
- **THEN** they have left "Ana's 30th" and see the full join screen for "Ski Trip", where they choose sharing, receiving, range, and album as on any first join

#### Scenario: Declining keeps the current event
- **WHEN** the member declines the switch
- **THEN** they remain in their current event with every setting unchanged

#### Scenario: Backing out after the leave leaves no event
- **WHEN** the member confirms the switch and then cancels on the new event's join screen
- **THEN** the device is in no event and the create screen is shown

#### Scenario: A switch to a vanished event does not leave
- **WHEN** a member opens the invite of a different event that no longer exists
- **THEN** they are told the invite is invalid or the event no longer exists, and remain in their current event

### Requirement: The joined screen offers the invite until the event closes
The joined screen SHALL offer, while the device is in an event that has not closed (capability
`event-lifetime`), two equal ways to invite: a share action that hands the event's invite link to the system share sheet, and an action that shows a scannable QR code of the same link. The
share sheet SHALL name the event as what is being shared. Both
SHALL be offered even when photo access is missing. The QR code SHALL be shown only on request, over the
joined screen, and SHALL close again without changing anything. The QR code SHALL be dark on a light background
in both light and dark appearance. Shown, the QR code SHALL be presented as an invitation to join this event,
and its caption SHALL be addressed to the member showing it, telling them to let family and friends scan it
with their camera. Sharing or showing the QR code SHALL have no effect on the app's state, whether completed
or cancelled. Invite affordances SHALL NOT appear while the device is in no event, nor once the event has
closed, nor while the device cannot read an encrypted event's key — after it lost the key (capability `sync-status`)
or while the phone is locked since it was started — so an invite is only ever offered whole, never without its key
(see "An incomplete invite never joins an encrypted event"); a QR code already shown then closes. They SHALL return as soon as the key can be read
again.

#### Scenario: A host shares the invite before granting photo access
- **WHEN** a host who has not granted photo access has just joined their new event
- **THEN** the joined screen offers to share the invite link and to show its QR code, and sharing sends the
  invite link through the system share sheet

#### Scenario: The QR code is shown on request
- **WHEN** a member taps the action to show the QR code
- **THEN** the event's QR code appears over the joined screen, and closing it returns to the joined screen
  unchanged

#### Scenario: The QR code is not shown until asked for
- **WHEN** a member opens the joined screen
- **THEN** no QR code is shown until they ask for it

#### Scenario: The share sheet names the event
- **WHEN** a member of the event "Anna's 40th" taps the share action, on iPhone or on Android
- **THEN** the system share sheet shows "Anna's 40th" as the title of what is being shared

#### Scenario: The QR stays scannable in dark mode
- **WHEN** the phone is in dark appearance and the member shows the QR code
- **THEN** the QR code is still drawn dark on a light background

#### Scenario: The QR code and the shared link are the same invite
- **WHEN** one guest scans the member's QR code and another taps the link the member shared
- **THEN** both reach the join screen of the same event

#### Scenario: The QR code reads as an invitation
- **WHEN** a member shows the QR code
- **THEN** it is labelled as an invitation to join this event, not as sharing their photos

#### Scenario: The caption addresses the member
- **WHEN** a member reads the caption beneath the shown QR code
- **THEN** it tells them to let family and friends scan it with their camera, and does not tell them to scan
  anything

#### Scenario: A closed event offers no invite
- **WHEN** the event closes while a member is looking at the joined screen, with or without the QR code shown
- **THEN** the share action, the QR action and any shown QR code disappear

#### Scenario: A phone that lost the event's key offers no invite
- **WHEN** a member of an encrypted event whose phone lost the event's key opens the joined screen
- **THEN** it offers neither to share the invite nor to show its QR code

#### Scenario: The invite returns with the key
- **WHEN** that member opens the event's whole invite and the key is restored
- **THEN** the joined screen offers to share the invite and to show its QR code again, both carrying the key
