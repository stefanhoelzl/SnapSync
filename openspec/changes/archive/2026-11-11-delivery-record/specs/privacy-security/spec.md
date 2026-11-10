# Spec Delta

## ADDED Requirements

### Requirement: The service records how photos are delivered
The service SHALL keep a delivery record of every request it is sent. For a request from a genuine SnapSync app it
SHALL record which phone sent it (by its device identifier, requirement "No account and no personal identity"),
the app's version, the phone's operating system and its version, its model, whether it is a release or a
development build, the photo access the app has, the kind of network the
phone is on and whether it may use mobile data for SnapSync, whether power saving is on, and whether the system
lets the app work in the background. For the photos the app shares and receives it SHALL record when each photo
was taken, when the app found it, when it was queued, sent and became available to the members, when it was
withdrawn, when each member
was told about it, when each member's app downloaded it and added it to their library, why the app acted at each
step (it was opened, woken for a new photo, joining, given photo access, changed its settings, checking whether
the event is finished, or a periodic background check), how often and from where an upload was resumed, how much
was sent, whether a photo arrived before the receiving member joined, and when a member had shared or received
everything. It SHALL record when events are created, joined, left, completed and deleted, and how much each
read of an event's photo list was given. For a request that is not a genuine app's, it SHALL record only that it
was not an app's, and when. The record SHALL NOT hold the photos, an event's name, the network address of a phone
or browser, or anything that identifies the person using the app. The record SHALL be visible to the operator
only, SHALL NOT be shown to members or visitors, and SHALL be kept for 30 days from the request it describes —
whether or not the event's photos have been deleted — and then deleted.

#### Scenario: A member's app is woken for a new photo
- **WHEN** another member's photo arrives and this member's app is woken and reads the photo list
- **THEN** the operator can see that this phone read the list because it was woken for a new photo

#### Scenario: A photo travels from one member to another
- **WHEN** a member takes a photo during an event and it reaches another member's library
- **THEN** the operator can see when it was taken, found, sent, announced, downloaded and added to the other
  member's library, and why the apps acted at each step

#### Scenario: The record outlives the event's photos
- **WHEN** an event's photos are deleted
- **THEN** the delivery record of its requests stays until 30 days after each request, and is deleted then

#### Scenario: The record holds nothing that identifies a person
- **WHEN** the operator reads the delivery record
- **THEN** it shows device identifiers, phone and app facts and times, and no photo, no event name, no network
  address and nothing that identifies the person using the app

#### Scenario: Members do not see each other's activity
- **WHEN** a member uses the app during an event
- **THEN** nothing in the app shows when or why other members' apps read, shared or received the event's photos

## MODIFIED Requirements

### Requirement: A web visitor leaves no trace in the event
Viewing the event page or downloading an event's photos SHALL NOT make the visitor a member, SHALL NOT take one
of the event's device places, and SHALL be invisible to the members. The service SHALL record only that a
browser read the event's photo list and when (requirement "The service records how photos are delivered"); it
SHALL NOT record the visitor's address, browser or anything else that identifies them. The one
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

### Requirement: Device logs stay on the device
The app's detailed activity logs SHALL stay on the phone. They SHALL leave it only inside a bug report the
user sends, or, stripped of identifiers, as the recent activity attached to an automatic failure report. The
facts the app sends with its requests for the delivery record (requirement "The service records how photos are
delivered") are not its activity log.

#### Scenario: No failure and no report
- **WHEN** a member uses SnapSync for a whole event without a crash, an error or a bug report
- **THEN** none of the app's activity log leaves the phone, and only the facts of the delivery record travel
  with the app's requests

### Requirement: The Privacy Policy states what leaves the device
The Privacy Policy published on the site (capability `web-site`) SHALL accurately describe every kind of
data that leaves a user's phone or browser — the shared photos, the phone's device identifier, the
notification token, the app-integrity check, the delivery record, automatic failure reports and user-sent bug
reports — why it is processed, which service providers process it, how long photos are kept (capability
`event-lifetime`) and how long the delivery record is kept, and how to exercise data-protection rights. Where a
kind of data goes to a different provider, or is checked or created a different way, on iPhone and on Android,
the policy SHALL describe each platform's case and name each platform's provider. It SHALL be updated in the
same release as any change to what leaves the device.

#### Scenario: A new kind of data starts leaving the device
- **WHEN** a release begins sending a kind of data the policy does not describe
- **THEN** the policy published with that release describes it and names the provider that receives it

#### Scenario: An Android user reads the policy
- **WHEN** someone who uses SnapSync on Android reads the Privacy Policy
- **THEN** it names the provider that issues and carries their phone's notification token and describes how their phone's app-integrity check works, as it does for an iPhone

#### Scenario: A user reads how their phone is identified
- **WHEN** someone reads the Privacy Policy
- **THEN** it says how SnapSync's identifier for their phone is created on iPhone and on Android, that it is
  linked to no person and no other app, and that it can stay the same when the app is installed again

#### Scenario: A user reads what is recorded about delivery
- **WHEN** someone reads the Privacy Policy
- **THEN** it says what the delivery record holds, that it holds nothing that identifies them, why it is kept,
  and that it is kept 30 days whether or not the event's photos have been deleted

## REMOVED Requirements

### Requirement: The service records who reads an event's photo list
**Reason**: Replaced by "The service records how photos are delivered", which keeps everything this record held
(each read, by which phone, why, how much it was given; when each photo became available and when it was
withdrawn) and adds the delivery
facts; the record is no longer deleted with the event's photos but kept 30 days from each request.
**Migration**: The record's facts are recorded in the delivery record from this release; the per-event read
record is no longer written, and what remains of it is deleted with its event as before.
