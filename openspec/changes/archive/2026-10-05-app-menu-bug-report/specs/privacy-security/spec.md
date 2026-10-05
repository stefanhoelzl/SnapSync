## MODIFIED Requirements

### Requirement: A detailed bug report leaves the phone only when the user sends one
Every build SHALL offer a visible way to report a problem — "Report a problem" in the app's menu (capability
`sync-status`) — and SHALL keep a second, hidden way: a double-tap on the app's name, on every screen. Either one
opens a sheet stating what the report holds (the app's recent activity log and its sync state) and where it goes,
and asking for a required description of up to 200 characters. On a distributed build the report goes to the
developer's error-tracking service, and the sheet says so. On a build that cannot report, the sheet SHALL say the
report is saved on this device, and the report SHALL be kept on the phone — replacing any report saved before it —
and SHALL NOT leave the phone. Nothing SHALL be sent or saved until the user writes a description and confirms;
cancelling or dismissing the sheet SHALL send and save nothing. Once the report has been handed off, the app SHALL
briefly confirm what happened — that it was sent, that it was saved on this device, or that it could be neither —
and SHALL NOT claim the report reached the developer. A report SHALL carry the recent activity of both the app and
its background uploader with identifiers intact, so the operator can find the affected event and photos.

#### Scenario: The user sends a report
- **WHEN** a user of a distributed build opens the menu, taps "Report a problem", describes the problem and taps Send
- **THEN** one report carrying their description, the recent logs and the sync state reaches the operator, and the
  app briefly confirms that the report was sent

#### Scenario: The hidden way still opens the sheet
- **WHEN** a user double-taps the app's name on any screen
- **THEN** the same report sheet opens

#### Scenario: A build that cannot report keeps the report on the phone
- **WHEN** a user of a build that cannot report opens the report sheet, describes the problem and taps Save
- **THEN** the sheet has said the report is saved on this device, one report carrying their description, the recent
  logs and the sync state is kept on the phone in place of any earlier one, nothing leaves the phone, and the app
  briefly confirms that the report was saved on this device

#### Scenario: The report can be neither sent nor saved
- **WHEN** a user confirms a report and the app can neither hand it to the reporting service nor save it
- **THEN** the app briefly says the report could not be sent, calmly and without blaming the user

#### Scenario: An empty description
- **WHEN** the sheet is open and the description is empty or only spaces
- **THEN** the report cannot be sent or saved

#### Scenario: The user cancels
- **WHEN** a user opens the sheet and then cancels or dismisses it
- **THEN** nothing is sent or saved, and no confirmation appears
