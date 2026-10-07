# Spec Delta

## ADDED Requirements

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

## MODIFIED Requirements

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
