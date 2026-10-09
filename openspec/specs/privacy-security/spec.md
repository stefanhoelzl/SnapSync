# privacy-security Specification

## Purpose
Serves every host, guest and web visitor with the promises they rely on but cannot inspect: who can see and
change an event's photos, what leaves their phone or their browser and when, and what the operator can learn from a
failure. SnapSync has no accounts — an event's invite is its key (capability `invite-link`) — so the promises are
stated in those terms: only a genuine SnapSync app can add to an event, nothing tracks a visitor, the app's automatic
failure reports carry nothing that identifies a person and never an event's key, and a detailed bug report leaves the
phone only when the user writes and sends one. How long an event and its photos are kept is capability
`event-lifetime`; which of a member's photos are shared at all is capability `photo-sharing`.
Decision record: changes/archive/2026-07-14-add-device-attestation

## Requirements

### Requirement: No account and no personal identity
SnapSync SHALL NOT ask for or store a name, email address, phone number, password or contacts. Each install
SHALL be known to the service only by a random identifier that is not linked to the person using it.

#### Scenario: Joining asks for nothing personal
- **WHEN** a guest installs SnapSync and joins an event
- **THEN** at no point are they asked for a name, email, phone number, password or access to contacts

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

### Requirement: Only a genuine SnapSync app can change an event
Creating an event, joining it, adding or withdrawing photos, renaming it and leaving it SHALL be possible
only from a genuine, unmodified SnapSync app on a genuine device: an Apple device, or an Android phone whose
hardware vouches for the app and which runs its maker's verified system. A browser, a script, a modified app,
an emulator, or an Android phone with an unlocked bootloader or a modified system SHALL be refused, even
when it holds the invite link; holding the link grants reading only. A genuine app SHALL act only for its own
device: only the device that shared a photo can withdraw it.

#### Scenario: A script tries to add a photo
- **WHEN** a program that is not a genuine SnapSync app tries to add a file to an event whose invite link it holds
- **THEN** it is refused and the event's members never receive the file

#### Scenario: One member's app tries to withdraw another member's photos
- **WHEN** a genuine SnapSync app acts in an event on behalf of a device other than its own
- **THEN** it is refused, and the other member's shared photos are unchanged

#### Scenario: A browser holding the link
- **WHEN** a web visitor with the invite link uses the event page
- **THEN** they can download the photos but cannot add, remove or rename anything

#### Scenario: A genuine Android phone joins
- **WHEN** a guest with an Android phone running its maker's system joins with the genuine SnapSync app
- **THEN** they can join and share like an iPhone member

#### Scenario: An Android phone with an unlocked bootloader
- **WHEN** a SnapSync app on an Android phone with an unlocked bootloader tries to join an event
- **THEN** it is refused, and the event is unchanged

### Requirement: A refused phone is told why
When the service refuses this phone as not genuine (as "Only a genuine SnapSync app can change an event" requires),
the app SHALL tell the user that this phone was refused and SHALL name which of three causes applies: the phone's
system is modified (an unlocked bootloader, or a system its maker did not ship); the phone could not be verified (the
proof its hardware gives is not one the service recognises); or this copy of the app is not the official one. It
SHALL NOT present a refusal as the server being unreachable or the network being at fault. When the phone could not
be verified, the app SHALL say the user did nothing wrong and SHALL offer to report the problem. When the app is not
the official one, it SHALL point the user to the official store. A refusal SHALL be told only while the app's latest
attempt to verify this phone was refused: an attempt that got no answer SHALL NOT be told as a refusal, and a
successful verification SHALL clear it at once. Where each screen shows the refusal is that screen's capability
(`create-event`, `join-event`, `sync-status`).

#### Scenario: A phone with an unlocked bootloader is told why
- **WHEN** the service refuses a phone because its bootloader is unlocked
- **THEN** the app says this phone's system is modified or its bootloader is unlocked and that SnapSync works only
  on a phone running its maker's system, and never says the server could not be reached

