## MODIFIED Requirements

### Requirement: Automatic failure reports are minimal and anonymous
Only builds distributed through the App Store, TestFlight or Google Play SHALL report failures automatically, and
they SHALL report only crashes, errors and the app freezing until the system closes it — with the recent app
activity leading up to them and technical facts such as device model, OS version, app version and how the app's
previous runs ended. They SHALL NOT carry analytics, usage tracking, performance monitoring or screen recording.
Before a report leaves the phone every identifier SHALL be removed from it — no event, device or membership
identifier is sent — except one random identifier per install, created by the reporting itself and linked to
nothing else, so the operator can count how many installs a problem affects. Reporting SHALL change nothing the
user sees or experiences.

#### Scenario: The app hits an error during an upload
- **WHEN** a distributed build hits an error while sharing a photo of an event
- **THEN** a report of the error reaches the operator, and it contains no identifier of the event or the device

#### Scenario: The app freezes and the system closes it
- **WHEN** a Google Play build stops responding and the system closes it
- **THEN** a report of the freeze reaches the operator, and it contains no identifier of the event or the device

#### Scenario: A development build
- **WHEN** a build not distributed through the App Store, TestFlight or Google Play crashes
- **THEN** nothing is reported anywhere
