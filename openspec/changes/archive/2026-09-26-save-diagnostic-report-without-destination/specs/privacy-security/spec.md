## MODIFIED Requirements

### Requirement: A detailed bug report leaves the phone only when the user sends one
Every build SHALL offer a hidden way to report a problem — a double-tap on the app's name, on every screen — that
opens a sheet stating what the report holds (the app's recent activity log and its sync state) and where it goes,
and asking for a required description of up to 200 characters. On a distributed build the report goes to the
developer's error-tracking service, and the sheet says so. On a build that cannot report, the sheet SHALL say the
report is saved on this device, and the report SHALL be kept on the phone — replacing any report saved before it —
and SHALL NOT leave the phone. Nothing SHALL be sent or saved until the user writes a description and confirms;
cancelling or dismissing the sheet SHALL send and save nothing. A report SHALL carry the recent activity of both the
app and its background uploader with identifiers intact, so the operator can find the affected event and photos.

#### Scenario: The user sends a report
- **WHEN** a user of a distributed build double-taps the app's name, describes the problem and taps Send
- **THEN** one report carrying their description, the recent logs and the sync state reaches the operator

#### Scenario: A build that cannot report keeps the report on the phone
- **WHEN** a user of a build that cannot report double-taps the app's name, describes the problem and taps Save
- **THEN** the sheet has said the report is saved on this device, one report carrying their description, the recent
  logs and the sync state is kept on the phone in place of any earlier one, and nothing leaves the phone

#### Scenario: An empty description
- **WHEN** the sheet is open and the description is empty or only spaces
- **THEN** the report cannot be sent or saved

#### Scenario: The user cancels
- **WHEN** a user opens the sheet and then cancels or dismisses it
- **THEN** nothing is sent or saved