#### Scenario: A phone whose proof is not recognised is not blamed
- **WHEN** the service refuses a phone because the proof its hardware gives is not one the service recognises
- **THEN** the app says this phone could not be verified, that this is not something the user did, and offers to
  report the problem

#### Scenario: An unofficial copy of the app is pointed to the store
- **WHEN** the service refuses a copy of the app that is not the official one
- **THEN** the app says this copy is not the official one and points the user to the official store

#### Scenario: No answer is not a refusal
- **WHEN** the app's attempt to verify the phone gets no answer from the service
- **THEN** no refusal is told, and a failed create or join says the server could not be reached as before

#### Scenario: A refusal the service stops making clears
- **WHEN** a phone was refused and a later attempt to verify it succeeds
- **THEN** the refusal is no longer shown anywhere, and creating and joining work as on any genuine phone

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
Viewing the event page or downloading an event's photos SHALL NOT make the visitor a member, SHALL NOT take one
of the event's device places, and SHALL be invisible to the members. The service SHALL record only that a
browser read the event's photo list and when (requirement "The service records who reads an event's photo
list"); it SHALL NOT record the visitor's address, browser or anything else that identifies them. The one
exception is a failure: when the service fails unexpectedly while serving the visitor, the report it sends the
operator carries that request's details, including the visitor's browser, approximate location and connection
fingerprint, but never their address (requirement "The service reports its own failures to the operator").

#### Scenario: Many visitors download
- **WHEN** twenty people download an event's photos through its invite link
- **THEN** the event's membership and its remaining device places are unchanged, and no member sees them

#### Scenario: What a visit leaves behind
- **WHEN** a web visitor opens an event page and downloads its photos, and the service serves them without
  failing
- **THEN** the operator can see that a browser read the event's photo list at that time, and nothing that
  identifies the visitor

#### Scenario: The service fails while serving a visitor
- **WHEN** the service fails unexpectedly while serving a web visitor the event page
- **THEN** the operator's failure report carries the visitor's browser, approximate location and connection
  fingerprint, and not the visitor's address

### Requirement: The service records who reads an event's photo list
For each event the service SHALL keep a record of when each shared photo became available to the members and
when it was withdrawn, and of every read of the event's photo list: for a read by a genuine SnapSync app, which
install read it (by its random install identifier), why the app read it (it was opened, woken for a new
photo, joining, given photo access, changed its settings, checking whether the event is finished, or a
periodic background check), and how much it was given; for any other read, only that it was not an app's.
The record SHALL be visible to the operator only, SHALL NOT be shown to members or visitors, and SHALL be
deleted together with the event's photos (capability `event-lifetime`).

#### Scenario: A member's app is woken for a new photo
- **WHEN** another member's photo arrives and this member's app is woken and reads the photo list
- **THEN** the operator can see that this install read the list because it was woken for a new photo

#### Scenario: The record ends with the event
- **WHEN** an event's photos are deleted
- **THEN** its record of photos and reads is deleted with them

#### Scenario: Members do not see each other's reads
- **WHEN** a member uses the app during an event
- **THEN** nothing in the app shows when or why other members read the event's photo list

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

### Requirement: The service reports its own failures to the operator
When SnapSync's service fails unexpectedly while handling a request from an app or a browser, a report of
the failure SHALL reach the operator's error-tracking service. The report SHALL carry the request as the
service received it: what was asked for, including any event, device or photo identifiers and file names it
named, and the details the request arrived with, such as the browser or app making it, the approximate
location it came from and its connection's fingerprint. It SHALL NOT carry the requester's network address.
The report SHALL be visible to the operator only, and SHALL change nothing about what the app or the browser
is answered. Only the operator's deployed service SHALL report; a development or test copy of the service
SHALL report nothing.

#### Scenario: The service fails while a member's app uploads a photo
- **WHEN** the service fails unexpectedly while receiving a member's photo
- **THEN** a report reaches the operator naming the event, the device and the photo that request was for,
  and the member's app is answered exactly as it would be without the report

