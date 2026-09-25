# privacy-security Specification

## Purpose
Serves every host, guest and web visitor with the promises they rely on but cannot inspect: who can see and
change an event's photos, what leaves their phone or their browser and when, and what the operator can
learn from a failure. SnapSync has no accounts — an event's invite link is its key — so the promises are
stated in those terms: anyone holding the link can see the event, only a genuine SnapSync app can add to
it, nothing tracks a visitor, automatic failure reports carry no identifiers, and a detailed bug report
leaves the phone only when the user writes and sends one. How long an event and its photos are kept is
capability `event-lifetime`; which of a member's photos are shared at all is capability `photo-sharing`.
Decision record: changes/archive/2026-07-14-add-device-attestation

## Requirements
### Requirement: No account and no personal identity
SnapSync SHALL NOT ask for or store a name, email address, phone number, password or contacts. Each install
SHALL be known to the service only by a random identifier that is not linked to the person using it.

#### Scenario: Joining asks for nothing personal
- **WHEN** a guest installs SnapSync and joins an event
- **THEN** at no point are they asked for a name, email, phone number, password or access to contacts

### Requirement: The invite link is the key to an event
Anyone who holds an event's invite link or QR code SHALL be able to join the event with the app (capability
`join-event`) and to see the event's name and download all of its shared photos in a browser (capability
`event-site`). There SHALL be no other gate — no account, approval or password — so sharing the QR code
shares the event. Without the link, an event's photos SHALL NOT be discoverable: the service offers no
listing or search of events, and stored photos are not publicly browsable.

#### Scenario: A forwarded link grants access
- **WHEN** a member forwards the invite link to someone outside the event
- **THEN** that person can see the event's name and download its shared photos in a browser

#### Scenario: Without the link there is no way in
- **WHEN** someone without the invite link looks for an event's photos on SnapSync's web address or its storage
- **THEN** they can find neither the event nor any of its photos

### Requirement: Links to individual photos expire
A link to a single stored photo, as handed to a member's app or to the event page, SHALL stop working after
a limited time (at most 7 days); a fresh one is obtainable only by someone who holds the event's invite
link or is a member.

#### Scenario: A copied photo link goes stale
- **WHEN** someone copies the address of a single photo out of the event page and opens it 8 days later
- **THEN** the photo is not served

### Requirement: Only a genuine SnapSync app can change an event
Creating an event, joining it, adding or withdrawing photos, renaming it and leaving it SHALL be possible
only from a genuine, unmodified SnapSync app on a genuine Apple device. A browser, a script or a modified
app SHALL be refused, even when it holds the invite link; holding the link grants reading only. A genuine
app SHALL act only for its own device: only the device that shared a photo can withdraw it.

#### Scenario: A script tries to add a photo
- **WHEN** a program that is not a genuine SnapSync app tries to add a file to an event whose invite link it holds
- **THEN** it is refused and the event's members never receive the file

#### Scenario: One member's app tries to withdraw another member's photos
- **WHEN** a genuine SnapSync app acts in an event on behalf of a device other than its own
- **THEN** it is refused, and the other member's shared photos are unchanged

#### Scenario: A browser holding the link
- **WHEN** a web visitor with the invite link uses the event page
- **THEN** they can download the photos but cannot add, remove or rename anything

### Requirement: A verification problem never loses a photo
Photos waiting to be shared SHALL be held on the device and retried, never dropped, while the app cannot
currently prove it is genuine — for example because its proof expired while the app was not opened — and
SHALL be shared once the app is verified again. The stall SHALL be made visible
to the member (capability `sync-status`), never hidden behind a screen that reads as healthy. A proof that
is still valid SHALL keep sharing working even when an attempt to renew it fails.

#### Scenario: The proof expires while the phone sits unused
- **WHEN** a member does not open SnapSync for longer than the proof lasts, while taking event photos
- **THEN** those photos are not lost, and they are shared after the member next opens the app and it is verified

#### Scenario: A renewal fails while the proof is still valid
- **WHEN** the app tries to renew its proof while offline, and the current proof has not yet expired
- **THEN** photos keep being shared and the member is not told sharing has stopped

### Requirement: The event's identity stays off the wire until it is needed
Opening an invite link SHALL NOT transmit the event's identity to any server: a browser or the operating
system receives only the page address, never the part of the link that names the event. The event page
SHALL send the event's identity only to SnapSync's own service, to load that event, and SHALL NEVER send it
to any third party. The event's identity SHALL NOT appear in any automatic failure report.

#### Scenario: Opening a link where no app is installed
- **WHEN** a visitor opens an invite link in a browser
- **THEN** the request that loads the page carries nothing that identifies the event

