# Spec Delta

## MODIFIED Requirements

### Requirement: No account and no personal identity
SnapSync SHALL NOT ask for or store a name, email address, phone number, password or contacts. Each phone
SHALL be known to the service only by its device identifier: one SnapSync creates for that phone — at random on
iPhone, and on Android from the identifier Android gives SnapSync alone — that is linked to no person, no account
and no other app. The device identifier MAY stay the same when SnapSync is deleted and installed again on the same
phone; it ends with a reset of the phone to its factory settings.

#### Scenario: Joining asks for nothing personal
- **WHEN** a guest installs SnapSync and joins an event
- **THEN** at no point are they asked for a name, email, phone number, password or access to contacts

#### Scenario: Reinstalling on the same phone
- **WHEN** a member deletes SnapSync and installs it again on the same phone
- **THEN** the phone may be known to the service by the same device identifier as before, and still nothing
  personal is asked for

### Requirement: The service records who reads an event's photo list
For each event the service SHALL keep a record of when each shared photo became available to the members and
when it was withdrawn, and of every read of the event's photo list: for a read by a genuine SnapSync app, which
phone read it (by its device identifier, requirement "No account and no personal identity"), why the app read it
(it was opened, woken for a new photo, joining, given photo access, changed its settings, checking whether the
event is finished, or a periodic background check), and how much it was given; for any other read, only that it
was not an app's. The record SHALL be visible to the operator only, SHALL NOT be shown to members or visitors,
and SHALL be deleted together with the event's photos (capability `event-lifetime`).

#### Scenario: A member's app is woken for a new photo
- **WHEN** another member's photo arrives and this member's app is woken and reads the photo list
- **THEN** the operator can see that this phone read the list because it was woken for a new photo

#### Scenario: The record ends with the event
- **WHEN** an event's photos are deleted
- **THEN** its record of photos and reads is deleted with them

#### Scenario: Members do not see each other's reads
- **WHEN** a member uses the app during an event
- **THEN** nothing in the app shows when or why other members read the event's photo list

### Requirement: The Privacy Policy states what leaves the device
The Privacy Policy published on the site (capability `web-site`) SHALL accurately describe every kind of
data that leaves a user's phone or browser — the shared photos, the phone's device identifier, the
notification token, the app-integrity check, automatic failure reports and user-sent bug reports — why it
is processed, which service providers process it, how long photos are kept (capability `event-lifetime`),
and how to exercise data-protection rights. Where a kind of data goes to a different provider, or is checked
or created a different way, on iPhone and on Android, the policy SHALL describe each platform's case and name
each platform's provider. It SHALL be updated in the same release as any change to what leaves the device.

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

### Requirement: Automatic failure reports are minimal and anonymous
Only builds distributed through the App Store, TestFlight or Google Play SHALL report failures automatically, and
they SHALL report only crashes, errors and the app freezing until the system closes it — with the recent app
activity leading up to them and technical facts such as device model, OS version, app version and how the app's
previous runs ended. They SHALL NOT carry analytics, usage tracking, performance monitoring or screen recording. A
report MAY carry the identifiers SnapSync already exchanges with its own service — of the device, the event
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