#### Scenario: The requester's address is never sent
- **WHEN** a failure report is sent for any request
- **THEN** it contains no network address of the phone or browser that made the request

#### Scenario: A development copy of the service fails
- **WHEN** a copy of the service running for development or tests fails unexpectedly
- **THEN** nothing is reported anywhere

### Requirement: A detailed bug report leaves the phone only when the user sends one
Every build SHALL offer a visible way to report a problem — "Report a problem" in the app's menu (capability
`sync-status`) — and SHALL keep a second, hidden way: a double-tap on the app's name, on every screen. Either one
opens a sheet stating what the report holds (the app's recent activity log, its sync state and the device's state)
and where it goes, and asking for a required description of up to 200 characters. On a distributed build the report
goes to the developer's error-tracking service, and the sheet says so. On a build that cannot report, the sheet SHALL
say the report is saved on this device, and the report SHALL be kept on the phone — replacing any report saved before
it — and SHALL NOT leave the phone. Nothing SHALL be sent or saved until the sheet holds a description and the user confirms; the description is the
user's own, except where the app offers to report a problem it has itself found ("A refused phone is told why"),
which opens the sheet with a description already written that the user may change before confirming;
cancelling or dismissing the sheet SHALL send and save nothing. Once the report has been handed off, the app SHALL
briefly confirm what happened — that it was sent, that it was saved on this device, or that it could be neither —
and SHALL NOT claim the report reached the developer. A report SHALL carry the recent activity of both the app and
its background uploader with identifiers intact, so the operator can find the affected event and photos.

A report SHALL also carry, as they were at the moment the user confirmed: the numbers the screen showed for shared
and received photos; under limited photo access, how many photos the user's selection holds; and the device's state
— whether the app could reach the network and whether that network was one the user may want photos kept off,
whether power saving was on, whether the system allowed the app to work in the background, the battery's level,
whether it was charging, how hot the device was, the device's time zone and the app's own device identifier. A fact
the phone's system does not offer SHALL be left out; a fact that could not be read SHALL be marked as failed with its
reason, and SHALL NOT stop the report from being sent or saved.

A report the app offers for a phone that could not be verified ("A refused phone is told why") SHALL also carry what
the service answered when it refused the phone and, where the phone's system exposes them, the certificates the phone
presented as its proof, summarised: for each certificate above the phone's own key, whom it names, who issued it, when
it is valid and what kind of key it holds, and a fingerprint of the topmost certificate's key — names as the
certificates give them, even where a certificate is named after its own serial number. It SHALL NOT carry the
certificates themselves. A report opened any other way SHALL NOT carry these facts.

A report SHALL hold only this app's own state and the device's settings and conditions, and, for a report the app
offered, the verification facts above. It SHALL NOT hold a photo or
anything a photo shows, another app's data, the device's location beyond its time zone, the user's contacts, the
device's free storage, or the name the user gave the device.

#### Scenario: The user sends a report
- **WHEN** a user of a distributed build opens the menu, taps "Report a problem", describes the problem and taps Send
- **THEN** one report carrying their description, the recent logs, the sync state and the device's state reaches the
  operator, and the app briefly confirms that the report was sent

#### Scenario: The hidden way still opens the sheet
- **WHEN** a user double-taps the app's name on any screen
- **THEN** the same report sheet opens

#### Scenario: A build that cannot report keeps the report on the phone
- **WHEN** a user of a build that cannot report opens the report sheet, describes the problem and taps Save
- **THEN** the sheet has said the report is saved on this device, one report carrying their description, the recent
  logs, the sync state and the device's state is kept on the phone in place of any earlier one, nothing leaves the
  phone, and the app briefly confirms that the report was saved on this device

#### Scenario: The report can be neither sent nor saved
- **WHEN** a user confirms a report and the app can neither hand it to the reporting service nor save it
- **THEN** the app briefly says the report could not be sent, calmly and without blaming the user