#### Scenario: The event page loads the event
- **WHEN** the event page loads the event's name and photo list
- **THEN** it asks only SnapSync's own service, and no other host learns which event is being viewed

### Requirement: The web pages track no one
SnapSync's web pages SHALL use no analytics, advertising, tracking or cookies, and SHALL set nothing on the
visitor's device. They SHALL load nothing from any other site — no scripts, fonts, styles or images — and
the only other host they contact is SnapSync's own photo storage, to fetch the photos a visitor downloads.

#### Scenario: A visitor loads the landing page
- **WHEN** a visitor opens the landing page
- **THEN** no cookie is set, no analytics or tracking runs, and no request goes to any other site

#### Scenario: A visitor downloads an event
- **WHEN** a visitor downloads an event's photos from the event page
- **THEN** the only requests go to SnapSync's service and SnapSync's photo storage

### Requirement: A web visitor leaves no trace in the event
Viewing the event page or downloading an event's photos SHALL register nothing: the visitor SHALL NOT
become a member, SHALL NOT take one of the event's device places, and SHALL be invisible to the members.

#### Scenario: Many visitors download
- **WHEN** twenty people download an event's photos through its invite link
- **THEN** the event's membership and its remaining device places are unchanged, and no member sees them

### Requirement: Automatic failure reports are minimal and anonymous
Only builds distributed through the App Store or TestFlight SHALL report failures automatically, and they
SHALL report only crashes and errors — with the recent app activity leading up to them and technical facts
such as device model, iOS version, app version and how the app's previous runs ended. They SHALL NOT carry
analytics, usage tracking, performance monitoring or screen recording. Before a report leaves the phone
every identifier SHALL be removed from it — no event, device or membership identifier is sent —
except one random identifier per install, created by the reporting itself and linked to nothing else, so
the operator can count how many installs a problem affects. Reporting SHALL change nothing the user sees
or experiences.

#### Scenario: The app hits an error during an upload
- **WHEN** an App Store build hits an error while sharing a photo of an event
- **THEN** a report of the error reaches the operator, and it contains no identifier of the event or the device

#### Scenario: A development build
- **WHEN** a build not distributed through the App Store or TestFlight crashes
- **THEN** nothing is reported anywhere

### Requirement: A detailed bug report leaves the phone only when the user sends one
A distributed build SHALL offer a hidden way to report a problem — a double-tap on the app's name, on every
screen — that opens a sheet stating what will be sent (the app's recent activity log and its sync state, to
the developer's error-tracking service) and asking for a required description of up to 200 characters.
Nothing SHALL be sent until the user writes a description and taps Send; cancelling or dismissing the
sheet SHALL send nothing. A sent report SHALL carry the recent activity of both the app and its background
uploader with identifiers intact, so the operator can find the affected event and photos. On a build that
cannot report, the gesture SHALL do nothing.

#### Scenario: The user sends a report
- **WHEN** a user double-taps the app's name, describes the problem and taps Send
- **THEN** one report carrying their description, the recent logs and the sync state reaches the operator

#### Scenario: An empty description
- **WHEN** the sheet is open and the description is empty or only spaces
- **THEN** the report cannot be sent

#### Scenario: The user cancels
- **WHEN** a user opens the sheet and then cancels or dismisses it
- **THEN** nothing is sent

### Requirement: Device logs stay on the device
The app's detailed activity logs SHALL stay on the phone. They SHALL leave it only inside a bug report the
user sends, or, stripped of identifiers, as the recent activity attached to an automatic failure report.

#### Scenario: No failure and no report
- **WHEN** a member uses SnapSync for a whole event without a crash, an error or a bug report
- **THEN** none of the app's activity log leaves the phone

### Requirement: The notification token is used only to deliver new photos
The token Apple gives the app for waking it SHALL be used only to tell that device that new photos are
ready in its event.

#### Scenario: Another member shares a photo
- **WHEN** another member's photo arrives in the event
- **THEN** the token is used to wake this device for it, and for nothing else

### Requirement: The Privacy Policy states what leaves the device
The Privacy Policy published on the site (capability `web-site`) SHALL accurately describe every kind of
data that leaves a user's phone or browser — the shared photos, the random install identifier, the
notification token, the app-integrity check, automatic failure reports and user-sent bug reports — why it
is processed, which service providers process it, how long photos are kept (capability `event-lifetime`),
and how to exercise data-protection rights. It SHALL be updated in the same release as any change to what
leaves the device.

#### Scenario: A new kind of data starts leaving the device
- **WHEN** a release begins sending a kind of data the policy does not describe
- **THEN** the policy published with that release describes it and names the provider that receives it
