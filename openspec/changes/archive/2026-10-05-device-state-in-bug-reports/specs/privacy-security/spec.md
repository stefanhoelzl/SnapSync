## MODIFIED Requirements

### Requirement: A detailed bug report leaves the phone only when the user sends one
Every build SHALL offer a hidden way to report a problem — a double-tap on the app's name, on every screen — that
opens a sheet stating what the report holds (the app's recent activity log, its sync state and the device's state)
and where it goes, and asking for a required description of up to 200 characters. On a distributed build the report
goes to the developer's error-tracking service, and the sheet says so. On a build that cannot report, the sheet SHALL
say the report is saved on this device, and the report SHALL be kept on the phone — replacing any report saved before
it — and SHALL NOT leave the phone. Nothing SHALL be sent or saved until the user writes a description and confirms;
cancelling or dismissing the sheet SHALL send and save nothing. A report SHALL carry the recent activity of both the
app and its background uploader with identifiers intact, so the operator can find the affected event and photos.

A report SHALL also carry, as they were at the moment the user confirmed: the numbers the screen showed for shared
and received photos; under limited photo access, how many photos the user's selection holds; and the device's state
— whether the app could reach the network and whether that network was one the user may want photos kept off,
whether power saving was on, whether the system allowed the app to work in the background, the battery's level,
whether it was charging, how hot the device was, the device's time zone and the app's own device identifier. A fact
the phone's system does not offer SHALL be left out; a fact that could not be read SHALL be marked as failed with its
reason, and SHALL NOT stop the report from being sent or saved.

A report SHALL hold only this app's own state and the device's settings and conditions. It SHALL NOT hold a photo or
anything a photo shows, another app's data, the device's location beyond its time zone, the user's contacts, the
device's free storage, or the name the user gave the device.

#### Scenario: The user sends a report
- **WHEN** a user of a distributed build double-taps the app's name, describes the problem and taps Send
- **THEN** one report carrying their description, the recent logs, the sync state and the device's state reaches the
  operator

#### Scenario: A build that cannot report keeps the report on the phone
- **WHEN** a user of a build that cannot report double-taps the app's name, describes the problem and taps Save
- **THEN** the sheet has said the report is saved on this device, one report carrying their description, the recent
  logs, the sync state and the device's state is kept on the phone in place of any earlier one, and nothing leaves
  the phone

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
- **THEN** nothing is sent or saved