#### Scenario: The report shows what the user saw and what slowed the device
- **WHEN** a member whose screen reads 3 of 10 shared, on mobile data with power saving on, sends a report
- **THEN** the report reads 3 of 10 shared and says the network was a restricted one and power saving was on

#### Scenario: A member with limited photo access sends a report
- **WHEN** a member who gave the app access to 12 selected photos sends a report
- **THEN** the report says the selection holds 12 photos

#### Scenario: A device fact cannot be read
- **WHEN** a user sends a report and one of the device's facts cannot be read at that moment
- **THEN** the report is still sent, and it marks that fact as failed with the reason

#### Scenario: A report holds nothing beyond the app and the device's state
- **WHEN** the operator receives any report
- **THEN** it holds no photo, no location beyond the time zone, no contact, no other app's data, no free-storage
  figure and no device name

#### Scenario: An empty description
- **WHEN** the sheet is open and the description is empty or only spaces
- **THEN** the report cannot be sent or saved

#### Scenario: The user cancels
- **WHEN** a user opens the sheet and then cancels or dismisses it
- **THEN** nothing is sent or saved, and no confirmation appears

#### Scenario: A refused phone's offered report shows which root its proof ends at
- **WHEN** a user whose phone could not be verified taps the app's offer to report it and sends the report
- **THEN** the operator receives what the service answered and, for each certificate the phone presented above its
  own key, whom it names, who issued it, when it is valid and its key type, and the fingerprint of the topmost
  certificate's key; the report holds no certificate itself

#### Scenario: Any other report carries no certificate facts
- **WHEN** a user on a refused phone opens the report sheet from the menu, describes the problem and sends it
- **THEN** the report carries no answer from the service about the phone's verification and no certificate summary

#### Scenario: A report the app offers still waits for the user
- **WHEN** a user taps the app's offer to report that their phone could not be verified
- **THEN** the report sheet opens with a description already written, the user can change it, and nothing is sent
  or saved until they confirm; cancelling sends and saves nothing

### Requirement: Device logs stay on the device
The app's detailed activity logs SHALL stay on the phone. They SHALL leave it only inside a bug report the
user sends, or, stripped of identifiers, as the recent activity attached to an automatic failure report.

#### Scenario: No failure and no report
- **WHEN** a member uses SnapSync for a whole event without a crash, an error or a bug report
- **THEN** none of the app's activity log leaves the phone

### Requirement: The notification token is used only to deliver new photos
The token Apple gives the app for waking it SHALL be used only to tell that device that new photos are
ready in its event, or, once, that its event has closed (capability `event-lifetime`).

#### Scenario: Another member shares a photo
- **WHEN** another member's photo arrives in the event
- **THEN** the token is used to wake this device for it, and for nothing else

#### Scenario: The event closes
- **WHEN** the device's event closes
- **THEN** the token is used once to wake the device for it

### Requirement: The Privacy Policy states what leaves the device
The Privacy Policy published on the site (capability `web-site`) SHALL accurately describe every kind of
data that leaves a user's phone or browser — the shared photos, the random install identifier, the
notification token, the app-integrity check, automatic failure reports and user-sent bug reports — why it
is processed, which service providers process it, how long photos are kept (capability `event-lifetime`),
and how to exercise data-protection rights. Where a kind of data goes to a different provider, or is checked
a different way, on iPhone and on Android, the policy SHALL describe each platform's case and name each
platform's provider. It SHALL be updated in the same release as any change to what leaves the device.

#### Scenario: A new kind of data starts leaving the device
- **WHEN** a release begins sending a kind of data the policy does not describe
- **THEN** the policy published with that release describes it and names the provider that receives it

#### Scenario: An Android user reads the policy
- **WHEN** someone who uses SnapSync on Android reads the Privacy Policy
- **THEN** it names the provider that issues and carries their phone's notification token and describes how their phone's app-integrity check works, as it does for an iPhone
