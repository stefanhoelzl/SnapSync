# Spec Delta

## MODIFIED Requirements

### Requirement: Automatic failure reports are minimal and anonymous
Only builds distributed through the App Store, TestFlight or Google Play SHALL report failures automatically, and
they SHALL report only crashes, errors, the app freezing, and the system ending the app — with the recent app
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
#### Scenario: An iPhone says an earlier run ended abnormally or froze
- **WHEN** an App Store or TestFlight build learns from the iPhone that one of its earlier runs was ended by the
  system — it ran out of memory, overused the processor or crashed — or froze for a second or more
- **THEN** a report naming why reaches the operator, carrying how much memory the app last used, and it does not
  contain the key of the event the phone is in

#### Scenario: An iPhone's account with nothing to report
- **WHEN** the iPhone reports on the app's earlier runs and nothing in it counts: no run ended by the system on the
  app's account — the iPhone reclaiming a small, suspended app for memory is its own housekeeping — and no freeze of
  a second or more
- **THEN** no report reaches the operator

#### Scenario: A development build
- **WHEN** a build not distributed through the App Store, TestFlight or Google Play crashes
- **THEN** nothing is reported anywhere
